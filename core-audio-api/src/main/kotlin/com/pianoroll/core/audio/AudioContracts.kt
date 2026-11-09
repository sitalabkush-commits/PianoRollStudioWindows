package com.pianoroll.core.audio

import com.pianoroll.core.model.*

/** Byte sink for rendered audio/files. Android: wraps OutputStream/Uri. Desktop: File. */
interface AudioSink {
    fun write(buffer: ByteArray, offset: Int, length: Int)
    fun close()
}

data class RenderSettings(val sampleRate: Int = 44100, val channels: Int = 2, val bitsPerSample: Int = 16)

/** Claude C implements. Android: Oboe + FluidSynth. Desktop (later): miniaudio + FluidSynth. */
interface AudioEngine {
    fun loadSoundFont(path: String): Result<Unit>
    fun setProject(project: Project)
    fun play()
    fun pause()
    fun stop()
    fun seek(tick: Long)
    fun setLoop(startTick: Long?, endTick: Long?)
    fun previewNote(trackId: String, pitch: Int, velocity: Int)
    fun positionTick(): Long
    fun isPlaying(): Boolean
    /** Render the whole project to PCM 16-bit little-endian, as fast as possible (offline). */
    suspend fun renderOffline(project: Project, settings: RenderSettings, out: AudioSink): Result<Unit>
    fun release()
}
