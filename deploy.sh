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

# Not `adb shell su -c '<script>'`: adb hands the string to the device shell,
# which word-splits it, so su takes `set` as its command and `-e` as an option of
# its own. It then prints its usage and the remainder runs as the unprivileged
# shell user -- silently, until something happens to need root. Quoting the whole
# invocation keeps the script a single argument to su. The id check makes any
# future regression of this fail loudly instead.
"${ADB[@]}" shell "su -c '
  id -u | grep -qx 0 || { echo deploy: su did not return root >&2; exit 1; }
  set -e
  chmod 0755 /data/local/tmp/uac2bridge /data/local/tmp/usb-dac.sh
  mkdir -p /data/adb/service.d
  cp /data/local/tmp/usb-dac-service.sh /data/adb/service.d/usb-dac-service.sh
  chmod 0755 /data/adb/service.d/usb-dac-service.sh
  touch /data/adb/usbdac.autostart
'"
"${ADB[@]}" install -r "$DIR/ZX-DAC.apk"

echo "installed. Reboot the ZX500, open ZX DAC once, and grant it root in KernelSU."
