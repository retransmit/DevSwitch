// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkStatusTest {
    private fun status(vararg ips: String) = NetworkStatus(ips.toList(), onWifi = true, onEthernet = false)

    @Test
    fun prefersThePrivateLanAddressOverAVpnTunnel() {
        // The real case that bit us: a VPN owned the default route with a CGNAT address.
        assertEquals("192.168.0.192", status("100.80.0.4", "192.168.0.192").primaryIpv4)
    }

    @Test
    fun treatsEveryPrivateRangeAsLan() {
        assertEquals("10.0.0.5", status("100.64.1.1", "10.0.0.5").primaryIpv4)
        assertEquals("172.20.0.1", status("172.32.0.1", "172.20.0.1").primaryIpv4)
    }

    @Test
    fun fallsBackToTheFirstAddressWhenNoneIsPrivate() {
        assertEquals("100.80.0.4", status("100.80.0.4", "8.8.8.8").primaryIpv4)
    }

    @Test
    fun noAddressesMeansNotConnected() {
        val none = status()
        assertNull(none.primaryIpv4)
        assertFalse(none.connected)
    }

    @Test
    fun ownsOnlyItsOwnAddresses() {
        val me = status("192.168.0.192", "100.80.0.4")
        assertTrue(me.owns("192.168.0.192"))
        assertTrue(me.owns("100.80.0.4"))
        assertFalse(me.owns("192.168.0.50"))
        assertFalse(me.owns(null))
    }
}
