# ADB 连接时保持唤醒模块设计

## 1. 背景与目标

创建一个独立的现代 Xposed 模块 `AdbStayAwake`。当设备存在至少一个已认证且仍活跃的 ADB 客户端连接时，阻止系统因屏幕超时而自动休眠；最后一个 ADB 客户端断开后，恢复系统原有的屏幕超时策略。

模块同时支持：

- USB ADB；
- 无线调试 ADB；
- 同一密钥或多个密钥的并发连接；
- USB 与无线 ADB 同时连接。

行为参照开发者选项“充电时屏幕不休眠”：只干预自动休眠判断，不主动点亮已经熄灭的屏幕，也不阻止用户通过电源键手动熄屏。

## 2. 项目与运行范围

- 项目目录：`/Users/kofua/Projects/Android/AdbStayAwake`
- applicationId/namespace：`com.kofua.adbstayawake`
- 实现语言：Kotlin
- 模块形态：无桌面入口、无设置页面的独立 APK
- Xposed API：`io.github.libxposed:api:102.0.0`，仅以 `compileOnly` 引入
- Java/Kotlin 字节码目标：Java 17
- 首个验证目标：Android 16（API 36）、ColorOS 16.1、OnePlus 13T
- Xposed 作用域：仅 `system`

现代 Xposed 元数据：

- `META-INF/xposed/java_init.list`：声明模块入口类；
- `META-INF/xposed/scope.list`：只包含 `system`；
- `META-INF/xposed/module.prop`：
  - `minApiVersion=102`
  - `targetApiVersion=102`
  - `staticScope=true`
  - `autoHotReload=false`
  - `exceptionMode=protective`

模块不使用 `de.robv.android.xposed`、`XposedBridge`、`XposedHelpers` 等旧 API。

## 3. 已确认的系统行为

Android 16 的 `com.android.server.adb.AdbDebuggingManager` 维护两组活跃连接：

- `mConnectedKeys`：USB ADB 连接，值为引用计数；
- `mWifiConnectedKeys`：系统用于 UI 的无线 ADB key 集合；同一 key 的多条 transport 会被去重，不能直接作为连接数量。

在 ColorOS 上，USB gadget 断开后 `mConnectedKeys` 可能暂时保留授权引用，因此 USB 是否活跃还必须结合 `UsbDeviceManager.UsbHandler` 的 `mConnected`、`mConfigured` 与 `mCurrentFunctions`（包含 ADB function）判断。

`AdbDebuggingThread` 从 adbd 本地 socket 接收连接事件，并交给 `AdbDebuggingHandler.handleMessage(Message)`：

- `10`：USB/通用已认证密钥连接；
- `7`：USB/通用密钥断开；
- `22`：无线调试设备连接；
- `23`：无线调试设备断开。

不能使用 `adb_enabled`、`sys.usb.config` 或 `dumpsys adb` 的 `connected_to_adb` 作为唯一判断依据：它们可能只表示 ADB 服务或认证监听线程已启用，而不代表当前存在客户端连接。

Android 16 的 `PowerManagerService` 使用 `mStayOn` 参与以下自动休眠判断：

- `isBeingKeptAwakeLocked(PowerGroup)`；
- `isBeingKeptFromInattentiveSleepLocked()`。

这两个判断不会覆盖电源键等显式休眠请求，因此适合复用开发者选项的语义。

## 4. 架构

模块分为四个小组件。

### 4.1 `AdbStayAwakeModule`

继承 API 102 的 `XposedModule`，在 `onSystemServerStarting` 中安装全部 Hook。模块加载和失败信息统一通过现代 API 的 `log` 输出。

### 4.2 `AdbConnectionTracker`

负责把 USB 与无线调试连接集合汇总为一个布尔状态：

```text
connected = (USB transport 可用 && usbConnectedKeys 非空) || wifiConnectedKeys 非空
```

状态使用进程内原子变量保存。只有布尔状态发生变化时才通知电源策略，避免重复刷新系统电源状态。

不记录 ADB 公钥、指纹或密钥内容；日志只记录连接类型、连接数量和最终布尔状态。

### 4.3 `AdbDebuggingHook`

Hook `AdbDebuggingManager.AdbDebuggingHandler.handleMessage(Message)`，先执行原方法，再读取 USB key 集合；无线连接则按消息 `22/23` 自行维护“每个 key 的 transport 引用计数”，并在无线服务关闭、认证清理或 adbd socket 断开时清零。同时 Hook `UsbDeviceManager.UsbHandler.handleMessage(Message)`，读取 USB 物理连接、配置完成状态与当前 functions。

选择“原方法之后”读取，是为了确保引用计数和集合已经完成增删。Hook 对所有消息都可进行低成本重算，不依赖 ROM 是否调整具体消息编号；连接状态未改变时不触发后续操作。

如果目标类或字段不存在，则记录兼容性错误并保持系统原行为，不尝试以“ADB 功能已开启”代替真实连接状态。

### 4.4 `PowerPolicyHook`

Hook `PowerManagerService`：

1. 通过 `onBootPhase(int)` 捕获当前 system_server 生命周期内唯一的 `PowerManagerService` 实例；
2. Hook `isBeingKeptAwakeLocked(PowerGroup)`；
3. Hook `isBeingKeptFromInattentiveSleepLocked()`；
4. ADB 已连接且没有设备管理器强制的最大屏幕超时策略时，在原返回值基础上返回 `true`；
5. ADB 断开或连接状态发生变化时，调用原始 `userActivityInternal(...)`，从当前时刻重新计算正常屏幕超时，但不唤醒已经处于休眠状态的屏幕。

为避免 ART 内联导致私有判断 Hook 被绕过，对直接调用这些判断的 `isItBedTimeYetLocked(PowerGroup)` 执行 API 102 的 `deoptimize`。不扩大反优化范围。

## 5. 状态流

```text
adbd 连接事件
  -> AdbDebuggingHandler 原逻辑更新连接集合
  -> Hook 汇总 USB/Wi-Fi 活跃连接
  -> 状态没有变化：结束
  -> 状态发生变化：更新原子状态并通知 PowerPolicyHook
      -> 屏幕当前已亮：从当前时刻重置超时计算
      -> 屏幕当前已灭：保持熄屏，不主动唤醒

系统准备自动休眠
  -> 执行系统原有 kept-awake 判断
  -> ADB 未连接：完全使用原结果
  -> ADB 已连接：返回保持唤醒
  -> 电源键/显式休眠：不经过自动超时分支，照常熄屏
```

## 6. 并发与生命周期

- ADB 事件运行在 system_server 的 ADB Handler 线程；
- 电源策略判断运行在 PowerManagerService 的锁保护范围内；
- 两者只通过原子布尔值传递状态，不共享可变集合；
- `PowerManagerService` 引用只在同一个 system_server 生命周期内保存；system_server 重启后模块和引用一起重建；
- ADB 事件早于 PowerManagerService 就绪时，仅保存连接状态，待电源服务进入可用 Boot Phase 后再同步一次；
- 模块禁用、升级或作用域变化后需要重启 system_server，不启用热重载。

## 7. 故障与兼容策略

- 所有 Hook 使用 `ExceptionMode.PROTECTIVE`；
- 类、方法、字段签名解析相互隔离，失败时输出明确日志；
- ADB 状态 Hook 失败时，不启用常亮，避免错误地永久阻止休眠；
- 电源 Hook 失败时，不修改系统返回结果；
- 不修改 `stay_on_while_plugged_in`、`screen_off_timeout` 或 ADB 系统设置；
- 不持有长期屏幕 WakeLock，不增加轮询线程；
- 首版以当前 Android 16 真机签名为准。其他 Android/ROM 仅在目标签名匹配时启用，不做模糊匹配。

## 8. 测试设计

### 8.1 JVM 单元测试

- 无连接时返回 `false`；
- 仅 USB 连接时返回 `true`；
- 仅无线连接时返回 `true`；
- USB 与无线同时连接时返回 `true`；
- 多连接增减但最终布尔值未变化时不重复通知；
- 最后一个连接断开时只通知一次；
- ADB 已连接时自动休眠判断为保持唤醒；
- 未连接或设备策略限制时保留系统原结果。

### 8.2 构建检查

- `testDebugUnitTest`；
- `lintDebug`；
- `assembleDebug`；
- 检查 APK 内三个 `META-INF/xposed/` 文件；
- 检查 APK 不包含 `io.github.libxposed.api` 实现类；
- 检查 `scope.list` 仅包含 `system`。

### 8.3 真机验收

验收过程先保存并最终恢复用户原有的 `stay_on_while_plugged_in` 与 `screen_off_timeout`：

1. 安装 APK，在 LSPosed 中启用模块并确认作用域只有“系统框架”；
2. 软重启 Android Framework，确认 API 102 模块加载及 Hook 安装日志；
3. 临时关闭系统“充电时保持唤醒”，设置短屏幕超时；
4. USB ADB 已连接时静置超过超时时间，屏幕仍保持唤醒；
5. ADB 连接时按电源键，屏幕能够手动熄灭；再次点亮后继续保持唤醒；
6. 建立无线 ADB，拔掉 USB，静置超过超时时间，屏幕仍保持唤醒；
7. 断开最后一个无线 ADB 客户端，由设备侧延迟记录电源状态；超过正常超时后重新连接并确认屏幕已按系统策略休眠；
8. 恢复所有临时修改的系统设置。

## 9. 非目标

- 不提供按应用配置；
- 不提供通知、快捷开关或设置界面；
- 不在 ADB 连接时强制提高亮度；
- 不在 ADB 连接时主动解锁或点亮屏幕；
- 不阻止电源键、设备管理器或系统显式发起的锁屏；
- 不支持仅凭 USB 数据线插入就保持唤醒。

## 10. 完成标准

- 模块基于现代 Xposed API 102，可被当前 LSPosed/Vector API 102 框架识别；
- USB 与无线 ADB 活跃连接均能独立阻止自动超时休眠；
- 最后一个连接断开后恢复正常超时；
- 电源键手动熄屏不受影响；
- 不改变用户的充电常亮、屏幕超时和 ADB 设置；
- 单元测试、Lint、构建、APK 元数据检查和真机验收全部通过。
