# ADB Stay Awake

**English** | [简体中文](README.zh-CN.md)

An LSPosed module for Android developers that keeps your device awake during ADB debugging, so you can focus on development and testing without repeatedly unlocking your phone. Built with modern Xposed API 102.

## Why use it?

Debugging an app often means switching between editing code, reading logs on your computer, and checking the interface on your phone. When the screen times out and locks, having to wake and unlock it interrupts that workflow.

The **Stay awake** option in Android's Developer options prevents the screen from sleeping while charging, but applies to all charging sessions. You may only want the screen to stay awake during ADB debugging, while still letting it turn off normally when plugged into a charger.

ADB Stay Awake keeps your device awake when a USB debugging connection or an active wireless ADB connection is present. Normal screen timeout behavior resumes once all debugging connections are disconnected. To allow normal screen timeout while charging, leave **Stay awake** in Developer options disabled.

## How it works

The module prevents automatic sleep when USB is connected and configured with the ADB function enabled, or when a wireless ADB connection is active.

USB detection supports ADB Root and ADB setups without authentication, without relying on authentication key records. Connecting to a computer with USB debugging enabled keeps the device awake even when you are not running an adb command. Unplugging USB or disabling USB debugging restores normal screen timeout behavior if no wireless ADB connection remains active. A regular charger does not satisfy the USB configuration condition.

The module only changes automatic sleep decisions. It does not wake an already sleeping device or prevent you from turning the screen off with the power button. The initial development and validation target was Android 16 / ColorOS 16.1.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Hook diagnostics

LSPosed logs include caller deoptimization results, the first invocation of each hook, and ADB/USB state changes. `ADB keep-awake applied` indicates that a power decision hook has changed its return value for the first time. Restart the device after updating the module to load the new code.

Some Android 17 builds move the device administrator timeout check into `ScreenTimeoutConstants`. The module detects the actual class structure to support both the older `PowerManagerService` implementation and the newer `mScreenTimeoutConstants` delegate. The `admin timeout policy` log entry identifies the selected policy class. Device administrator timeout restrictions are still respected; if reading the policy fails, the original system decision is preserved.

See the [hook deoptimization audit (Chinese)](docs/hook-deoptimization-audit.md) for hook call chains, deoptimization coverage, and device verification steps.

## License

This project is licensed under the [MIT License](LICENSE). You may use, modify, and distribute it, including commercially, provided that you retain the copyright and permission notices.
