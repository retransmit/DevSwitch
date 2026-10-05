// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch

import android.app.Application
import android.content.res.Configuration
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import app.devswitch.quick.SwitchesWidget

/**
 * Keeps the home screen widget in step with the settings for as long as the process lives, so a
 * change made in the app, from a tile or in Android's own Settings shows up on the widget. When
 * the process is gone the widget catches up at its next periodic update or the next tap.
 */
class DevSwitchApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = SwitchesWidget.updateAll(this@DevSwitchApp)
        }
        DevSetting.entries.filter { it.supported }.forEach {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(it.key), false, observer)
        }
        // The permission may have been granted since the widget was last drawn.
        SwitchesWidget.updateAll(this)
    }

    /** A new language or theme: the widget's text is drawn here, so draw it again. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        SwitchesWidget.updateAll(this)
    }
}
