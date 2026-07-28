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

# Resolve both paths before the build starts: it runs from a different working
# directory (see BUILD_CWD below), so a relative argument would be interpreted
# against that directory instead of the caller's and `make -C` would fail.
KERNEL_SRC="$(cd "$KERNEL_SRC" 2>/dev/null && pwd)" ||
  { echo "ERROR: kernel source directory not found: $1" >&2; exit 1; }
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

# `git apply` run from a subdirectory of a repository silently ignores patch
# paths that fall outside that subdirectory, and still exits 0. KERNEL_SRC is
# kernel_imx/ inside the vendor repo and the patch paths are kernel-relative, so
# applying from there dropped the entire patch while reporting success -- and
# made both --check and --reverse --check pass vacuously. Apply from the repo
# root with an explicit prefix instead.
REPO="$(git -C "$KERNEL_SRC" rev-parse --show-toplevel)"
if [ "$REPO" = "$KERNEL_SRC" ]; then
  git_apply() { git -C "$KERNEL_SRC" apply "$@"; }
else
  git_apply() { git -C "$REPO" apply --directory="${KERNEL_SRC#"$REPO"/}" "$@"; }
fi

if git_apply --reverse --check "$PATCH" 2>/dev/null; then
  : # release patch already applied
else
  git_apply --check "$PATCH"
  git_apply "$PATCH"
fi

# The failure above was invisible because nothing checked the result. Assert on
# content: c_volume is a configfs attribute only this patch adds, and its absence
# is exactly what a silently skipped patch looks like.
if ! grep -q c_volume "$KERNEL_SRC/drivers/usb/gadget/function/f_uac2.c"; then
  echo "ERROR: release patch is not present in $KERNEL_SRC after applying it" >&2
  exit 1
fi

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
