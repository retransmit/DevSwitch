// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

/**
 * Parses an ADB pairing QR, "WIFI:T:ADB;S:<serviceName>;P:<password>;;", into (serviceName,
 * password). Returns null for anything else, including a Wi-Fi network QR with a different type.
 */
fun parseAdbPairingQr(text: String): Pair<String, String>? {
    if (!text.startsWith("WIFI:")) return null
    var type: String? = null
    var service: String? = null
    var password: String? = null
    for (field in text.removePrefix("WIFI:").split(";")) {
        when {
            field.startsWith("T:") -> type = field.removePrefix("T:")
            field.startsWith("S:") -> service = field.removePrefix("S:")
            field.startsWith("P:") -> password = field.removePrefix("P:")
        }
    }
    if (type != "ADB" || service.isNullOrBlank() || password.isNullOrBlank()) return null
    return service to password
}
