#!/bin/bash
# Install the final bridge, control script, boot service and control app.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ADB=(adb)
[ -z "${ADB_SERIAL:-}" ] || ADB+=( -s "$ADB_SERIAL" )

for file in bridge/uac2bridge usb-dac.sh usb-dac-service.sh ZX-DAC.apk; do
  [ -f "$DIR/$file" ] || { echo "missing release artifact: $file" >&2; exit 1; }
done

"${ADB[@]}" wait-for-device
"${ADB[@]}" push "$DIR/bridge/uac2bridge" /data/local/tmp/uac2bridge
"${ADB[@]}" push "$DIR/usb-dac.sh" /data/local/tmp/usb-dac.sh
"${ADB[@]}" push "$DIR/usb-dac-service.sh" /data/local/tmp/usb-dac-service.sh

"${ADB[@]}" shell su -c '
  set -e
  chmod 0755 /data/local/tmp/uac2bridge /data/local/tmp/usb-dac.sh
  mkdir -p /data/adb/service.d
  cp /data/local/tmp/usb-dac-service.sh /data/adb/service.d/usb-dac-service.sh
  chmod 0755 /data/adb/service.d/usb-dac-service.sh
  touch /data/adb/usbdac.autostart
'
"${ADB[@]}" install -r "$DIR/ZX-DAC.apk"

echo "installed. Reboot the ZX500, open ZX DAC once, and grant it root in KernelSU."
