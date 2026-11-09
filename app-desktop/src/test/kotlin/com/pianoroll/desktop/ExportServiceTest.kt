package com.pianoroll.desktop

import com.pianoroll.core.model.*
import com.pianoroll.desktop.audio.DesktopAudioEngine
import com.pianoroll.export.MidiWriter
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

class ExportServiceTest {
    private val engine = DesktopAudioEngine()
    private val svc = ExportService(engine)

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test fun midiFileMatchesWriter() = runBlocking {
        val f = File(tempDir(), "a.mid")
        val p = testProject()
        assertTrue(svc.midi(p, f).isSuccess)
        assertContentEquals(MidiWriter.toBytes(p), f.readBytes())
    }

    @Test fun wavFileHasValidHeaderAndAudio() = runBlocking {
        val f = File(tempDir(), "a.wav")
        val progress = mutableListOf<Float>()
        assertTrue(svc.wav(testProject(), f) { progress.add(it) }.isSuccess)
        val b = f.readBytes()
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4))
        assertEquals(b.size - 44, le32(b, 40))
        assertEquals(44100, le32(b, 24))
        assertTrue(b.drop(44).any { it.toInt() != 0 }, "rendered audio must not be silent")
        assertEquals(1f, progress.last())
    }

    @Test fun stemsOneFilePerTrackWithSafeNames() = runBlocking {
        val dir = tempDir()
        val p = testProject("My: Song")
        val weird = p.copy(patterns = p.patterns.map { pat -> pat.copy(tracks = pat.tracks.map { if (it.name == "Bass") it.copy(name = "Ba/ss") else it }) })
        val r = svc.stems(weird, dir, "My: Song")
        val files = r.getOrThrow()
        assertEquals(3, files.size)
        assertTrue(files.all { it.isFile && it.length() > 44 })
        assertTrue(files.any { it.name == "My_ Song - Ba_ss.wav" }, files.map { it.name }.toString())
    }

    @Test fun stemNamesStayUniqueAfterSanitizing() = runBlocking {
        val dir = tempDir()
        val p = testProject()
        val a = p.copy(patterns = p.patterns.map { pat -> pat.copy(tracks = pat.tracks.mapIndexed { i, t ->
            when (i) { 0 -> t.copy(name = "a/b"); 1 -> t.copy(name = "a:b"); else -> t } }) })
        val files = svc.stems(a, dir, "x").getOrThrow()
        assertEquals(files.size, files.map { it.name.lowercase() }.toSet().size)
    }

    @Test fun stemsFailForMissingFolder() = runBlocking {
        assertTrue(svc.stems(testProject(), File("/no/such/dir/at/all"), "x").isFailure)
    }

    @Test fun mp3IsUnsupportedAndCreatesNoFile() = runBlocking {
        val f = File(tempDir(), "a.mp3")
        val r = svc.mp3(testProject(), f)
        assertTrue(r.exceptionOrNull() is UnsupportedOperationException)
        assertFalse(f.exists())
    }

    @Test fun wavOfProjectWithBadSettingsFailsWithoutFile() = runBlocking {
        val bad = ExportService(engine, com.pianoroll.core.audio.RenderSettings(bitsPerSample = 24))
        val f = File(tempDir(), "bad.wav")
        assertTrue(bad.wav(testProject(), f).isFailure)
        assertFalse(f.exists())
    }
}
