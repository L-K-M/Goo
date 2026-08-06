package ch.lkmc.goo.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.lkmc.goo.data.ProjectStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The In room's half of project persistence: the shelf of saved goo, and
 * what it costs.
 *
 * Deliberately thin — no document parsing, no bitmaps decoded here. A
 * summary is a folder name, a timestamp, a preview file and a size
 * ([ProjectStore.list]); the tiles decode their own previews off the main
 * thread, the way the bundled samples already do.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectStore: ProjectStore,
) : ViewModel() {

    data class UiState(
        val projects: List<ProjectStore.Summary> = emptyList(),
        /** Free space where the projects live; 0 until the first listing. */
        val freeBytes: Long = 0L,
    ) {
        /** What the shelf occupies, all in. */
        val usedBytes: Long get() = projects.sumOf { it.bytes }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-read the shelf. Called on every resume, because the editor saves
     * behind this screen's back — returning from the Goo room must show
     * the project that was just written, and the size it added.
     */
    fun refresh() {
        viewModelScope.launch {
            val projects = projectStore.list()
            val free = projectStore.freeBytes()
            _uiState.update { it.copy(projects = projects, freeBytes = free) }
        }
    }

    /** Throw one project away, tile and pixels alike. */
    fun delete(id: String) {
        viewModelScope.launch {
            projectStore.delete(id)
            // Drop it from the shelf immediately rather than waiting for
            // the re-list: the tile the user just deleted must not linger.
            _uiState.update { state ->
                state.copy(projects = state.projects.filterNot { it.id == id })
            }
            // Then re-read, for the freed space.
            refresh()
        }
    }

    /** Throw the whole shelf away. */
    fun deleteAll() {
        viewModelScope.launch {
            projectStore.deleteAll()
            _uiState.update { it.copy(projects = emptyList()) }
            refresh()
        }
    }
}
