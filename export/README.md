# export (Claude E)

Pure Kotlin (no android.*). Reusable on desktop.

| File | What |
|---|---|
| `ExportContracts.kt` | `Exporter` interface |
| `DefaultExporter.kt` | MIDI, WAV, stems, MP3 (via `Mp3Encoder`), progress callback |
| `MidiWriter.kt` | SMF format 1 writer |
| `PcmSinks.kt` | `WavSink` (header + PCM), `ProgressSink`, `WavHeader`, `Mp3Encoder` interface |
| `Arrangement.kt` | pattern/playlist flattening, same rule as the audio engine |
| `ProjectFile.kt` | `.prs` JSON envelope |
| `song/SongCommands.kt` | pattern manager + playlist `Command`s |

```kotlin
val exporter = DefaultExporter(onProgress = { p -> /* 0..1 */ })
exporter.toWav(project, audioEngine, sink)          // Result<Unit>
exporter.toStems(project, audioEngine) { name -> sinkFor("$name.wav") }
store.execute(SongCommands.clonePattern(patternId))
```

## Not done
- MP3: implement `Mp3Encoder` (LAME JNI or MediaCodec) in an Android module and pass it to `DefaultExporter`.
- Playlist / Export UI, WorkManager background render and share sheet (need the Android toolchain).
- WAV is buffered in memory until close (a header needs the final length); fine for songs, not for hour-long renders.
