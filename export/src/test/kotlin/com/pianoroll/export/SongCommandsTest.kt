package com.pianoroll.export

import com.pianoroll.core.model.*
import com.pianoroll.export.song.SongCommands
import kotlin.test.*

class SongCommandsTest {
    private fun run(p: Project, vararg c: Command) = c.fold(p) { acc, cmd -> cmd.apply(acc) }
    private val base = sampleProject()

    @Test fun addPatternCopiesLayoutNotNotes() {
        val p = run(base, SongCommands.addPattern(id = "N", lengthBars = 8))
        val n = p.patterns.last()
        assertEquals("N", n.id); assertEquals(8, n.lengthBars); assertEquals("Pattern 3", n.name)
        assertEquals(base.patterns[0].tracks.map { it.name }, n.tracks.map { it.name })
        assertTrue(n.tracks.all { it.notes.isEmpty() })
        assertTrue(n.tracks.map { it.id }.intersect(base.patterns.flatMap { it.tracks }.map { it.id }.toSet()).isEmpty())
    }

    @Test fun cloneIsDeepWithFreshIdsAndInsertedAfter() {
        val p = run(base, SongCommands.clonePattern("A", id = "A2"))
        assertEquals(listOf("A", "A2", "B"), p.patterns.map { it.id })
        val a = p.patterns[0]; val c = p.patterns[1]
        assertEquals("Verse copy", c.name)
        assertEquals(a.tracks.map { t -> t.notes.map { it.pitch } }, c.tracks.map { t -> t.notes.map { it.pitch } })
        assertTrue((a.tracks + c.tracks).map { it.id }.toSet().size == a.tracks.size * 2)
        assertTrue((a.tracks.flatMap { it.notes } + c.tracks.flatMap { it.notes }).map { it.id }.toSet().size == a.tracks.sumOf { it.notes.size } * 2)
    }

    @Test fun deleteRemovesClipsAndKeepsLastPattern() {
        val p = run(base, SongCommands.deletePattern("A"))
        assertEquals(listOf("B"), p.patterns.map { it.id }); assertEquals(listOf("c2"), p.playlist.map { it.id })
        assertSame(p, SongCommands.deletePattern("B").apply(p))
    }

    @Test fun renameColorLength() {
        val p = run(base, SongCommands.renamePattern("A", "  Intro "), SongCommands.setPatternColor("A", 0xFF112233), SongCommands.setPatternLength("A", 9999))
        assertEquals("Intro", p.patterns[0].name); assertEquals(0xFF112233, p.patterns[0].colorArgb); assertEquals(SongCommands.MAX_BARS, p.patterns[0].lengthBars)
        assertEquals("Verse", run(base, SongCommands.renamePattern("A", "   ")).patterns[0].name)
    }

    @Test fun addClipDefaultsAndLanes() {
        val p0 = base.copy(playlist = emptyList())
        val p1 = run(p0, SongCommands.addClip("A", 0, id = "x"))
        assertEquals(1, p1.playlist[0].lengthBars)
        val p2 = run(p1, SongCommands.addClip("A", 0, lane = 0, id = "y"))        // same spot, lane 0 busy -> lane 1
        assertEquals(1, p2.playlist.first { it.id == "y" }.lane)
        val p3 = run(p2, SongCommands.addClip("A", 1, lane = 0, id = "z"))        // adjacent, not overlapping -> stays
        assertEquals(0, p3.playlist.first { it.id == "z" }.lane)
        assertSame(p3, SongCommands.addClip("nope", 0).apply(p3))
    }

    @Test fun moveClipAvoidsOverlapButNotItself() {
        val p = run(base, SongCommands.moveClip("c1", 1, 0))                       // c1 (2 bars) -> bar 1 overlaps c2 at bar 2 -> lane 1
        assertEquals(1, p.playlist.first { it.id == "c1" }.startBar); assertEquals(1, p.playlist.first { it.id == "c1" }.lane)
        val q = run(base, SongCommands.moveClip("c1", 1, 0).let { it }, SongCommands.moveClip("c1", 0, 0))
        assertEquals(0, q.playlist.first { it.id == "c1" }.lane)
        assertEquals(0, run(base, SongCommands.moveClip("c1", -5, 0)).playlist.first { it.id == "c1" }.startBar)
    }

    @Test fun resizeStopsAtNextClip() {
        val p = base.copy(playlist = listOf(PlaylistClip("a", "A", 0, 1), PlaylistClip("b", "B", 3, 1)))
        assertEquals(3, run(p, SongCommands.resizeClip("a", 10)).playlist.first { it.id == "a" }.lengthBars)
        assertEquals(2, run(p, SongCommands.resizeClip("a", 2)).playlist.first { it.id == "a" }.lengthBars)
        assertEquals(1, run(p, SongCommands.resizeClip("a", 0)).playlist.first { it.id == "a" }.lengthBars)
    }

    @Test fun duplicateAndSplit() {
        val d = run(base, SongCommands.duplicateClip("c1", id = "d"))
        val dup = d.playlist.first { it.id == "d" }
        assertEquals(2, dup.startBar)                                              // right after c1 (0..2) but c2 sits at 2 -> pushed to lane 1
        assertEquals(1, dup.lane)
        val s = run(base, SongCommands.splitClip("c1", 1, newId = "s"))
        assertEquals(listOf(0 to 1, 1 to 1), s.playlist.filter { it.id == "c1" || it.id == "s" }.map { it.startBar to it.lengthBars })
        assertSame(base, SongCommands.splitClip("c1", 0).apply(base)); assertSame(base, SongCommands.splitClip("c1", 2).apply(base))
    }

    @Test fun removeClipAndSongMode() {
        assertEquals(listOf("c2"), run(base, SongCommands.removeClip("c1")).playlist.map { it.id })
        assertTrue(run(base, SongCommands.setSongMode(true)).songMode)
    }

    @Test fun arrangeAllAndLength() {
        val p = run(base, SongCommands.arrangeAllPatterns(), SongCommands.setSongMode(true))
        assertEquals(listOf(0, 1), p.playlist.map { it.startBar }); assertEquals(2, SongCommands.songLengthBars(p))
        assertEquals(3, SongCommands.songLengthBars(sampleProject(song = true)))
        assertEquals(1, SongCommands.songLengthBars(sampleProject(song = false)))   // pattern mode: first pattern only
    }

    @Test fun worksThroughProjectStoreWithUndo() {
        val store = ProjectStore(base)
        store.execute(SongCommands.clonePattern("A", id = "A2"))
        assertEquals(3, store.state.value.patterns.size)
        store.undo(); assertEquals(2, store.state.value.patterns.size)
    }
}
