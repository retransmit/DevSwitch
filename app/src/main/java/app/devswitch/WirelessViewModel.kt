// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.devswitch.shizuku.ShizukuBridge
import app.devswitch.shizuku.ShizukuStatus
import app.devswitch.wireless.AdbServiceType
import app.devswitch.wireless.DeviceDiscovery
import app.devswitch.wireless.DiscoveredService
import app.devswitch.wireless.NetworkStatus
import app.devswitch.wireless.PairedDevice
import app.devswitch.wireless.WirelessInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.random.Random

// ~3 minutes at a 2s interval, longer than a person needs to scan and confirm.
private const val PAIRING_POLL_ATTEMPTS = 90
private const val PAIRING_POLL_MS = 2_000L

/** An active pairing offer: a QR to scan and the same code to type into `adb pair`. */
data class PairingSession(
    val code: String,
    val serviceName: String,
    val qrContent: String,
    /** True when the phone joined a pairing Android Studio started, by scanning its QR. */
    val scanned: Boolean = false,
)

data class WirelessUiState(
    val network: NetworkStatus? = null,
    val devices: List<DiscoveredService> = emptyList(),
    val scanning: Boolean = false,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NotInstalled,
    val pairingAvailable: Boolean = false,
    val pairing: PairingSession? = null,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val pairingBusy: Boolean = false,
    val pairingMessage: String? = null,
    /** The connect port as the framework reports it; 0 when unknown or off. Fills in before mDNS does. */
    val wirelessPort: Int = 0,
)

/** Backs the Wireless tab: device discovery, this device's address, and computer pairing. */
class WirelessViewModel(app: Application) : AndroidViewModel(app) {
    private val info = WirelessInfo(app)
    private val discovery = DeviceDiscovery(app)
    private val shizuku = ShizukuBridge(app)
    private val settings = DevSettings(app)

    private val _ui = MutableStateFlow(WirelessUiState())
    val ui: StateFlow<WirelessUiState> = _ui.asStateFlow()

    private var discoveryJob: Job? = null
    private var pairingPollJob: Job? = null

    /** The shell service kept bound for the whole pairing session: one process, not one per poll. */
    private var pairingHandle: ShizukuBridge.ServiceHandle? = null
    private var tabVisible = false

    // Shizuku can start, or be authorized, while this tab is open; refresh when either happens.
    private val binderListener = Shizuku.OnBinderReceivedListener { refreshPairing() }
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshPairing() }

    init {
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)
    }

    fun refreshNetwork() = _ui.update { it.copy(network = info.current()) }

    /** Called when the Wireless tab appears. */
    fun startDiscovery() {
        tabVisible = true
        refreshNetwork()
        refreshPairing()
        ensureDiscovery()
    }

    /**
     * Called when the tab is hidden. A pairing in progress is left alone: it ends only on Stop,
     * completion or timeout, so an accidental swipe cannot cancel it. Discovery keeps running while
     * a pairing is open, because completion is read from mDNS, and stops once the pairing ends.
     */
    fun stopDiscovery() {
        tabVisible = false
        if (_ui.value.pairing == null) haltDiscovery()
    }

    private fun ensureDiscovery() {
        if (discoveryJob?.isActive == true) return
        _ui.update { it.copy(scanning = true) }
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { list -> _ui.update { it.copy(devices = list) } }
        }
    }

    private fun haltDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _ui.update { it.copy(scanning = false) }
    }

    /** Pairing needs the shell identity; it is offered only when Shizuku is ready. */
    fun refreshPairing() {
        val status = shizuku.status()
        _ui.update { it.copy(shizukuStatus = status, pairingAvailable = status == ShizukuStatus.Ready) }
        // During a pairing the poll keeps the list current, so skip the extra process here.
        if (status == ShizukuStatus.Ready && _ui.value.pairing == null) loadPairedDevices()
    }

    fun requestShizukuPermission() {
        runCatching { shizuku.requestPermission() }
    }

    /** One bound service fetches both the paired list and the connect port. */
    private fun loadPairedDevices() {
        viewModelScope.launch {
            val result = runCatching {
                shizuku.withService { service ->
                    service.pairedDevices().map(PairedDevice::parse) to service.wirelessPort()
                }
            }.getOrNull() ?: return@launch
            _ui.update { it.copy(pairedDevices = result.first, wirelessPort = result.second) }
        }
    }

    fun startPairing() = beginPairing(
        serviceName = null,
        password = "%06d".format(Random.nextInt(0, 1_000_000)),
        scanned = false,
    )

    /**
     * Pairs from a QR that Android Studio shows. Studio picks the service name and password and
     * waits for a device with that name; the phone advertises under it, so Studio connects and
     * completes the pairing. Same one call as the code flow, with the scanned values.
     */
    fun pairWithScannedQr(serviceName: String, password: String) =
        beginPairing(serviceName, password, scanned = true)

    private fun beginPairing(serviceName: String?, password: String, scanned: Boolean) {
        val state = _ui.value
        if (state.pairingBusy || state.pairing != null || !state.pairingAvailable) return
        viewModelScope.launch {
            _ui.update { it.copy(pairingBusy = true, pairingMessage = null) }
            val result = runCatching {
                val handle = shizuku.openService()
                val name = try {
                    withContext(Dispatchers.IO) {
                        ensureWirelessDebuggingOn(handle.service)
                        val name = serviceName
                            ?: handle.service.deviceGuid().ifBlank { "DevSwitch-%08x".format(Random.nextInt()) }
                        handle.service.enablePairing(name, password)
                        name
                    }
                } catch (e: Throwable) {
                    handle.close()
                    throw e
                }
                pairingHandle = handle
                name
            }
            result.onSuccess { name ->
                val baseline = _ui.value.pairedDevices.map { it.fingerprint }.toSet()
                val qr = if (scanned) "" else "WIFI:T:ADB;S:$name;P:$password;;"
                _ui.update {
                    it.copy(pairing = PairingSession(password, name, qr, scanned), pairingBusy = false)
                }
                ensureDiscovery()
                pollForPairing(baseline)
            }.onFailure { error ->
                Log.e("DevSwitch", "pairing failed", error)
                _ui.update { it.copy(pairingBusy = false, pairingMessage = friendlyPairingError(error)) }
            }
        }
    }

    /**
     * The pairing server only runs while wireless debugging is on. The app turns it on when it
     * holds WRITE_SECURE_SETTINGS; failing that, the shell service can, since shell may write it.
     */
    private fun ensureWirelessDebuggingOn(service: IShellService) {
        if (settings.isEnabled(DevSetting.WIRELESS_DEBUGGING)) return
        if (runCatching { settings.set(DevSetting.WIRELESS_DEBUGGING, true) }.isFailure) {
            service.execute(arrayOf("settings", "put", "global", DevSetting.WIRELESS_DEBUGGING.key, "1"))
        }
    }

    /**
     * Result broadcasts can't reach the app, so completion is watched two ways. A new fingerprint
     * in the paired list means a new computer paired. And adbd advertises the pairing service
     * only while the offer is open, dropping it the moment pairing finishes, so its disappearance
     * means done even when the computer was already known and no fingerprint changes. It gives up
     * after a few minutes so it never polls, or holds a pairing open, forever.
     */
    private fun pollForPairing(baseline: Set<String>) {
        pairingPollJob?.cancel()
        pairingPollJob = viewModelScope.launch {
            var advertised = false
            repeat(PAIRING_POLL_ATTEMPTS) {
                delay(PAIRING_POLL_MS)
                val handle = pairingHandle ?: return@launch
                val devices = runCatching {
                    withContext(Dispatchers.IO) { handle.service.pairedDevices().map(PairedDevice::parse) }
                }.getOrNull()
                if (devices == null) {
                    endPairing("Lost contact with Shizuku, so pairing stopped.")
                    return@launch
                }
                _ui.update { it.copy(pairedDevices = devices) }
                val fresh = devices.firstOrNull { it.fingerprint !in baseline }
                if (fresh != null) {
                    endPairing("Paired with ${fresh.label}")
                    return@launch
                }
                if (selfPairingAdvertised()) {
                    advertised = true
                } else if (advertised) {
                    endPairing("Pairing finished.")
                    return@launch
                }
            }
            endPairing("Pairing timed out. Start again to retry.")
        }
    }

    private fun selfPairingAdvertised(): Boolean {
        val state = _ui.value
        val network = state.network ?: return false
        return state.devices.any { it.service == AdbServiceType.PAIRING && network.owns(it.host) }
    }

    /** Closes the pairing offer and releases the bound service. */
    private suspend fun endPairing(message: String?) {
        val handle = pairingHandle
        pairingHandle = null
        if (handle != null) {
            runCatching { withContext(Dispatchers.IO) { handle.service.disablePairing() } }
            handle.close()
        }
        _ui.update { it.copy(pairing = null, pairingMessage = message ?: it.pairingMessage) }
        if (!tabVisible) haltDiscovery()
    }

    fun stopPairing() {
        pairingPollJob?.cancel()
        pairingPollJob = null
        if (_ui.value.pairing == null && pairingHandle == null) return
        viewModelScope.launch { endPairing(message = null) }
    }

    fun unpair(device: PairedDevice) {
        viewModelScope.launch {
            runCatching { shizuku.withService { it.unpairDevice(device.fingerprint) } }
            loadPairedDevices()
        }
    }

    fun reportPairingMessage(message: String) = _ui.update { it.copy(pairingMessage = message) }

    fun clearPairingMessage() = _ui.update { it.copy(pairingMessage = null) }

    /** The full cause goes to the log; the screen gets a plain sentence with the likely reason. */
    private fun friendlyPairingError(error: Throwable): String {
        val text = generateSequence(error) { it.cause }.joinToString(" ") { it.message.orEmpty() }
        val reason = when {
            error is TimeoutCancellationException -> "Shizuku took too long to respond."
            "did not respond" in text -> "Shizuku's service did not respond. Is Shizuku still running?"
            "unavailable" in text -> "The adb service is not reachable on this device."
            "SecurityException" in text -> "Not permitted. Check that Shizuku access is still allowed."
            else -> "See the log for details."
        }
        return "Could not start pairing. $reason"
    }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        pairingPollJob?.cancel()
        // viewModelScope is gone by now, so close an open pairing on an independent scope, best effort,
        // rather than leave the phone accepting pairings after the app is killed.
        pairingHandle?.let { handle ->
            pairingHandle = null
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { handle.service.disablePairing() }
                handle.close()
            }
        }
    }
}
