#!/usr/bin/env bash
# Usage: tools/merge.sh <claudeC.zip> <claudeD.zip> <claudeE.zip> ...
# Copies ONLY each owner's module folder into this repo (A's contract files are never overwritten).
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OWNED="audio-android core-audio-engine core-music export ui-shared"
for z in "$@"; do
  tmp="$(mktemp -d)"; unzip -q "$z" -d "$tmp"
  src="$(find "$tmp" -maxdepth 2 -name settings.gradle.kts -printf '%h\n' | head -1)"
  [ -z "$src" ] && src="$tmp"
  for m in $OWNED; do
    if [ -d "$src/$m" ]; then echo "merge $m from $z"; rm -rf "$ROOT/$m"; cp -r "$src/$m" "$ROOT/$m"; fi
  done
  rm -rf "$tmp"
done
echo "done. Now: ./gradlew test :app-android:assembleDebug"
