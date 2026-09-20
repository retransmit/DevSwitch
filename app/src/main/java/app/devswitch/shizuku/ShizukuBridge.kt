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

    /** Runs [command] with the shell uid through a Shizuku user service and returns its output. */
    suspend fun runAsShell(command: List<String>): String = withTimeout(30_000) {
        val args = Shizuku.UserServiceArgs(ComponentName(appContext, ShellService::class.java))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
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
                Shizuku.bindUserService(args, conn)
            }
            withContext(Dispatchers.IO) { service.execute(command.toTypedArray()) }
        } finally {
            // remove = true also stops the service process; nothing lingers with shell rights.
            connection?.let { Shizuku.unbindUserService(args, it, true) }
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 1001
    }
}
