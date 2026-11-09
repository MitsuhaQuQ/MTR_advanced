#!/bin/bash
# Builds the new editor page into common/src/main/resources/assets/mtr/editor/next/, which is committed: the mod's
# Gradle build packs it like any other resource and needs no Node. Run after every change under editor-web/ and commit
# the output with the source.
set -e
cd "$(dirname "$0")"
[ -d node_modules ] || npm ci
npm run build
# The game's Content-Security-Policy refuses inline scripts: make sure the build made none
if grep -q "<script>" ../common/src/main/resources/assets/mtr/editor/next/index.html; then
  echo "inline script in next/index.html: the editor's CSP will block it" >&2
  exit 1
fi
echo "built into common/src/main/resources/assets/mtr/editor/next/"
