# Contributing

1. `core-*` and `export` must not import `android.*` (or anything Context-related). UI modules see interfaces, not implementations.
2. Edit the project only through `ProjectStore.execute(Command)`; the Project is immutable.
3. Work in your own module folder. A contract change needs an issue titled `contract: ...` and approval from the Architect (A) first.
4. Branches: `feature/ui`, `feature/audio`, `feature/music`, `feature/export`. One PR = build + tests green.
5. Commit format: `feat(ui): ...`, `fix(audio): ...`, `test(export): ...`. Code comments in English.
6. Every feature needs a unit test (pure JVM where possible).
7. Secrets (keystore, passwords) go in GitHub Secrets, never in the repo.
8. SoundFonts: only free/CC0 `.sf2` (GeneralUser GS, FluidR3 after checking the license). MP3: check LAME's LGPL terms or use MediaCodec (AAC/M4A).
9. Merging per-Claude zips: `tools/merge.sh <zip>...` copies only each owner's module folders; A's contract files are never overwritten.
