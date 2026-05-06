package org.olcbox.app.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mobile.Mobile
import org.olcbox.app.data.model.LocationConfig
import java.net.ServerSocket

internal object OlcRtcConnectionChecker {
    suspend fun check(locationConfig: LocationConfig): Long? {
        return withContext(Dispatchers.IO) {
            val config = locationConfig.normalized()
            if (!config.isComplete()) return@withContext null

            repeat(CONNECTION_CHECK_ATTEMPTS) {
                val socksPort = allocateLocalPort()
                val result = runCatching {
                    val startedAt = System.currentTimeMillis()
                    Mobile.startWithTransport(
                        config.bypassProvider,
                        config.transport,
                        config.id,
                        config.clientId,
                        config.key,
                        socksPort.toLong(),
                        "",
                        ""
                    )
                    Mobile.waitReady(CONNECTION_CHECK_TIMEOUT_MS)
                    (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                }.getOrNull()
                runCatching { Mobile.stop() }
                if (result != null && result > 0L) return@withContext result
            }
            null
        }
    }

    private fun allocateLocalPort(): Int {
        return ServerSocket(0).use { it.localPort }
    }

    private const val CONNECTION_CHECK_ATTEMPTS = 2
    private const val CONNECTION_CHECK_TIMEOUT_MS = 8_000L
}
