# ZX500 USB-DAC

[English](README.md) | **简体中文**

将 Sony NW-ZX505 改造成原生 UAC2 USB DAC。本发布分支只包含最终生产实现及其配套诊断 bridge。

## 功能

- 单一输出设备，名称为 **ZX500 USB-DAC**，不再提供假麦克风
- 双声道 24-bit PCM：44.1/48/88.2/96/176.4/192/352.8/384 kHz
- 主机原生切换采样率，无需重建 gadget 或重新插拔
- 主机原生控制音量和静音，并设有硬件安全音量上限
- 支持 macOS 和 Windows 11，可直连、通过 USB Hub 或雷电扩展坞使用
- 基于事件的采样率/音量控制和音频流自动恢复
- 按采样率归一化的单帧漂移校正
- USB-DAC 工作期间保护 Sony 音频时钟，不受 Android 应用提示音干扰
- 生产运行时保持静默：不写日志，不进行周期性闪存写入

生产数据路径固定为：UAC2 adaptive OUT → ALSA capture → `uac2bridge` → Sony `card1` playback。
诊断二进制由同一份源码编译，仅额外启用统计输出；生产启动脚本不会选择诊断版。

## 仓库结构

| 路径 | 用途 |
|---|---|
| `kernel/` | 单一最终内核补丁、配置、构建脚本和 boot 打包工具 |
| `bridge/uac2bridge.c` | 最终 bridge 源码 |
| `bridge/uac2bridge` | 生产 bridge（静默、已剥离符号） |
| `bridge/uac2bridge-diag` | 显式启用的诊断版本 |
| `usb-dac.sh` | 设备端 gadget/bridge 控制脚本 |
| `usb-dac-service.sh` | KernelSU 开机服务 |
| `deploy.sh` | 将用户态生产文件部署到播放器 |
| `flash.sh` | 将本地打包的 boot 镜像安全刷入 `boot_a` |
| `app/`、`ZX-DAC.apk` | 可选的设备端控制 App |
| `tests/resampler-test.c` | 漂移校正渲染器安全测试 |

此分支不包含阶段补丁、编号 bridge、开发内核或旧版回退实现。

## 环境要求

- 基于 4.04.00 CHN 固件的 Sony NW-ZX505
- 已解锁 bootloader，且已关闭 AVB 验证
- 已安装 KernelSU，并向 shell 和 ZX DAC App 授予 root
- Android platform tools（`adb`、`fastboot`）
- 用于打包的原厂 4.04.00 CHN `boot.img`；本仓库不分发该文件

改变 AVB 状态可能触发恢复出厂设置。`flash.sh` 不会修改 vbmeta，也不会清除用户数据。

## 构建与安装

完整、可复现的构建步骤见 [BUILDING.zh-CN.md](BUILDING.zh-CN.md)。简要流程如下：

```bash
# 1. 编译内核，并写入 Sony 原厂 boot 镜像结构。
kernel/build.sh /path/to/2019_android_walkman/kernel_imx
kernel/pack-boot.py /path/to/stock-boot.img \
  kernel/out/arch/arm64/boot/Image boot-zx500-usb-dac.img

# 2. 使用 aarch64 编译器构建生产版和诊断版 bridge。
CC=aarch64-linux-gnu-gcc bridge/build.sh

# 3. 仅刷入 boot_a，然后部署用户态文件。
./flash.sh boot-zx500-usb-dac.img
./deploy.sh
```

重启后打开一次 **ZX DAC**，并在 KernelSU 中授予 root。存在
`/data/adb/usbdac.autostart` 时，开机服务会自动进入 DAC 模式；`deploy.sh` 会创建该标记。

## 使用

采样率、音量和静音均在电脑端选择。App 只负责切换 DAC 与普通/MTP USB 模式，并显示当前主机状态。

设备端命令：

```sh
su -c 'sh /data/local/tmp/usb-dac.sh status'
su -c 'sh /data/local/tmp/usb-dac.sh appstatus'
su -c 'sh /data/local/tmp/usb-dac.sh dac'   # 启用自动启动并立即进入 DAC
su -c 'sh /data/local/tmp/usb-dac.sh mtp'   # 下次重启恢复普通 USB
su -c 'sh /data/local/tmp/usb-dac.sh off'   # 立即停止 DAC
```

诊断时，可手动使用 `bridge/uac2bridge-diag` 替换 `/data/local/tmp/uac2bridge`，并将 stderr
输出到 `/dev` 等易失性存储。可选参数 `-1` 或 `+1` 用于强制对应的漂移校正方向。
生产服务和生产启动脚本不会运行诊断二进制。

## 安全与恢复

- 主机音量最高映射到 Sony 硬件音量 80；无法确认音量或静音状态时，gadget 不会绑定。
- 启动失败时，控制脚本会确认 UDC 已解绑。
- 内核 Image 必须能装入 Sony 的 28,940,800 字节内核槽位。
- 此 bootloader 上的 `fastboot boot` 无效。出现启动问题时，需要在 fastboot 模式下将已验证的
  Sony boot 镜像刷回 `boot_a`。

这是非官方设备改造。刷机前请保留经过校验的原厂 boot 镜像，并确认自己知道设备进入 fastboot
模式的实体按键组合。
