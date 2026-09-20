// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The three global settings this app controls. Reading them needs no permission; writing
 * needs WRITE_SECURE_SETTINGS.
 */
enum class DevSetting(val key: String, private val minSdk: Int = Build.VERSION_CODES.BASE) {
    DEVELOPER_OPTIONS(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED),
    USB_DEBUGGING(Settings.Global.ADB_ENABLED),

    /** Settings.Global.ADB_WIFI_ENABLED is @hide in the SDK, hence the literal. Android 11+. */
    WIRELESS_DEBUGGING("adb_wifi_enabled", Build.VERSION_CODES.R);

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= minSdk
}

data class DevState(
    val developerOptions: Boolean = false,
    val usbDebugging: Boolean = false,
    val wirelessDebugging: Boolean = false,
)

class DevSettings(context: Context) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    val hasWritePermission: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isEnabled(setting: DevSetting): Boolean =
        setting.supported && Settings.Global.getInt(resolver, setting.key, 0) != 0

    fun read() = DevState(
        developerOptions = isEnabled(DevSetting.DEVELOPER_OPTIONS),
        usbDebugging = isEnabled(DevSetting.USB_DEBUGGING),
        wirelessDebugging = isEnabled(DevSetting.WIRELESS_DEBUGGING),
    )

    /**
     * Writes [setting]. The system reacts on its own: AdbService observes adb_enabled and
     * adb_wifi_enabled and starts or stops the daemon, and the Settings app reads
     * development_settings_enabled to show or hide the Developer options screen.
     *
     * @throws SecurityException when WRITE_SECURE_SETTINGS has not been granted.
     * @throws IllegalStateException when the settings provider refuses the write.
     */
    fun set(setting: DevSetting, enabled: Boolean) {
        if (!setting.supported) return
        write(setting, enabled)
        // Switching Developer options off in the Settings app also resets the debugging
        // switches beneath it. Mirror that so no debugging channel stays open unnoticed.
        if (setting == DevSetting.DEVELOPER_OPTIONS && !enabled) {
            write(DevSetting.USB_DEBUGGING, false)
            if (DevSetting.WIRELESS_DEBUGGING.supported) write(DevSetting.WIRELESS_DEBUGGING, false)
        }
    }

    private fun write(setting: DevSetting, enabled: Boolean) {
        check(Settings.Global.putInt(resolver, setting.key, if (enabled) 1 else 0)) {
            "The settings provider rejected the write for ${setting.key}"
        }
    }

    /** Emits the current state right away, then again whenever one of the settings changes. */
    fun observe(): Flow<DevState> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(read())
            }
        }
        DevSetting.entries.filter { it.supported }.forEach {
            resolver.registerContentObserver(Settings.Global.getUriFor(it.key), false, observer)
        }
        trySend(read())
        awaitClose { resolver.unregisterContentObserver(observer) }
    }
}
