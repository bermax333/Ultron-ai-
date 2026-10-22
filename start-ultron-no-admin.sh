#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
MM_DIR="$HOME/.local/bin"
ENV_DIR="$HOME/.local/share/ultron-neo-env"
mkdir -p "$MM_DIR"

if [[ -s "$HOME/.nvm/nvm.sh" ]]; then
  # Load the user's Node.js installation, including when this script is launched from a file manager.
  . "$HOME/.nvm/nvm.sh"
fi

if ! command -v node >/dev/null 2>&1 || [[ "$(node -p 'Number(process.versions.node.split(".")[0])')" -lt 20 ]]; then
  if [[ ! -s "$HOME/.nvm/nvm.sh" ]]; then
    echo "Installiere Node.js 20 in deinem Benutzerkonto (ohne sudo) …"
    curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.7/install.sh | bash
    . "$HOME/.nvm/nvm.sh"
  fi
  nvm install 20
fi

if ! command -v micromamba >/dev/null 2>&1 && [[ ! -x "$MM_DIR/micromamba" ]]; then
  echo "Lade Micromamba in dein Benutzerkonto …"
  curl -Ls https://micro.mamba.pm/api/micromamba/linux-64/latest \
    | tar -xj -C "$MM_DIR" --strip-components=1 bin/micromamba
fi
export PATH="$MM_DIR:$PATH"
MICROMAMBA="$(command -v micromamba || printf '%s' "$MM_DIR/micromamba")"

if [[ ! -x "$ENV_DIR/bin/ffmpeg" || ! -x "$ENV_DIR/bin/colmap" ]]; then
  echo "Installiere FFmpeg und COLMAP nur für dein Benutzerkonto. Das kann beim ersten Mal dauern …"
  if [[ -d "$ENV_DIR/conda-meta" ]]; then
    "$MICROMAMBA" install -y -p "$ENV_DIR" -c conda-forge ffmpeg colmap
  else
    "$MICROMAMBA" create -y -p "$ENV_DIR" -c conda-forge ffmpeg colmap
  fi
fi

export PATH="$ENV_DIR/bin:$PATH"
if ! colmap help 2>&1 | grep -q automatic_reconstructor; then
  echo "Diese COLMAP-Version enthält automatic_reconstructor nicht."
  echo "Aktualisiere die Pakete mit: micromamba install -p \"$ENV_DIR\" -c conda-forge colmap"
  exit 1
fi

cd "$ROOT"
exec bash "$ROOT/start-ultron.sh"
