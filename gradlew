#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="9.5.0"
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
BOOT_DIR="$BASE_DIR/.gradle-bootstrap"
DIST_DIR="$BOOT_DIR/gradle-$GRADLE_VERSION"
ZIP="$BOOT_DIR/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"

if [[ ! -x "$DIST_DIR/bin/gradle" ]]; then
  mkdir -p "$BOOT_DIR"
  echo "Bootstrapping Gradle $GRADLE_VERSION..."
  if command -v curl >/dev/null 2>&1; then
    curl -L --fail --retry 3 -o "$ZIP" "$URL"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$ZIP" "$URL"
  else
    echo "curl or wget is required for the first bootstrap." >&2
    exit 1
  fi
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP" -d "$BOOT_DIR"
fi

exec "$DIST_DIR/bin/gradle" "$@"
