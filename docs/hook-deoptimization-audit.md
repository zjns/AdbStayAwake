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
