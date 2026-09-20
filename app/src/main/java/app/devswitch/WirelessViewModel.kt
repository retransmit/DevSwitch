package app.devswitch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.devswitch.wireless.DeviceDiscovery
import app.devswitch.wireless.DiscoveredService
import app.devswitch.wireless.NetworkStatus
import app.devswitch.wireless.WirelessInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WirelessUiState(
    val network: NetworkStatus? = null,
    val devices: List<DiscoveredService> = emptyList(),
    val scanning: Boolean = false,
)

/** Backs the Wireless tab: the device's own address and a live scan for adb endpoints. */
class WirelessViewModel(app: Application) : AndroidViewModel(app) {
    private val info = WirelessInfo(app)
    private val discovery = DeviceDiscovery(app)

    private val _ui = MutableStateFlow(WirelessUiState())
    val ui: StateFlow<WirelessUiState> = _ui.asStateFlow()

    private var discoveryJob: Job? = null

    fun refreshNetwork() = _ui.update { it.copy(network = info.current()) }

    /** Starts the mDNS scan. Called when the Wireless tab appears; cheap to call again. */
    fun startDiscovery() {
        refreshNetwork()
        if (discoveryJob?.isActive == true) return
        _ui.update { it.copy(scanning = true) }
        discoveryJob = viewModelScope.launch {
            discovery.discover().collect { list -> _ui.update { it.copy(devices = list) } }
        }
    }

    /** Stops the scan when the tab is hidden, so it does not run in the background. */
    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _ui.update { it.copy(scanning = false) }
    }
}
