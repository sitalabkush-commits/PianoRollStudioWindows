package com.pianoroll.export.song

import com.pianoroll.core.model.Command
import com.pianoroll.core.model.Pattern
import com.pianoroll.core.model.PlaylistClip
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.newId
import com.pianoroll.export.Arrangement

/**
 * Pattern manager + Playlist (Song Mode) logic as Commands, so the UI gets undo/redo for free.
 * All commands are no-ops (return the same Project) when their target does not exist, so a stale UI can't corrupt state.
 * Clips never overlap inside one lane: placing/moving into an occupied spot picks the nearest free lane.
 */
object SongCommands {
    const val MAX_BARS = 512
    const val MAX_LANES = 32

    // ---------- patterns ----------

    /** New empty pattern. Track layout (names, instruments, mixer, effects) is copied from the first pattern, notes are not. */
    fun addPattern(name: String? = null, lengthBars: Int = 4, colorArgb: Long? = null, id: String = newId()) = Command { p ->
        val template = p.patterns.firstOrNull()
        val tracks = template?.tracks?.map { it.copy(id = newId(), notes = emptyList()) } ?: emptyList()
        val pat = Pattern(
            id = id,
            name = name ?: "Pattern ${p.patterns.size + 1}",
            lengthBars = lengthBars.coerceIn(1, MAX_BARS),
            tracks = tracks,
            colorArgb = colorArgb ?: PALETTE[p.patterns.size % PALETTE.size],
        )
        p.copy(patterns = p.patterns + pat)
    }

    /** Deep copy with fresh track and note ids; inserted right after the original. */
    fun clonePattern(patternId: String, id: String = newId(), name: String? = null) = Command { p ->
        val i = p.patterns.indexOfFirst { it.id == patternId }
        if (i < 0) return@Command p
        val src = p.patterns[i]
        val copy = src.copy(
            id = id,
            name = name ?: "${src.name} copy",
            tracks = src.tracks.map { t -> t.copy(id = newId(), notes = t.notes.map { n -> n.copy(id = newId()) }) },
        )
        p.copy(patterns = p.patterns.toMutableList().also { it.add(i + 1, copy) })
    }

    /** Removes the pattern and every clip that uses it. The last remaining pattern cannot be deleted. */
    fun deletePattern(patternId: String) = Command { p ->
        if (p.patterns.size <= 1 || p.patterns.none { it.id == patternId }) p
        else p.copy(patterns = p.patterns.filterNot { it.id == patternId }, playlist = p.playlist.filterNot { it.patternId == patternId })
    }

    fun renamePattern(patternId: String, name: String) = Command { p -> p.mapPattern(patternId) { it.copy(name = name.trim().ifEmpty { it.name }) } }

    fun setPatternColor(patternId: String, colorArgb: Long) = Command { p -> p.mapPattern(patternId) { it.copy(colorArgb = colorArgb) } }

    fun setPatternLength(patternId: String, lengthBars: Int) = Command { p -> p.mapPattern(patternId) { it.copy(lengthBars = lengthBars.coerceIn(1, MAX_BARS)) } }

    // ---------- playlist ----------

    /** Adds a clip. [lengthBars] defaults to the pattern length. Returns a no-op when the pattern is unknown. */
    fun addClip(patternId: String, startBar: Int, lane: Int = 0, lengthBars: Int? = null, id: String = newId()) = Command { p ->
        val pat = p.patterns.firstOrNull { it.id == patternId } ?: return@Command p
        val len = (lengthBars ?: pat.lengthBars).coerceIn(1, MAX_BARS)
        val start = startBar.coerceAtLeast(0)
        val l = freeLane(p.playlist, null, start, len, lane)
        p.copy(playlist = p.playlist + PlaylistClip(id = id, patternId = patternId, startBar = start, lengthBars = len, lane = l))
    }

    fun moveClip(clipId: String, startBar: Int, lane: Int) = Command { p ->
        val c = p.playlist.firstOrNull { it.id == clipId } ?: return@Command p
        val start = startBar.coerceAtLeast(0)
        val l = freeLane(p.playlist, clipId, start, c.lengthBars, lane)
        p.mapClip(clipId) { it.copy(startBar = start, lane = l) }
    }

    /** Resize from the right edge. Shrinks to the room available if the next clip in the lane is in the way. */
    fun resizeClip(clipId: String, lengthBars: Int) = Command { p ->
        val c = p.playlist.firstOrNull { it.id == clipId } ?: return@Command p
        val nextStart = p.playlist.filter { it.id != clipId && it.lane == c.lane && it.startBar >= c.startBar + 1 }.minOfOrNull { it.startBar }
        val room = if (nextStart == null) MAX_BARS else nextStart - c.startBar
        p.mapClip(clipId) { it.copy(lengthBars = lengthBars.coerceIn(1, maxOf(1, minOf(MAX_BARS, room)))) }
    }

    fun removeClip(clipId: String) = Command { p -> p.copy(playlist = p.playlist.filterNot { it.id == clipId }) }

    /** Copy placed immediately after the original (same lane if free, otherwise the nearest free lane). */
    fun duplicateClip(clipId: String, id: String = newId()) = Command { p ->
        val c = p.playlist.firstOrNull { it.id == clipId } ?: return@Command p
        val start = c.startBar + c.lengthBars
        val l = freeLane(p.playlist, null, start, c.lengthBars, c.lane)
        p.copy(playlist = p.playlist + c.copy(id = id, startBar = start, lane = l))
    }

    /** Splits at an absolute bar strictly inside the clip. The second half gets [newId]. */
    fun splitClip(clipId: String, atBar: Int, newId: String = newId()) = Command { p ->
        val c = p.playlist.firstOrNull { it.id == clipId } ?: return@Command p
        if (atBar <= c.startBar || atBar >= c.startBar + c.lengthBars) return@Command p
        val left = c.copy(lengthBars = atBar - c.startBar)
        val right = c.copy(id = newId, startBar = atBar, lengthBars = c.startBar + c.lengthBars - atBar)
        p.copy(playlist = p.playlist.flatMap { if (it.id == clipId) listOf(left, right) else listOf(it) })
    }

    fun setSongMode(on: Boolean) = Command { p -> if (p.songMode == on) p else p.copy(songMode = on) }

    /** Song Mode needs at least one clip to be audible; this lays every pattern end to end on lane 0. */
    fun arrangeAllPatterns() = Command { p ->
        var bar = 0
        val clips = p.patterns.map { pat -> PlaylistClip(patternId = pat.id, startBar = bar, lengthBars = pat.lengthBars, lane = 0).also { bar += pat.lengthBars } }
        p.copy(playlist = clips)
    }

    // ---------- queries ----------

    /** Project length in bars for the current mode (matches what playback / export will render). */
    fun songLengthBars(p: Project): Int {
        val bar = Arrangement.barTicks(p)
        return if (bar <= 0) 0 else ((Arrangement.lengthTicks(p) + bar - 1) / bar).toInt()
    }

    // ---------- helpers ----------

    private val PALETTE = longArrayOf(0xFF4FC3F7, 0xFFFF8A65, 0xFF81C784, 0xFFBA68C8, 0xFFFFD54F, 0xFF4DB6AC, 0xFFE57373, 0xFF9575CD)

    private fun Project.mapPattern(id: String, f: (Pattern) -> Pattern) = copy(patterns = patterns.map { if (it.id == id) f(it) else it })
    private fun Project.mapClip(id: String, f: (PlaylistClip) -> PlaylistClip) = copy(playlist = playlist.map { if (it.id == id) f(it) else it })

    private fun overlaps(a: PlaylistClip, start: Int, len: Int) = a.startBar < start + len && start < a.startBar + a.lengthBars

    /** Wanted lane if free, else the nearest free lane (searching up, then down), else the wanted lane. */
    internal fun freeLane(clips: List<PlaylistClip>, ignoreId: String?, start: Int, len: Int, wanted: Int): Int {
        val w = wanted.coerceIn(0, MAX_LANES - 1)
        fun free(l: Int) = clips.none { it.id != ignoreId && it.lane == l && overlaps(it, start, len) }
        if (free(w)) return w
        for (d in 1 until MAX_LANES) {
            if (w + d < MAX_LANES && free(w + d)) return w + d
            if (w - d >= 0 && free(w - d)) return w - d
        }
        return w
    }
}
