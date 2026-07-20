#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
KERNEL_SRC="${1:?usage: $0 /path/to/kernel_imx [out-dir]}"
OUT_DIR="${2:-$ROOT/out}"
PATCH="$ROOT/zx500-usb-dac.patch"
CONFIG="$ROOT/kernel.config"
ARCH="${ARCH:-arm64}"
CROSS_COMPILE="${CROSS_COMPILE:-aarch64-linux-gnu-}"
CC_BIN="${KERNEL_CC:-${CROSS_COMPILE}gcc}"

if git -C "$KERNEL_SRC" apply --reverse --check "$PATCH" 2>/dev/null; then
  : # release patch already applied
else
  git -C "$KERNEL_SRC" apply --check "$PATCH"
  git -C "$KERNEL_SRC" apply "$PATCH"
fi

mkdir -p "$OUT_DIR"
cp "$CONFIG" "$OUT_DIR/.config"

# The vendor Hantro Makefile expands $(PWD) into an include path without quoting.
# Build from a space-free directory even when this release checkout contains spaces.
cd "${BUILD_CWD:-$HOME}"
make -C "$KERNEL_SRC" O="$OUT_DIR" ARCH="$ARCH" \
  CROSS_COMPILE="$CROSS_COMPILE" CC="$CC_BIN" olddefconfig
make -C "$KERNEL_SRC" O="$OUT_DIR" ARCH="$ARCH" \
  CROSS_COMPILE="$CROSS_COMPILE" CC="$CC_BIN" \
  DTB_FILE_NAME=sony-imx8mm-icx1295 -j"${JOBS:-4}" Image dtbs

IMAGE="$OUT_DIR/arch/arm64/boot/Image"
SIZE=$(wc -c < "$IMAGE")
LIMIT=28940800
if [ "$SIZE" -gt "$LIMIT" ]; then
  echo "ERROR: Image is $SIZE bytes; Sony kernel slot limit is $LIMIT" >&2
  exit 1
fi
echo "kernel Image: $IMAGE ($SIZE bytes)"
