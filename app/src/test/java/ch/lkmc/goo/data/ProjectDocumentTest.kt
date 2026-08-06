package ch.lkmc.goo.data

import ch.lkmc.goo.engine.core.BrushTool
import ch.lkmc.goo.engine.core.CropRect
import ch.lkmc.goo.engine.core.GlobalParams
import ch.lkmc.goo.engine.core.Stamp
import ch.lkmc.goo.engine.core.Stroke
import ch.lkmc.goo.engine.core.StrokeLog
import ch.lkmc.goo.engine.core.StrokeRevisionId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project file's own rules: what a full document survives on the way
 * to disk and back, and what a file that did NOT come from this app is
 * allowed to do on the way in.
 *
 * ProjectStore's file handling needs a Context and lives outside this
 * JVM-only tier (AGENTS.md); everything decision-shaped is here.
 */
class ProjectDocumentTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun stroke(n: Int) = Stroke(
        tool = BrushTool.MOVE,
        radius = 0.13f,
        strength = 0.7f,
        stamps = listOf(Stamp(n / 10f, 0.5f, 0.01f, -0.02f)),
    )

    private fun document(): ProjectDocument {
        val log = StrokeLog()
        log.push(stroke(1))
        val pin = log.currentRevision
        log.push(stroke(2))
        return ProjectDocument(
            updatedAtMillis = 1_700_000_000_000L,
            fusion = ProjectDocument.FUSION_FILE,
            crop = CropRecord.of(CropRect(0.1f, 0.2f, 0.8f, 0.9f)),
            globals = GlobalParams(bulge = 0.5f, twirl = -0.25f),
            log = log.snapshot(pins = listOf(pin)),
            keyframes = listOf(
                KeyframeRecord(pin.id.value, GlobalParams(bulge = 0.5f)),
                KeyframeRecord(log.currentRevision.id.value, GlobalParams(twirl = -0.25f)),
            ),
        )
    }

    @Test
    fun `a full document round-trips through JSON`() {
        val original = document()
        val decoded = json.decodeFromString<ProjectDocument>(json.encodeToString(original))
        assertEquals(original, decoded)

        val log = StrokeLog()
        val table = assertNotNull(log.restore(decoded.log))
        assertEquals(2, log.strokes.size)
        // Both pins resolve, including the one that is not the head.
        for (keyframe in decoded.keyframes) {
            assertNotNull(table[StrokeRevisionId(keyframe.revision)])
        }
        assertEquals(CropRect(0.1f, 0.2f, 0.8f, 0.9f), decoded.cropRect())
        assertEquals(GlobalParams(bulge = 0.5f, twirl = -0.25f), decoded.globals)
    }

    @Test
    fun `a file from an older version loads on its defaults`() {
        // Only the terms schema 1 shipped with are required; anything a
        // later version adds must not make this file unreadable, and
        // anything it omits must land on a sane default rather than
        // throwing the whole project away.
        val minimal = """{"schema":1,"log":{"revisions":[{"id":0}],"history":[0],"cursor":0}}"""
        val decoded = json.decodeFromString<ProjectDocument>(minimal)
        assertEquals(ProjectDocument.SOURCE_FILE, decoded.source)
        assertNull(decoded.fusion)
        assertNull(decoded.cropRect())
        assertTrue(decoded.globals.isIdentity)
        assertTrue(decoded.keyframes.isEmpty())
        assertTrue(decoded.namesAreLocal())
    }

    @Test
    fun `unknown terms from a newer version are ignored`() {
        val future = """{"schema":99,"source":"source.img","mood":"purple"}"""
        assertEquals("source.img", json.decodeFromString<ProjectDocument>(future).source)
    }

    @Test
    fun `a malformed crop uncrops instead of producing a degenerate decode`() {
        // Same policy as a restored session (CropRect.fromArray): the
        // strokes that rect framed are gone either way, and a zero-width
        // subrect would be a crash at decode.
        assertNull(document().copy(crop = CropRecord(0.8f, 0f, 0.2f, 1f)).cropRect())
        assertNull(document().copy(crop = CropRecord(0.5f, 0f, 0.5f, 1f)).cropRect())
        assertNull(document().copy(crop = CropRecord(-0.1f, 0f, 1f, 1f)).cropRect())
        assertNotNull(document().copy(crop = CropRecord(0f, 0f, 1f, 1f)).cropRect())
    }

    @Test
    fun `source names that leave the project folder are refused`() {
        assertTrue(document().namesAreLocal())
        assertFalse(document().copy(source = "../../databases/photos.db").namesAreLocal())
        assertFalse(document().copy(source = "sub/source.img").namesAreLocal())
        assertFalse(document().copy(source = "..").namesAreLocal())
        assertFalse(document().copy(source = "").namesAreLocal())
        assertFalse(document().copy(fusion = "/etc/hosts").namesAreLocal())
    }

    @Test
    fun `project ids are the only thing that names a folder`() {
        assertTrue(ProjectStore.isValidId(ProjectStore.newId()))
        assertFalse(ProjectStore.isValidId(".."))
        assertFalse(ProjectStore.isValidId("../other"))
        assertFalse(ProjectStore.isValidId("p 1"))
        assertFalse(ProjectStore.isValidId(""))
        assertFalse(ProjectStore.isValidId("p".repeat(65)))
    }
}
