package com.pianoroll.core.music

import com.pianoroll.core.model.ChordBlock
import com.pianoroll.core.model.DrumGenre
import com.pianoroll.core.model.DrumOptions
import com.pianoroll.core.model.Note
import com.pianoroll.core.model.NoteSource
import com.pianoroll.core.model.PPQ
import com.pianoroll.core.model.Voicing
import com.pianoroll.core.model.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import com.pianoroll.core.model.ChordQuality as Q

/** Phrase helpers: save a selection as a phrase, place a phrase on a track. */
object PhraseTools {
    private const val BAR = PPQ * 4L

    /** "Save as Phrase": normalises notes so the phrase starts at tick 0; length rounds up to whole bars. */
    fun fromSelection(name: String, category: String, notes: List<Note>, id: String = newId()): Phrase {
        if (notes.isEmpty()) return Phrase(id, name, category, BAR, emptyList())
        val start = notes.minOf { it.startTick }
        val end = notes.maxOf { it.startTick + it.durationTick } - start
        val len = ((end + BAR - 1) / BAR).coerceAtLeast(1L) * BAR
        val shifted = notes.map { it.copy(id = newId(), startTick = it.startTick - start, locked = false) }
        return Phrase(id, name, category, len, shifted)
    }

    /** Copies of the phrase notes at [atTick] (fresh ids, source = PHRASE). */
    fun place(phrase: Phrase, atTick: Long, semitones: Int = 0): List<Note> =
        phrase.notes.map {
            it.copy(
                id = newId(),
                pitch = (it.pitch + semitones).coerceIn(0, 127),
                startTick = it.startTick + atTick,
                locked = false,
                source = NoteSource.PHRASE,
            )
        }
}

/** Factory phrases (melody / chord / bass / drums). Ids start with "builtin.". */
object BuiltInPhrases {
    private const val BAR = PPQ * 4L
    private const val EIGHTH = PPQ / 2L

    private fun n(pitch: Int, startEighth: Int, lenEighth: Int, vel: Int = 100) = Note(
        id = "n-$pitch-$startEighth",
        pitch = pitch,
        startTick = startEighth * EIGHTH,
        durationTick = lenEighth * EIGHTH,
        velocity = vel,
        source = NoteSource.PHRASE,
    )

    private fun chordPhrase(id: String, name: String, chords: List<Pair<Int, Q>>): Phrase {
        val gen = DefaultChordGenerator()
        val blocks = chords.mapIndexed { i, (root, q) -> ChordBlock(startTick = i * BAR, durationTick = BAR, rootPc = root, quality = q) }
        val notes = gen.toNotesVoiceLed(blocks, Voicing()).map { it.copy(source = NoteSource.PHRASE) }
        return Phrase(id, name, "Chord", chords.size * BAR, notes)
    }

    private fun drumPhrase(id: String, name: String, genre: DrumGenre): Phrase {
        val notes = DefaultDrumGenerator().generate(DrumOptions(genre = genre, intensity = 0.5f, seed = 1L), 1)
            .map { it.copy(source = NoteSource.PHRASE) }
        return Phrase(id, name, "Drums", BAR, notes)
    }

    fun all(): List<Phrase> = listOf(
        Phrase(
            "builtin.melody.rising-hook", "Rising Hook", "Melody", 2 * BAR,
            listOf(n(60, 0, 2), n(62, 2, 2), n(64, 4, 2), n(67, 6, 2), n(69, 8, 3), n(67, 11, 1), n(64, 12, 4)),
        ),
        Phrase(
            "builtin.melody.call-response", "Call & Response", "Melody", 2 * BAR,
            listOf(n(67, 0, 1), n(69, 1, 1), n(67, 2, 2), n(64, 4, 4), n(72, 8, 1), n(71, 9, 1), n(69, 10, 2), n(67, 12, 4)),
        ),
        Phrase(
            "builtin.melody.pentatonic-run", "Pentatonic Run", "Melody", 2 * BAR,
            listOf(60, 62, 64, 67, 69, 72, 69, 67).mapIndexed { i, p -> n(p, i, 1, 90) } +
                listOf(n(67, 8, 2), n(64, 10, 2), n(62, 12, 2), n(60, 14, 2)),
        ),
        Phrase(
            "builtin.bass.root-fifth", "Root-Fifth Bass", "Bass", BAR,
            listOf(n(36, 0, 2), n(43, 2, 2), n(36, 4, 2), n(43, 6, 2)),
        ),
        chordPhrase("builtin.chord.pop-1564", "Pop I-V-vi-IV (C)", listOf(0 to Q.MAJOR, 7 to Q.MAJOR, 9 to Q.MINOR, 5 to Q.MAJOR)),
        chordPhrase("builtin.chord.sad-6415", "Emotional vi-IV-I-V (C)", listOf(9 to Q.MINOR, 5 to Q.MAJOR, 0 to Q.MAJOR, 7 to Q.MAJOR)),
        chordPhrase("builtin.chord.lofi-251", "Lo-fi ii-V-I (C)", listOf(2 to Q.MIN7, 7 to Q.DOM7, 0 to Q.MAJ7, 0 to Q.MAJ7)),
        chordPhrase("builtin.chord.cinematic", "Cinematic I-iii-IV-iv (C)", listOf(0 to Q.ADD9, 4 to Q.MINOR, 5 to Q.MAJ7, 5 to Q.MINOR)),
        drumPhrase("builtin.drums.pop", "Basic Pop Beat", DrumGenre.POP),
        drumPhrase("builtin.drums.lofi", "Lo-fi Groove", DrumGenre.LOFI),
        drumPhrase("builtin.drums.trap", "Trap Beat", DrumGenre.TRAP),
        drumPhrase("builtin.drums.edm", "Four On The Floor", DrumGenre.EDM),
        drumPhrase("builtin.drums.folk", "Bengali Folk Groove", DrumGenre.BENGALI_FOLK),
    )
}

// ------------------------------------------------------------------ library + persistence

/** Library that lives only in memory (built-ins + whatever is saved this session). */
class InMemoryPhraseLibrary(initial: List<Phrase> = BuiltInPhrases.all()) : PhraseLibrary {
    private val list = ArrayList(initial)

    @Synchronized
    override fun all(): List<Phrase> = list.toList()

    @Synchronized
    override fun save(phrase: Phrase) {
        val i = list.indexOfFirst { it.id == phrase.id }
        if (i >= 0) list[i] = phrase else list.add(phrase)
    }
}

/** Where user phrases are stored. Android: a file in app storage. Desktop: a file in user dir. */
interface PhraseStorage {
    fun read(): String?
    fun write(text: String)
}

/** Built-ins + user phrases, user phrases saved through [PhraseStorage] as JSON (.prs-friendly). */
class PersistentPhraseLibrary(private val storage: PhraseStorage) : PhraseLibrary {
    private val list: MutableList<Phrase> = loadAll()

    private fun loadAll(): MutableList<Phrase> {
        val user = storage.read()?.let { text -> runCatching { PhraseJson.decode(text) }.getOrNull() } ?: emptyList()
        return (BuiltInPhrases.all() + user).toMutableList()
    }

    @Synchronized
    override fun all(): List<Phrase> = list.toList()

    @Synchronized
    override fun save(phrase: Phrase) {
        val i = list.indexOfFirst { it.id == phrase.id }
        if (i >= 0) list[i] = phrase else list.add(phrase)
        storage.write(PhraseJson.encode(list.filterNot { it.id.startsWith("builtin.") }))
    }
}

@Serializable
private data class PhraseDto(val id: String, val name: String, val category: String, val lengthTicks: Long, val notes: List<Note>)

@Serializable
private data class PhraseFile(val version: Int = 1, val phrases: List<PhraseDto>)

/** Phrase library file format (version 1). */
object PhraseJson {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(phrases: List<Phrase>): String =
        json.encodeToString(PhraseFile.serializer(), PhraseFile(1, phrases.map { PhraseDto(it.id, it.name, it.category, it.lengthTicks, it.notes) }))

    fun decode(text: String): List<Phrase> =
        json.decodeFromString(PhraseFile.serializer(), text).phrases.map { Phrase(it.id, it.name, it.category, it.lengthTicks, it.notes) }
}
