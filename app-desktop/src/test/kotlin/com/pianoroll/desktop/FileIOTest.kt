package com.pianoroll.desktop

import com.pianoroll.core.model.Commands
import com.pianoroll.core.model.Note
import java.io.File
import kotlin.test.*

class FileIOTest {
    @Test fun sanitizeReplacesIllegalCharacters() {
        assertEquals("a_b_c_d", FileNames.sanitize("a/b:c*d"))
        assertEquals("untitled", FileNames.sanitize("   "))
        assertEquals("untitled", FileNames.sanitize("..."))
        assertEquals("Lead", FileNames.sanitize("Lead. "))
    }

    @Test fun sanitizeHandlesReservedNamesAndLength() {
        assertEquals("_con", FileNames.sanitize("con"))
        assertEquals("_NUL.txt", FileNames.sanitize("NUL.txt"))
        assertEquals("_lpt1", FileNames.sanitize("lpt1"))
        assertEquals(10, FileNames.sanitize("x".repeat(50), maxLength = 10).length)
        assertEquals("Console", FileNames.sanitize("Console"))
    }

    @Test fun withExtensionAddsOnlyWhenMissing() {
        val dir = tempDir()
        assertEquals("song.prs", FileNames.withExtension(File(dir, "song"), "prs").name)
        assertEquals("song.PRS", FileNames.withExtension(File(dir, "song.PRS"), "prs").name)
        assertEquals("a.b.prs", FileNames.withExtension(File(dir, "a.b"), "prs").name)
    }

    @Test fun uniqueIsCaseInsensitive() {
        val used = HashSet<String>()
        assertEquals("Bass", FileNames.unique("Bass", used))
        assertEquals("bass 2", FileNames.unique("bass", used))
        assertEquals("BASS 3", FileNames.unique("BASS", used))
    }

    @Test fun fileSinkWritesAndReplaces() {
        val f = File(tempDir(), "out.bin")
        f.writeText("old")
        val s = FileSink(f)
        s.write(byteArrayOf(1, 2, 3, 4), 1, 2)
        s.close(); s.close()   // idempotent
        assertContentEquals(byteArrayOf(2, 3), f.readBytes())
        assertFalse(File(f.parentFile, "out.bin.tmp").exists())
    }

    @Test fun fileSinkWithoutWritesKeepsOldFile() {
        val f = File(tempDir(), "keep.bin")
        f.writeText("precious")
        FileSink(f).close()
        assertEquals("precious", f.readText())
        val g = File(f.parentFile, "never.bin")
        FileSink(g).close()
        assertFalse(g.exists())
    }

    @Test fun projectSaveLoadRoundTrip() {
        val f = File(tempDir(), "song.prs")
        val p = testProject()
        assertTrue(ProjectIO.save(p, f).isSuccess)
        assertTrue(f.readText().contains("pianoroll-prs"))
        assertEquals(p, ProjectIO.load(f).getOrThrow())
    }

    @Test fun loadFailsCleanly() {
        val dir = tempDir()
        assertTrue(ProjectIO.load(File(dir, "missing.prs")).isFailure)
        val bad = File(dir, "bad.prs"); bad.writeText("{not json")
        assertTrue(ProjectIO.load(bad).isFailure)
        val foreign = File(dir, "foreign.prs"); foreign.writeText("""{"format":"other","version":1,"project":{"name":"x"}}""")
        assertTrue(ProjectIO.load(foreign).isFailure)
        val newer = File(dir, "newer.prs")
        newer.writeText(com.pianoroll.export.ProjectFile.encode(testProject()).replace("\"version\":1", "\"version\":99"))
        assertTrue(ProjectIO.load(newer).exceptionOrNull()?.message?.contains("newer") == true)
    }

    @Test fun saveFailsWhenTargetFolderIsMissing() {
        val f = File(File(tempDir(), "no-such-folder"), "x.prs")
        assertTrue(ProjectIO.save(testProject(), f).isFailure)
    }

    @Test fun windowTitleShowsDirtyMark() {
        assertEquals("*Song - PianoRoll Studio", windowTitle(null, "Song", true))
        assertEquals("a.prs - PianoRoll Studio", windowTitle(File("a.prs"), "Song", false))
    }
}
