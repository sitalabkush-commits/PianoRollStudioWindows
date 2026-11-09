# app-desktop (Windows EXE)

Compose Desktop app: Piano Roll screen from `ui-shared`, same `ProjectStore`.

| File | What |
|---|---|
| `Main.kt` | `main()`, window, menu bar (File / Export / Edit), status bar, "unsaved changes" dialog |
| `DesktopSession.kt` | new / open / save / export logic (no UI, unit tested). `FileChooser` interface = file dialogs |
| `AwtFileChooser.kt` | native Windows open/save dialogs (AWT `FileDialog`) + folder picker |
| `FileIO.kt` | `FileSink` (safe file writer), `ProjectIO` (.prs save/load), `FileNames` (Windows-safe names) |
| `ExportService.kt` | MIDI / WAV / stems to real files via `DefaultExporter`. MP3 = unsupported (returns failure, no file) |
| `audio/DesktopAudioEngine.kt` | `AudioEngine` = `SynthEngine` -> `javax.sound.sampled` (256-frame blocks, 4-block line buffer). No sound device -> silent fallback + message in status bar |

## Build
```
./gradlew :app-desktop:run                 # start the app (needs a desktop)
./gradlew :app-desktop:portableZip         # build/portable/PianoRollStudio-portable-<ver>.zip  (no WiX)
./gradlew :app-desktop:packageMsi :app-desktop:packageExe   # installers (WiX 3, preinstalled on GitHub windows-latest)
./gradlew :app-desktop:test :ui-shared:desktopTest          # pure-JVM tests
```
Version: `-PappVersion=1.2.3` (MSI needs MAJOR.MINOR.BUILD with MAJOR >= 1).

## Known limits (v1)
- Piano Roll scrolls with the hand tool and the zoom buttons; mouse-wheel scroll / zoom is not wired yet (shared code is touch-first).
- SoundFont (.sf2) and MP3 not supported. Mixer / Playlist / Effect UI do not exist yet (same as Android).
- Installer and portable build are not code-signed: Windows SmartScreen will warn.
