package com.pianoroll.ui.pianoroll

import com.pianoroll.core.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PianoRollMathTest {
    private val M = PianoRollMath

    @Test fun grid() {
        assertEquals(480L, M.gridTicks(4)); assertEquals(120L, M.gridTicks(16)); assertEquals(60L, M.gridTicks(32))
        assertEquals(1920L, M.ticksPerBar(4, 4)); assertEquals(1440L, M.ticksPerBar(3, 4))
    }

    @Test fun snapping() {
        assertEquals(120L, M.snapDown(179, 120)); assertEquals(120L, M.snapRound(179, 120)); assertEquals(240L, M.snapRound(181, 120))
        assertEquals(-120L, M.snapDown(-1, 120)); assertEquals(-120L, M.snapRound(-70, 120))
    }

    @Test fun scale() {
        assertTrue(M.inScale(60, 0, ScaleType.MAJOR)); assertFalse(M.inScale(61, 0, ScaleType.MAJOR))
        assertTrue(M.inScale(66, 7, ScaleType.MAJOR)) // F# in G major
        assertEquals(60, M.nearestInScale(61, 0, ScaleType.MAJOR))
        assertEquals("C4", M.noteName(60)); assertEquals("A#3", M.noteName(58))
        assertTrue(M.isBlackKey(61)); assertFalse(M.isBlackKey(60))
    }

    @Test fun moveClampsAndLocksAuto() {
        val user = Note(id = "u", pitch = 60, startTick = 100, durationTick = 120)
        val auto = Note(id = "a", pitch = 64, startTick = 200, durationTick = 120, source = NoteSource.AUTO_CHORD)
        val out = M.moveNotes(listOf(user, auto), setOf("u", "a"), -500, 2, null, 10_000)
        assertEquals(0L, out[0].startTick); assertEquals(100L, out[1].startTick) // shifted together, min start clamped to 0
        assertEquals(62, out[0].pitch)
        assertFalse(out[0].locked); assertTrue(out[1].locked)
    }

    @Test fun resizeAndVelocity() {
        val n = Note(id = "n", pitch = 60, startTick = 0, durationTick = 240)
        assertEquals(120L, M.resizeNotes(listOf(n), setOf("n"), -999, 120)[0].durationTick)
        assertEquals(127, M.setVelocity(listOf(n), setOf("n"), 500)[0].velocity)
    }

    @Test fun rect() {
        val n = Note(id = "n", pitch = 60, startTick = 100, durationTick = 100)
        assertEquals(setOf("n"), M.notesInRect(listOf(n), 150, 400, 58, 62))
        assertEquals(emptySet(), M.notesInRect(listOf(n), 200, 400, 58, 62))
    }
}
