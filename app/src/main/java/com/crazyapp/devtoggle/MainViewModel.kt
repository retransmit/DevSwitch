package com.crazyapp.devtoggle

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.crazyapp.devtoggle.privilege.RootGrant
import com.crazyapp.devtoggle.shizuku.ShizukuBridge
import com.crazyapp.devtoggle.shizuku.ShizukuStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

data class UiState(
    val hasPermission: Boolean = false,
    val state: DevState = DevState(),
    val shizuku: ShizukuStatus = ShizukuStatus.NotInstalled,
    val busy: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = DevSettings(app)
    private val shizuku = ShizukuBridge(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener { refresh() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    init {
        refresh()
        viewModelScope.launch {
            settings.observe().collect { state -> _ui.update { it.copy(state = state) } }
        }
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun refresh() {
        _ui.update {
            it.copy(
                hasPermission = settings.hasWritePermission,
                shizuku = shizuku.status(),
                state = settings.read(),
            )
        }
    }

    fun toggle(setting: DevSetting, enabled: Boolean) {
        try {
            settings.set(setting, enabled)
        } catch (e: SecurityException) {
            _ui.update {
                it.copy(hasPermission = false, message = "WRITE_SECURE_SETTINGS is not granted. See the setup card.")
            }
        } catch (e: RuntimeException) {
            _ui.update { it.copy(message = e.message ?: "Could not change ${setting.key}") }
        }
        _ui.update { it.copy(state = settings.read()) }
    }

    fun requestShizukuPermission() {
        runCatching { shizuku.requestPermission() }
            .onFailure { e -> _ui.update { it.copy(message = "Shizuku: ${e.message}") } }
    }

    fun grantWithShizuku() = grant("Shizuku") {
        shizuku.runAsShell(listOf("pm", "grant", packageName, PERMISSION))
    }

    fun grantWithRoot() = grant("root") {
        withContext(Dispatchers.IO) { RootGrant.grant(packageName, PERMISSION) }
    }

    private fun grant(via: String, block: suspend () -> String) {
        if (_ui.value.busy) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = true) }
            val result = runCatching { block() }
            refresh()
            _ui.update {
                it.copy(
                    busy = false,
                    message = when {
                        result.isFailure -> "Granting via $via failed: ${result.exceptionOrNull()?.message}"
                        it.hasPermission -> "Permission granted via $via."
                        else -> "The $via command ran but the permission is still missing. ${result.getOrNull()?.trim().orEmpty()}"
                    },
                )
            }
        }
    }

    fun clearMessage() = _ui.update { it.copy(message = null) }

    override fun onCleared() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
    }

    private val packageName: String
        get() = getApplication<Application>().packageName

    companion object {
        const val PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"

        fun adbCommand(packageName: String) = "adb shell pm grant $packageName $PERMISSION"
    }
}
