// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.wireless

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.annotation.StringRes
import app.devswitch.R
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address
import java.net.InetAddress

/** The two mDNS service types adbd advertises when wireless debugging is active. */
enum class AdbServiceType(val type: String, @StringRes val labelRes: Int) {
    CONNECT("_adb-tls-connect._tcp", R.string.wireless_debugging),
    PAIRING("_adb-tls-pairing._tcp", R.string.service_pairing),
}

/** One adb endpoint seen on the local network, this device's own included. */
data class DiscoveredService(
    val name: String,
    val service: AdbServiceType,
    val host: String?,
    val port: Int,
) {
    val endpoint: String? get() = host?.let { "$it:$port" }
}

/**
 * Discovers adb-over-Wi-Fi endpoints on the current network with [NsdManager]. Resolution goes
 * through the system mDNS daemon, so the app opens no socket of its own, but NsdService admits
 * only clients that hold INTERNET, which is why the manifest declares it. Should the system still
 * refuse the app, discovery reports an empty list rather than taking the whole screen down.
 *
 * resolveService cannot run two lookups at once on older releases, so found services are
 * resolved through a single queue rather than concurrently.
 */
class DeviceDiscovery(context: Context) {
    private val appContext = context.applicationContext

    /** Fetched on first use: the system service call itself throws when INTERNET is missing. */
    private val nsd: NsdManager? by lazy {
        runCatching { appContext.getSystemService(NsdManager::class.java) }.getOrNull()
    }

    fun discover(): Flow<List<DiscoveredService>> = callbackFlow {
        val manager = nsd
        if (manager == null) {
            trySend(emptyList())
            awaitClose()
            return@callbackFlow
        }
        val found = LinkedHashMap<String, DiscoveredService>()
        val queue = ArrayDeque<Pair<NsdServiceInfo, AdbServiceType>>()
        var resolving = false

        fun key(name: String, type: AdbServiceType) = "${type.type}/$name"
        fun publish() { trySend(found.values.sortedBy { it.service.ordinal }) }

        fun pump() {
            if (resolving) return
            val (info, type) = queue.removeFirstOrNull() ?: return
            resolving = true
            manager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    @Suppress("DEPRECATION") val address: InetAddress? = resolved.host
                    val host = (address as? Inet4Address)?.hostAddress ?: address?.hostAddress
                    found[key(resolved.serviceName, type)] =
                        DiscoveredService(resolved.serviceName, type, host, resolved.port)
                    resolving = false
                    publish(); pump()
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    resolving = false; pump()
                }
            })
        }

        val listeners = AdbServiceType.entries.map { type ->
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onServiceFound(info: NsdServiceInfo) {
                    queue.addLast(info to type); pump()
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    if (found.remove(key(info.serviceName, type)) != null) publish()
                }
            }
            runCatching { manager.discoverServices(type.type, NsdManager.PROTOCOL_DNS_SD, listener) }
            listener
        }

        publish()
        awaitClose { listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } } }
    }
}
