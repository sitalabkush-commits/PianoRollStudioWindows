package com.pianoroll.desktop.audio

import kotlin.test.*

class Pcm16Test {
    private fun s16(b: ByteArray, i: Int) = ((b[i * 2].toInt() and 0xFF) or (b[i * 2 + 1].toInt() shl 8)).toShort().toInt()

    @Test fun convertsLittleEndianInterleaved() {
        val out = ByteArray(8)
        val n = Pcm16.fromStereoFloat(floatArrayOf(0f, 0.5f, -0.5f, 1f), 2, out)
        assertEquals(8, n)
        assertEquals(0, s16(out, 0)); assertEquals(16383, s16(out, 1)); assertEquals(-16383, s16(out, 2)); assertEquals(32767, s16(out, 3))
    }

    @Test fun clipsAndHandlesNaN() {
        val out = ByteArray(8)
        Pcm16.fromStereoFloat(floatArrayOf(5f, -5f, Float.NaN, Float.POSITIVE_INFINITY), 2, out)
        assertEquals(32767, s16(out, 0)); assertEquals(-32767, s16(out, 1)); assertEquals(0, s16(out, 2)); assertEquals(32767, s16(out, 3))
    }

    @Test fun rejectsTooSmallBuffers() {
        assertFailsWith<IllegalArgumentException> { Pcm16.fromStereoFloat(FloatArray(2), 2, ByteArray(8)) }
        assertFailsWith<IllegalArgumentException> { Pcm16.fromStereoFloat(FloatArray(4), 2, ByteArray(4)) }
    }
}
