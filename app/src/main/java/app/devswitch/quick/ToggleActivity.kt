// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.quick

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.devswitch.DevSetting
import app.devswitch.MainActivity

/**
 * Target of the launcher shortcuts. Shortcuts can only start activities, so this one has no
 * screen: it flips the setting named by the intent action and finishes at once.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val setting = when (intent?.action) {
            ACTION_DEVELOPER_OPTIONS -> DevSetting.DEVELOPER_OPTIONS
            ACTION_USB_DEBUGGING -> DevSetting.USB_DEBUGGING
            ACTION_WIRELESS_DEBUGGING -> DevSetting.WIRELESS_DEBUGGING
            else -> null
        }
        if (setting != null && QuickToggle.flip(this, setting) == QuickToggle.Outcome.NEEDS_SETUP) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }

    companion object {
        const val ACTION_DEVELOPER_OPTIONS = "app.devswitch.action.TOGGLE_DEVELOPER_OPTIONS"
        const val ACTION_USB_DEBUGGING = "app.devswitch.action.TOGGLE_USB_DEBUGGING"
        const val ACTION_WIRELESS_DEBUGGING = "app.devswitch.action.TOGGLE_WIRELESS_DEBUGGING"
    }
}
