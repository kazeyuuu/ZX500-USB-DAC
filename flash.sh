#!/bin/bash
# Flash the final ZX500 USB-DAC kernel to boot_a only.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
IMG="${1:-boot-zx500-usb-dac.img}"

case "$IMG" in -h|--help)
  echo "usage: $0 [boot-zx500-usb-dac.img]"
  exit 0
esac

[ -f "$IMG" ] || { echo "missing image: $IMG" >&2; exit 1; }

# A listed release image must match its published hash. A freshly built local
# image is accepted after structural validation and its hash is displayed.
if [ -f SHA256SUMS ] && grep -q "  $IMG\$" SHA256SUMS; then
  grep "  $IMG\$" SHA256SUMS | shasum -a 256 -c -
else
  echo "local image SHA-256: $(shasum -a 256 "$IMG" | awk '{print $1}')"
fi

python3 - "$IMG" <<'PY'
import struct, sys
p = sys.argv[1]
with open(p, "rb") as f:
    d = f.read(64)
if d[:8] != b"ANDROID!":
    raise SystemExit(f"refusing: {p} is not an Android boot image")
kernel_size = struct.unpack("<I", d[8:12])[0]
page_size = struct.unpack("<I", d[36:40])[0]
if kernel_size != 28_940_800 or page_size != 2048:
    raise SystemExit(
        f"refusing: unexpected Sony boot header "
        f"(kernel_size={kernel_size}, page={page_size})"
    )
print(f"header OK: kernel_size={kernel_size}, page={page_size}")
PY

echo ">> waiting for exactly one fastboot device"
until fastboot devices | grep -q .; do sleep 1; done
count=$(fastboot devices | grep -c .)
[ "$count" -eq 1 ] || { echo "refusing: found $count fastboot devices" >&2; exit 1; }
fastboot devices

# This is an A/B device and this flasher writes boot_a. Writing the inactive
# slot changes nothing observable: the player boots the stock kernel exactly as
# before, which is the hardest possible failure to diagnose. Refuse only on a
# definite answer of "not a" -- a bootloader that does not implement the
# variable at all still gets to flash, after a warning and the prompt below.
SLOT=$(fastboot getvar current-slot 2>&1 | tr -d '\r' |
       awk -F': *' '/^current-slot:/{print $2; exit}')
case "$SLOT" in
  a) TARGET_NOTE="boot_a (the active slot)";;
  '') TARGET_NOTE="boot_a (WARNING: bootloader did not report current-slot)";;
  *)  echo "refusing: active slot is '$SLOT'; this flasher writes boot_a." >&2
      echo "Run 'fastboot set_active a', reboot to the bootloader, then retry." >&2
      exit 1;;
esac

echo
echo "About to write '$IMG' to $TARGET_NOTE. No wipe; vbmeta is untouched."
read -r -p "Type 'flash' to proceed: " answer
[ "$answer" = flash ] || { echo "aborted"; exit 1; }

fastboot flash boot_a "$IMG"
fastboot reboot
echo ">> done"
