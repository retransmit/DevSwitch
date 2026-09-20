package app.devswitch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.devswitch.shizuku.ShizukuBridge
import app.devswitch.shizuku.ShizukuStatus
import app.devswitch.wireless.AdbManagerReflect
import app.devswitch.wireless.DeviceDiscovery
import app.devswitch.wireless.DiscoveredService
import app.devswitch.wireless.NetworkStatus
import app.devswitch.wireless.PairedDevice
import app.devswitch.wireless.WirelessInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** An active pairing offer: a QR to scan and the same code to type into `adb pair`. */
data class PairingSession(
    val code: String,
    val serviceName: String,
    val qrContent: String,
)

data class WirelessUiState(
    val network: NetworkStatus? = null,
    val devices: List<DiscoveredService> = emptyList(),
    val scanning: Boolean = false,
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
        val available = shizuku.status() == ShizukuStatus.Ready
        _ui.update { it.copy(pairingAvailable = available) }
        if (available) loadPairedDevices()
    }

    private fun loadPairedDevices() {
        viewModelScope.launch {
            val devices = withContext(Dispatchers.IO) {
                runCatching { AdbManagerReflect.pairedDevices() }.getOrDefault(emptyList())
            }
            _ui.update { it.copy(pairedDevices = devices) }
        }
    }

    fun startPairing() {
        val state = _ui.value
        if (state.pairingBusy || state.pairing != null || !state.pairingAvailable) return
        viewModelScope.launch {
            _ui.update { it.copy(pairingBusy = true, pairingMessage = null) }
            val code = "%06d".format(Random.nextInt(0, 1_000_000))
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // The pairing server only runs while wireless debugging is on. Enable it first;
                    // the app can, since it holds WRITE_SECURE_SETTINGS.
                    runCatching { settings.set(DevSetting.WIRELESS_DEBUGGING, true) }
                    val name = AdbManagerReflect.deviceGuid() ?: "DevSwitch-%08x".format(Random.nextInt())
                    AdbManagerReflect.enablePairing(name, code)
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
                _ui.update {
                    it.copy(pairingBusy = false, pairingMessage = "Could not start pairing: ${error.message}")
                }
            }
        }
    }

    /** Broadcasts can't reach the app, so success is detected by a new device appearing. */
    private fun pollForPairing(baseline: Set<String>) {
        pairingPollJob?.cancel()
        pairingPollJob = viewModelScope.launch {
            while (true) {
                delay(2_000)
                val devices = withContext(Dispatchers.IO) {
                    runCatching { AdbManagerReflect.pairedDevices() }.getOrDefault(emptyList())
                }
                _ui.update { it.copy(pairedDevices = devices) }
                val fresh = devices.firstOrNull { it.fingerprint !in baseline }
                if (fresh != null) {
                    withContext(Dispatchers.IO) { runCatching { AdbManagerReflect.disablePairing() } }
                    _ui.update { it.copy(pairing = null, pairingMessage = "Paired with ${fresh.label}") }
                    break
                }
            }
        }
    }

    fun stopPairing() {
        pairingPollJob?.cancel()
        pairingPollJob = null
        if (_ui.value.pairing == null) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { AdbManagerReflect.disablePairing() } }
            _ui.update { it.copy(pairing = null) }
        }
    }

    fun unpair(device: PairedDevice) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { AdbManagerReflect.unpair(device.fingerprint) } }
            loadPairedDevices()
        }
    }

    fun clearPairingMessage() = _ui.update { it.copy(pairingMessage = null) }
}
