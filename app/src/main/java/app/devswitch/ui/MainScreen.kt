package app.devswitch.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.devswitch.DevSetting
import app.devswitch.MainViewModel
import app.devswitch.R
import app.devswitch.UiState
import app.devswitch.shizuku.ShizukuBridge
import app.devswitch.shizuku.ShizukuStatus
import kotlinx.coroutines.delay

@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // Coming back from a terminal, Shizuku or a root prompt is when the permission state changes.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    // Nothing broadcasts "a permission was granted to you", and the grant usually happens while
    // this screen is open (adb from a computer, Shizuku, a root prompt). Poll it, cheaply, only
    // while it is missing and only while the screen is in the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, ui.hasPermission) {
        if (ui.hasPermission) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(1_000)
                viewModel.refresh()
            }
        }
    }
    LaunchedEffect(ui.message) {
        val message = ui.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.hasPermission) ReadyCard() else SetupCard(ui, viewModel)

            ToggleCard(
                title = stringResource(R.string.developer_options),
                subtitle = stringResource(R.string.developer_options_hint),
                checked = ui.state.developerOptions,
                enabled = ui.hasPermission,
                onCheckedChange = { viewModel.toggle(DevSetting.DEVELOPER_OPTIONS, it) },
            )
            ToggleCard(
                title = stringResource(R.string.usb_debugging),
                subtitle = stringResource(R.string.usb_debugging_hint),
                checked = ui.state.usbDebugging,
                enabled = ui.hasPermission,
                onCheckedChange = { viewModel.toggle(DevSetting.USB_DEBUGGING, it) },
            )
            val wirelessSupported = DevSetting.WIRELESS_DEBUGGING.supported
            ToggleCard(
                title = stringResource(R.string.wireless_debugging),
                subtitle = stringResource(
                    if (wirelessSupported) R.string.wireless_debugging_hint else R.string.wireless_debugging_unsupported,
                ),
                checked = ui.state.wirelessDebugging,
                enabled = ui.hasPermission && wirelessSupported,
                onCheckedChange = { viewModel.toggle(DevSetting.WIRELESS_DEBUGGING, it) },
            )
        }
    }
}

@Composable
private fun ReadyCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ready_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.ready_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SetupCard(ui: UiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val command = MainViewModel.adbCommand(context.packageName)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium)

            Text(stringResource(R.string.setup_adb_step), style = MaterialTheme.typography.labelLarge)
            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(
                onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("adb command", command))
                },
            ) { Text(stringResource(R.string.copy_command)) }

            Text(stringResource(R.string.setup_shizuku_step), style = MaterialTheme.typography.labelLarge)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    enabled = !ui.busy,
                    onClick = {
                        when (ui.shizuku) {
                            ShizukuStatus.NotInstalled ->
                                context.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")))
                            ShizukuStatus.NotRunning ->
                                context.open(context.packageManager.getLaunchIntentForPackage(ShizukuBridge.SHIZUKU_PACKAGE))
                            ShizukuStatus.PermissionNeeded -> viewModel.requestShizukuPermission()
                            ShizukuStatus.Ready -> viewModel.grantWithShizuku()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            when (ui.shizuku) {
                                ShizukuStatus.NotInstalled -> R.string.shizuku_get
                                ShizukuStatus.NotRunning -> R.string.shizuku_start
                                ShizukuStatus.PermissionNeeded -> R.string.shizuku_allow
                                ShizukuStatus.Ready -> R.string.shizuku_grant
                            },
                        ),
                    )
                }
                OutlinedButton(enabled = !ui.busy, onClick = viewModel::grantWithRoot) {
                    Text(stringResource(R.string.root_grant))
                }
                if (ui.busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

private fun Context.open(intent: Intent?) {
    if (intent == null) return
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
