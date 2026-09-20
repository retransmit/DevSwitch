package app.devswitch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.devswitch.WirelessViewModel
import app.devswitch.wireless.AdbServiceType
import app.devswitch.wireless.DiscoveredService
import app.devswitch.wireless.NetworkStatus

@Composable
fun WirelessTab(
    viewModel: WirelessViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    // Scan only while this tab is on screen.
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }

    val network = ui.network
    val self = ui.devices.firstOrNull {
        it.service == AdbServiceType.CONNECT && network?.owns(it.host) == true
    }
    val selfPairing = ui.devices.firstOrNull {
        it.service == AdbServiceType.PAIRING && network?.owns(it.host) == true
    }
    val others = ui.devices.filter { it != self && it != selfPairing }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThisDeviceCard(network, self, selfPairing)
        NetworkDevicesCard(others, scanning = ui.scanning)
    }
}

@Composable
private fun ThisDeviceCard(
    network: NetworkStatus?,
    connect: DiscoveredService?,
    pairing: DiscoveredService?,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("This device", style = MaterialTheme.typography.titleMedium)
            when {
                network?.connected != true ->
                    Text(
                        "Not connected to Wi-Fi. Wireless debugging needs a network.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                else -> {
                    InfoRow("IP address", network.primaryIpv4 ?: "unknown")
                    if (connect != null) {
                        InfoRow("Wireless debugging", "${connect.host}:${connect.port}")
                    } else {
                        Text(
                            "Wireless debugging is off, or its address has not been announced yet. Turn it on from the Switches tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (pairing != null) {
                        InfoRow("Pairing port", "${pairing.host}:${pairing.port}")
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkDevicesCard(devices: List<DiscoveredService>, scanning: Boolean) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "On this network",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (devices.isEmpty()) {
                Text(
                    "No other adb devices found. Devices show up here while their wireless debugging is on and they are on this Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${device.service.label}  •  ${device.endpoint ?: "resolving…"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}
