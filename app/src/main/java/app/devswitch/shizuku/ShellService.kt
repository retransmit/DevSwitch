package app.devswitch.shizuku

import android.content.Context
import android.os.Build
import android.os.IBinder
import androidx.annotation.Keep
import app.devswitch.IShellService
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import kotlin.system.exitProcess
import java.lang.reflect.Array as ReflectArray

/**
 * Runs in a process that Shizuku spawns with the shell uid (2000) and shell SELinux context, the
 * same identity `adb shell` has. Shizuku loads it from this APK by class name, so the class is kept
 * by name in release builds and offers both constructors Shizuku may call.
 *
 * The wireless-debugging methods reflect on the framework's hidden `android.debug.IAdbManager`.
 * This must happen here rather than in the app: the app's own SELinux context is denied even the
 * lookup of the "adb" service, whereas this shell context can look it up and holds MANAGE_DEBUGGING.
 */
@Keep
class ShellService : IShellService.Stub {
    constructor() : super() {
        exemptHiddenApi()
    }

    /** Shizuku v13 and newer pass a Context. */
    @Suppress("unused")
    constructor(context: Context) : super() {
        exemptHiddenApi()
    }

    private fun exemptHiddenApi() {
        // The Shizuku process may already permit hidden-API access; do it anyway, defensively.
        runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun execute(command: Array<String>): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) {
            "${command.first()} exited with code $exit" + if (output.isBlank()) "" else ": ${output.trim()}"
        }
        return output
    }

    override fun deviceGuid(): String = runCatching {
        val systemProperties = Class.forName("android.os.SystemProperties")
        systemProperties.getMethod("get", String::class.java)
            .invoke(null, "persist.adb.wifi.guid") as? String
    }.getOrNull().orEmpty()

    override fun enablePairing(serviceName: String, password: String) {
        binderSafe {
            val manager = adbManager()
            manager.javaClass.getMethod("enablePairingByQrCode", String::class.java, String::class.java)
                .invoke(manager, serviceName, password)
        }
    }

    override fun disablePairing() {
        binderSafe {
            val manager = adbManager()
            manager.javaClass.getMethod("disablePairing").invoke(manager)
        }
    }

    override fun wirelessPort(): Int = binderSafe {
        val manager = adbManager()
        manager.javaClass.getMethod("getAdbWirelessPort").invoke(manager) as? Int ?: 0
    }

    override fun unpairDevice(fingerprint: String) {
        binderSafe {
            val manager = adbManager()
            manager.javaClass.getMethod("unpairDevice", String::class.java).invoke(manager, fingerprint)
        }
    }

    override fun pairedDevices(): Array<String> = binderSafe {
        val manager = adbManager()
        val result = manager.javaClass.getMethod("getPairedDevices").invoke(manager)
            ?: return@binderSafe emptyArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) parseArray(result) else parseMap(result)
    }

    private fun adbManager(): Any {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val binder = serviceManager.getMethod("getService", String::class.java).invoke(null, "adb") as? IBinder
            ?: error("The adb system service is unavailable")
        val stub = Class.forName("android.debug.IAdbManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: error("IAdbManager is unavailable")
    }

    // API 30-32: HashMap<fingerprint, PairDevice>, fields read through getters.
    private fun parseMap(result: Any): Array<String> {
        val map = result as? Map<*, *> ?: return emptyArray()
        return map.entries.mapNotNull { (key, device) ->
            device ?: return@mapNotNull null
            val guid = getString(device, "getGuid").orEmpty()
            val name = getString(device, "getDeviceName").orEmpty()
            val fingerprint = (key as? String) ?: guid
            encode(fingerprint, guid.ifBlank { name }, getBoolean(device, "isConnected"))
        }.toTypedArray()
    }

    // API 33+: FingerprintAndPairDevice[] with public fields; PairDevice exposes public fields.
    private fun parseArray(result: Any): Array<String> {
        val length = runCatching { ReflectArray.getLength(result) }.getOrNull() ?: return emptyArray()
        val out = ArrayList<String>(length)
        for (index in 0 until length) {
            val entry = ReflectArray.get(result, index) ?: continue
            val device = getField(entry, "device") ?: continue
            val fingerprint = getField(entry, "keyFingerprint") as? String ?: ""
            val host = (getField(device, "guid") as? String).orEmpty()
            val connected = (getField(device, "connected") as? Boolean) == true
            out.add(encode(fingerprint, host, connected))
        }
        return out.toTypedArray()
    }

    private fun encode(fingerprint: String, host: String, connected: Boolean) =
        "$fingerprint\u0001$host\u0001$connected"

    private fun getString(target: Any, getter: String): String? =
        runCatching { target.javaClass.getMethod(getter).invoke(target) as? String }.getOrNull()

    private fun getBoolean(target: Any, getter: String): Boolean =
        runCatching { target.javaClass.getMethod(getter).invoke(target) as? Boolean }.getOrNull() == true

    private fun getField(target: Any, field: String): Any? =
        runCatching { target.javaClass.getField(field).get(target) }.getOrNull()

    /** Reflection wraps failures in InvocationTargetException, which Binder cannot carry. Convert to
     *  an IllegalStateException with a readable message, which it can. */
    private inline fun <T> binderSafe(block: () -> T): T = try {
        block()
    } catch (e: Throwable) {
        val cause = (e as? InvocationTargetException)?.cause ?: e
        throw IllegalStateException("${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}")
    }
}
