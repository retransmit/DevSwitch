// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.quick

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.devswitch.DevSetting

/** Receives the tap on a widget cell. Only the app's own PendingIntent can reach it. */
class WidgetToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val setting = DevSetting.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_SETTING) }
        if (setting != null) QuickToggle.flip(context, setting)
    }

    companion object {
        const val EXTRA_SETTING = "setting"
    }
}
