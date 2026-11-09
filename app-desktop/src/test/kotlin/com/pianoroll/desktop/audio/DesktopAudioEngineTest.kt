package com.pianoroll.desktop.audio

import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.desktop.MemSink
import com.pianoroll.desktop.testProject
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class DesktopAudioEngineTest {
    /** Records what the engine sends; sleeps 1 ms per block so the audio thread does not spin. */
    class FakeOutput : PcmOutput {
        val blocks = AtomicInteger(); val nonSilent = AtomicInteger(); val starts = AtomicInteger(); val pauses = AtomicInteger()
        val closed = AtomicInteger(); val bytes = AtomicLong()
        override fun start() { starts.incrementAndGet() }
        override fun pause() { pauses.incrementAndGet() }
        override fun write(bytes: ByteArray, length: Int) {
            blocks.incrementAndGet(); this.bytes.addAndGet(length.toLong())
            if ((0 until length).any { bytes[it].toInt() != 0 }) nonSilent.incrementAndGet()
            Thread.sleep(1)
        }
        override fun close() { closed.incrementAndGet() }
    }

    private fun waitFor(ms: Long = 5000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    @Test fun playSendsNonSilentPcmAndAdvancesPosition() {
        val out = FakeOutput()
        val e = DesktopAudioEngine(outputFactory = { out })
        e.setProject(testProject())
        e.play()
        assertTrue(waitFor { out.nonSilent.get() > 0 }, "no audio arrived")
        assertTrue(waitFor { e.positionTick() > 0 || !e.isPlaying() })
        assertEquals(256 * 4, (out.bytes.get() / out.blocks.get()).toInt())   // 256 stereo frames, 16-bit
        e.release()
        assertEquals(1, out.closed.get())
        assertNull(e.outputError)
    }

    @Test fun stopRewindsAndStopsPlaying() {
        val out = FakeOutput()
        val e = DesktopAudioEngine(outputFactory = { out })
        e.setProject(testProject().copy(bpm = 60f))   // 4 s long: still playing when we stop
        e.play()
        assertTrue(waitFor { e.isPlaying() })
        e.stop()
        assertTrue(waitFor { !e.isPlaying() && e.positionTick() == 0L })
        e.release()
    }

    @Test fun seekMovesPlayhead() {
        val out = FakeOutput()
        val e = DesktopAudioEngine(outputFactory = { out })
        e.setProject(testProject().copy(bpm = 60f))
        e.play()
        e.seek(960)
        assertTrue(waitFor { e.positionTick() >= 960 })
        e.release()
    }

    @Test fun previewNoteMakesSoundWithoutPlaying() {
        val out = FakeOutput()
        val p = testProject()
        val e = DesktopAudioEngine(outputFactory = { out })
        e.setProject(p)
        e.previewNote(p.patterns[0].tracks[0].id, 60, 100)
        assertTrue(waitFor { out.nonSilent.get() > 0 }, "preview made no sound")
        assertFalse(e.isPlaying())
        e.release()
    }

    @Test fun missingSoundDeviceFallsBackAndStillRuns() {
        val fallback = FakeOutput()
        val e = DesktopAudioEngine(
            outputFactory = { throw IllegalStateException("no mixer") },
            fallbackFactory = { fallback },
        )
        e.setProject(testProject())
        e.play()
        assertTrue(waitFor { fallback.blocks.get() > 0 })
        assertEquals("no mixer", e.outputError)
        e.release()
    }

    @Test fun engineSleepsWhenIdleAndWakesOnPlay() {
        val out = FakeOutput()
        val e = DesktopAudioEngine(sampleRate = 8000, blockFrames = 256, outputFactory = { out })   // idle after ~37 blocks
        e.setProject(testProject())
        e.previewNote(testProject().patterns[0].tracks[0].id, 60, 100)
        assertTrue(waitFor(8000) { out.pauses.get() >= 1 }, "engine never went idle")
        val startsBefore = out.starts.get()
        e.play()
        assertTrue(waitFor { out.starts.get() > startsBefore }, "engine did not wake up")
        e.release()
    }

    @Test fun releaseIsIdempotentAndLaterCallsAreHarmless() {
        val out = FakeOutput()
        val e = DesktopAudioEngine(outputFactory = { out })
        e.play()
        assertTrue(waitFor { out.blocks.get() > 0 })
        e.release(); e.release()
        e.play(); e.previewNote("x", 60, 100)   // must not start a new thread or throw
        assertEquals(1, out.closed.get())
    }

    @Test fun releaseWithoutPlayDoesNotOpenOutput() {
        val opened = AtomicInteger()
        val e = DesktopAudioEngine(outputFactory = { opened.incrementAndGet(); FakeOutput() })
        e.release()
        assertEquals(0, opened.get())
    }

    @Test fun soundFontIsReportedUnsupported() {
        val e = DesktopAudioEngine(outputFactory = { FakeOutput() })
        assertTrue(e.loadSoundFont("x.sf2").exceptionOrNull() is UnsupportedOperationException)
        e.release()
    }

    @Test fun offlineRenderWorksWithoutAudioThread() = runBlocking {
        val opened = AtomicInteger()
        val e = DesktopAudioEngine(outputFactory = { opened.incrementAndGet(); FakeOutput() })
        val sink = MemSink()
        val r = e.renderOffline(testProject(), RenderSettings(), sink)
        assertTrue(r.isSuccess)
        assertEquals(0, sink.bytes.size() % 4)
        assertTrue(sink.bytes.size() > 44100 * 4)   // at least 1 s (bar + 2 s tail)
        assertTrue(sink.bytes.toByteArray().any { it.toInt() != 0 })
        assertEquals(1, sink.closeCount)
        assertEquals(0, opened.get())
    }
}
