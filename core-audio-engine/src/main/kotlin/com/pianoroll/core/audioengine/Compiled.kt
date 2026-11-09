package com.pianoroll.core.audioengine

import com.pianoroll.core.model.*

internal class Ev(val tick: Long, val on: Boolean, val track: Int, val pitch: Int, val vel: Int)

internal class TrackInfo(
    val id: String,
    val instrument: String,
    val volume: Float,
    val pan: Float,
    val mute: Boolean,
    val solo: Boolean,
    val effects: List<EffectSpec>,
)

/** Immutable, sorted, ready-to-play snapshot of a Project. Built off the audio thread. */
internal class Compiled(
    val bpm: Float,
    val tracks: List<TrackInfo>,
    val events: List<Ev>,
    val lengthTicks: Long,
    val loopStart: Long?,
    val loopEnd: Long?,
    val timeEffects: List<TimeEffect>,
) {
    val anySolo: Boolean = tracks.any { it.solo }

    /** Index of the first event with tick >= t. */
    fun firstEventAt(t: Long): Int {
        var lo = 0
        var hi = events.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (events[mid].tick < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun barTicks(p: Project): Long = PPQ.toLong() * 4L * p.timeSigNum / p.timeSigDen

        /**
         * Not song mode: plays the first pattern. Song mode: plays the playlist clips (pattern repeats to fill the clip).
         * Track index = unique (pattern, track) pair.
         */
        fun from(p: Project, honorLoop: Boolean): Compiled {
            val bar = barTicks(p)
            val tracks = ArrayList<TrackInfo>()
            val events = ArrayList<Ev>()
            val trackIndex = HashMap<String, Int>()

            fun indexOf(pattern: Pattern, t: Track): Int = trackIndex.getOrPut(pattern.id + "/" + t.id) {
                tracks.add(TrackInfo(t.id, t.instrumentId, t.volume, t.pan, t.mute, t.solo, t.effects))
                tracks.size - 1
            }

            fun addPattern(pattern: Pattern, offset: Long, maxLen: Long) {
                val patLen = pattern.lengthBars * bar
                if (patLen <= 0) return
                for (t in pattern.tracks) {
                    val ti = indexOf(pattern, t)
                    var rep = 0L
                    while (rep * patLen < maxLen) {
                        val base = offset + rep * patLen
                        val limit = offset + maxLen
                        for (n in t.notes) {
                            if (n.startTick >= patLen) continue
                            val s = base + n.startTick
                            if (s >= limit) continue
                            val e = minOf(s + maxOf(n.durationTick, 1L), limit)
                            val v = n.velocity.coerceIn(1, 127)
                            events.add(Ev(s, true, ti, n.pitch.coerceIn(0, 127), v))
                            events.add(Ev(e, false, ti, n.pitch.coerceIn(0, 127), 0))
                        }
                        if (!p.songMode) break
                        rep++
                    }
                }
            }

            var length = 0L
            if (p.songMode && p.playlist.isNotEmpty()) {
                for (c in p.playlist) {
                    val pat = p.patterns.firstOrNull { it.id == c.patternId } ?: continue
                    val start = c.startBar * bar
                    val len = c.lengthBars * bar
                    addPattern(pat, start, len)
                    length = maxOf(length, start + len)
                }
            } else {
                val pat = p.patterns.firstOrNull()
                if (pat != null) {
                    length = pat.lengthBars * bar
                    addPattern(pat, 0L, length)
                }
            }
            // offs before ons at the same tick, so a re-struck note retriggers cleanly
            events.sortWith(compareBy<Ev> { it.tick }.thenBy { if (it.on) 1 else 0 })

            var ls: Long? = null
            var le: Long? = null
            val lsBar = p.loopStartBar
            val leBar = p.loopEndBar
            if (honorLoop && lsBar != null && leBar != null && leBar > lsBar) {   // loopEndBar is EXCLUSIVE
                ls = lsBar * bar
                le = leBar * bar
            }
            return Compiled(p.bpm.coerceIn(20f, 400f), tracks, events, length, ls, le, p.timeEffects)
        }
    }
}
