package app.devswitch.wireless

/** A computer paired for wireless debugging, decoded from the shell service's string form. */
data class PairedDevice(
    val fingerprint: String,
    val host: String,
    val connected: Boolean,
) {
    /** getPairedDevices reports a fresh device's host as "user@hostname"; fall back to the fingerprint. */
    val label: String get() = host.ifBlank { fingerprint }

    companion object {
        fun parse(encoded: String): PairedDevice {
            val parts = encoded.split('\u0001')
            return PairedDevice(
                fingerprint = parts.getOrNull(0).orEmpty(),
                host = parts.getOrNull(1).orEmpty(),
                connected = parts.getOrNull(2) == "true",
            )
        }
    }
}
