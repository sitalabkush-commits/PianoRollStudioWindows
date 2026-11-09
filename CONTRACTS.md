# CONTRACTS (Claude A owns. Others: do NOT edit without asking.)

## Modules and owners
| Module | Type | Owner |
|---|---|---|
| core-model | pure Kotlin | A |
| core-audio-api | pure Kotlin | A (interface), C (uses) |
| core-music | pure Kotlin | D |
| export | pure Kotlin | E |
| audio-android | Android + NDK | C |
| data-android | Android | A |
| ui-shared | Compose (no android.* imports) | B |
| app-android | Android app | A (wiring), others add screens |

## Rules
1. core-*, export: NO android.* imports. EXE comes later.
2. UI reads `ProjectStore.state` and edits only via `ProjectStore.execute(Command)`.
3. Project is immutable. PPQ = 480 ticks per quarter note.
4. Auto generators never modify notes with `locked = true` or `source = USER`.
5. Chord names are not drawn by default. Chords appear as notes (`NoteSource.AUTO_CHORD`) on a CHORD track.
6. Need a contract change? Open an issue "contract: ..." and wait for A.
7. Branches: feature/ui (B), feature/audio (C), feature/music (D), feature/export (E).
8. Commit format: `feat(ui): ...`, `fix(audio): ...`. PR must pass CI.

## Where each Claude plugs in
- B: replace `PianoRollScreen` in ui-shared. Add Mixer/Playlist UI there.
- C: replace `NoopAudioEngine`; change one line in `AppModule.audio()`.
- D: implement interfaces in `core-music/MusicContracts.kt` (ChordGenerator, Arpeggiator, DrumGenerator, TransposeEngine, ChopEngine).
- E: `DefaultExporter` implements `Exporter` (provided in `AppModule.exporter()`); Playlist/Song Mode Commands live in `export/song/SongCommands.kt`.

## Audio engine (A+B+C combined)
- New pure-Kotlin module `core-audio-engine` (no android.*): `SynthEngine` (sample-accurate sequencer, 64-voice synth, mixer, effects, time effects) + `OfflineRenderer` (PCM16 LE, faster than realtime). Reused as-is on desktop.
- `audio-android/AudioTrackEngine` = AudioEngine impl (AudioTrack float, low-latency, sleeps when idle). Wired in `AppModule.audio()`; `MainActivity` passes it to `PianoRollScreen`.
- Playback rules: not song mode -> first pattern; song mode -> playlist clips (pattern repeats to fill clip). `loopEndBar` is exclusive. At end without loop: stop, rewind to 0.
- Instruments: `gm:<program>` mapped to built-in synth families; `gm:drums` = GM drum synth (35/36 kick, 38 snare, 39 clap, 42/44/46 hats, toms, 49/57 crash...). `sf2:` falls back to piano timbre until FluidSynth lands.
- Effect params (EffectSpec.params): REVERB mix/room/damp; DELAY time/feedback/mix; CHORUS rate/depth/mix; DISTORTION drive/mix; EQ lowDb/highDb; FILTER cutoff/type(0 LP,1 HP); COMPRESSOR thresholdDb/ratio/attackMs/releaseMs/makeupDb.
- Time effects: HALF_SPEED, TAPE_STOP, VINYL_BRAKE, SCRATCH (rate), GATE, STUTTER (gate). REVERSE not implemented.
- Not done: SoundFont (.sf2) via FluidSynth/Oboe NDK, real-device latency test.

## Export and Song Mode (Claude E)
- `Exporter` uses `AudioSink` (not `OutputSink` as in the first spec draft). Every method closes the sink exactly once, success or failure.
- `DefaultExporter(settings, mp3Encoder, midiOptions, onProgress)`: `toMidi` (pure Kotlin `MidiWriter`), `toWav` / `toStems` (via `AudioEngine.renderOffline`), `toMp3` (needs a platform `Mp3Encoder`; without one it returns `Result.failure(UnsupportedOperationException)`, so the UI should offer WAV).
- MIDI: SMF format 1, PPQ 480. Track 0 = name/tempo/time signature. One MIDI track per distinct track NAME (patterns merged in song mode). Drums on channel 10. Muted tracks are skipped, and if any track is soloed only soloed tracks are written (`MidiOptions(respectMuteSolo = false)` writes everything).
- Stems: one WAV per audible track name, rendered by soloing that name in every pattern. File names are made unique case-insensitively.
- Flattening rule (`Arrangement`, identical to `core-audio-engine` `Compiled.from`): not song mode -> first pattern once; song mode -> playlist clips, pattern repeats to fill the clip, notes cut at the clip end.
- Song Mode edits go through `SongCommands` (add/clone/delete/rename/recolor/resize pattern, add/move/resize/duplicate/split/remove clip, `setSongMode`, `arrangeAllPatterns`). Clips never overlap inside a lane; a clash moves the clip to the nearest free lane. The last pattern cannot be deleted.
- `.prs` project file: JSON envelope `{format:"pianoroll-prs", version:1, project:{...}}` (`ProjectFile.encode/decode`). Unknown keys are ignored; a newer `version` is refused with a clear message.
