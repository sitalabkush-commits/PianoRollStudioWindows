package com.pianoroll.desktop.audio

import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.audioengine.OfflineRenderer
import com.pianoroll.core.audioengine.SynthEngine
import com.pianoroll.core.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop [AudioEngine]: pure-Kotlin [SynthEngine] -> [PcmOutput] (javax.sound.sampled by default).
 * Mirrors audio-android's AudioTrackEngine: one render thread, sleeps when idle, wakes on play()/previewNote().
 * If the sound device cannot be opened the engine keeps working silently ([ClockOutput]) and reports why in [outputError].
 * Offline render uses its own SynthEngine instance (OfflineRenderer), so exporting never disturbs live playback.
 */
class DesktopAudioEngine(
    private val sampleRate: Int = 44100,
    private val blockFrames: Int = 256,
    private val outputFactory: () -> PcmOutput = { JavaSoundOutput(sampleRate, blockFrames) },
    private val fallbackFactory: () -> PcmOutput = { ClockOutput(sampleRate) },
) : AudioEngine {
    private val synth = SynthEngine(sampleRate)
    private val lock = Object()
    @Volatile private var running = true
    private var wakeSeq = 0L            // guarded by lock
    private var thread: Thread? = null  // guarded by lock

    /** Why real audio is unavailable (null = output is fine or not opened yet). */
    @Volatile var outputError: String? = null
        private set

    private fun wake() {
        synchronized(lock) {
            if (!running) return
            wakeSeq++
            if (thread == null) {
                thread = Thread({ loop() }, "pianoroll-audio").also {
                    it.isDaemon = true
                    it.priority = Thread.MAX_PRIORITY
                    it.start()
                }
            }
            lock.notifyAll()
        }
    }

    private fun openOutput(): PcmOutput =
        try {
            outputFactory()
        } catch (t: Throwable) {
            outputError = t.message ?: t.javaClass.simpleName
            fallbackFactory()
        }

    private fun loop() {
        val out = openOutput()
        val buf = FloatArray(blockFrames * 2)
        val bytes = ByteArray(blockFrames * 4)
        val idleBlocks = (sampleRate * 1.2 / blockFrames).toInt()   // ~1.2 s of silence while stopped -> sleep
        var silent = 0
        try {
            out.start()
            while (running) {
                val seen = synchronized(lock) { wakeSeq }
                val peak = synth.render(buf, blockFrames)
                val n = Pcm16.fromStereoFloat(buf, blockFrames, bytes)
                out.write(bytes, n)
                if (!synth.isPlaying() && peak < 1e-5f) silent++ else silent = 0
                if (silent > idleBlocks) {
                    out.pause()
                    synchronized(lock) {
                        while (wakeSeq == seen && running) lock.wait()   // a wake() after `seen` means: do not sleep
                    }
                    silent = 0
                    if (running) out.start()
                }
            }
        } catch (e: InterruptedException) {
            // release() asked us to stop
        } finally {
            out.close()
        }
    }

    override fun loadSoundFont(path: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("SoundFont (.sf2) needs FluidSynth (planned). Built-in synth is used."))

    override fun setProject(project: Project) = synth.setProject(project)
    override fun play() { synth.play(); wake() }
    override fun pause() = synth.pause()
    override fun stop() = synth.stop()
    override fun seek(tick: Long) = synth.seek(tick)
    override fun setLoop(startTick: Long?, endTick: Long?) = synth.setLoop(startTick, endTick)
    override fun previewNote(trackId: String, pitch: Int, velocity: Int) { synth.previewNote(trackId, pitch, velocity); wake() }
    override fun positionTick(): Long = synth.positionTick()
    override fun isPlaying(): Boolean = synth.isPlaying()

    override suspend fun renderOffline(project: Project, settings: RenderSettings, out: AudioSink): Result<Unit> =
        withContext(Dispatchers.Default) { OfflineRenderer.render(project, settings, out) }

    override fun release() {
        val t = synchronized(lock) {
            running = false
            lock.notifyAll()
            thread
        }
        t?.join(500)
    }
}
