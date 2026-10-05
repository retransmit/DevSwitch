// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelfEndpointTest {
    private val network = NetworkStatus(listOf("192.168.0.211"), onWifi = true, onEthernet = false)
    private val stale = DiscoveredService("adb-phone", AdbServiceType.CONNECT, "192.168.0.211", 46539)
    private val fresh = DiscoveredService("adb-phone (2)", AdbServiceType.CONNECT, "192.168.0.211", 38903)
    private val other = DiscoveredService("adb-tablet", AdbServiceType.CONNECT, "192.168.0.50", 40000)

    @Test
    fun `the framework port wins over a lingering announcement`() {
        assertEquals(fresh, pickSelfConnect(listOf(stale, fresh, other), network, wirelessOn = true, frameworkPort = 38903))
    }

    @Test
    fun `without the framework port the newest announcement is used`() {
        assertEquals(fresh, pickSelfConnect(listOf(stale, fresh, other), network, wirelessOn = true, frameworkPort = 0))
    }

    @Test
    fun `a stale announcement is ignored while wireless debugging is off`() {
        assertNull(pickSelfConnect(listOf(stale), network, wirelessOn = false, frameworkPort = 0))
    }

    @Test
    fun `no match for the framework port yields nothing rather than a wrong address`() {
        assertNull(pickSelfConnect(listOf(stale, other), network, wirelessOn = true, frameworkPort = 38903))
    }

    @Test
    fun `another device on the network is never taken for this one`() {
        assertNull(pickSelfConnect(listOf(other), network, wirelessOn = true, frameworkPort = 0))
    }
}
