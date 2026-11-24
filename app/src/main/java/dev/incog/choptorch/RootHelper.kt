package dev.incog.choptorch

import java.util.concurrent.TimeUnit

/**
 * Uses root (Magisk/KernelSU `su`) to exempt the background service from Doze and App Standby.
 * Every command is best-effort; failures are reported but do not abort the rest.
 */
object RootHelper {

    fun commands(pkg: String) = listOf(
        // Exempt from Doze / App Standby so the sensor keeps running with the screen off.
        "dumpsys deviceidle whitelist +$pkg",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        "cmd appops set $pkg RUN_IN_BACKGROUND allow",
        "cmd appops set $pkg WAKE_LOCK allow",
        "am set-standby-bucket $pkg active",
    )

    fun hasRoot(): Boolean = run("id").let { it.first == 0 && it.second.contains("uid=0") }

    /** Runs all keep-alive commands. Returns a human-readable log. */
    fun applyKeepAlive(pkg: String): String {
        if (!hasRoot()) return "Root not granted. Allow Chop Torch in Magisk / KernelSU and try again."
        val sb = StringBuilder()
        for (cmd in commands(pkg)) {
            val (code, out) = run(cmd)
            sb.append(if (code == 0) "OK   " else "FAIL ").append(cmd)
            if (out.isNotBlank() && code != 0) sb.append("\n     ").append(out.trim().take(120))
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Runs a multi-line shell script as root. */
    fun runScript(script: String): Pair<Int, String> = run(script)

    private fun run(cmd: String): Pair<Int, String> = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(15, TimeUnit.SECONDS)) {
            p.destroy(); -1 to "timeout"
        } else p.exitValue() to out
    } catch (e: Exception) {
        -1 to (e.message ?: e.toString())
    }
}
