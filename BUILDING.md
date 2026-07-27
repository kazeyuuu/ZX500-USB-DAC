# Build and deployment

**English** | [简体中文](BUILDING.zh-CN.md)

## Exact source baselines

| Component | Baseline |
|---|---|
| Sony/community kernel | `97lily/2019_android_walkman`, commit `1b316da17a6f4c50511f46498de0a16dd53231d9`, directory `kernel_imx/` |
| tinyalsa | commit `e43025bbf702eb7dd8edd48c1eb50530c60f1de8` |
| Kernel compiler | Ubuntu/Linaro `aarch64-linux-gnu-gcc-4.9` 4.9.3-5ubuntu1 |
| Sony boot base | firmware 4.04.00 CHN `boot.img`, SHA-256 `e2e398948de76977549d922f8c0891969a3277813e83c75462bbf184c24a193a` |

The stock boot image is proprietary and is not included. Modern GCC builds are
too large for this bootloader's kernel slot; use the pinned GCC 4.9 toolchain.

## Kernel

```bash
git clone https://github.com/97lily/2019_android_walkman.git
git -C 2019_android_walkman checkout 1b316da17a6f4c50511f46498de0a16dd53231d9

export KERNEL_CC=/path/to/aarch64-linux-gnu-gcc-4.9
export CROSS_COMPILE=aarch64-linux-gnu-
export JOBS=4
kernel/build.sh 2019_android_walkman/kernel_imx
```

`kernel/build.sh` applies `kernel/zx500-usb-dac.patch` once, copies the release
config and builds from a space-free working directory. The generated Image must
be no larger than 28,940,800 bytes. A clean verification build currently
produces 28,819,968 bytes; ordinary warnings already present in the vendor tree
do not indicate a release-patch failure.

Pack without rebuilding the ramdisk/header/footer:

```bash
kernel/pack-boot.py stock-4.04.00-chn-boot.img \
  kernel/out/arch/arm64/boot/Image boot-zx500-usb-dac.img
./flash.sh boot-zx500-usb-dac.img
```

Do not use generic `mkbootimg`; this bootloader needs the stock Sony image layout.

## Bridge

The bridge is statically linked against the pinned tinyalsa. `bridge/build.sh`
clones tinyalsa when necessary and applies the one accessor patch idempotently.

```bash
CC=/path/to/aarch64-linux-gnu-gcc bridge/build.sh
```

Outputs:

- `bridge/uac2bridge`: silent stripped production binary
- `bridge/uac2bridge-diag`: identical data path with 10-second diagnostics

Run the correction renderer test under an aarch64 Linux environment:

```bash
TINYALSA_DIR=bridge/.build/tinyalsa
aarch64-linux-gnu-gcc -O1 -g -fsanitize=address,undefined \
  -I"$TINYALSA_DIR/include" -o /tmp/resampler-test \
  tests/resampler-test.c \
  "$TINYALSA_DIR"/src/{pcm,pcm_hw,pcm_plugin,mixer,mixer_hw,mixer_plugin,limits,snd_card_plugin}.c \
  -ldl -lpthread
/tmp/resampler-test
```

Expected result:

```text
resampler: 8 rates x 24-bit x 4 patterns x 3 deltas + canaries PASS
```

## App

The manual build requires Android SDK build-tools 37, platform 34 and a JDK:

```bash
app/build.sh
```

The script creates `app/local.keystore` unless `APK_KEYSTORE`, `APK_KEY_ALIAS`
and `APK_KEY_PASS` are supplied. Signing keys are ignored by git. Preserve your
key to install future updates over your own build.

## Userspace deployment

With Android booted, root granted to adb shell and USB debugging enabled:

```bash
./deploy.sh
# For Wi-Fi adb or multiple devices:
ADB_SERIAL=192.168.1.20:5555 ./deploy.sh
```

The installer deploys only `uac2bridge`, `usb-dac.sh`,
`usb-dac-service.sh` and `ZX-DAC.apk`. The diagnostic bridge stays in the repo.
Reboot after installation.
