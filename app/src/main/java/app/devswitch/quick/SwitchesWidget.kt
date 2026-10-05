// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.quick

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.StringRes
import app.devswitch.DevSetting
import app.devswitch.DevSettings
import app.devswitch.MainActivity
import app.devswitch.R

/**
 * Home screen widget with one cell per setting; tapping a cell flips that setting. This provider
 * has to be exported for the launcher, so it only draws. The flip itself goes to
 * [WidgetToggleReceiver], which other apps cannot reach.
 */
class SwitchesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = render(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    /** Also told when the phone's language or this app's own language changes, to redraw the text. */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCALE_CHANGED) updateAll(context) else super.onReceive(context, intent)
    }

    private class Cell(
        val setting: DevSetting,
        val cell: Int,
        val fill: Int,
        val icon: Int,
        val label: Int,
        @StringRes val text: Int,
    )

    companion object {
        private val cells = listOf(
            Cell(
                DevSetting.DEVELOPER_OPTIONS, R.id.cell_developer, R.id.fill_developer, R.id.icon_developer,
                R.id.label_developer, R.string.widget_short_developer,
            ),
            Cell(
                DevSetting.USB_DEBUGGING, R.id.cell_usb, R.id.fill_usb, R.id.icon_usb,
                R.id.label_usb, R.string.widget_short_usb,
            ),
            Cell(
                DevSetting.WIRELESS_DEBUGGING, R.id.cell_wireless, R.id.fill_wireless, R.id.icon_wireless,
                R.id.label_wireless, R.string.widget_short_wireless,
            ),
        )

        /** Redraws every placed widget from the current settings. Cheap, and a no-op when none is placed. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, SwitchesWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, render(context))
        }

        private fun render(context: Context): RemoteViews {
            val settings = DevSettings(context)
            val views = RemoteViews(context.packageName, R.layout.widget_switches)
            for (cell in cells) {
                if (!cell.setting.supported) {
                    views.setViewVisibility(cell.cell, View.GONE)
                    continue
                }
                val on = settings.isEnabled(cell.setting)
                // Set from here so the label follows the app's own language, not only the phone's.
                views.setTextViewText(cell.label, context.getString(cell.text))
                // The selectors in the layout colour a view by its enabled state; the cell itself
                // stays enabled so that it keeps receiving taps.
                for (id in intArrayOf(cell.fill, cell.icon, cell.label)) views.setBoolean(id, "setEnabled", on)
                views.setContentDescription(
                    cell.cell,
                    context.getString(QuickToggle.label(cell.setting)) + ", " +
                        context.getString(if (on) R.string.tile_on else R.string.tile_off),
                )
                views.setOnClickPendingIntent(cell.cell, click(context, settings, cell.setting))
            }
            return views
        }

        /** Without the permission a tap opens the app, which walks through the one-time setup. */
        private fun click(context: Context, settings: DevSettings, setting: DevSetting): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            if (!settings.hasWritePermission) {
                val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return PendingIntent.getActivity(context, 0, open, flags)
            }
            val toggle = Intent(context, WidgetToggleReceiver::class.java)
                .putExtra(WidgetToggleReceiver.EXTRA_SETTING, setting.name)
            return PendingIntent.getBroadcast(context, setting.ordinal + 1, toggle, flags)
        }
    }
}
