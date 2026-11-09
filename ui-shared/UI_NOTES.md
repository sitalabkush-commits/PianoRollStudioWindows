# ui-shared notes (Claude B)

## Done (phase 1: Piano Roll core)
Canvas grid + keyboard + ruler, draw/move/resize/erase/marquee, long-press multi-select, snap 1/4..1/32,
velocity lane, pinch zoom + two-finger pan (+ toolbar zoom, ✋ pan tool), scale lock, ghost notes,
multi-track chips with M/S, loop region (drag ruler), playhead, key preview, undo/redo buttons.
Only visible notes/rows/lines are drawn. All edits go through `ProjectStore.execute(Commands.*)`; a drag = 1 undo step.

## Not done yet (next phases)
(Audio now wired: MainActivity passes the engine to PianoRollScreen.)
Mixer screen, Transpose UI, Chord strip Replace menu + Lock icon, Effect Strip, Playlist UI (E).

## Assumptions Claude A / C / D / E should check
1. `loopEndBar` is EXCLUSIVE: loop = [loopStartBar*barTicks, loopEndBar*barTicks). (CONTRACTS.md is silent.)
2. Editing an auto note (move/resize/velocity) sets `locked = true` so generators keep it (rule 4).
3. `PianoRollScreen(store, modifier, audio: AudioEngine? = null)`: new optional param. If given, UI calls
   `audio.setProject()` on every project change, reads `positionTick()/isPlaying()` per frame, and calls
   play/pause/stop/seek/previewNote. A: pass it in MainActivity (`@Inject lateinit var audio: AudioEngine`).
4. Scale intervals live in `PianoRollMath.scaleIntervals` (duplicate of what D may add; fine to merge later).
5. Not compiled here (no Gradle/Kotlin in my sandbox). First `./gradlew :ui-shared:testDebugUnitTest :app-android:assembleDebug` may show small compile errors.

## Desktop (Compose Multiplatform)
ui-shared is now a Kotlin Multiplatform module with two targets: `androidTarget()` and `jvm("desktop")`.
All UI code lives in `src/shared/kotlin` (tests in `src/sharedTest/kotlin`) and is compiled by BOTH targets,
so `android.*` imports break the desktop build. It is not `commonMain` because core-model/core-music/core-audio-api
are JVM-only modules (common code cannot depend on them); moving to commonMain needs those converted to KMP first.
Android keeps the androidx BOM; desktop uses the Compose Multiplatform plugin artifacts.
Tests: `./gradlew :ui-shared:desktopTest` (JVM) and `:ui-shared:testDebugUnitTest` (Android).
