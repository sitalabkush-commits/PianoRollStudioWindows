package com.pianoroll.desktop.audio

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where the engine thread sends PCM16 LE stereo. [write] must block (that is what paces the audio thread). */
interface PcmOutput {
    fun start()
    /** Pause output and drop queued audio (used when the engine goes idle). */
    fun pause()
    fun write(bytes: ByteArray, length: Int)
    fun close()
}

/** javax.sound.sampled output. Small line buffer (a few blocks) for low latency. */
class JavaSoundOutput(sampleRate: Int, blockFrames: Int, blocksInBuffer: Int = 4) : PcmOutput {
    private val line: SourceDataLine

    init {
        val format = AudioFormat(sampleRate.toFloat(), 16, 2, true, false)   // signed, little-endian
        line = AudioSystem.getSourceDataLine(format)                          // throws if no device
        line.open(format, blockFrames * 4 * blocksInBuffer)
    }

    override fun start() = line.start()

    override fun pause() {
        line.stop()
        line.flush()
    }

    override fun write(bytes: ByteArray, length: Int) {
        var off = 0
        while (off < length) {
            val n = line.write(bytes, off, length - off)
            if (n <= 0) break
            off += n
        }
    }

    override fun close() {
        runCatching { line.stop() }
        runCatching { line.flush() }
        runCatching { line.close() }
    }
}

/** Fallback when no sound device exists: no sound, but real-time pacing so the playhead still moves. */
class ClockOutput(private val sampleRate: Int) : PcmOutput {
    override fun start() {}
    override fun pause() {}
    override fun write(bytes: ByteArray, length: Int) {
        val frames = length / 4
        Thread.sleep((frames * 1000L) / sampleRate)
    }
    override fun close() {}
}
