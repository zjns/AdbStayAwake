# ADB 连接时保持唤醒 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个基于现代 Xposed API 102 的独立模块，在 USB 或无线 ADB 存在已认证活跃连接时阻止系统自动超时休眠，同时保留电源键手动熄屏能力。

**Architecture:** 模块只加载到 `system`。ADB Hook 在系统原逻辑更新连接集合后汇总 USB/Wi-Fi 状态；PowerManager Hook 把该状态并入系统原有 kept-awake 判断，并在连接变化时重新计算正常屏幕超时。

**Tech Stack:** Kotlin、AGP 9.0.1、Gradle 9.3.1、Java 17、compile/target SDK 36、libxposed API 102.0.0、JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-21-adb-stay-awake-design.md`

## Global Constraints

- 项目目录：`/Users/kofua/Projects/Android/AdbStayAwake`。
- applicationId/namespace：`com.kofua.adbstayawake`。
- `io.github.libxposed:api:102.0.0` 必须使用 `compileOnly`。
- Xposed 元数据固定为 API 102、静态 `system` 作用域、关闭热重载、保护异常模式。
- 首个运行目标为 Android 16、ColorOS 16.1、OnePlus 13T。
- 不修改充电常亮、屏幕超时和 ADB 设置；不持有长期屏幕 WakeLock；不轮询。
- 不记录 ADB 公钥、指纹或密钥内容。
- 关键反射和并发逻辑使用中文注释。
- 本轮实现提交最终 squash 为一个提交。

## Review Focus

- 同一 USB 密钥多个 transport 只断开一个：Task 2 引用计数测试。
- USB 与无线连接交叠时任一侧断开：Task 2 交叉连接测试。
- 重复 Handler 消息不能重复刷新电源状态：Task 2 通知去重测试。
- 零或负连接数必须按未连接处理：Task 2 边界值测试。
- 设备管理器强制最大屏幕超时时不得绕过：Task 2 策略测试。

---

## File Structure

```text
app/src/main/java/com/kofua/adbstayawake/
├── AdbStayAwakeModule.kt       # API 102 入口与安装编排
├── AdbConnectionState.kt       # 线程安全的连接状态和通知去重
├── AdbDebuggingHook.kt         # 读取 USB/Wi-Fi 活跃连接集合
├── PowerPolicyDecision.kt      # 可单测的 kept-awake 决策
└── PowerPolicyHook.kt          # PowerManagerService Hook
```

### Task 1: 初始化 API 102 模块工程

**Files:**
- Create: `.gitignore`
- Create: `README.md`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/resources/META-INF/xposed/java_init.list`
- Create: `app/src/main/resources/META-INF/xposed/module.prop`
- Create: `app/src/main/resources/META-INF/xposed/scope.list`

**Interfaces:**
- Consumes: Maven Central 的 `io.github.libxposed:api:102.0.0`。
- Produces: 可编译的 `:app` 和入口类名 `com.kofua.adbstayawake.AdbStayAwakeModule`。

- [ ] **Step 1: 写基础构建文件**

`settings.gradle.kts` 使用 `google()`、`mavenCentral()`、`gradlePluginPortal()`，设置 `rootProject.name = "AdbStayAwake"` 并 `include(":app")`。

`gradle/libs.versions.toml`：

```toml
[versions]
agp = "9.0.1"
libxposed = "102.0.0"
junit = "4.13.2"

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }

[libraries]
libxposed-api = { module = "io.github.libxposed:api", version.ref = "libxposed" }
junit = { module = "junit:junit", version.ref = "junit" }
```

`build.gradle.kts` 只声明 `alias(libs.plugins.android.application) apply false`；`gradle.properties` 设置 UTF-8、2 GiB Gradle 堆和 `android.useAndroidX=true`。

- [ ] **Step 2: 写 app 构建文件**

`app/build.gradle.kts` 必须包含：

```kotlin
plugins { alias(libs.plugins.android.application) }

android {
    namespace = "com.kofua.adbstayawake"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.kofua.adbstayawake"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.resources {
        merges += "META-INF/xposed/*"
        excludes += "**"
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    testImplementation(libs.junit)
}
```

- [ ] **Step 3: 写 Manifest 和 Xposed 元数据**

Manifest 只包含无入口的 application，label 为 `ADB Stay Awake`，`allowBackup=false`。三个资源文件内容：

```text
# java_init.list
com.kofua.adbstayawake.AdbStayAwakeModule

# module.prop
minApiVersion=102
targetApiVersion=102
staticScope=true
autoHotReload=false
exceptionMode=protective

# scope.list
system
```

R8 规则：

```proguard
-dontwarn io.github.libxposed.annotation.**
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
```

- [ ] **Step 4: 写 README、`.gitignore` 并生成 Wrapper**

README 记录用途、行为边界、API 102 和构建命令。忽略 `.gradle/`、`.idea/`、`local.properties`、`**/build/`。运行：

```bash
/Users/kofua/Projects/Android/libxposed/example/gradlew -p "$PWD" wrapper --gradle-version 9.3.1
```

- [ ] **Step 5: 写最小入口并构建**

入口暂时只覆盖 `onModuleLoaded` 并用 `log(Log.INFO, "AdbStayAwake", ...)` 输出 API 版本。运行 `./gradlew :app:assembleDebug`，预期 `BUILD SUCCESSFUL`。

- [ ] **Step 6: 提交脚手架**

```bash
git add .
git commit -m "build: 初始化 API 102 模块工程"
```

### Task 2: 用 TDD 实现状态模型与电源决策

**Files:**
- Create: `app/src/main/java/com/kofua/adbstayawake/AdbConnectionState.kt`
- Create: `app/src/main/java/com/kofua/adbstayawake/PowerPolicyDecision.kt`
- Test: `app/src/test/java/com/kofua/adbstayawake/AdbConnectionStateTest.kt`
- Test: `app/src/test/java/com/kofua/adbstayawake/PowerPolicyDecisionTest.kt`

**Interfaces:**
- Produces: `update(Int, Int): Boolean`、`isConnected(): Boolean`、`shouldKeepAwake(Boolean, Boolean, Boolean): Boolean`。

- [ ] **Step 1: 写失败测试**

连接状态测试覆盖：USB `1/0`、Wi-Fi `0/1`、两者 `2/3`、零和负数、USB 引用计数 `2 -> 1 -> 0`、`2/0 -> 1/0 -> 1/1 -> 0/1 -> 0/0` 只通知 `[true, false]`。策略测试覆盖原结果为 true、ADB 保持唤醒、设备管理器限制、断开状态。

- [ ] **Step 2: 运行测试确认 RED**

Run: `./gradlew :app:testDebugUnitTest`

Expected: FAIL，提示两个生产类型不存在。

- [ ] **Step 3: 实现连接状态**

```kotlin
internal class AdbConnectionState(private val onChanged: (Boolean) -> Unit) {
    private val connected = AtomicBoolean(false)

    fun update(usbConnections: Int, wifiConnections: Int): Boolean {
        val next = usbConnections > 0 || wifiConnections > 0
        val previous = connected.getAndSet(next)
        if (previous != next) onChanged(next)
        return next
    }

    fun isConnected(): Boolean = connected.get()
}
```

- [ ] **Step 4: 实现策略决策**

```kotlin
internal object PowerPolicyDecision {
    fun shouldKeepAwake(
        original: Boolean,
        adbConnected: Boolean,
        adminTimeoutEnforced: Boolean,
    ): Boolean = original || (adbConnected && !adminTimeoutEnforced)
}
```

- [ ] **Step 5: 运行测试确认 GREEN 并提交**

运行 `./gradlew :app:testDebugUnitTest`，预期 8 个测试通过；提交：

```bash
git add app/src/main app/src/test
git commit -m "feat: 实现 ADB 连接状态模型"
```

### Task 3: Hook PowerManagerService

**Files:**
- Create: `app/src/main/java/com/kofua/adbstayawake/PowerPolicyHook.kt`

**Interfaces:**
- Consumes: `isAdbConnected: () -> Boolean`。
- Produces: `install(() -> Boolean)` 和 `onAdbConnectionChanged(Boolean)`。

- [ ] **Step 1: 一次性解析全部反射目标**

解析 `PowerManagerService`、`PowerGroup` 及下列方法，在全部解析成功后才注册 Hook：

```kotlin
onBootPhase(int)
isBeingKeptAwakeLocked(PowerGroup)
isBeingKeptFromInattentiveSleepLocked()
isItBedTimeYetLocked(PowerGroup)
isMaximumScreenOffTimeoutFromDeviceAdminEnforcedLocked()
userActivityInternal(int, long, int, int, int)
```

调用 `module.deoptimize(isItBedTimeYetLocked)`，只反优化直接调用 kept-awake 判断的方法。

- [ ] **Step 2: Hook 两个自动休眠判断**

每个 interceptor 先执行 `chain.proceed()`，然后调用：

```kotlin
PowerPolicyDecision.shouldKeepAwake(
    original = original,
    adbConnected = isAdbConnected(),
    adminTimeoutEnforced = adminMethod.invoke(chain.thisObject) as Boolean,
)
```

反射异常时记录错误并返回 `original`；Hook 使用 `ExceptionMode.PROTECTIVE`。

- [ ] **Step 3: 捕获服务实例并重算超时**

Hook `onBootPhase`，原方法返回后把 `chain.thisObject` 保存到 `AtomicReference`；若连接状态已经是 `true`，立即同步一次，覆盖 ADB 事件早于电源服务就绪的启动顺序。连接状态变化时先检查反射方法已初始化，再调用：

```kotlin
userActivityInternal.invoke(
    service,
    0,
    SystemClock.uptimeMillis(),
    0, // USER_ACTIVITY_EVENT_OTHER
    0,
    1000, // SYSTEM_UID
)
```

该调用只在屏幕已亮时刷新活动时间，不唤醒已熄灭屏幕。日志只写 `connected=true/false`。

- [ ] **Step 4: 编译、跑回归测试并提交**

Run: `./gradlew :app:testDebugUnitTest :app:assembleDebug`

Expected: `BUILD SUCCESSFUL`，8 个测试保持通过。

```bash
git add app/src/main/java/com/kofua/adbstayawake/PowerPolicyHook.kt
git commit -m "feat: 接入系统自动休眠策略"
```

### Task 4: Hook ADB 连接事件并完成入口

**Files:**
- Create: `app/src/main/java/com/kofua/adbstayawake/AdbDebuggingHook.kt`
- Modify: `app/src/main/java/com/kofua/adbstayawake/AdbStayAwakeModule.kt`

**Interfaces:**
- Consumes: `AdbConnectionState`、`PowerPolicyHook`。
- Produces: system_server 内完整的数据流。

- [ ] **Step 1: 解析 ADB Handler 与连接集合**

加载：

```text
com.android.server.adb.AdbDebuggingManager
com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler
```

解析 ADB Handler 与 `UsbDeviceManager$UsbHandler` 的 `handleMessage(Message)`；外部 manager 字段按 `field.type == managerClass` 查找。USB key 字段为 `mConnectedKeys`；无线消息 `22/23` 使用模块自己的按 key transport 引用计数；USB transport 字段为 `mConnected`、`mConfigured`，最终 functions 从 `sys.usb.state` 读取。所有 Field 设置 `isAccessible=true`。

- [ ] **Step 2: Hook 原逻辑之后的连接集合**

```kotlin
module.hook(handleMessage)
    .setExceptionMode(ExceptionMode.PROTECTIVE)
    .intercept { chain ->
        val result = chain.proceed()
        runCatching {
            val manager = outerField.get(chain.thisObject)
            val usbCount = (usbField.get(manager) as Map<*, *>).size
            val wifiCount = (wifiField.get(manager) as Set<*>).size
            val before = state.isConnected()
            val after = state.update(usbCount, wifiCount, usbTransportConnected)
            if (before != after) {
                module.log(Log.INFO, TAG, "connected=$after, usb=$usbCount, wifi=$wifiCount")
            }
        }.onFailure { module.log(Log.ERROR, TAG, "failed to read ADB state", it) }
        result
    }
```

- [ ] **Step 3: 完成 API 102 入口**

`onSystemServerStarting` 按顺序创建 `PowerPolicyHook`、`AdbConnectionState(powerPolicy::onAdbConnectionChanged)`，再分别安装 Power 和 ADB Hook。两组安装各自使用 `runCatching` 隔离并输出成功/失败日志。

- [ ] **Step 4: 运行完整本地门禁并提交**

Run:

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Expected: 单测、Lint、构建全部成功。

```bash
git add app/src/main/java/com/kofua/adbstayawake
git commit -m "feat: 监听 ADB 活跃连接"
```

### Task 5: APK、真机验收与提交整理

**Files:**
- Verify: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Consumes: 设备 `3B15AW007EP00000` 与 LSPosed/Vector API 102。
- Produces: 构建、加载、USB/无线 ADB 和手动熄屏证据。

- [ ] **Step 1: 干净构建和 APK 检查**

```bash
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'META-INF/xposed/(java_init.list|module.prop|scope.list)'
unzip -p app/build/outputs/apk/debug/app-debug.apk META-INF/xposed/module.prop
unzip -p app/build/outputs/apk/debug/app-debug.apk META-INF/xposed/scope.list
```

确认 scope 只有 `system`，并确认 APK 内不存在 `io/github/libxposed/api` 类。

- [ ] **Step 2: 安装、启用系统作用域并软重启**

安装 APK，在 LSPosed 中启用模块并确认静态作用域只有“系统框架”，然后执行：

```bash
adb -s 3B15AW007EP00000 shell su -c 'stop; start'
```

等待 ADB 稳定，日志必须出现 Power Hook、ADB Hook 安装成功以及 `connected=true`。

- [ ] **Step 3: 验证 USB ADB 与手动电源键**

先保存 `stay_on_while_plugged_in` 与 `screen_off_timeout`，临时改为 `0` 和 `15000`。USB ADB 连接时静置 20 秒，`dumpsys power` 必须仍为 Awake；发送 `KEYCODE_POWER` 必须可以手动熄屏，再次点亮后仍不自动休眠。

- [ ] **Step 4: 验证无线 ADB 与最后连接断开**

建立无线 ADB 后拔掉 USB，日志应显示 `wifi=1`，静置 20 秒仍保持 Awake。设备侧安排 25 秒延迟 `dumpsys power` 到 `/data/local/tmp/adbstayawake-power-after-disconnect.txt`，断开最后一个无线客户端；重新连接后确认日志为 `connected=false` 且延迟采样已按正常超时休眠。

- [ ] **Step 5: 恢复设备设置**

无论验收是否成功，都恢复原 `stay_on_while_plugged_in` 与 `screen_off_timeout`，并清理临时采样文件。

- [ ] **Step 6: Squash 本轮实现提交**

```bash
plan_commit=$(git log -1 --format=%H -- docs/superpowers/plans/2026-09-21-adb-stay-awake.md)
git reset --soft "$plan_commit"
git commit -m "feat: 新增 ADB 连接时保持唤醒模块" -m "- 支持 USB 与无线 ADB 活跃连接检测
- 接入 system_server 自动休眠策略并保留手动熄屏
- 补充 API 102 元数据、单元测试和构建配置"
```

- [ ] **Step 7: 最终验证**

```bash
./gradlew clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
git status --short
git log --oneline --decorate -5
```

Expected: 构建成功、工作区干净、计划提交之后只有一个实现提交。记录 APK 绝对路径、SHA-256、测试数量、system_server PID、LSPosed 命中日志和设置恢复证据。
