# core-music (Claude D)

Pure Kotlin (no android.*). Implements the interfaces in `MusicContracts.kt`. Does not change any contract.

| File | What |
|---|---|
| `Scales.kt` | scale tables, snap-to-scale, scale-step index helpers |
| `ChordTheory.kt` | chord intervals, names, diatonic triads, `identify()` (notes -> chord, for Replace menu) |
| `DefaultChordGenerator.kt` | `detectKey` (Krumhansl-Schmuckler), `generate` (Viterbi over diatonic chords, Style/Complexity/seed), `suggest` (3 options per bar), `toNotes` (inversion/open/slash), `toNotesVoiceLed` |
| `DefaultArpeggiator.kt` | Up/Down/Up-Down/Random, rate 1/8-1/32, octave range, gate, swing |
| `DefaultDrumGenerator.kt` | Pop/Lo-fi/Trap/EDM/Bengali folk, intensity layers, swing, fill, seed |
| `DefaultTransposeEngine.kt` | chromatic or scale-aware (diatonic) shift, locked notes stay |
| `DefaultChopEngine.kt` | cut on grid (1/2..1/16), Shuffle/Reverse/Repeat/Rearrange, `slicePads` |
| `Phrases.kt` | `PhraseTools` (save selection / place), `BuiltInPhrases` (13), `InMemoryPhraseLibrary`, `PersistentPhraseLibrary` + `PhraseStorage`, `PhraseJson` |
| `AutoCommands.kt` | `Command`s for the UI: autoChords, setKeyAndRegenerateChords, replaceChord, setChordLock, autoArp, autoDrums, transposeNotes/Project, chopNotes, placePhrase |
| `MusicEngine.kt` | shared default instances |

## UI usage (for Claude B)
```kotlin
store.execute(AutoCommands.autoChords(patternId, melodyTrackId, chordTrackId, ChordOptions(style = ChordStyle.LOFI)))
store.execute(AutoCommands.autoDrums(patternId, drumTrackId, DrumOptions(genre = DrumGenre.TRAP)))
val blocks = AutoCommands.chordBlocks(chordTrack)          // for Replace menu / lock icon / name toggle
ChordTheory.name(block)                                      // only when the user turns names on
MusicEngine.chords.suggest(melody, barStartTick, barTicks, key, 3)
```

## Rules kept
- Generators only replace unlocked notes of their own source (AUTO_CHORD / AUTO_DRUM / AUTO_ARP). USER, PHRASE and locked notes are never touched.
- Track with `autoMode = false` = Manual: generators do nothing.
- Drum and arp generation assume a 4/4 grid (16 steps per bar).
- Not done: triplet arp rates, audio-block chop (Claude C's engine), DeepSeek/Groq client interface.
