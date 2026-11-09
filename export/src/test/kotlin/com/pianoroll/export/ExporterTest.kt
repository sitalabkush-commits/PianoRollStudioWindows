package com.pianoroll.export

import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.audioengine.OfflineRenderer
import com.pianoroll.core.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Records what it was asked to render and emits a fixed PCM blob. */
class FakeEngine(private val pcm: ByteArray = ByteArray(1000) { 1 }, private val fail: Boolean = false) : AudioEngine {
    val rendered = ArrayList<Project>()
    override fun loadSoundFont(path: String) = Result.success(Unit)
    override fun setProject(project: Project) {}
    override fun play() {}; override fun pause() {}; override fun stop() {}; override fun seek(tick: Long) {}
    override fun setLoop(startTick: Long?, endTick: Long?) {}
    override fun previewNote(trackId: String, pitch: Int, velocity: Int) {}
    override fun positionTick() = 0L; override fun isPlaying() = false
    override suspend fun renderOffline(project: Project, settings: RenderSettings, out: AudioSink): Result<Unit> {
        rendered.add(project)
        if (fail) return Result.failure(IllegalStateException("boom"))
        out.write(pcm, 0, 400); out.write(pcm, 400, 600); out.close(); return Result.success(Unit)
    }
    override fun release() {}
}

/** Real synth, offline. */
class RealEngine : AudioEngine by FakeEngine() {
    override suspend fun renderOffline(project: Project, settings: RenderSettings, out: AudioSink) = OfflineRenderer.render(project, settings, out, tailSeconds = 0.2f)
}

class ExporterTest {
    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test fun wavHeaderAndBody() = runBlocking {
        val s = MemSink()
        val r = DefaultExporter().toWav(sampleProject(), FakeEngine(), s)
        assertTrue(r.isSuccess); assertEquals(1, s.closeCount)
        val b = s.data()
        assertEquals(44 + 1000, b.size)
        assertEquals("RIFF", String(b, 0, 4)); assertEquals("WAVE", String(b, 8, 4)); assertEquals("data", String(b, 36, 4))
        assertEquals(36 + 1000, le32(b, 4)); assertEquals(1000, le32(b, 40))
        assertEquals(44100, le32(b, 24)); assertEquals(44100 * 4, le32(b, 28))
        assertEquals(2, b[22].toInt()); assertEquals(16, b[34].toInt())
    }

    @Test fun failedRenderWritesNothingAndStillCloses() = runBlocking {
        val s = MemSink()
        val r = DefaultExporter().toWav(sampleProject(), FakeEngine(fail = true), s)
        assertTrue(r.isFailure); assertEquals(0, s.data().size); assertEquals(1, s.closeCount)
    }

    @Test fun mp3WithoutEncoderFailsCleanly() = runBlocking {
        val s = MemSink()
        val r = DefaultExporter().toMp3(sampleProject(), FakeEngine(), 192, s)
        assertTrue(r.exceptionOrNull() is UnsupportedOperationException); assertEquals(1, s.closeCount)
    }

    @Test fun mp3RejectsOddBitrate() = runBlocking {
        val r = DefaultExporter(mp3Encoder = fakeEncoder()).toMp3(sampleProject(), FakeEngine(), 100, MemSink())
        assertTrue(r.exceptionOrNull() is IllegalArgumentException)
    }

    private fun fakeEncoder() = object : Mp3Encoder {
        var kbps = 0
        override fun open(settings: RenderSettings, kbps: Int, out: AudioSink): AudioSink {
            this.kbps = kbps
            return object : AudioSink {
                override fun write(buffer: ByteArray, offset: Int, length: Int) = out.write(buffer, offset, length)
                override fun close() { out.write("ID3".toByteArray(), 0, 3); out.close() }
            }
        }
    }

    @Test fun mp3UsesEncoderAndBitrate() = runBlocking {
        val enc = fakeEncoder(); val s = MemSink()
        val r = DefaultExporter(mp3Encoder = enc).toMp3(sampleProject(), FakeEngine(), 320, s)
        assertTrue(r.isSuccess); assertEquals(320, enc.kbps); assertEquals(1000 + 3, s.data().size); assertEquals(1, s.closeCount)
    }

    @Test fun midiViaExporter() = runBlocking {
        val s = MemSink(); assertTrue(DefaultExporter().toMidi(sampleProject(), s).isSuccess)
        assertEquals(4, Smf.parse(s.data()).tracks.size); assertEquals(1, s.closeCount)
    }

    @Test fun stemsOnePerTrackNameWithSoloIsolation() = runBlocking {
        val outs = LinkedHashMap<String, MemSink>(); val eng = FakeEngine()
        val r = DefaultExporter().toStems(sampleProject(song = true), eng, { n -> MemSink().also { outs[n] = it } })
        assertTrue(r.isSuccess)
        assertEquals(listOf("Melody", "Bass", "Drums"), outs.keys.toList())
        assertTrue(outs.values.all { it.data().size == 1044 && it.closeCount == 1 })
        // Stem #1 (Melody): only Melody tracks soloed, in every pattern.
        val soloNames = eng.rendered[0].patterns.flatMap { it.tracks }.filter { it.solo }.map { it.name }.toSet()
        assertEquals(setOf("Melody"), soloNames)
        assertTrue(eng.rendered[0].patterns.flatMap { it.tracks }.filter { !it.solo }.none { it.name == "Melody" })
    }

    @Test fun stemsSkipMutedAndReportProgress() = runBlocking {
        val p = sampleProject().let { pr -> pr.copy(patterns = pr.patterns.map { it.copy(tracks = it.tracks.map { t -> if (t.name == "Bass") t.copy(mute = true) else t }) }) }
        val seen = ArrayList<Float>(); val outs = ArrayList<String>()
        val r = DefaultExporter(onProgress = { seen.add(it) }).toStems(p, FakeEngine(), { n -> outs.add(n); MemSink() })
        assertTrue(r.isSuccess); assertEquals(listOf("Melody", "Drums"), outs)
        assertEquals(1f, seen.last());        assertTrue(seen.all { it in 0f..1f })
    }

    @Test fun stemNamesAreUniqueEvenIgnoringCase() = runBlocking {
        val tracks = listOf("Lead", "lead").map { Track(name = it, type = TrackType.MELODY, notes = listOf(note(60, 0))) }
        val p = Project(name = "x", patterns = listOf(Pattern(name = "p", tracks = tracks)))
        val outs = ArrayList<String>()
        DefaultExporter().toStems(p, FakeEngine(), { n -> outs.add(n); MemSink() })
        assertEquals(listOf("Lead", "lead 2"), outs)
    }

    @Test fun stemsWithNothingAudibleFails() = runBlocking {
        val r = DefaultExporter().toStems(Project(name = "e"), FakeEngine(), { MemSink() })
        assertTrue(r.isFailure)
    }

    @Test fun endToEndWithRealSynth() = runBlocking {
        val s = MemSink()
        val r = DefaultExporter(onProgress = {}).toWav(sampleProject(), RealEngine(), s)
        assertTrue(r.isSuccess)
        val b = s.data()
        assertEquals("RIFF", String(b, 0, 4))
        val dataLen = le32(b, 40); assertEquals(b.size - 44, dataLen)
        // 1 bar @100 bpm = 2.4 s + 0.2 s tail => about 2.6 s of audio, and it must not be silence.
        val seconds = dataLen / 4.0 / 44100
        assertTrue(seconds in 2.4..3.2, "length was $seconds")
        var peak = 0
        for (i in 44 until b.size - 1 step 2) peak = maxOf(peak, Math.abs(((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()))
        assertTrue(peak > 1000, "rendered audio is silent (peak=$peak)")
    }

    @Test fun progressSinkClampsBelowOneUntilClose() {
        val seen = ArrayList<Float>(); val s = ProgressSink(MemSink(), 100) { seen.add(it) }
        s.write(ByteArray(500), 0, 500); assertEquals(0.99f, seen.last()); s.close(); assertEquals(1f, seen.last())
    }
}
