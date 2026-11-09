package com.pianoroll.desktop.audio

/** Float (-1..1, interleaved) -> PCM16 little-endian. Pure function, easy to test. */
object Pcm16 {
    /** Converts [frames] stereo frames from [src] into [dst]. Returns the number of bytes written (frames * 4). */
    fun fromStereoFloat(src: FloatArray, frames: Int, dst: ByteArray): Int {
        require(src.size >= frames * 2) { "src too small" }
        require(dst.size >= frames * 4) { "dst too small" }
        var o = 0
        for (i in 0 until frames * 2) {
            val f = src[i]
            val clipped = if (f > 1f) 1f else if (f < -1f) -1f else if (f.isNaN()) 0f else f
            val v = (clipped * 32767f).toInt()
            dst[o++] = (v and 0xFF).toByte()
            dst[o++] = ((v shr 8) and 0xFF).toByte()
        }
        return frames * 4
    }
}
