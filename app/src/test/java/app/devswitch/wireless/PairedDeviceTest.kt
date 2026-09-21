// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedDeviceTest {
    @Test
    fun decodesTheShellServiceFormat() {
        val device = PairedDevice.parse("AA:BB\u0001user@host\u0001true")
        assertEquals("AA:BB", device.fingerprint)
        assertEquals("user@host", device.host)
        assertTrue(device.connected)
        assertEquals("user@host", device.label)
    }

    @Test
    fun labelFallsBackToTheFingerprintWhenTheHostIsBlank() {
        val device = PairedDevice.parse("AA:BB\u0001\u0001false")
        assertEquals("AA:BB", device.label)
        assertFalse(device.connected)
    }

    @Test
    fun toleratesMissingFields() {
        val device = PairedDevice.parse("onlyfp")
        assertEquals("onlyfp", device.fingerprint)
        assertEquals("", device.host)
        assertFalse(device.connected)
    }

    @Test
    fun connectedOnlyWhenExactlyTrue() {
        assertFalse(PairedDevice.parse("fp\u0001h\u0001TRUE").connected)
        assertFalse(PairedDevice.parse("fp\u0001h\u0001yes").connected)
        assertTrue(PairedDevice.parse("fp\u0001h\u0001true").connected)
    }
}
