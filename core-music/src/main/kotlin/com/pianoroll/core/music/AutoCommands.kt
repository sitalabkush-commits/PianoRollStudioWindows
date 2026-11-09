package com.pianoroll.core.music

import com.pianoroll.core.model.ArpOptions
import com.pianoroll.core.model.ChopMode
import com.pianoroll.core.model.ChordBlock
import com.pianoroll.core.model.ChordOptions
import com.pianoroll.core.model.ChordQuality
import com.pianoroll.core.model.Command
import com.pianoroll.core.model.Commands
import com.pianoroll.core.model.DrumOptions
import com.pianoroll.core.model.KeyResult
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.Pattern
import com.pianoroll.core.model.Project
import com.pianoroll.core.model.ScaleType
import com.pianoroll.core.model.Track
import com.pianoroll.core.model.TrackType
import com.pianoroll.core.model.Voicing

/**
 * Commands that run the generators and edit the Project (undo/redo comes from ProjectStore).
 *
 * Safety rules (CONTRACTS rule 4):
 *  - a generator only replaces notes of its own source (AUTO_CHORD / AUTO_DRUM / AUTO_ARP) that are not locked;
 *  - USER notes, PHRASE notes and locked notes are never touched by generators;
 *  - a track with autoMode = false is in Manual mode: generators leave it alone.
 */
object AutoCommands {

    private val chordGen = DefaultChordGenerator()
    private val drumGen = DefaultDrumGenerator()
    private val arp = DefaultArpeggiator()
    private val transposer = DefaultTransposeEngine()
    private val chopper = DefaultChopEngine()

    fun barTicks(p: Project): Long = PPQ.toLong() * 4L * p.timeSigNum / p.timeSigDen.coerceAtLeast(1)

    private fun pattern(p: Project, id: String): Pattern? = p.patterns.firstOrNull { it.id == id }
    private fun track(pat: Pattern, id: String): Track? = pat.tracks.firstOrNull { it.id == id }

    private fun setNotes(p: Project, patternId: String, trackId: String, notes: List<Note>): Project =
        Commands.updateTrack(patternId, trackId) { t -> t.copy(notes = notes.sortedBy { it.startTick }) }.apply(p)

    // ---------------------------------------------------------------- auto chord

    /** Melody track -> chord notes (AUTO_CHORD) on the chord track. */
    fun autoChords(
        patternId: String,
        melodyTrackId: String,
        chordTrackId: String,
        options: ChordOptions = ChordOptions(),
        voicing: Voicing = Voicing(),
        useProjectKey: Boolean = true,
    ): Command = Command { p -> applyChords(p, patternId, melodyTrackId, chordTrackId, options, voicing, useProjectKey) }

    private fun applyChords(
        p: Project, patternId: String, melodyTrackId: String, chordTrackId: String,
        options: ChordOptions, voicing: Voicing, useProjectKey: Boolean,
    ): Project {
        val pat = pattern(p, patternId) ?: return p
        val melody = track(pat, melodyTrackId) ?: return p
        val chord = track(pat, chordTrackId) ?: return p
        if (!chord.autoMode || melody.notes.isEmpty()) return p
        val key = if (useProjectKey) KeyResult(p.keyRoot, p.scale) else chordGen.detectKey(melody.notes)
        val bar = barTicks(p)
        val patEnd = pat.lengthBars * bar
        val blocks = chordGen.generate(melody.notes, key, options, bar)
            .filter { it.startTick < patEnd }
            .map { if (it.startTick + it.durationTick > patEnd) it.copy(durationTick = patEnd - it.startTick) else it }
        val lockedAuto = chord.notes.filter { it.locked && it.source == NoteSource.AUTO_CHORD }
        val free = blocks.filter { b ->
            lockedAuto.none { l -> l.startTick < b.startTick + b.durationTick && b.startTick < l.startTick + l.durationTick }
        }
        val fresh = chordGen.toNotesVoiceLed(free, voicing)
        val kept = chord.notes.filterNot { it.source == NoteSource.AUTO_CHORD && !it.locked }
        return setNotes(p, patternId, chordTrackId, kept + fresh)
    }

    /** Change the project key, then rebuild the auto chords for it ("key change -> auto chord adapts"). */
    fun setKeyAndRegenerateChords(
        root: Int, scale: ScaleType,
        patternId: String, melodyTrackId: String, chordTrackId: String,
        options: ChordOptions = ChordOptions(), voicing: Voicing = Voicing(),
    ): Command = Command { p ->
        val keyed = Commands.setKey(root, scale).apply(p)
        applyChords(keyed, patternId, melodyTrackId, chordTrackId, options, voicing, true)
    }

    /** Detect the key of a melody track and write it to the project. */
    fun detectAndSetKey(patternId: String, melodyTrackId: String): Command = Command { p ->
        val notes = pattern(p, patternId)?.let { track(it, melodyTrackId) }?.notes.orEmpty()
        if (notes.isEmpty()) p else {
            val k = chordGen.detectKey(notes)
            Commands.setKey(k.root, k.scale).apply(p)
        }
    }

    // ---------------------------------------------------------------- advanced chord edit (Replace / Lock)

    /** Chord blocks rebuilt from the AUTO_CHORD notes of a track (for the Replace menu and name toggle). */
    fun chordBlocks(track: Track): List<ChordBlock> =
        track.notes.filter { it.source == NoteSource.AUTO_CHORD }
            .groupBy { it.startTick to it.durationTick }
            .entries.sortedBy { it.key.first }
            .mapNotNull { (k, ns) ->
                val id = ChordTheory.identify(ns.map { it.pitch }) ?: return@mapNotNull null
                ChordBlock(startTick = k.first, durationTick = k.second, rootPc = id.rootPc, quality = id.quality,
                    bassPc = id.bassPc, locked = ns.all { it.locked })
            }

    /** Advanced Replace: sus2/sus4/dim/aug/7ths/9-11-13/slash/inversion/voicing for the chord at [startTick]. */
    fun replaceChord(
        patternId: String, trackId: String, startTick: Long, quality: ChordQuality,
        rootPc: Int? = null, bassPc: Int? = null, voicing: Voicing = Voicing(), lock: Boolean = false,
    ): Command = Command { p ->
        val t = pattern(p, patternId)?.let { track(it, trackId) }
        val group = t?.notes?.filter { it.source == NoteSource.AUTO_CHORD && it.startTick == startTick }.orEmpty()
        if (t == null || group.isEmpty() || group.any { it.locked }) p else {
            val id = ChordTheory.identify(group.map { it.pitch })
            val root = rootPc ?: id?.rootPc
            if (root == null) p else {
                val block = ChordBlock(startTick = startTick, durationTick = group.first().durationTick,
                    rootPc = root, quality = quality, bassPc = bassPc, locked = lock)
                val rest = t.notes.filterNot { it.source == NoteSource.AUTO_CHORD && it.startTick == startTick }
                setNotes(p, patternId, trackId, rest + chordGen.toNotes(block, voicing))
            }
        }
    }

    /** Lock icon on a chord block: Auto will not change it any more. */
    fun setChordLock(patternId: String, trackId: String, startTick: Long, locked: Boolean): Command =
        Commands.updateTrack(patternId, trackId) { t ->
            t.copy(notes = t.notes.map {
                if (it.source == NoteSource.AUTO_CHORD && it.startTick == startTick) it.copy(locked = locked) else it
            })
        }

    // ---------------------------------------------------------------- arpeggio

    /** Chord track notes -> arpeggio notes (AUTO_ARP) on the target track. */
    fun autoArp(patternId: String, chordTrackId: String, arpTrackId: String, options: ArpOptions = ArpOptions()): Command =
        Command { p ->
            val pat = pattern(p, patternId)
            val src = pat?.let { track(it, chordTrackId) }
            val dst = pat?.let { track(it, arpTrackId) }
            if (src == null || dst == null || !dst.autoMode || src.notes.isEmpty()) p else {
                val fresh = arp.apply(src.notes, options)
                val kept = dst.notes.filterNot { it.source == NoteSource.AUTO_ARP && !it.locked }
                setNotes(p, patternId, arpTrackId, kept + fresh)
            }
        }

    // ---------------------------------------------------------------- drums

    /** Generate drums for the whole pattern length (4/4 grid). */
    fun autoDrums(patternId: String, drumTrackId: String, options: DrumOptions = DrumOptions()): Command = Command { p ->
        val pat = pattern(p, patternId)
        val dst = pat?.let { track(it, drumTrackId) }
        if (pat == null || dst == null || !dst.autoMode) p else {
            val fresh = drumGen.generate(options, pat.lengthBars)
            val lockedDrums = dst.notes.filter { it.locked && it.source == NoteSource.AUTO_DRUM }
            val usable = fresh.filter { f -> lockedDrums.none { it.pitch == f.pitch && it.startTick == f.startTick } }
            val kept = dst.notes.filterNot { it.source == NoteSource.AUTO_DRUM && !it.locked }
            setNotes(p, patternId, drumTrackId, kept + usable)
        }
    }

    // ---------------------------------------------------------------- transpose

    /** Transpose selected notes (or the whole track when [noteIds] is null). Drum tracks are skipped. */
    fun transposeNotes(
        patternId: String, trackId: String, semitones: Int, scaleAware: Boolean, noteIds: Set<String>? = null,
    ): Command = Command { p ->
        val t = pattern(p, patternId)?.let { track(it, trackId) }
        if (t == null || t.type == TrackType.DRUMS) p else {
            val key = KeyResult(p.keyRoot, p.scale)
            val s = semitones.coerceIn(-12, 12)
            val (sel, other) = t.notes.partition { noteIds == null || it.id in noteIds }
            setNotes(p, patternId, trackId, other + transposer.transpose(sel, s, scaleAware, key))
        }
    }

    /** Transpose the whole project (all melodic tracks in all patterns). Plain shift also moves the key. */
    fun transposeProject(semitones: Int, scaleAware: Boolean): Command = Command { p ->
        val s = semitones.coerceIn(-12, 12)
        if (s == 0) p else {
            val key = KeyResult(p.keyRoot, p.scale)
            val patterns = p.patterns.map { pat ->
                pat.copy(tracks = pat.tracks.map { t ->
                    if (t.type == TrackType.DRUMS || t.type == TrackType.AUDIO) t
                    else t.copy(notes = transposer.transpose(t.notes, s, scaleAware, key))
                })
            }
            p.copy(patterns = patterns, keyRoot = if (scaleAware) p.keyRoot else (p.keyRoot + s).mod(12))
        }
    }

    // ---------------------------------------------------------------- chop

    fun chopNotes(
        patternId: String, trackId: String, division: Int, mode: ChopMode, seed: Long = 0L, noteIds: Set<String>? = null,
    ): Command = Command { p ->
        val t = pattern(p, patternId)?.let { track(it, trackId) }
        if (t == null) p else {
            val (sel, other) = t.notes.partition { noteIds == null || it.id in noteIds }
            setNotes(p, patternId, trackId, other + chopper.chop(sel, division, mode, seed))
        }
    }

    // ---------------------------------------------------------------- phrases

    fun placePhrase(patternId: String, trackId: String, phrase: Phrase, atTick: Long, semitones: Int = 0): Command = Command { p ->
        val t = pattern(p, patternId)?.let { track(it, trackId) }
        if (t == null) p else setNotes(p, patternId, trackId, t.notes + PhraseTools.place(phrase, atTick, semitones))
    }
}
