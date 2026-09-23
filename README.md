# ADB Stay Awake

基于现代 Xposed API 102 的独立模块。当 USB 或无线 ADB 存在已认证的活跃连接时，阻止 Android 因屏幕超时自动休眠。

模块只干预自动休眠判断：不会主动点亮已经熄灭的屏幕，也不会阻止电源键手动熄屏。首个支持和验证目标为 Android 16 / ColorOS 16.1。

## 构建

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

## Hook 诊断

LSPosed 日志会逐项记录调用者的反优化结果、每个 hook 首次进入，以及 ADB/USB 状态变化。`ADB keep-awake applied` 表示电源判断首次实际改变了返回值。更新模块后需重启才能加载新代码。

全部 hook 点的调用链、反优化范围和真机验证步骤见 [反优化检查](docs/hook-deoptimization-audit.md)。
