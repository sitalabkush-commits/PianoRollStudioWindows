package com.pianoroll.export

import com.pianoroll.core.audio.AudioSink
import com.pianoroll.core.audio.RenderSettings
import java.io.ByteArrayOutputStream

/** Platform MP3 encoder (LAME via JNI on Android). Input is PCM16 little-endian. */
interface Mp3Encoder {
    /** Returns a sink that takes PCM16 LE; its `close()` must flush the encoder and close [out]. */
    fun open(settings: RenderSettings, kbps: Int, out: AudioSink): AudioSink
}

object WavHeader {
    fun build(dataBytes: Int, s: RenderSettings): ByteArray {
        val byteRate = s.sampleRate * s.channels * s.bitsPerSample / 8
        val blockAlign = s.channels * s.bitsPerSample / 8
        val b = ByteArrayOutputStream(44)
        fun str(x: String) = b.write(x.toByteArray(Charsets.US_ASCII))
        fun i32(v: Int) { b.write(v); b.write(v ushr 8); b.write(v ushr 16); b.write(v ushr 24) }
        fun i16(v: Int) { b.write(v); b.write(v ushr 8) }
        str("RIFF"); i32(36 + dataBytes); str("WAVE")
        str("fmt "); i32(16); i16(1); i16(s.channels); i32(s.sampleRate); i32(byteRate); i16(blockAlign); i16(s.bitsPerSample)
        str("data"); i32(dataBytes)
        return b.toByteArray()
    }
}

/**
 * Wraps a real sink. Buffers PCM16 LE (a WAV header needs the final length), then on `close()` writes header + data.
 * `discard()` closes the target without writing anything (used when a render fails).
 */
class WavSink(private val target: AudioSink, private val settings: RenderSettings) : AudioSink {
    private val pcm = ByteArrayOutputStream()
    private var done = false

    override fun write(buffer: ByteArray, offset: Int, length: Int) { if (!done) pcm.write(buffer, offset, length) }

    override fun close() {
        if (done) return
        done = true
        val data = pcm.toByteArray()
        try {
            val header = WavHeader.build(data.size, settings)
            target.write(header, 0, header.size)
            target.write(data, 0, data.size)
        } finally { target.close() }
    }

    fun discard() {
        if (done) return
        done = true
        target.close()
    }
}

/** Passes bytes through and reports fraction = bytes / expectedBytes (clamped to 0..0.99 until finished). */
class ProgressSink(
    private val target: AudioSink,
    private val expectedBytes: Long,
    private val onProgress: (Float) -> Unit,
) : AudioSink {
    private var written = 0L
    private var closed = false

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        target.write(buffer, offset, length)
        written += length
        if (expectedBytes > 0) onProgress((written.toDouble() / expectedBytes).toFloat().coerceIn(0f, 0.99f))
    }

    override fun close() {
        if (closed) return
        closed = true
        target.close()
        onProgress(1f)
    }
}
