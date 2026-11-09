package com.pianoroll.desktop

import com.pianoroll.core.model.*
import com.pianoroll.desktop.audio.DesktopAudioEngine
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class DesktopSessionTest {
    private fun session(chooser: FileChooser, project: Project = testProject()): DesktopSession {
        val engine = DesktopAudioEngine()   // never opens a sound device unless play() is called
        return DesktopSession(ProjectStore(project), engine, chooser, ExportService(engine))
    }

    @Test fun dirtyTracking() {
        val s = session(FakeChooser())
        assertFalse(s.isDirty)
        s.store.execute(Commands.setBpm(100f))
        assertTrue(s.isDirty)
        s.store.undo()
        assertFalse(s.isDirty)
    }

    @Test fun saveAsThenSaveAddsExtensionAndClearsDirty() {
        val dir = tempDir()
        val c = FakeChooser(save = File(dir, "mysong"))
        val s = session(c)
        s.store.execute(Commands.setBpm(99f))
        assertTrue(s.saveProject())
        assertEquals("mysong.prs", s.currentFile.value?.name)
        assertTrue(File(dir, "mysong.prs").isFile)
        assertFalse(s.isDirty)
        // second save must not ask for a file again
        c.save = null
        s.store.execute(Commands.setBpm(101f))
        assertTrue(s.saveProject())
        assertEquals(101f, ProjectIO.load(File(dir, "mysong.prs")).getOrThrow().bpm)
    }

    @Test fun saveCancelledChangesNothing() {
        val s = session(FakeChooser(save = null))
        s.store.execute(Commands.setBpm(99f))
        assertFalse(s.saveProject())
        assertTrue(s.isDirty)
        assertNull(s.currentFile.value)
    }

    @Test fun openLoadsProjectAndResetsHistory() {
        val dir = tempDir()
        val f = File(dir, "a.prs")
        val saved = testProject("Loaded").copy(bpm = 77f)
        ProjectIO.save(saved, f).getOrThrow()
        val s = session(FakeChooser(open = f))
        s.store.execute(Commands.setBpm(150f))
        assertTrue(s.openProject())
        assertEquals(saved, s.store.state.value)
        assertFalse(s.store.canUndo.value)
        assertFalse(s.isDirty)
        assertEquals(f, s.currentFile.value)
    }

    @Test fun openBadFileKeepsCurrentProjectAndShowsError() {
        val bad = File(tempDir(), "bad.prs"); bad.writeText("nope")
        val s = session(FakeChooser(open = bad))
        val before = s.store.state.value
        assertFalse(s.openProject())
        assertEquals(before, s.store.state.value)
        assertTrue(s.status.value.isError)
    }

    @Test fun newProjectResetsEverything() {
        val s = session(FakeChooser())
        s.store.execute(Commands.setBpm(99f))
        s.newProject()
        assertEquals("Untitled", s.store.state.value.name)
        assertFalse(s.isDirty)
        assertFalse(s.store.canUndo.value)
        assertNull(s.currentFile.value)
    }

    @Test fun exportsGoThroughChooser() = runBlocking {
        val dir = tempDir()
        val s = session(FakeChooser(save = File(dir, "song"), dir = dir))
        // chooser returns "song" for every save dialog: extension is added per format
        assertTrue(s.exportMidi())
        assertTrue(File(dir, "song.mid").isFile)
        assertTrue(s.exportWav())
        assertTrue(File(dir, "song.wav").isFile)
        assertTrue(s.exportStems())
        assertEquals(3, dir.listFiles { f -> f.name.startsWith("Test Song - ") && f.name.endsWith(".wav") }!!.size)
        assertFalse(s.status.value.isError)
    }

    @Test fun exportCancelledDoesNothing() = runBlocking {
        val s = session(FakeChooser())
        assertFalse(s.exportMidi()); assertFalse(s.exportWav()); assertFalse(s.exportStems())
    }
}
