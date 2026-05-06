package org.olcbox.app.vpn.desktop

import org.olcbox.app.data.model.LocationConfig
import java.nio.file.Path

internal data class OlcRtcCommand(
    val binary: Path,
    val location: LocationConfig,
    val socksHost: String = PacServer.LOCAL_SOCKS_HOST,
    val socksPort: Int = PacServer.LOCAL_SOCKS_PORT,
    val dataDir: Path? = null
) {
    fun args(): List<String> {
        val config = location.normalized()
        val provider = desktopProviderArg(config.bypassProvider)
        val baseArgs = listOf(
            binary.toString(),
            "-mode", "cnc",
            "-link", "direct",
            "-transport", config.transport,
            "-carrier", provider,
            "-id", config.id,
            "-client-id", config.clientId,
            "-key", config.key,
            "-socks-host", socksHost,
            "-socks-port", socksPort.toString(),
            "-dns", "1.1.1.1:53"
        )
        val transportArgs = when (config.transport) {
            LocationConfig.TRANSPORT_VP8CHANNEL -> listOf(
                "-vp8-fps", config.vp8Fps.toString(),
                "-vp8-batch", config.vp8Batch.toString()
            )
            else -> emptyList()
        }
        return baseArgs + transportArgs + listOfNotNull(
            dataDir?.let { "-data" },
            dataDir?.toString()
        )
    }

    companion object {
        fun desktopProviderArg(provider: String): String {
            val normalizedProvider = LocationConfig.normalizeProvider(provider)
            return when (normalizedProvider) {
                LocationConfig.PROVIDER_WB_STREAM -> "wbstream"
                else -> normalizedProvider
            }
        }
    }
}
