// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.tiles

import android.app.PendingIntent
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.devswitch.DevSetting
import app.devswitch.DevSettings
import app.devswitch.MainActivity
import app.devswitch.R

/** One Quick Settings tile per setting; the concrete subclasses only pick the setting. */
abstract class ToggleTileService(private val setting: DevSetting) : TileService() {
    private val settings by lazy { DevSettings(this) }
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = render()
    }

    override fun onStartListening() {
        if (setting.supported) {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(setting.key), false, observer)
        }
        render()
    }

    override fun onStopListening() {
        contentResolver.unregisterContentObserver(observer)
    }

    override fun onClick() {
        if (!setting.supported) return
        if (!settings.hasWritePermission) {
            openApp()
            return
        }
        // Opening a debugging channel from the lock screen would be a hole; insist on unlock.
        if (isLocked) unlockAndRun { flip() } else flip()
    }

    private fun flip() {
        runCatching { settings.set(setting, !settings.isEnabled(setting)) }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val enabled = settings.isEnabled(setting)
        tile.state = when {
            !setting.supported -> Tile.STATE_UNAVAILABLE
            enabled -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !setting.supported -> R.string.tile_unsupported
                    !settings.hasWritePermission -> R.string.tile_needs_setup
                    enabled -> R.string.tile_on
                    else -> R.string.tile_off
                },
            )
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

class DeveloperOptionsTile : ToggleTileService(DevSetting.DEVELOPER_OPTIONS)

class UsbDebuggingTile : ToggleTileService(DevSetting.USB_DEBUGGING)

class WirelessDebuggingTile : ToggleTileService(DevSetting.WIRELESS_DEBUGGING)
