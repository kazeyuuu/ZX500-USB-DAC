# ZX500 USB-DAC

Turn a Sony NW-ZX505 into a native UAC2 USB DAC. This release contains only the
final production implementation and its matching diagnostic bridge.

## Features

- One output-only device named **ZX500 USB-DAC** (no fake microphone)
- Stereo 24-bit PCM at 44.1/48/88.2/96/176.4/192/352.8/384 kHz
- Native host sample-rate switching without rebuilding the gadget or unplugging
- Native host volume and mute, with a hardware safety ceiling
- macOS and Windows 11 support; direct, hub and Thunderbolt-dock operation
- Event-driven rate/volume control and automatic stream recovery
- Rate-normalized single-frame drift correction
- Sony audio clock ownership protection while Android apps remain running
- Silent production runtime: no log files, no periodic flash writes

The production data path is fixed: UAC2 adaptive OUT → ALSA capture →
`uac2bridge` → Sony `card1` playback. The diagnostic binary is built from the
same source with statistics enabled; the production launcher never selects it.

## Repository layout

| Path | Purpose |
|---|---|
| `kernel/` | One final kernel patch, config, build script and boot packer |
| `bridge/uac2bridge.c` | Final bridge source |
| `bridge/uac2bridge` | Production bridge (silent, stripped) |
| `bridge/uac2bridge-diag` | Explicit diagnostic build |
| `usb-dac.sh` | Final on-device gadget/bridge controller |
| `usb-dac-service.sh` | KernelSU boot service |
| `deploy.sh` | Installs the userspace release onto the player |
| `flash.sh` | Safely flashes a locally packed boot image to `boot_a` |
| `app/`, `ZX-DAC.apk` | Optional on-device control app |
| `tests/resampler-test.c` | Drift renderer safety test |

There are no staged patches, numbered bridges, development kernels or fallback
implementations on this branch.

## Requirements

- Sony NW-ZX505 based on firmware 4.04.00 CHN
- Unlocked bootloader and AVB already disabled
- KernelSU with root granted to shell and the ZX DAC app
- Android platform tools (`adb`, `fastboot`)
- A stock 4.04.00 CHN `boot.img` for packing; it is not redistributed here

Changing AVB state may require a factory reset. `flash.sh` deliberately never
touches vbmeta and never wipes data.

## Build and install

Detailed reproducible steps are in [BUILDING.md](BUILDING.md). In short:

```bash
# 1. Build the kernel and pack it into the stock Sony boot image.
kernel/build.sh /path/to/2019_android_walkman/kernel_imx
kernel/pack-boot.py /path/to/stock-boot.img \
  kernel/out/arch/arm64/boot/Image boot-zx500-usb-dac.img

# 2. Build both bridge variants with an aarch64 compiler.
CC=aarch64-linux-gnu-gcc bridge/build.sh

# 3. Flash only boot_a, then install userspace files.
./flash.sh boot-zx500-usb-dac.img
./deploy.sh
```

After reboot, open **ZX DAC** once and grant root in KernelSU. The boot service
starts DAC mode when `/data/adb/usbdac.autostart` exists; `deploy.sh` creates it.

## Use

Choose sample rate, volume and mute on the host. The app only switches between
DAC and ordinary/MTP USB modes and displays the live host state.

On-device commands:

```sh
su -c 'sh /data/local/tmp/usb-dac.sh status'
su -c 'sh /data/local/tmp/usb-dac.sh appstatus'
su -c 'sh /data/local/tmp/usb-dac.sh dac'   # enable autostart and start now
su -c 'sh /data/local/tmp/usb-dac.sh mtp'   # ordinary USB after next reboot
su -c 'sh /data/local/tmp/usb-dac.sh off'   # stop now
```

For diagnostics, manually replace `/data/local/tmp/uac2bridge` with
`bridge/uac2bridge-diag` and capture stderr to volatile storage such as `/dev`.
An optional `-1` or `+1` argument forces the corresponding correction direction.
The production service and launcher never run the diagnostic binary.

## Safety and recovery

- Host volume is limited to Sony hardware level 80; unknown volume/mute state
  prevents the gadget from binding.
- On startup failure the controller verifies that the UDC is unbound.
- The kernel image must fit Sony's 28,940,800-byte kernel slot.
- `fastboot boot` is ineffective on this bootloader. Recovery requires flashing
  a known-good Sony boot image to `boot_a` from fastboot mode.

This is an unofficial device modification. Keep a verified stock boot image and
know the hardware fastboot key combination before flashing.
