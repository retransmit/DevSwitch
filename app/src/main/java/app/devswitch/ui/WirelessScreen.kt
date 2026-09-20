package app.devswitch.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.devswitch.PairingSession
import app.devswitch.WirelessUiState
import app.devswitch.WirelessViewModel
import app.devswitch.shizuku.ShizukuBridge
import app.devswitch.shizuku.ShizukuStatus
import app.devswitch.wireless.AdbServiceType
import app.devswitch.wireless.DiscoveredService
import app.devswitch.wireless.NetworkStatus
import app.devswitch.wireless.PairedDevice

@Composable
fun WirelessTab(
    viewModel: WirelessViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Scan and offer pairing only while this tab is on screen.
    DisposableEffect(Unit) {
        viewModel.startDiscovery()
        onDispose { viewModel.stopDiscovery() }
    }
    LaunchedEffect(ui.pairingMessage) {
        if (ui.pairingMessage != null) {
            kotlinx.coroutines.delay(5_000)
            viewModel.clearPairingMessage()
        }
    }

    var scanning by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanning = true else viewModel.reportPairingMessage("Camera permission is needed to scan the QR.")
    }
    fun requestScan() {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) scanning = true else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    if (scanning) {
        Dialog(
            onDismissRequest = { scanning = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            QrScannerOverlay(
                onResult = { text ->
                    scanning = false
                    val parsed = parseAdbPairingQr(text)
                    if (parsed != null) {
                        viewModel.pairWithScannedQr(parsed.first, parsed.second)
                    } else {
                        viewModel.reportPairingMessage("That QR is not an Android wireless-debugging code.")
                    }
                },
                onClose = { scanning = false },
            )
        }
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
        PairingCard(ui, viewModel, pairingEndpoint = selfPairing?.endpoint, onScan = ::requestScan)
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
            if (network?.connected != true) {
                Text(
                    "Not connected to Wi-Fi. Wireless debugging needs a network.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
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
                if (pairing != null) InfoRow("Pairing port", "${pairing.host}:${pairing.port}")
            }
        }
    }
}

@Composable
private fun PairingCard(
    ui: WirelessUiState,
    viewModel: WirelessViewModel,
    pairingEndpoint: String?,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pair a computer", style = MaterialTheme.typography.titleMedium)

            when {
                !ui.pairingAvailable -> when (ui.shizukuStatus) {
                    ShizukuStatus.PermissionNeeded -> {
                        Text(
                            "Shizuku is running. Allow DevSwitch to use it, then pairing and the paired-computers list work here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.requestShizukuPermission() }) {
                            Text("Grant Shizuku access")
                        }
                    }

                    ShizukuStatus.NotRunning -> {
                        Text(
                            "Shizuku is installed but not started. Start it, then reopen this tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                runCatching {
                                    context.packageManager
                                        .getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE)
                                        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }) { Text("Open Shizuku") }
                            OutlinedButton(onClick = { openDeveloperOptions(context) }) {
                                Text("Android settings")
                            }
                        }
                    }

                    else -> {
                        Text(
                            "Generating a pairing code needs the shell identity, which DevSwitch gets through " +
                                "Shizuku, the same way it grants its own permission. Set up Shizuku on the " +
                                "Switches tab, or pair from Android's own screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { openDeveloperOptions(context) }) {
                            Text("Open Developer options")
                        }
                    }
                }

                ui.pairing != null -> PairingActive(ui.pairing!!, pairingEndpoint) { viewModel.stopPairing() }

                else -> {
                    Text(
                        "Pair a computer over Wi-Fi. Show a code to type into `adb pair`, or scan the QR " +
                            "that Android Studio shows.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !ui.pairingBusy, onClick = { viewModel.startPairing() }) {
                            if (ui.pairingBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Pair with code")
                            }
                        }
                        OutlinedButton(enabled = !ui.pairingBusy, onClick = onScan) {
                            Text("Scan Studio QR")
                        }
                    }
                }
            }

            ui.pairingMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (ui.pairedDevices.isNotEmpty()) {
                HorizontalDivider()
                Text("Paired computers", style = MaterialTheme.typography.labelLarge)
                ui.pairedDevices.forEach { device ->
                    PairedDeviceRow(device) { viewModel.unpair(device) }
                }
            }
        }
    }
}

@Composable
private fun PairingActive(session: PairingSession, endpoint: String?, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (session.scanned) {
            Text(
                "Scanned Android Studio's code. Finishing the pairing on this device.",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                "On the computer run this and enter the code. In Android Studio, use Pair using pairing " +
                    "code, choose this device, and enter it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "adb pair ${endpoint ?: "<pairing port appears here>"}",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            InfoRow("Pairing code", session.code)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                if (session.scanned) "Waiting for Android Studio…" else "Waiting for a computer to pair…",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onStop) { Text("Stop") }
    }
}

@Composable
private fun PairedDeviceRow(device: PairedDevice, onUnpair: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (device.connected) "Connected" else "Paired",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnpair) { Text("Unpair") }
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
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

private fun openDeveloperOptions(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
