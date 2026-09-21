// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.devswitch.PairingNote
import app.devswitch.PairingSession
import app.devswitch.R
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
    // Some builds drop wireless debugging the moment the screen sleeps, which would cut a pairing short.
    KeepScreenOn(ui.pairing != null)

    var scanning by remember { mutableStateOf(false) }
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) scanning = true else viewModel.reportPairingMessage(R.string.msg_camera_permission)
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
                onResult = { name, password ->
                    scanning = false
                    viewModel.pairWithScannedQr(name, password)
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
    // adb can advertise several connect services at once; anything on the phone's own addresses is this device.
    val others = ui.devices.filter { network?.owns(it.host) != true }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThisDeviceCard(network, self, selfPairing, fallbackPort = ui.wirelessPort)
        PairingCard(ui, viewModel, pairingEndpoint = selfPairing?.endpoint, onScan = ::requestScan)
        NetworkDevicesCard(others, scanning = ui.scanning)
    }
}

@Composable
private fun ThisDeviceCard(
    network: NetworkStatus?,
    connect: DiscoveredService?,
    pairing: DiscoveredService?,
    fallbackPort: Int,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.wireless_this_device), style = MaterialTheme.typography.titleMedium)
            if (network?.connected != true) {
                Text(stringResource(R.string.wireless_no_wifi), style = MaterialTheme.typography.bodyMedium)
            } else {
                val ip = network.primaryIpv4
                InfoRow(stringResource(R.string.wireless_ip), ip ?: stringResource(R.string.wireless_unknown))
                when {
                    connect != null ->
                        InfoRow(stringResource(R.string.wireless_debugging), "${connect.host}:${connect.port}")
                    // The framework's own port answers before mDNS resolves, when Shizuku can ask it.
                    fallbackPort > 0 && ip != null ->
                        InfoRow(stringResource(R.string.wireless_debugging), "$ip:$fallbackPort")
                    else -> Text(
                        stringResource(R.string.wireless_off_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pairing != null) {
                    InfoRow(stringResource(R.string.wireless_pairing_port), "${pairing.host}:${pairing.port}")
                }
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
    var pendingUnpair by remember { mutableStateOf<PairedDevice?>(null) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pair_title), style = MaterialTheme.typography.titleMedium)

            when {
                !ui.pairingAvailable -> when (ui.shizukuStatus) {
                    ShizukuStatus.PermissionNeeded -> {
                        Text(
                            stringResource(R.string.pair_shizuku_permission),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { viewModel.requestShizukuPermission() }) {
                            Text(stringResource(R.string.pair_grant_shizuku))
                        }
                    }

                    ShizukuStatus.NotRunning -> {
                        Text(
                            stringResource(R.string.pair_shizuku_not_running),
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
                            }) { Text(stringResource(R.string.pair_open_shizuku)) }
                            OutlinedButton(onClick = { openDeveloperOptions(context) }) {
                                Text(stringResource(R.string.pair_android_settings))
                            }
                        }
                    }

                    else -> {
                        Text(
                            stringResource(R.string.pair_needs_shizuku),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedButton(onClick = { openDeveloperOptions(context) }) {
                            Text(stringResource(R.string.pair_open_dev_options))
                        }
                    }
                }

                ui.pairing != null -> PairingActive(ui.pairing!!, pairingEndpoint) { viewModel.stopPairing() }

                else -> {
                    Text(
                        stringResource(R.string.pair_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !ui.pairingBusy, onClick = { viewModel.startPairing() }) {
                            if (ui.pairingBusy) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.pair_with_code))
                            }
                        }
                        OutlinedButton(enabled = !ui.pairingBusy, onClick = onScan) {
                            Text(stringResource(R.string.pair_scan_studio_qr))
                        }
                    }
                }
            }

            ui.pairingMessage?.let { note ->
                Text(
                    noteText(note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            if (ui.pairedDevices.isNotEmpty()) {
                HorizontalDivider()
                Text(stringResource(R.string.pair_paired_computers), style = MaterialTheme.typography.labelLarge)
                ui.pairedDevices.forEach { device ->
                    PairedDeviceRow(device) { pendingUnpair = device }
                }
            }
        }
    }
    // Unpairing the computer you are connected through drops that connection, so ask first.
    pendingUnpair?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingUnpair = null },
            title = { Text(stringResource(R.string.pair_unpair_title, device.label)) },
            text = { Text(stringResource(R.string.pair_unpair_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.unpair(device); pendingUnpair = null }) {
                    Text(stringResource(R.string.pair_unpair))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnpair = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Resolves a view-model message, which carries a resource id so the wording lives in resources. */
@Composable
private fun noteText(note: PairingNote): String =
    if (note.arg != null) stringResource(note.id, note.arg) else stringResource(note.id)

@Composable
private fun PairingActive(session: PairingSession, endpoint: String?, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (session.scanned) {
            Text(stringResource(R.string.pair_scanned_finishing), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                stringResource(R.string.pair_code_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.pair_adb_pair_cmd, endpoint ?: stringResource(R.string.pair_port_placeholder)),
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            InfoRow(stringResource(R.string.pair_code_label), session.code)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(if (session.scanned) R.string.pair_waiting_studio else R.string.pair_waiting_computer),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onStop) { Text(stringResource(R.string.pair_stop)) }
    }
}

@Composable
private fun PairedDeviceRow(device: PairedDevice, onUnpair: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(if (device.connected) R.string.pair_state_connected else R.string.pair_state_paired),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onUnpair) { Text(stringResource(R.string.pair_unpair)) }
    }
}

@Composable
private fun NetworkDevicesCard(devices: List<DiscoveredService>, scanning: Boolean) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.network_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            if (devices.isEmpty()) {
                Text(
                    stringResource(R.string.network_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                        val label = stringResource(device.service.labelRes)
                        val endpoint = device.endpoint ?: stringResource(R.string.network_resolving)
                        Text(
                            "$label  •  $endpoint",
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
