# 构建与部署

[English](BUILDING.md) | **简体中文**

## 精确源码基线

| 组件 | 基线 |
|---|---|
| Sony/社区内核 | `97lily/2019_android_walkman`，commit `1b316da17a6f4c50511f46498de0a16dd53231d9`，源码目录 `kernel_imx/` |
| tinyalsa | commit `e43025bbf702eb7dd8edd48c1eb50530c60f1de8` |
| 内核编译器 | Ubuntu/Linaro `aarch64-linux-gnu-gcc-4.9` 4.9.3-5ubuntu1 |
| Sony boot 基础镜像 | 4.04.00 CHN 固件的 `boot.img`，SHA-256：`e2e398948de76977549d922f8c0891969a3277813e83c75462bbf184c24a193a` |

原厂 boot 镜像属于专有内容，本仓库不提供。使用现代 GCC 编译得到的内核通常会超过此 bootloader
的内核槽位，因此必须使用上述 GCC 4.9 工具链。

## 内核

```bash
git clone https://github.com/97lily/2019_android_walkman.git
git -C 2019_android_walkman checkout 1b316da17a6f4c50511f46498de0a16dd53231d9

export KERNEL_CC=/path/to/aarch64-linux-gnu-gcc-4.9
export CROSS_COMPILE=aarch64-linux-gnu-
export JOBS=4
kernel/build.sh 2019_android_walkman/kernel_imx
```

`kernel/build.sh` 会以幂等方式应用一次 `kernel/zx500-usb-dac.patch`，复制 release 配置，
并从不含空格的工作目录执行构建。生成的 Image 不得超过 28,940,800 字节。当前干净验证构建
生成 28,819,968 字节；厂商源码树原本就存在的普通编译警告不代表 release 补丁失败。

在不重建 ramdisk、header 或 footer 的情况下打包：

```bash
kernel/pack-boot.py stock-4.04.00-chn-boot.img \
  kernel/out/arch/arm64/boot/Image boot-zx500-usb-dac.img
./flash.sh boot-zx500-usb-dac.img
```

不要使用通用 `mkbootimg`；此 bootloader 需要保持 Sony 原厂镜像结构。

## Bridge

Bridge 会与指定版本的 tinyalsa 静态链接。`bridge/build.sh` 会在需要时克隆 tinyalsa，
并以幂等方式应用唯一的 accessor 补丁。

```bash
CC=/path/to/aarch64-linux-gnu-gcc bridge/build.sh
```

输出文件：

- `bridge/uac2bridge`：静默运行、已剥离符号的生产二进制
- `bridge/uac2bridge-diag`：数据路径相同，每 10 秒输出一次诊断统计

在 aarch64 Linux 环境中运行校正渲染器测试：

```bash
TINYALSA_DIR=bridge/.build/tinyalsa
aarch64-linux-gnu-gcc -O1 -g -fsanitize=address,undefined \
  -I"$TINYALSA_DIR/include" -o /tmp/resampler-test \
  tests/resampler-test.c \
  "$TINYALSA_DIR"/src/{pcm,pcm_hw,pcm_plugin,mixer,mixer_hw,mixer_plugin,limits,snd_card_plugin}.c \
  -ldl -lpthread
/tmp/resampler-test
```

预期结果：

```text
resampler: 8 rates x 24-bit x 4 patterns x 3 deltas + canaries PASS
```

## App

手动构建需要 Android SDK build-tools 37、platform 34 和 JDK：

```bash
app/build.sh
```

如果没有提供 `APK_KEYSTORE`、`APK_KEY_ALIAS` 和 `APK_KEY_PASS`，脚本会创建
`app/local.keystore`。签名密钥已被 git 忽略。请妥善保管自己的密钥，否则之后自行构建的 APK
无法覆盖安装。

## 用户态部署

确认 Android 已启动、adb shell 已获得 root 授权，并已启用 USB 调试：

```bash
./deploy.sh
# 使用 Wi-Fi adb 或连接了多台设备时：
ADB_SERIAL=192.168.1.20:5555 ./deploy.sh
```

安装脚本只会部署 `uac2bridge`、`usb-dac.sh`、`usb-dac-service.sh` 和
`ZX-DAC.apk`。诊断 bridge 只保留在仓库中，不会安装到设备。安装完成后请重启。
