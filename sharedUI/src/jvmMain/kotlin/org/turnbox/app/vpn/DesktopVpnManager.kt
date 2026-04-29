package org.turnbox.app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.turnbox.app.data.model.LocationConfig
import org.turnbox.app.data.repository.LocationsRepository
import org.turnbox.app.desktop.DesktopOs
import org.turnbox.app.desktop.DesktopPaths
import org.turnbox.app.vpn.desktop.DesktopNativeAssets
import org.turnbox.app.vpn.desktop.DesktopProxyController
import org.turnbox.app.vpn.desktop.LinuxPrivilege
import org.turnbox.app.vpn.desktop.LinuxTunController
import org.turnbox.app.vpn.desktop.OlcRtcCommand
import org.turnbox.app.vpn.desktop.PacServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class DesktopVpnManager private constructor(
    private val locationsRepository: LocationsRepository,
    private val proxyController: DesktopProxyController = DesktopProxyController.current(),
    private val pacServer: PacServer = PacServer()
) : VpnManager {

    constructor(locationsRepository: LocationsRepository) : this(
        locationsRepository = locationsRepository,
        proxyController = DesktopProxyController.current(),
        pacServer = PacServer()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    override val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val _status = MutableStateFlow<VpnStatus>(VpnStatus.Disconnected)
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private var operationJob: Job? = null
    private var logJob: Job? = null
    private var tunLogJob: Job? = null
    private var process: Process? = null
    private var tunProcess: Process? = null
    private var generation = 0L
    private val linuxTunController = LinuxTunController(::addLog)

    override fun needsPermission(): Boolean = false

    override fun startVpn() {
        val requestGeneration = ++generation
        operationJob?.cancel()
        operationJob = scope.launch {
            mutex.withLock {
                if (requestGeneration != generation) return@withLock
                startDesktopMode()
            }
        }
    }

    override fun stopVpn() {
        generation++
        operationJob?.cancel()
        operationJob = scope.launch {
            mutex.withLock {
                stopDesktopMode()
            }
        }
    }

    override suspend fun ping(locationConfig: LocationConfig): Long? = checkConnection(locationConfig)

    override suspend fun checkConnection(locationConfig: LocationConfig): Long? = withContext(Dispatchers.IO) {
        val location = locationConfig.normalized()
        if (!location.isComplete()) return@withContext null

        if (_status.value is VpnStatus.Connected) {
            return@withContext measureTcpConnect(PacServer.LOCAL_SOCKS_PORT)
        }

        runCatching {
            val port = allocateLocalPort()
            val binary = DesktopNativeAssets.resolveOlcRtcBinary()
            val ready = CompletableDeferred<Unit>()
            val checkProcess = startOlcRtcProcess(binary, location, port, ready, logOutput = false, privileged = false)
            val startedAt = System.currentTimeMillis()
            try {
                waitForOlcRtcReady(checkProcess, ready, port)
                System.currentTimeMillis() - startedAt
            } finally {
                stopProcess(checkProcess)
            }
        }.getOrNull()
    }

    fun close() {
        runBlocking {
            generation++
            operationJob?.cancel()
            mutex.withLock {
                stopDesktopMode()
            }
            scope.cancel()
        }
    }

    private suspend fun startDesktopMode() {
        if (_status.value is VpnStatus.Connected || _status.value is VpnStatus.Connecting) return

        setStatus(VpnStatus.Connecting)
        val active = locationsRepository.getActiveLocation()
        val location = active?.location?.normalized()
        if (location == null || !location.isComplete()) {
            setStatus(VpnStatus.Error("No active location"))
            addLog("Add a valid location before starting desktop proxy")
            return
        }

        try {
            val binary = DesktopNativeAssets.resolveOlcRtcBinary()
            val ready = CompletableDeferred<Unit>()
            val useLinuxTun = DesktopPaths.os == DesktopOs.Linux
            process = startOlcRtcProcess(
                binary = binary,
                location = location,
                socksPort = PacServer.LOCAL_SOCKS_PORT,
                ready = ready,
                logOutput = true,
                privileged = useLinuxTun
            )
            waitForOlcRtcReady(process ?: error("olcRTC process is missing"), ready, PacServer.LOCAL_SOCKS_PORT)
            if (useLinuxTun) {
                val hevBinary = DesktopNativeAssets.resolveHevSocks5TunnelBinary()
                tunProcess = linuxTunController.start(hevBinary)
                startTunLogReader(tunProcess ?: error("hev-socks5-tunnel process is missing"))
            } else {
                pacServer.start()
                proxyController.enable(pacServer.url)
            }
            setStatus(VpnStatus.Connected)
            addLog(if (useLinuxTun) "Desktop Linux TUN connected" else "Desktop proxy connected")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            addLog("Desktop start failed: ${e.message}")
            if (DesktopPaths.os == DesktopOs.Linux) {
                runCatching { linuxTunController.stop(tunProcess) }
                    .onFailure { addLog("Linux TUN cleanup failed: ${it.message}") }
                tunProcess = null
            } else {
                runCatching { proxyController.restore() }.onFailure { addLog("Proxy restore failed: ${it.message}") }
            }
            pacServer.stop()
            stopProcess(process)
            process = null
            setStatus(VpnStatus.Error(e.message ?: "Desktop start failed"))
        }
    }

    private suspend fun stopDesktopMode() {
        if (_status.value is VpnStatus.Disconnected && process == null && tunProcess == null) return

        setStatus(VpnStatus.Stopping)
        if (DesktopPaths.os == DesktopOs.Linux) {
            runCatching { linuxTunController.stop(tunProcess) }.onFailure {
                addLog("Linux TUN stop failed: ${it.message}")
            }
            tunProcess = null
        } else {
            runCatching { proxyController.restore() }.onFailure {
                addLog("Proxy restore failed: ${it.message}")
            }
        }
        pacServer.stop()
        stopProcess(process)
        process = null
        logJob?.cancel()
        logJob = null
        tunLogJob?.cancel()
        tunLogJob = null
        setStatus(VpnStatus.Disconnected)
        addLog(if (DesktopPaths.os == DesktopOs.Linux) "Desktop Linux TUN stopped" else "Desktop proxy stopped")
    }

    private fun startOlcRtcProcess(
        binary: Path,
        location: LocationConfig,
        socksPort: Int,
        ready: CompletableDeferred<Unit>,
        logOutput: Boolean,
        privileged: Boolean
    ): Process {
        val command = OlcRtcCommand(binary, location, PacServer.LOCAL_SOCKS_HOST, socksPort).args()
        addLog("Starting olcRTC provider=${location.bypassProvider}, room=${location.id}, port=$socksPort")
        if (privileged) {
            addLog("Linux TUN mode starts olcRTC with elevated privileges to bypass the TUN route")
        }
        val processBuilder = ProcessBuilder(if (privileged) LinuxPrivilege.command(command) else command)
            .redirectErrorStream(true)
        processBuilder.environment()["NO_PROXY"] = "127.0.0.1,localhost"
        processBuilder.environment()["no_proxy"] = "127.0.0.1,localhost"
        val startedProcess = processBuilder.start()

        val readerJob = scope.launch {
            startedProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isActive) return@forEach
                    if (logOutput) addLog("rtc: $line")
                    if (line.contains("SOCKS5 server listening", ignoreCase = true)) {
                        ready.complete(Unit)
                    }
                }
            }
        }
        if (logOutput) {
            logJob?.cancel()
            logJob = readerJob
        }
        return startedProcess
    }

    private fun startTunLogReader(target: Process) {
        tunLogJob?.cancel()
        tunLogJob = scope.launch {
            target.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (!isActive) return@forEach
                    addLog("tun: $line")
                }
            }
        }
    }

    private suspend fun waitForOlcRtcReady(
        process: Process,
        ready: CompletableDeferred<Unit>,
        socksPort: Int
    ) {
        val deadline = System.currentTimeMillis() + OLC_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ready.isCompleted || canConnectToSocks(socksPort)) return
            if (!process.isAlive) error("olcRTC exited before SOCKS5 was ready")
            delay(READY_POLL_INTERVAL_MS)
        }
        error("olcRTC start timed out")
    }

    private fun canConnectToSocks(port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(PacServer.LOCAL_SOCKS_HOST, port), TCP_CONNECT_TIMEOUT_MS.toInt())
            }
        }.isSuccess
    }

    private fun measureTcpConnect(port: Int): Long? {
        val startedAt = System.currentTimeMillis()
        return if (canConnectToSocks(port)) System.currentTimeMillis() - startedAt else null
    }

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private fun stopProcess(target: Process?) {
        if (target == null) return
        if (!target.isAlive) return
        target.toHandle().descendants().forEach { it.destroy() }
        target.destroy()
        if (!target.waitFor(PROCESS_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            target.toHandle().descendants().forEach { it.destroyForcibly() }
            target.destroyForcibly()
            target.waitFor(PROCESS_KILL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun setStatus(status: VpnStatus) {
        _status.value = status
        _isConnected.value = status is VpnStatus.Connected
    }

    private fun addLog(message: String) {
        _logs.update { (it + message).takeLast(120) }
    }

    private companion object {
        const val OLC_READY_TIMEOUT_MS = 25_000L
        const val READY_POLL_INTERVAL_MS = 200L
        const val TCP_CONNECT_TIMEOUT_MS = 250L
        const val PROCESS_STOP_TIMEOUT_MS = 3_000L
        const val PROCESS_KILL_TIMEOUT_MS = 1_000L
    }
}
