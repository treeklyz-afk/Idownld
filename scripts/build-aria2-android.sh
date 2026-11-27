#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARIA2_TAG="release-1.37.0"
SOURCE_DIR="/tmp/aria2-android-source"
OUT_DIR="$ROOT/app/src/main/assets/runtimes"

rm -rf "$SOURCE_DIR"
git clone --depth 1 --branch "$ARIA2_TAG" https://github.com/aria2/aria2.git "$SOURCE_DIR"
mkdir -p "$OUT_DIR"

for ABI in arm64-v8a x86_64; do
  DOCKERFILE="$SOURCE_DIR/Dockerfile.android"
  BUILD_DOCKERFILE="$SOURCE_DIR/Dockerfile.android.$ABI"
  cp "$DOCKERFILE" "$BUILD_DOCKERFILE"
  sed -i \
    -e 's/OPENSSL_VERSION=1.1.1w/OPENSSL_VERSION=3.5.9/' \
    -e 's/--with-libexpat/--without-libexpat/' \
    -e 's/--with-libcares/--without-libcares/' \
    -e 's/--with-libssh2/--without-libssh2/' \
    "$BUILD_DOCKERFILE"
  if [[ "$ABI" == "x86_64" ]]; then
    sed -i \
      -e 's/aarch64-linux-android/x86_64-linux-android/g' \
      -e 's/android-arm64/android-x86_64/g' \
      "$BUILD_DOCKERFILE"
  fi

  IMAGE="idownld-aria2:$ABI"
  docker build --build-arg "ARIA2_VERSION=$ARIA2_TAG" -f "$BUILD_DOCKERFILE" -t "$IMAGE" "$SOURCE_DIR"
  CONTAINER="idownld-aria2-build-$ABI"
  docker create --name "$CONTAINER" "$IMAGE" >/dev/null
  docker cp "$CONTAINER:/root/build/aria2/src/aria2c" "$OUT_DIR/aria2c-$ABI"
  docker rm "$CONTAINER" >/dev/null
  chmod 0755 "$OUT_DIR/aria2c-$ABI"
  "$ANDROID_HOME/ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -h "$OUT_DIR/aria2c-$ABI" | grep -E 'Class:|Machine:'
done
