# ADB Stay Awake

[English](README.md) | **简体中文**

面向 Android 开发者的 LSPosed 模块，让手机在 ADB 调试期间保持唤醒，减少反复解锁对开发和测试的打断。基于现代 Xposed API 102。

## 使用场景

调试 App 时，经常需要一边在电脑上修改代码、查看日志，一边观察手机上的界面。如果手机因超时自动锁屏，就需要反复点亮、解锁，打断调试过程。

开发者选项中的“充电时屏幕不休眠”可以避免这个问题，但它会作用于所有充电场景。很多时候，我们只希望连接 ADB 调试时不自动锁屏，平时接充电器充电时，仍按系统设置正常熄屏。

ADB Stay Awake 将保活条件限定在 USB 调试连接或无线 ADB 活跃连接上，断开所有调试连接后恢复系统的自动休眠行为。如需普通充电时仍能自动熄屏，请关闭开发者选项中的“充电时屏幕不休眠”。

## 工作方式

当 USB 已连接、配置完成且启用 ADB 功能，或无线 ADB 存在活跃连接时，模块阻止 Android 因屏幕超时自动休眠。

USB 判断兼容 ADB Root／免授权 ADB，不依赖认证密钥记录。连接电脑并启用 USB 调试后，即使没有运行 adb 命令也会保活；拔线或关闭 USB 调试后，如果没有活跃的无线 ADB 连接，就会恢复自动休眠。普通充电器连接不会满足 USB 配置完成条件。

无线连接同时支持开发者选项中的配对无线调试，以及通过 `adb -d tcpip 5555` 或 `setprop service.adb.tcp.port 5555` 开启的传统 TCP/IP 调试。传统模式每两秒通过 Netlink 检查配置端口上的 TCP 连接，兼容 IPv4、IPv6 和免授权 ADB；仅开启监听端口不会保活，最后一个连接断开后恢复超时。也支持 `persist.adb.tcp.port` 回退配置和自定义端口。检测依据是已建立的 TCP 传输，不代表已完成 ADB 授权；读取失败时取消传统 TCP 保活并记录错误。

模块只干预自动休眠判断：不会主动点亮已经熄灭的屏幕，也不会阻止电源键手动熄屏。首个支持和验证目标为 Android 16 / ColorOS 16.1。

## 构建

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## Hook 诊断

LSPosed 日志会逐项记录调用者的反优化结果、每个 hook 首次进入，以及 ADB/USB 状态变化。`ADB keep-awake applied` 表示电源判断首次实际改变了返回值。更新模块后需重启才能加载新代码。

`Legacy ADB TCP monitor installed` 表示传统模式监测已启动，`TCP state: port=5555, available=true` 表示检测到传统连接。连接正常建立或断开后的识别通常需要约两秒；网络异常断开还取决于内核何时确认 TCP 连接失效。

Android 17 的部分系统将设备管理超时判断移入 `ScreenTimeoutConstants`。模块按实际类结构兼容旧版 `PowerManagerService` 和新版 `mScreenTimeoutConstants`，日志中的 `admin timeout policy` 会显示最终使用的策略类。仍然遵守设备管理超时限制；读取失败时保留系统原判断。

全部 hook 点的调用链、反优化范围和真机验证步骤见 [反优化检查](docs/hook-deoptimization-audit.md)。

## 开源协议

本项目采用 [MIT License](LICENSE) 开源，可在保留版权声明和许可声明的前提下使用、修改和分发，包括商业用途。
