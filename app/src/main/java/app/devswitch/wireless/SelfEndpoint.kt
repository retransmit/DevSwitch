// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

/**
 * Picks the wireless debugging address of this device out of what mDNS found.
 *
 * Switching wireless debugging off and on makes Android choose a new port, and the announcement
 * for the old one can linger for minutes, so several entries may claim to be this device. The port
 * the framework reports ([frameworkPort], known only when Shizuku can ask) settles it. Without
 * that, the entry discovered last is the best guess, since a fresh announcement arrives after the
 * stale one.
 *
 * Returns null when wireless debugging is off or nothing matches.
 */
fun pickSelfConnect(
    devices: List<DiscoveredService>,
    network: NetworkStatus?,
    wirelessOn: Boolean,
    frameworkPort: Int,
): DiscoveredService? {
    if (!wirelessOn || network == null) return null
    val mine = devices.filter { it.service == AdbServiceType.CONNECT && network.owns(it.host) }
    return if (frameworkPort > 0) mine.lastOrNull { it.port == frameworkPort } else mine.lastOrNull()
}
