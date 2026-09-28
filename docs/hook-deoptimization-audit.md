# Hook 反优化检查（2026-09-23）

## 证据与边界

目标设备为 PKX110 / Android 16，LSPosed IT 2.2.0-it (7891)。现有日志只有模块加载和安装成功，未出现连接状态变化；USB 已连接、已配置，`sys.usb.state=mtp,adb`，最近休眠原因为 `timeout`。这些现象能确认功能失效，不能单独证明发生了 ART 内联。

核对了已安装 APK、设备上的 `services.jar` 和 `framework.jar`。`services.jar` SHA-256 为 `7aae9691a99870a4f20b2ca72b0fd5810770acc9a635ad184f5663025bf0132b`。下表的大小来自 DEX `code_item.insns_size`，单位为 16 位代码单元，不是 Java 行数。

API 102 的 `deoptimize` 需要作用于包含内联副本的调用者。它返回成功不代表所有调用路径都已覆盖，也不自动清除更上层方法里的内联副本。参见 [LSPlant 维护者说明](https://github.com/LSPosed/LSPlant/discussions/5)。

## 全部 hook 点

| Hook 点 | DEX 大小 | 调用路径和本次处理 |
| --- | ---: | --- |
| `PowerManagerService.isBeingKeptAwakeLocked(PowerGroup)` | 43 | 补齐普通超时、梦境分支及上层电源更新入口的反优化。 |
| `PowerManagerService.isBeingKeptFromInattentiveSleepLocked()` | 21 | 补齐注意力超时、警告隐藏及上层电源更新入口的反优化。 |
| `PowerManagerService.onBootPhase(int)` | 163 | 调用者是 `SystemServiceManager.startBootPhase(TimingsTraceAndSlog,int)`；对这个启动入口做预防性反优化，记录首次回调。尚无证据证明原方法被内联。 |
| `AdbDebuggingManager$AdbDebuggingHandler.handleMessage(Message)` | 1660 | 经 `Handler.dispatchMessage` 调度。方法较大，当前将内联列为较低优先级假设；记录首次回调及 USB 认证数量、无线连接数量变化。 |
| `UsbDeviceManager$UsbHandler.handleMessage(Message)` | 1178 | HAL 实现通过 `super.handleMessage` 进入父类。父类和 HAL 两处已有 hook；记录首次回调和 USB 可用状态变化。 |
| `UsbDeviceManager$UsbHandlerHal.handleMessage(Message)` | 494 | 经 `Handler.dispatchMessage` 调度，同样先用日志验证。 |

大小只用于评估风险，不能替代设备实际 AOT/JIT 编译证据。没有检查设备编译产物中的 inline metadata，因此本检查不声称这些方法一定被内联或绝不会被内联。

## 电源调用链

```text
updatePowerStateLocked
  → updateWakefulnessLocked
    → isItBedTimeYetLocked
      → isBeingKeptAwakeLocked / isBeingKeptFromInattentiveSleepLocked
  → updateAttentiveStateLocked
    → isBeingKeptFromInattentiveSleepLocked
    → maybeHideInattentiveSleepWarningLocked
      → isBeingKeptFromInattentiveSleepLocked

handleSandman
  → isBeingKeptAwakeLocked
  → canDreamLocked → isBeingKeptAwakeLocked
  → isItBedTimeYetLocked → 两个被 hook 的判断

onDreamSuppressionChangedLocked
  → isItBedTimeYetLocked → 两个被 hook 的判断
```

本次逐项反优化上述 8 个已核实的电源调用者。现有实现只处理 `isItBedTimeYetLocked`，遗漏直接调用者及可能包含嵌套内联的上层方法。再对 `SystemServiceManager.startBootPhase` 做一次启动阶段保护，共 9 个名称；逐项记录成功、返回 false、方法缺失或异常。不同 ROM 有同名重载时逐一处理，不反优化整个类或整个进程。

这是一份针对当前 ROM 的有限名单，不保证覆盖未来 ROM 新增的路径。

## 为什么暂不反优化公共消息循环

```text
Looper.loop → Looper.loopOnce → Handler.dispatchMessage
  → AdbDebuggingHandler.handleMessage
  → UsbHandlerHal.handleMessage → UsbHandler.handleMessage
```

`Handler.dispatchMessage` 为 25 个 DEX 代码单元，可能被内联到 `Looper.loopOnce`（647 个代码单元）。但仅内联分发方法并不等于内联了三个较大的目标 `handleMessage`。若目标调用仍存在，原 hook 仍能触发。

反优化公共 `dispatchMessage`、`loopOnce` 会影响 system_server 中其他 Handler 的消息处理，当前证据不足以支持扩大到这里。因此保留原 hook，先检查三个 `hook entered`。如果重启后仍缺失，下一轮再对公共分发链做独立对照，避免同时修改多个变量。

## 验证方式

- 本地测试覆盖反优化名称解析、重载处理、缺失方法/类，以及返回 false 或抛异常后继续处理其余调用者。测试替代的是 LSPosed 边界，不模拟 ART 内联。
- `hook entered`：每个 hook 每进程最多一次，证明回调进入。
- `ADB state` / `USB state`：首次及相关状态变化时记录，不包含密钥、指纹或消息对象。
- `connection changed`：模块认定的 ADB 连接状态变化。
- `ADB keep-awake applied`：每个电源判断首次把原结果 false 改为 true，证明策略实际介入。
- `isBeingKeptFromInattentiveSleepLocked` 的路径可能因设备未启用注意力超时而不执行；单独缺少它的命中日志不能判定 hook 失败。

真机验收需要安装新 APK 并重启：保留 USB ADB 连接，确认 `usb>0`、USB `available=true` 和 `connected=true`，静置超过屏幕超时，检查策略日志和电源状态；然后验证电源键仍能熄屏、断开最后一条连接后恢复超时。还需多次重启对照，单次通过不能证明间歇性问题完全消失。

本地验证结果：17 项 JVM 单测全部通过，`lintDebug`、`assembleDebug`、`assembleRelease` 通过。Debug/Release APK 的实际模块入口、`scope.list=system` 均已核对，未打入 libxposed/JUnit 实现类。Release 签名与设备现有安装包一致，可覆盖安装。

截至本次代码提交，未安装新包或重启设备，真机验收待执行。

## Android 17 兼容修复（2026-09-28）

从 PKX110 真机读取到 Android 17 / API 37，构建版本 `PKX110_17.0.0.100(CN01)`。本次 `services.jar` 的 SHA-256 为 `25401d2c9f961049a26dbc32db7c7e73ff1487ad793e08f499070502148d3918`。

反编译确认 `isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()` 已从 `PowerManagerService` 移至 `com.android.server.power.ScreenTimeoutConstants`，由服务的 `mScreenTimeoutConstants` 字段持有。系统自己的 `updateStayOnLocked` 和 `getScreenOffTimeoutLocked` 也经这个字段调用。原模块在安装任何电源 hook 前解析旧位置，因而方法缺失会中止整组电源 hook 的安装。两个保活判断、`onBootPhase(int)` 和 `userActivityInternal(int,long,int,int,int)` 的签名仍符合原实现。

修复优先解析旧位置，仅遇到 `NoSuchMethodException` 时尝试委托对象；按结构探测，不硬编码 SDK 分界。反射信息在安装时解析，每次判断在原 Locked 调用链内读取当前对象的策略，继续遵守设备管理限制。若两种结构均不支持，仍明确报告安装失败；运行时策略读取异常仍保留原结果，不将“读取失败”视为“无限制”。

回归测试直接执行 `PowerPolicyHook` 的安装及两类决策回调，覆盖旧结构、新结构、动态管理限制、ADB 断开和策略调用异常。修复前旧结构通过，3 项新结构测试因相同的 `NoSuchMethodException` 失败；修复后全部 21 项 JVM 单测、`lintDebug`、`assembleDebug`、`assembleRelease` 通过。Lint 为 0 错误、11 条警告，涉及私有 API、目标版本、构建工具版本及备份配置；Release APK 签名校验通过。

已核对真机系统结构，尚未安装此次 APK 或重启设备。重启后应看到 `admin timeout policy: com.android.server.power.ScreenTimeoutConstants`、`PowerManager hooks installed` 及 `ADB keep-awake applied`。仍需按上面的真机验收步骤确认实际保活、手动熄屏和断连恢复超时。

## ADB Root 下的 USB 连接判断（2026-09-28）

后续安装和软重启验证发现，电源 hook 安装成功不等于保活生效。PID 3405 的进程曾记录 `usb=1` 并在无操作 91 秒后仍保活，但 PID 30837 的进程一直是 `usb=0`，设置 15 秒超时后正常进入了超时休眠。

临时诊断版在 ADB Handler 自身线程读取系统集合，得到 `ADB snapshot: systemUsb=0, cachedUsb=0, transport=true`，确认不是模块漏读已有的连接记录。设备上 KernelSU 的 `adb_root` 功能为开启状态，adbd 加载了 `libadbroot.so`；用户也确认启用了 ADB Root／免授权模式。这一设备环境中，认证集合为空不能代表 USB ADB 不可用。后续另一次启动又出现 `usb=1`，因此不将“开启 ADB Root”与“认证集合必然为空”画等号。

经用户确认，USB 改为以 `connected && configured && adbFunctionEnabled` 为准，不再要求认证集合非空。这也意味着连接电脑并启用 USB 调试后，即使没有运行 adb 命令也保活。无线 ADB 仍依赖活跃连接；USB 不可用且没有无线连接时恢复超时。临时诊断 hook 和逐事件日志已移除，没有修改 KernelSU 设置，也没有扩大公共消息分发的反优化范围。

原实现下，USB 无认证记录及认证记录消失两项回归测试失败；修改后全部 22 项 JVM 单测通过，`lintDebug` 为 0 错误、11 条警告，Debug/Release 构建通过。

正式修复版已安装，等待 20 秒后软重启，验证进程为 PID 20248。屏幕超时保留为 15000 ms，KernelSU ADB Root 保持开启：

- 启动日志出现 `connected=true, usb=0, usbTransport=true, wifi=0`，证明 USB 保活不再依赖认证记录。随后另有认证事件将 `usb` 更新为 1。
- 日志出现 `ADB keep-awake applied: isBeingKeptAwakeLocked`；无操作约 25 秒时仍为 `Awake`，且 `mStayOn=false`、`userActivitySummary=0x4`，不是充电常亮或尚未达到超时。
- 临时关闭 USB 调试后，日志转为 `connected=false`。最后用户活动时间为 147349123，休眠时间为 147364137，相差 15014 ms，休眠原因为 `timeout`，状态为 `Dozing`。
- 自动恢复 USB 调试及 ADB 连接 8 秒后仍为 `Dozing`，未主动点亮屏幕。
- ADB 连接期间发送电源键事件，采样得到 `Dozing` 和 `power_button`，手动熄屏仍生效。

验证结束时 USB 调试已恢复开启，超时仍为 15 秒，ADB Root 未改动。本轮未进行无线 ADB 真机验证。
