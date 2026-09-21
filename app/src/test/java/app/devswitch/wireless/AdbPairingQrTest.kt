// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbPairingQrTest {
    @Test
    fun parsesTheFormatAndroidStudioShows() {
        assertEquals("studio-abc" to "123456", parseAdbPairingQr("WIFI:T:ADB;S:studio-abc;P:123456;;"))
    }

    @Test
    fun fieldOrderDoesNotMatter() {
        assertEquals("name" to "pw", parseAdbPairingQr("WIFI:P:pw;T:ADB;S:name;;"))
    }

    @Test
    fun rejectsAWifiNetworkQr() {
        assertNull(parseAdbPairingQr("WIFI:T:WPA;S:home;P:secret;;"))
    }

    @Test
    fun rejectsAnythingThatIsNotWifiFormat() {
        assertNull(parseAdbPairingQr("https://example.com"))
        assertNull(parseAdbPairingQr(""))
    }

    @Test
    fun rejectsMissingOrBlankFields() {
        assertNull(parseAdbPairingQr("WIFI:T:ADB;S:name;;"))
        assertNull(parseAdbPairingQr("WIFI:T:ADB;P:pw;;"))
        assertNull(parseAdbPairingQr("WIFI:T:ADB;S:;P:pw;;"))
        assertNull(parseAdbPairingQr("WIFI:T:ADB;S:name;P:;;"))
    }
}
