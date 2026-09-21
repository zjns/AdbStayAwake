package com.kofua.adbstayawake

import android.os.Message
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class AdbDebuggingHook(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
) {
    private val usbKeyCount = AtomicInteger(0)
    private val wirelessConnections = WirelessConnectionRefCounts()
    private val usbTransportConnected = AtomicBoolean(false)

    fun install(state: AdbConnectionState) {
        val managerClass = classLoader.loadClass(ADB_DEBUGGING_MANAGER)
        val handlerClass = classLoader.loadClass(ADB_DEBUGGING_HANDLER)
        val usbHandlerClass = classLoader.loadClass(USB_HANDLER)
        val handleMessage = handlerClass.getDeclaredMethod(
            "handleMessage",
            Message::class.java,
        )
        val usbHandleMessages = listOf(
            usbHandlerClass,
            classLoader.loadClass(USB_HANDLER_HAL),
        ).map { handler ->
            handler.getDeclaredMethod("handleMessage", Message::class.java)
        }
        val outerManager = handlerClass.declaredFields
            .single { field -> field.type == managerClass }
            .apply { isAccessible = true }
        val usbKeys = managerClass.getDeclaredField("mConnectedKeys")
            .apply { isAccessible = true }
        val usbConnected = usbHandlerClass.getDeclaredField("mConnected")
            .apply { isAccessible = true }
        val usbConfigured = usbHandlerClass.getDeclaredField("mConfigured")
            .apply { isAccessible = true }
        val getSystemProperty = classLoader.loadClass("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java)
            .apply { isAccessible = true }

        module.hook(handleMessage)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                updateAfterSystemHandler(
                    handler = chain.thisObject,
                    message = chain.args[0] as Message,
                    outerManager = outerManager,
                    usbKeys = usbKeys,
                    state = state,
                )
                result
            }

        usbHandleMessages.forEach { handleUsbMessage ->
            module.hook(handleUsbMessage)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    updateAfterUsbHandler(
                        handler = chain.thisObject,
                        usbConnected = usbConnected,
                        usbConfigured = usbConfigured,
                        getSystemProperty = getSystemProperty,
                        state = state,
                    )
                    result
                }
        }
    }

    private fun updateAfterSystemHandler(
        handler: Any,
        message: Message,
        outerManager: Field,
        usbKeys: Field,
        state: AdbConnectionState,
    ) {
        runCatching {
            // 系统原 Handler 已完成集合增删，此处只读取数量，绝不记录密钥内容。
            val manager = outerManager.get(handler)
            val usbCount = (usbKeys.get(manager) as Map<*, *>).size
            usbKeyCount.set(usbCount)
            updateWirelessConnections(message)
            publishState(state)
        }.onFailure { error ->
            module.log(Log.ERROR, TAG, "failed to read ADB connection state", error)
        }
    }

    private fun updateWirelessConnections(message: Message) {
        val key = message.obj as? String
        when (message.what) {
            MESSAGE_WIFI_DEVICE_CONNECTED -> wirelessConnections.onConnected(key)
            MESSAGE_WIFI_DEVICE_DISCONNECTED -> wirelessConnections.onDisconnected(key)
            MESSAGE_CLEAR_AUTHORIZATIONS,
            MESSAGE_WIFI_DISABLE,
            MESSAGE_WIFI_DENY,
            MESSAGE_WIFI_SERVER_DISCONNECTED,
            MESSAGE_ADBD_SOCKET_DISCONNECTED,
            -> wirelessConnections.clear()
        }
    }

    private fun updateAfterUsbHandler(
        handler: Any,
        usbConnected: Field,
        usbConfigured: Field,
        getSystemProperty: Method,
        state: AdbConnectionState,
    ) {
        runCatching {
            val usbState = getSystemProperty.invoke(null, USB_STATE_PROPERTY) as String
            val adbFunctionEnabled = usbState.split(',').any { function -> function == "adb" }
            val available =
                usbConnected.getBoolean(handler) &&
                    usbConfigured.getBoolean(handler) &&
                    adbFunctionEnabled
            usbTransportConnected.set(available)
            publishState(state)
        }.onFailure { error ->
            module.log(Log.ERROR, TAG, "failed to read USB transport state", error)
        }
    }

    private fun publishState(state: AdbConnectionState) {
        val before = state.isConnected()
        val usb = usbKeyCount.get()
        val wifi = wirelessConnections.connectionCount()
        val usbTransport = usbTransportConnected.get()
        val after = state.update(
            usbConnections = usb,
            wifiConnections = wifi,
            usbTransportConnected = usbTransport,
        )
        if (before != after) {
            module.log(
                Log.INFO,
                TAG,
                "connection changed: connected=$after, usb=$usb, " +
                    "usbTransport=$usbTransport, wifi=$wifi",
            )
        }
    }

    private companion object {
        const val TAG = "AdbStayAwake"
        const val ADB_DEBUGGING_MANAGER = "com.android.server.adb.AdbDebuggingManager"
        const val ADB_DEBUGGING_HANDLER =
            "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler"
        const val USB_HANDLER = "com.android.server.usb.UsbDeviceManager\$UsbHandler"
        const val USB_HANDLER_HAL = "com.android.server.usb.UsbDeviceManager\$UsbHandlerHal"
        const val USB_STATE_PROPERTY = "sys.usb.state"
        const val MESSAGE_CLEAR_AUTHORIZATIONS = 6
        const val MESSAGE_WIFI_DISABLE = 12
        const val MESSAGE_WIFI_DENY = 19
        const val MESSAGE_WIFI_DEVICE_CONNECTED = 22
        const val MESSAGE_WIFI_DEVICE_DISCONNECTED = 23
        const val MESSAGE_WIFI_SERVER_DISCONNECTED = 25
        const val MESSAGE_ADBD_SOCKET_DISCONNECTED = 27
    }
}
