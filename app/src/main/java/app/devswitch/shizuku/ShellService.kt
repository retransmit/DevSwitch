package app.devswitch.shizuku

import android.content.Context
import androidx.annotation.Keep
import app.devswitch.IShellService
import kotlin.system.exitProcess

/**
 * Runs in a process that Shizuku spawns with the shell uid (2000), the same identity
 * `adb shell` has. Shizuku loads it from this APK by class name, so the class is kept by
 * name in release builds and offers both constructors Shizuku may call.
 */
@Keep
class ShellService : IShellService.Stub {
    constructor() : super()

    /** Shizuku v13 and newer pass a Context. */
    @Suppress("unused")
    constructor(context: Context) : super()

    override fun destroy() {
        exitProcess(0)
    }

    override fun execute(command: Array<String>): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        // IllegalStateException is one of the exceptions Binder carries back to the caller.
        check(exit == 0) {
            "${command.first()} exited with code $exit" + if (output.isBlank()) "" else ": ${output.trim()}"
        }
        return output
    }
}
