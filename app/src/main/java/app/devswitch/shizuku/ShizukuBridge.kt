// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Lennox

package app.devswitch.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import app.devswitch.BuildConfig
import app.devswitch.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ShizukuStatus {
    data object NotInstalled : ShizukuStatus
    data object NotRunning : ShizukuStatus
    data object PermissionNeeded : ShizukuStatus
    data object Ready : ShizukuStatus
}

class ShizukuBridge(context: Context) {
    private val appContext = context.applicationContext

    fun status(): ShizukuStatus = when {
        Shizuku.pingBinder() && !Shizuku.isPreV11() ->
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.Ready
            } else {
                ShizukuStatus.PermissionNeeded
            }
        isInstalled() -> ShizukuStatus.NotRunning
        else -> ShizukuStatus.NotInstalled
    }

    private fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun requestPermission() {
        Shizuku.requestPermission(REQUEST_CODE)
    }

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(appContext, ShellService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    /** A bound shell-uid user service kept open across several calls. Close it when done. */
    class ServiceHandle(val service: IShellService, private val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }

    /**
     * Binds the shell-uid user service and hands it back still bound, so a session of many calls
     * (a pairing, polled every couple of seconds) costs one process rather than one per call. The
     * caller must close the handle; closing stops the service process, so nothing lingers with
     * shell rights. The pairing methods must go through this service because only its process can
     * reach the adb system service.
     */
    suspend fun openService(): ServiceHandle = withTimeout(30_000) {
        var connection: ServiceConnection? = null
        try {
            val service = suspendCancellableCoroutine<IShellService> { continuation ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        if (!continuation.isActive) return
                        if (binder != null && binder.pingBinder()) {
                            continuation.resume(IShellService.Stub.asInterface(binder))
                        } else {
                            continuation.resumeWithException(
                                IllegalStateException("The Shizuku user service did not respond"),
                            )
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName?) = Unit
                }
                connection = conn
                Shizuku.bindUserService(userServiceArgs, conn)
            }
            val bound = connection!!
            ServiceHandle(service) { runCatching { Shizuku.unbindUserService(userServiceArgs, bound, true) } }
        } catch (e: Throwable) {
            connection?.let { runCatching { Shizuku.unbindUserService(userServiceArgs, it, true) } }
            throw e
        }
    }

    /** Binds, runs [block] on the service off the main thread, and unbinds. For one-off calls. */
    suspend fun <T> withService(block: (IShellService) -> T): T {
        val handle = openService()
        try {
            return withContext(Dispatchers.IO) { block(handle.service) }
        } finally {
            handle.close()
        }
    }

    /** Runs [command] with the shell uid through the user service and returns its output. */
    suspend fun runAsShell(command: List<String>): String =
        withService { it.execute(command.toTypedArray()) }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 1001
    }
}
