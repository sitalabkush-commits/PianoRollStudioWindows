package com.pianoroll.desktop

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.model.*
import java.io.File
import java.nio.file.Files

/** Short project (1 bar, fast tempo) so offline renders in tests take milliseconds. */
fun testProject(name: String = "Test Song"): Project {
    fun n(p: Int, s: Long) = Note(pitch = p, startTick = s, durationTick = 240)
    val mel = Track(name = "Melody", type = TrackType.MELODY, instrumentId = "gm:0", notes = listOf(n(60, 0), n(64, 480), n(67, 960)))
    val bass = Track(name = "Bass", type = TrackType.BASS, instrumentId = "gm:33", notes = listOf(n(36, 0)))
    val drums = Track(name = "Drums", type = TrackType.DRUMS, instrumentId = "gm:drums", notes = listOf(n(36, 0), n(38, 480)))
    return Project(id = "p1", name = name, bpm = 240f, patterns = listOf(Pattern(id = "a", name = "P1", lengthBars = 1, tracks = listOf(mel, bass, drums))))
}

fun tempDir(): File = Files.createTempDirectory("prs-test").toFile().also { it.deleteOnExit() }

class MemSink : AudioSink {
    val bytes = java.io.ByteArrayOutputStream()
    var closeCount = 0
    override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes.write(buffer, offset, length) }
    override fun close() { closeCount++ }
}

class FakeChooser(
    var open: File? = null,
    var save: File? = null,
    var dir: File? = null,
) : FileChooser {
    var lastSuggested: String? = null
    override fun chooseOpenProject(): File? = open
    override fun chooseSave(title: String, suggestedName: String, extension: String): File? { lastSuggested = "$suggestedName.$extension"; return save }
    override fun chooseDirectory(title: String): File? = dir
}
