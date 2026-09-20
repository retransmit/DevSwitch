// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.devswitch.shizuku.ShizukuBridge
import app.devswitch.shizuku.ShizukuStatus
import app.devswitch.wireless.DeviceDiscovery
import app.devswitch.wireless.DiscoveredService
import app.devswitch.wireless.NetworkStatus
import app.devswitch.wireless.PairedDevice
import app.devswitch.wireless.WirelessInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import kotlin.random.Random

// ~3 minutes at a 2s interval, longer than a person needs to scan and confirm.
private const val PAIRING_POLL_ATTEMPTS = 90

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
        refreshNetwork()
        refreshPairing()
        if (discoveryJob?.isActive == true) return
        _ui.update { it.copy(scanning = true) }
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { list -> _ui.update { it.copy(devices = list) } }
        }
    }

    /** Called when the tab is hidden: stop the scan and any pairing offer left open. */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _ui.update { it.copy(scanning = false) }
        stopPairing()
    }

    /** Pairing needs the shell identity; it is offered only when Shizuku is ready. */
    fun refreshPairing() {
        val status = shizuku.status()
        _ui.update { it.copy(shizukuStatus = status, pairingAvailable = status == ShizukuStatus.Ready) }
        if (status == ShizukuStatus.Ready) loadPairedDevices()
    }

    fun requestShizukuPermission() {
        runCatching { shizuku.requestPermission() }
    }

    private fun loadPairedDevices() {
        viewModelScope.launch {
            val devices = runCatching {
                shizuku.withService { service -> service.pairedDevices().map(PairedDevice::parse) }
            }.getOrDefault(emptyList())
            _ui.update { it.copy(pairedDevices = devices) }
        }
    }

    fun startPairing() {
        val state = _ui.value
        if (state.pairingBusy || state.pairing != null || !state.pairingAvailable) return
        viewModelScope.launch {
            _ui.update { it.copy(pairingBusy = true, pairingMessage = null) }
            val code = "%06d".format(Random.nextInt(0, 1_000_000))
            val result = runCatching {
                // The pairing server only runs while wireless debugging is on. Enable it first; the
                // app can, since it holds WRITE_SECURE_SETTINGS.
                runCatching { settings.set(DevSetting.WIRELESS_DEBUGGING, true) }
                shizuku.withService { service ->
                    val name = service.deviceGuid().ifBlank { "DevSwitch-%08x".format(Random.nextInt()) }
                    service.enablePairing(name, code)
                    name
                }
            }
            result.onSuccess { name ->
                val baseline = _ui.value.pairedDevices.map { it.fingerprint }.toSet()
                _ui.update {
                    it.copy(
                        pairing = PairingSession(code, name, "WIFI:T:ADB;S:$name;P:$code;;"),
                        pairingBusy = false,
                    )
                }
                pollForPairing(baseline)
            }.onFailure { error ->
                val cause = generateSequence(error) { it.cause }.last()
                android.util.Log.e("DevSwitch", "pairing failed", error)
                _ui.update {
                    it.copy(
                        pairingBusy = false,
                        pairingMessage = "Could not start pairing: " +
                            "${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}",
                    )
                }
            }
        }
    }

    /**
     * Pairs from a QR that Android Studio shows. Studio picks the service name and password and
     * waits for a device with that name; the phone advertises under it, so Studio connects and
     * completes the pairing. Same one call as the code flow, with the scanned values.
     */
    fun pairWithScannedQr(serviceName: String, password: String) {
        val state = _ui.value
        if (state.pairingBusy || state.pairing != null || !state.pairingAvailable) return
        viewModelScope.launch {
            _ui.update { it.copy(pairingBusy = true, pairingMessage = null) }
            val result = runCatching {
                runCatching { settings.set(DevSetting.WIRELESS_DEBUGGING, true) }
                shizuku.withService { it.enablePairing(serviceName, password) }
            }
            result.onSuccess {
                val baseline = _ui.value.pairedDevices.map { it.fingerprint }.toSet()
                _ui.update {
                    it.copy(
                        pairing = PairingSession(password, serviceName, "", scanned = true),
                        pairingBusy = false,
                    )
                }
                pollForPairing(baseline)
            }.onFailure { error ->
                val cause = generateSequence(error) { it.cause }.last()
                android.util.Log.e("DevSwitch", "scanned pairing failed", error)
                _ui.update {
                    it.copy(
                        pairingBusy = false,
                        pairingMessage = "Could not pair: ${cause.javaClass.simpleName}: ${cause.message ?: "no detail"}",
                    )
                }
            }
        }
    }

    /**
     * Broadcasts can't reach the app, so a completed pairing is detected by a new fingerprint
     * appearing. It gives up after a few minutes so it never polls, or holds a pairing open, forever.
     */
    private fun pollForPairing(baseline: Set<String>) {
        pairingPollJob?.cancel()
        pairingPollJob = viewModelScope.launch {
            repeat(PAIRING_POLL_ATTEMPTS) {
                delay(2_000)
                val devices = runCatching {
                    shizuku.withService { service -> service.pairedDevices().map(PairedDevice::parse) }
                }.getOrDefault(emptyList())
                _ui.update { it.copy(pairedDevices = devices) }
                val fresh = devices.firstOrNull { it.fingerprint !in baseline }
                if (fresh != null) {
                    runCatching { shizuku.withService { it.disablePairing() } }
                    _ui.update { it.copy(pairing = null, pairingMessage = "Paired with ${fresh.label}") }
                    return@launch
                }
            }
            runCatching { shizuku.withService { it.disablePairing() } }
            _ui.update { it.copy(pairing = null, pairingMessage = "Pairing timed out. Start again to retry.") }
        }
    }

    fun stopPairing() {
        pairingPollJob?.cancel()
        pairingPollJob = null
        if (_ui.value.pairing == null) return
        viewModelScope.launch {
            runCatching { shizuku.withService { it.disablePairing() } }
            _ui.update { it.copy(pairing = null) }
        }
    }

    fun unpair(device: PairedDevice) {
        viewModelScope.launch {
            runCatching { shizuku.withService { it.unpairDevice(device.fingerprint) } }
            loadPairedDevices()
        }
    }

    fun reportPairingMessage(message: String) = _ui.update { it.copy(pairingMessage = message) }

    fun clearPairingMessage() = _ui.update { it.copy(pairingMessage = null) }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
