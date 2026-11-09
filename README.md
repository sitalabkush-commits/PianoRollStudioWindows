# PianoRoll Studio

User composes the melody on a Piano Roll. The app generates chords (as notes), bass and drums. Everything stays editable.
Android APK and Windows EXE (all `core-*`, `export` and `ui-shared` are Android-free on purpose).

## Setup
```
./gradlew test                          # all unit tests (core-* run on plain JVM)
./gradlew :app-android:assembleDebug    # debug APK -> app-android/build/outputs/apk/debug/
./gradlew :app-desktop:portableZip      # Windows portable zip -> app-desktop/build/portable/ (see app-desktop/README.md)
```
Needs JDK 17 and Android SDK 34 (set `ANDROID_HOME` or `local.properties`). The Gradle wrapper (8.9) is committed.

## Modules
| Module | What | Claude |
|---|---|---|
| core-model | Note/Track/Pattern/Project, `ProjectStore` (undo/redo), `Commands` | A |
| core-audio-api | `AudioEngine`, `AudioSink`, `RenderSettings` | A |
| core-audio-engine | Pure-Kotlin synth, mixer, effects, time effects, offline renderer | C |
| core-music | Key detection, auto chord, arpeggio, drums, transpose, chop, phrases | D |
| export | MIDI / WAV / stems / `.prs`, Song Mode (playlist) commands | E |
| audio-android | `AudioTrackEngine` (AudioTrack output) | C |
| data-android | Room project storage | A |
| ui-shared | Compose Piano Roll, velocity lane | B |
| app-android | Activity, Hilt wiring | A |
| app-desktop | Compose Desktop app, Windows EXE/MSI/portable zip, Java Sound output | A+B |

See `CONTRACTS.md` (module rules, contracts) and `CONTRIBUTING.md`.

## Status (v0.1)
Done: Piano Roll, multi-track, scale lock, ghost notes, loop, auto chord/arp/drums/transpose/chop/phrases (engine level), synth + effects + time effects, offline render, MIDI/WAV/stems export, Song Mode logic, `.prs` format, save/load storage.

Not done yet (UI or native work):
- UI for: Mixer, Transpose, Chord Replace menu / Lock icon, Effect Strip, auto-generator buttons, Playlist / Song Mode, Export + share, background render (WorkManager).
- MP3 (needs a LAME/MediaCodec `Mp3Encoder`), SoundFont `.sf2` (FluidSynth/Oboe NDK), real-device latency test.
- CI: ktlint/detekt, release signing, NDK/CMake cache.
- Never built on a real Android toolchain yet: the first `./gradlew :app-android:assembleDebug` may show small Compose/Hilt compile errors (see `ui-shared/UI_NOTES.md`).
