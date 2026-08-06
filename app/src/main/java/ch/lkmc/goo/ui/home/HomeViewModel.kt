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
 * The In room's half of project persistence: the shelf of saved goo.
 *
 * Deliberately thin — no document parsing, no bitmaps decoded here. A
 * summary is a folder name, a timestamp and a preview file
 * ([ProjectStore.list]); the tiles decode their own previews off the main
 * thread, the way the bundled samples already do.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val projectStore: ProjectStore,
) : ViewModel() {

    data class UiState(
        val projects: List<ProjectStore.Summary> = emptyList(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Re-read the shelf. Called on every resume, because the editor saves
     * behind this screen's back — returning from the Goo room must show
     * the project that was just written.
     */
    fun refresh() {
        viewModelScope.launch {
            val projects = projectStore.list()
            _uiState.update { it.copy(projects = projects) }
        }
    }

    /** Throw a project away, tile and pixels alike. */
    fun delete(id: String) {
        viewModelScope.launch {
            projectStore.delete(id)
            // Drop it from the shelf immediately rather than waiting for
            // the re-list: the tile the user just deleted must not linger.
            _uiState.update { state ->
                state.copy(projects = state.projects.filterNot { it.id == id })
            }
        }
    }
}
