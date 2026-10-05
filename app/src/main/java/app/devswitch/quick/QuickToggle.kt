// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.quick

import android.app.KeyguardManager
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import app.devswitch.DevSetting
import app.devswitch.DevSettings
import app.devswitch.R

/** Flips one setting from outside the app's own screen: the home screen widget and the shortcuts. */
object QuickToggle {
    enum class Outcome { TURNED_ON, TURNED_OFF, NEEDS_SETUP, LOCKED, UNSUPPORTED, FAILED }

    @StringRes
    fun label(setting: DevSetting): Int = when (setting) {
        DevSetting.DEVELOPER_OPTIONS -> R.string.developer_options
        DevSetting.USB_DEBUGGING -> R.string.usb_debugging
        DevSetting.WIRELESS_DEBUGGING -> R.string.wireless_debugging
    }

    /** Flips [setting] and tells the user what happened with a toast. */
    fun flip(context: Context, setting: DevSetting): Outcome {
        val settings = DevSettings(context)
        val outcome = when {
            !setting.supported -> Outcome.UNSUPPORTED
            !settings.hasWritePermission -> Outcome.NEEDS_SETUP
            // Opening a debugging channel on a locked phone would be a hole, as with the tiles.
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true -> Outcome.LOCKED
            else -> {
                val turnOn = !settings.isEnabled(setting)
                if (runCatching { settings.set(setting, turnOn) }.isSuccess) {
                    if (turnOn) Outcome.TURNED_ON else Outcome.TURNED_OFF
                } else {
                    Outcome.FAILED
                }
            }
        }
        val name = context.getString(label(setting))
        val message = when (outcome) {
            Outcome.TURNED_ON -> context.getString(R.string.quick_turned_on, name)
            Outcome.TURNED_OFF -> context.getString(R.string.quick_turned_off, name)
            Outcome.FAILED -> context.getString(R.string.quick_failed, name)
            Outcome.LOCKED -> context.getString(R.string.quick_unlock_first)
            Outcome.UNSUPPORTED -> context.getString(R.string.wireless_debugging_unsupported)
            // The caller opens the app, which explains the setup; a toast would only get in the way.
            Outcome.NEEDS_SETUP -> null
        }
        if (message != null) Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        SwitchesWidget.updateAll(context)
        return outcome
    }
}
