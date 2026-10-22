#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

missing=0
for program in node npm ffmpeg colmap; do
  if ! command -v "$program" >/dev/null 2>&1; then
    printf 'Fehlt: %s\n' "$program"
    missing=1
  fi
done
if (( missing )); then
  printf '\nInstalliere zuerst Node.js 20+, FFmpeg und COLMAP mit dem CLI-Befehl automatic_reconstructor.\n'
  printf 'COLMAP prüfen mit: colmap automatic_reconstructor -h\n'
  exit 1
fi

node -e 'if (Number(process.versions.node.split(".")[0]) < 20) { console.error("Node.js 20 oder neuer erforderlich."); process.exit(1); }'
if ! colmap help 2>/dev/null | grep -q automatic_reconstructor; then
  printf 'Die installierte COLMAP-Version bietet automatic_reconstructor nicht. Bitte COLMAP aktualisieren.\n'
  exit 1
fi

printf 'ULTRON startet auf http://127.0.0.1:8787\n'
if command -v xdg-open >/dev/null 2>&1; then
  (sleep 1; xdg-open http://127.0.0.1:8787 >/dev/null 2>&1 || true) &
fi
cd "$ROOT/pc-relay"
npm start
