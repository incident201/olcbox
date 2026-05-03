package org.turnbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LocationConfig(
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = DEFAULT_BYPASS_PROVIDER,
    val transport: String = DEFAULT_TRANSPORT,
    @SerialName("vp8_fps")
    val vp8Fps: Int = DEFAULT_VP8_FPS,
    @SerialName("vp8_batch")
    val vp8Batch: Int = DEFAULT_VP8_BATCH
) {
    fun normalized(): LocationConfig {
        val provider = normalizeProvider(bypassProvider)
        return copy(
            name = name.trim(),
            id = id.trim(),
            key = key.trim(),
            bypassProvider = provider,
            transport = normalizeTransport(transport, provider),
            vp8Fps = sanitizeVp8Fps(vp8Fps),
            vp8Batch = sanitizeVp8Batch(vp8Batch)
        )
    }

    fun isComplete(): Boolean = id.isNotBlank() && key.isNotBlank()

    fun displayName(): String = name.ifBlank { id }

    fun providerName(): String = providerDisplayName(bypassProvider)

    fun transportName(): String = transportDisplayName(transport)

    companion object {
        const val PROVIDER_JAZZ = "jazz"
        const val PROVIDER_TELEMOST = "telemost"
        const val PROVIDER_WB_STREAM = "wbstream"
        const val DEFAULT_BYPASS_PROVIDER = PROVIDER_WB_STREAM
        const val TRANSPORT_DATACHANNEL = "datachannel"
        const val TRANSPORT_VP8CHANNEL = "vp8channel"
        const val DEFAULT_TRANSPORT = TRANSPORT_VP8CHANNEL
        const val DEFAULT_VP8_FPS = 60
        const val DEFAULT_VP8_BATCH = 8

        val supportedBypassProviders = listOf(
            PROVIDER_JAZZ,
            PROVIDER_TELEMOST,
            PROVIDER_WB_STREAM
        )

        val supportedTransports = listOf(
            TRANSPORT_VP8CHANNEL,
            TRANSPORT_DATACHANNEL
        )

        fun supportedTransportsForProvider(provider: String): List<String> {
            return when (normalizeProvider(provider)) {
                PROVIDER_TELEMOST -> listOf(TRANSPORT_VP8CHANNEL)
                else -> supportedTransports
            }
        }

        fun normalizeProvider(value: String): String {
            return when (value.trim().lowercase()) {
                PROVIDER_JAZZ, "sberjazz", "sber_jazz" -> PROVIDER_JAZZ
                PROVIDER_TELEMOST, "yandex", "yandex_telemost" -> PROVIDER_TELEMOST
                PROVIDER_WB_STREAM, "wbstream", "wb-stream", "wildberries" -> PROVIDER_WB_STREAM
                else -> DEFAULT_BYPASS_PROVIDER
            }
        }

        fun normalizeTransport(value: String, provider: String = DEFAULT_BYPASS_PROVIDER): String {
            val normalized = when (value.trim().lowercase()) {
                TRANSPORT_DATACHANNEL, "data", "dc" -> TRANSPORT_DATACHANNEL
                TRANSPORT_VP8CHANNEL, "vp8", "video_vp8", "video-vp8" -> TRANSPORT_VP8CHANNEL
                else -> DEFAULT_TRANSPORT
            }
            return normalized.takeIf { it in supportedTransportsForProvider(provider) }
                ?: DEFAULT_TRANSPORT
        }

        fun providerDisplayName(provider: String): String {
            return when (normalizeProvider(provider)) {
                PROVIDER_JAZZ -> "Jazz"
                PROVIDER_TELEMOST -> "Telemost"
                PROVIDER_WB_STREAM -> "WB Stream"
                else -> "WB Stream"
            }
        }

        fun transportDisplayName(transport: String): String {
            return when (normalizeTransport(transport)) {
                TRANSPORT_VP8CHANNEL -> "VP8"
                TRANSPORT_DATACHANNEL -> "Data"
                else -> "VP8"
            }
        }

        fun transportDescription(transport: String): String {
            return when (normalizeTransport(transport)) {
                TRANSPORT_VP8CHANNEL -> "Media tunnel"
                TRANSPORT_DATACHANNEL -> "WebRTC data"
                else -> "Media tunnel"
            }
        }

        fun sanitizeVp8Fps(value: Int): Int = value.coerceIn(1, 120)

        fun sanitizeVp8Batch(value: Int): Int = value.coerceIn(1, 32)
    }
}

@Serializable
data class LocationEntry(
    @SerialName("storage_id")
    val storageId: String,
    val name: String = "",
    val id: String = "",
    val key: String = "",
    @SerialName("bypass_provider")
    val bypassProvider: String = LocationConfig.DEFAULT_BYPASS_PROVIDER,
    val transport: String = LocationConfig.DEFAULT_TRANSPORT,
    @SerialName("vp8_fps")
    val vp8Fps: Int = LocationConfig.DEFAULT_VP8_FPS,
    @SerialName("vp8_batch")
    val vp8Batch: Int = LocationConfig.DEFAULT_VP8_BATCH
) {
    val location: LocationConfig
        get() = LocationConfig(
            name,
            id,
            key,
            bypassProvider,
            transport,
            vp8Fps,
            vp8Batch
        ).normalized()

    fun normalized(): LocationEntry {
        val config = location
        return copy(
            storageId = storageId.trim(),
            name = config.name,
            id = config.id,
            key = config.key,
            bypassProvider = config.bypassProvider,
            transport = config.transport,
            vp8Fps = config.vp8Fps,
            vp8Batch = config.vp8Batch
        )
    }

    companion object {
        fun from(storageId: String, location: LocationConfig): LocationEntry {
            val config = location.normalized()
            return LocationEntry(
                storageId = storageId,
                name = config.name,
                id = config.id,
                key = config.key,
                bypassProvider = config.bypassProvider,
                transport = config.transport,
                vp8Fps = config.vp8Fps,
                vp8Batch = config.vp8Batch
            ).normalized()
        }
    }
}

@Serializable
data class LocationBundleV3(
    val version: Int = 3,
    @SerialName("active_location_id")
    val activeLocationId: String? = null,
    val locations: List<LocationEntry> = emptyList()
) {
    fun normalized(): LocationBundleV3 {
        val normalizedLocations = locations
            .map { it.normalized() }
            .filter { it.storageId.isNotBlank() && it.location.isComplete() }
            .distinctBy { it.storageId }

        val active = activeLocationId
            ?.takeIf { id -> normalizedLocations.any { it.storageId == id } }
            ?: normalizedLocations.firstOrNull()?.storageId

        return copy(
            version = 3,
            activeLocationId = active,
            locations = normalizedLocations
        )
    }
}
