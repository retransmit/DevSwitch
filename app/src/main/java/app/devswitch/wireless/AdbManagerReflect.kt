package app.devswitch.wireless

import android.os.Build
import android.os.IBinder
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Array as ReflectArray

/** A computer paired for wireless debugging, from IAdbManager.getPairedDevices(). */
data class PairedDevice(
    val fingerprint: String,
    val host: String,
    val connected: Boolean,
) {
    /** getPairedDevices reports a fresh device's host as "user@hostname"; fall back to the fingerprint. */
    val label: String get() = host.ifBlank { fingerprint }
}

/**
 * Drives wireless-debugging pairing through the framework's hidden `android.debug.IAdbManager`.
 *
 * Those calls require MANAGE_DEBUGGING, which only the shell and system identities hold, so every
 * call is routed through the Shizuku binder (shell uid); the check is on the caller, so this
 * succeeds without root. The proxy is built by reflecting on the device's own IAdbManager$Stub,
 * so transaction codes always match the running platform even though the method set shifted at
 * API 33.
 *
 * Pairing results are delivered by broadcasts that need MANAGE_DEBUGGING to *receive*, which the
 * app's own uid lacks, so there is no way to observe them here. Callers poll [pairedDevices] and
 * [wirelessPort] instead.
 */
object AdbManagerReflect {
    @Volatile private var exempted = false

    private fun ensureExempt() {
        if (exempted) return
        // Reflection on android.debug.* is blocked as non-SDK interface access on API 28+.
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        exempted = true
    }

    private fun manager(): Any {
        ensureExempt()
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("adb"))
        val stub = Class.forName("android.debug.IAdbManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("The adb system service is unavailable")
    }

    /** The device's wireless-debugging identity, used as the QR service name. May be blank if unset. */
    fun deviceGuid(): String? = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java)
            .invoke(null, "persist.adb.wifi.guid") as? String
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Starts a pairing session advertised as [serviceName], secured by [password]. A computer pairs
     * either by scanning a QR carrying the same name and password, or by running
     * `adb pair <host:port>` and typing the password: both complete the same SPAKE2 exchange.
     */
    fun enablePairing(serviceName: String, password: String) {
        val m = manager()
        m.javaClass.getMethod("enablePairingByQrCode", String::class.java, String::class.java)
            .invoke(m, serviceName, password)
    }

    fun disablePairing() {
        val m = manager()
        runCatching { m.javaClass.getMethod("disablePairing").invoke(m) }
    }

    /** The wireless-debugging connect port, or 0 when wireless debugging is off. */
    fun wirelessPort(): Int = runCatching {
        val m = manager()
        m.javaClass.getMethod("getAdbWirelessPort").invoke(m) as? Int
    }.getOrNull() ?: 0

    fun unpair(fingerprint: String) {
        val m = manager()
        runCatching { m.javaClass.getMethod("unpairDevice", String::class.java).invoke(m, fingerprint) }
    }

    fun pairedDevices(): List<PairedDevice> {
        val result = manager().let { m ->
            m.javaClass.getMethod("getPairedDevices").invoke(m)
        } ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            parseArray(result)
        } else {
            parseMap(result)
        }
    }

    // API 30-32: HashMap<fingerprint, PairDevice>, fields read through getters.
    private fun parseMap(result: Any): List<PairedDevice> {
        val map = result as? Map<*, *> ?: return emptyList()
        return map.entries.mapNotNull { (key, device) ->
            device ?: return@mapNotNull null
            PairedDevice(
                fingerprint = key as? String ?: getString(device, "getGuid").orEmpty(),
                host = getString(device, "getGuid").orEmpty().ifBlank { getString(device, "getDeviceName").orEmpty() },
                connected = getBoolean(device, "isConnected"),
            )
        }
    }

    // API 33+: FingerprintAndPairDevice[] with public fields; PairDevice exposes public name/guid/connected.
    private fun parseArray(result: Any): List<PairedDevice> {
        val length = runCatching { ReflectArray.getLength(result) }.getOrNull() ?: return emptyList()
        val devices = ArrayList<PairedDevice>(length)
        for (index in 0 until length) {
            val entry = ReflectArray.get(result, index) ?: continue
            val device = getField(entry, "device") ?: continue
            val fingerprint = getField(entry, "keyFingerprint") as? String ?: ""
            devices.add(
                PairedDevice(
                    fingerprint = fingerprint,
                    host = (getField(device, "guid") as? String).orEmpty(),
                    connected = (getField(device, "connected") as? Boolean) == true,
                ),
            )
        }
        return devices
    }

    private fun getString(target: Any, getter: String): String? =
        runCatching { target.javaClass.getMethod(getter).invoke(target) as? String }.getOrNull()

    private fun getBoolean(target: Any, getter: String): Boolean =
        runCatching { target.javaClass.getMethod(getter).invoke(target) as? Boolean }.getOrNull() == true

    private fun getField(target: Any, field: String): Any? =
        runCatching { target.javaClass.getField(field).get(target) }.getOrNull()
}
