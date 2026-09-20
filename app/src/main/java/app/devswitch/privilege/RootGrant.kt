package app.devswitch.privilege

object RootGrant {
    /**
     * Runs `pm grant` through `su`. Throws when there is no su binary, root is denied, or
     * the package manager rejects the grant.
     */
    fun grant(packageName: String, permission: String): String {
        val process = ProcessBuilder("su", "-c", "pm grant $packageName $permission")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exit = process.waitFor()
        check(exit == 0) {
            "su exited with code $exit" + if (output.isBlank()) "" else ": ${output.trim()}"
        }
        return output
    }
}
