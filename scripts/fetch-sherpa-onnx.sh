#!/usr/bin/env bash
# Fetches sherpa-onnx's prebuilt Android libraries (speaker diarization + embeddings,
# with onnxruntime) into app/src/main/jniLibs/arm64-v8a. Used by CI before the build;
# run it once locally too. Version and checksum are pinned here.
set -euo pipefail

VERSION=v1.13.8
SHA256="2ff63469a71cb6009aa2e3ed5f4a670f8abdcbe4bb9ffd23776afc792a6b4f44"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/${VERSION}/sherpa-onnx-${VERSION}-android.tar.bz2"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

curl -fsSL --retry 3 -o "$TMP/libs.tar.bz2" "$URL"
ACTUAL="$(sha256sum "$TMP/libs.tar.bz2" | cut -d' ' -f1)"
echo "sherpa-onnx ${VERSION} sha256 ${ACTUAL}"
if [ "$SHA256" != "PIN_ME" ] && [ "$ACTUAL" != "$SHA256" ]; then
  echo "checksum mismatch: expected ${SHA256}" >&2
  exit 1
fi

tar xjf "$TMP/libs.tar.bz2" -C "$TMP"   # members are ./jniLibs/<abi>/...
SRC="$TMP/jniLibs/arm64-v8a"
mkdir -p "$OUT"
# The JNI library plus whatever it links against from the same package (onnxruntime…).
copy() {
  [ -f "$OUT/$1" ] && return
  cp "$SRC/$1" "$OUT/"
  for dep in $(readelf -d "$SRC/$1" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p'); do
    [ -f "$SRC/$dep" ] && copy "$dep"
  done
  return 0
}
copy libsherpa-onnx-jni.so
ls -l "$OUT"
