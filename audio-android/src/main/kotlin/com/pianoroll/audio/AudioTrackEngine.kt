package com.pianoroll.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.pianoroll.core.audio.AudioEngine
import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import com.pianoroll.core.audioengine.OfflineRenderer
import com.pianoroll.core.audioengine.SynthEngine
import com.pianoroll.core.model.Project

/**
 * Android AudioEngine: pure-Kotlin SynthEngine -> AudioTrack (float PCM, low-latency mode).
 * The render thread sleeps when nothing is playing and wakes on play()/previewNote().
 * Oboe/FluidSynth (SoundFont .sf2) will replace the output + voice layer later without changing this interface.
 */
class AudioTrackEngine(private val sampleRate: Int = 44100) : AudioEngine {
    private val synth = SynthEngine(sampleRate)
    private val lock = Object()
    @Volatile private var running = true
    @Volatile private var awake = false
    private var thread: Thread? = null

    private fun ensureThread() {
        synchronized(lock) {
            awake = true
            if (thread == null) {
                thread = Thread({ loop() }, "pianoroll-audio").also { it.isDaemon = true; it.start() }
            }
            lock.notifyAll()
        }
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frames = 256
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, frames * 2 * 4 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        val buf = FloatArray(frames * 2)
        var silent = 0
        try {
            track.play()
            while (running) {
                val peak = synth.render(buf, frames)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
                if (!synth.isPlaying() && peak < 1e-5f) silent++ else silent = 0
                if (silent > 200) {   // ~1.2 s of silence while stopped: sleep until needed
                    track.pause(); track.flush()
                    synchronized(lock) {
                        awake = false
                        while (!awake && running) lock.wait()
                    }
                    silent = 0
                    if (running) track.play()
                }
            }
        } finally {
            track.stop(); track.release()
        }
    }

    override fun loadSoundFont(path: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("SoundFont (.sf2) needs the FluidSynth native layer (next step). Built-in synth is used."))

    override fun setProject(project: Project) = synth.setProject(project)
    override fun play() { synth.play(); ensureThread() }
    override fun pause() = synth.pause()
    override fun stop() = synth.stop()
    override fun seek(tick: Long) = synth.seek(tick)
    override fun setLoop(startTick: Long?, endTick: Long?) = synth.setLoop(startTick, endTick)
    override fun previewNote(trackId: String, pitch: Int, velocity: Int) { synth.previewNote(trackId, pitch, velocity); ensureThread() }
    override fun positionTick(): Long = synth.positionTick()
    override fun isPlaying(): Boolean = synth.isPlaying()

    override suspend fun renderOffline(project: Project, settings: RenderSettings, out: AudioSink): Result<Unit> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { OfflineRenderer.render(project, settings, out) }

    override fun release() {
        synchronized(lock) { running = false; lock.notifyAll() }
    }
}
