package org.turnbox.app.vpn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.turnbox.app.desktop.DesktopPaths
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.io.path.exists

internal class LinuxTunController(
    private val addLog: (String) -> Unit
) {
    private var routesInstalled = false

    suspend fun start(hevBinary: Path): Process {
        val config = writeConfig()
        val process = startPrivilegedProcess(listOf(hevBinary.toString(), config.toString()))
        try {
            waitForInterface(process)
            runPrivilegedScript(writeUpScript())
            routesInstalled = true
            addLog("Linux TUN connected on $TUN_NAME")
            return process
        } catch (e: Exception) {
            stop(process)
            throw e
        }
    }

    suspend fun stop(process: Process?) {
        if (routesInstalled) {
            runCatching { runPrivilegedScript(writeDownScript()) }
                .onFailure { addLog("Linux TUN route cleanup failed: ${it.message}") }
            routesInstalled = false
        }
        stopProcess(process)
    }

    private fun writeConfig(): Path {
        val config = DesktopPaths.appDataDir().resolve("linux-tun.yml")
        Files.writeString(
            config,
            configContent()
        )
        return config
    }

    private fun writeUpScript(): Path {
        return writeScript(
            name = "linux-tun-up.sh",
            body = upScriptContent()
        )
    }

    private fun writeDownScript(): Path {
        return writeScript(
            name = "linux-tun-down.sh",
            body = downScriptContent()
        )
    }

    private fun writeScript(name: String, body: String): Path {
        val script = DesktopPaths.appDataDir().resolve(name)
        Files.writeString(script, body)
        script.toFile().setExecutable(true, true)
        return script
    }

    private suspend fun waitForInterface(process: Process) {
        val deadline = System.currentTimeMillis() + TUN_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) error("hev-socks5-tunnel exited before $TUN_NAME was ready")
            if (interfaceExists()) return
            delay(TUN_READY_POLL_MS)
        }
        error("$TUN_NAME was not created")
    }

    private suspend fun interfaceExists(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("ip", "link", "show", TUN_NAME)
                .redirectErrorStream(true)
                .start()
            process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() == 0
        }.getOrDefault(false)
    }

    private suspend fun runPrivilegedScript(script: Path) {
        runPrivilegedCommand(listOf(script.toString()))
    }

    private suspend fun runPrivilegedCommand(command: List<String>): String = withContext(Dispatchers.IO) {
        val process = ProcessBuilder(LinuxPrivilege.command(command))
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            error("${command.joinToString(" ")} failed with code $exitCode: $output")
        }
        output
    }

    private fun startPrivilegedProcess(command: List<String>): Process {
        return ProcessBuilder(LinuxPrivilege.command(command))
            .redirectErrorStream(true)
            .start()
    }

    private fun stopProcess(process: Process?) {
        if (process == null || !process.isAlive) return
        process.toHandle().descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    internal companion object {
        const val TUN_NAME = "turnbox0"
        const val TUN_MTU = 1500
        const val TUN_IPV4_ADDRESS = "10.0.88.88"
        const val MAPDNS_ADDRESS = "1.1.1.1"
        const val MAPDNS_NETWORK = "100.64.0.0"
        const val MAPDNS_NETMASK = "255.192.0.0"
        const val ROUTE_TABLE = "51820"
        const val ROOT_BYPASS_RULE_PREF = "10"
        const val TUN_RULE_PREF = "20"
        const val TUN_READY_TIMEOUT_MS = 10_000L
        const val TUN_READY_POLL_MS = 100L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L

        fun configContent(): String {
            return """
                tunnel:
                  name: $TUN_NAME
                  mtu: $TUN_MTU
                  multi-queue: false
                  ipv4: $TUN_IPV4_ADDRESS

                socks5:
                  address: ${PacServer.LOCAL_SOCKS_HOST}
                  port: ${PacServer.LOCAL_SOCKS_PORT}
                  udp: 'tcp'
                  pipeline: false

                mapdns:
                  address: $MAPDNS_ADDRESS
                  port: 53
                  network: $MAPDNS_NETWORK
                  netmask: $MAPDNS_NETMASK
                  cache-size: 10000

                misc:
                  task-stack-size: 24576
                  tcp-buffer-size: 4096
                  max-session-count: 1200
                  connect-timeout: 10000
                  tcp-read-write-timeout: 300000
                  udp-read-write-timeout: 60000
                  log-file: stderr
                  log-level: warn
            """.trimIndent()
        }

        fun upScriptContent(): String {
            return """
                #!/bin/sh
                set -eu
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                sysctl -w net.ipv4.conf.all.rp_filter=0 >/dev/null 2>&1 || true
                sysctl -w net.ipv4.conf.$TUN_NAME.rp_filter=0 >/dev/null 2>&1 || true
                ip link set $TUN_NAME up
                ip rule add uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF
                ip route add default dev $TUN_NAME table $ROUTE_TABLE
                ip rule add lookup $ROUTE_TABLE pref $TUN_RULE_PREF
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl dns $TUN_NAME $MAPDNS_ADDRESS >/dev/null 2>&1 || true
                  resolvectl domain $TUN_NAME '~.' >/dev/null 2>&1 || true
                  resolvectl default-route $TUN_NAME yes >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }

        fun downScriptContent(): String {
            return """
                #!/bin/sh
                ip rule del uidrange 0-0 lookup main pref $ROOT_BYPASS_RULE_PREF 2>/dev/null || true
                ip rule del lookup $ROUTE_TABLE pref $TUN_RULE_PREF 2>/dev/null || true
                ip route flush table $ROUTE_TABLE 2>/dev/null || true
                if command -v resolvectl >/dev/null 2>&1; then
                  resolvectl revert $TUN_NAME >/dev/null 2>&1 || true
                fi
            """.trimIndent()
        }
    }
}

internal object LinuxPrivilege {
    fun command(command: List<String>): List<String> {
        if (isRoot()) return command
        val preferred = System.getenv("TURNBOX_LINUX_PRIVILEGE")?.lowercase()
        return when {
            preferred == "sudo" -> listOf("sudo", "-n") + command
            preferred == "pkexec" -> listOf("pkexec") + command
            executableExists("pkexec") -> listOf("pkexec") + command
            else -> listOf("sudo", "-n") + command
        }
    }

    private fun isRoot(): Boolean {
        return runCatching {
            val process = ProcessBuilder("id", "-u")
                .redirectErrorStream(true)
                .start()
            val uid = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor(1, TimeUnit.SECONDS) && uid == "0"
        }.getOrDefault(false)
    }

    private fun executableExists(name: String): Boolean {
        val path = System.getenv("PATH").orEmpty()
        return path.split(':')
            .filter { it.isNotBlank() }
            .map { Path(it).resolve(name) }
            .any { it.exists() && Files.isExecutable(it) }
    }
}
