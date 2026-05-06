package org.olcbox.app.ui.features.locations

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository

data class LocationItem(
    val storageId: String,
    val fullName: String,
    val config: LocationConfig? = null,
    val subscriptionUrl: String? = null
)

sealed class PingsState {
    object Idle : PingsState()
    data class Loading(val lastPings: Map<String, Int?>? = null) : PingsState()
    data class Success(val pings: Map<String, Int?>) : PingsState()
    data class Error(val message: String) : PingsState()
}

class LocationViewModel(
    private val locationsRepository: LocationsRepository,
) : ViewModel() {

    var locations = mutableStateListOf<LocationItem>()
        private set

    var selectedLocationId by mutableStateOf<String?>(null)
        private set

    var pingsState by mutableStateOf<PingsState>(PingsState.Idle)
        private set

    private var refreshPingsJob: Job? = null

    var editingConfig by mutableStateOf(LocationConfig())
    var editingName by mutableStateOf("")
    var editingId by mutableStateOf<String?>(null)

    var isSaving by mutableStateOf(false)
        private set

    var nameError by mutableStateOf<String?>(null)
        private set

    var serverError by mutableStateOf<String?>(null)
        private set

    var keyError by mutableStateOf<String?>(null)
        private set

    var clientIdError by mutableStateOf<String?>(null)
        private set

    val isFormValid: Boolean
        get() = nameError == null && serverError == null && keyError == null && clientIdError == null &&
                editingName.isNotBlank() && editingConfig.id.isNotBlank() &&
                editingConfig.key.isNotBlank() && editingConfig.clientId.isNotBlank()


    init {
        loadLocations()
    }

    fun loadLocations() {
        viewModelScope.launch {
            val savedConfigs = locationsRepository.getAllLocations()
            val currentSelectedId = locationsRepository.getActiveLocationId()

            locations.clear()

            savedConfigs.forEach { entry ->
                val normalized = entry.location
                locations.add(
                    LocationItem(
                        storageId = entry.storageId,
                        fullName = normalized.displayName(),
                        config = normalized,
                        subscriptionUrl = entry.subscriptionUrl
                    )
                )
            }

            if (locations.isNotEmpty() && (currentSelectedId.isNullOrBlank() || locations.none { it.storageId == currentSelectedId })) {
                val nextId = locations.firstOrNull()?.storageId
                locationsRepository.setActiveLocationId(nextId)
                selectedLocationId = nextId
            } else {
                selectedLocationId = currentSelectedId
            }
        }
    }

    fun selectLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.setActiveLocationId(id)
            selectedLocationId = id
            onComplete()
        }
    }

    fun refreshPings(performPing: suspend (LocationConfig) -> Long?) {
        val previousPings = (pingsState as? PingsState.Success)?.pings
            ?: (pingsState as? PingsState.Loading)?.lastPings
        val locationsSnapshot = locations.toList()

        refreshPingsJob?.cancel()
        refreshPingsJob = viewModelScope.launch {
            pingsState = PingsState.Loading(lastPings = previousPings)
            try {
                val results = supervisorScope {
                    locationsSnapshot.map { location ->
                        async {
                            location.storageId to checkLocationPing(location, performPing)?.toInt()
                        }
                    }.awaitAll().toMap()
                }

                pingsState = PingsState.Success(results)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                pingsState = PingsState.Error(e.message ?: "Error")
            }
        }
    }

    private suspend fun checkLocationPing(
        location: LocationItem,
        performPing: suspend (LocationConfig) -> Long?
    ): Long? {
        val config = location.config?.takeIf { it.isComplete() } ?: return null
        return withTimeoutOrNull(LOCATION_PING_TIMEOUT_MS) {
            repeat(LOCATION_PING_ATTEMPTS) { attempt ->
                val result = try {
                    performPing(config)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (result != null) return@withTimeoutOrNull result
                if (attempt < LOCATION_PING_ATTEMPTS - 1) {
                    delay(LOCATION_PING_RETRY_DELAY_MS)
                }
            }
            null
        }
    }

    fun startEditing(id: String?) {
        nameError = null
        serverError = null
        keyError = null
        clientIdError = null
        isSaving = false

        if (id == null) {
            editingId = null
            editingConfig = LocationConfig()
            editingName = ""
        } else {
            val location = locations.find { it.storageId == id }
            editingId = id
            editingConfig = location?.config?.normalized() ?: LocationConfig()
            editingName = editingConfig.displayName()
        }
    }


    fun onNameChanged(value: String) {
        editingName = value
        validateName(value)
    }

    fun onServerChanged(value: String) {
        editingConfig = editingConfig.copy(id = value)
        validateServer(value)
    }

    fun onSniChanged(value: String) = Unit

    fun onPasswordChanged(value: String) {
        editingConfig = editingConfig.copy(key = value)
        validateKey(value)
    }

    fun onClientIdChanged(value: String) {
        editingConfig = editingConfig.copy(clientId = value)
        validateClientId(value)
    }

    fun onBypassProviderChanged(value: String) {
        val provider = LocationConfig.normalizeProvider(value)
        editingConfig = editingConfig.copy(
            bypassProvider = provider,
            transport = LocationConfig.normalizeTransport(editingConfig.transport, provider)
        )
    }

    fun onTransportChanged(value: String) {
        editingConfig = editingConfig.copy(
            transport = LocationConfig.normalizeTransport(value, editingConfig.bypassProvider)
        )
    }

    fun onVp8FpsChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Fps = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    fun onVp8BatchChanged(value: String) {
        editingConfig = editingConfig.copy(
            vp8Batch = value.filter { it.isDigit() }.toIntOrNull() ?: 0
        )
    }

    private fun validateName(name: String) {
        nameError = when {
            name.isBlank() -> "Name cannot be empty"
            name.length > 30 -> "Name is too long (max 30 chars)"
            else -> null
        }
    }

    private fun validateServer(server: String) {
        serverError = when {
            server.isBlank() -> "Room ID cannot be empty"
            server.length > 256 -> "Room ID is too long"
            else -> null
        }
    }

    private fun validateKey(key: String) {
        keyError = when {
            key.isBlank() -> "Key cannot be empty"
            !key.matches(Regex("^[a-fA-F0-9]{64}$")) -> "Key must be 64 hex characters"
            else -> null
        }
    }

    private fun validateClientId(clientId: String) {
        clientIdError = when {
            clientId.isBlank() -> "Client ID cannot be empty"
            clientId.any { it.isWhitespace() } -> "Client ID cannot contain spaces"
            clientId.length > 128 -> "Client ID is too long"
            else -> null
        }
    }

    fun saveEditing(onComplete: () -> Unit) {
        validateName(editingName)
        validateServer(editingConfig.id)
        validateClientId(editingConfig.clientId)
        validateKey(editingConfig.key)

        if (!isFormValid || isSaving) return

        viewModelScope.launch {
            isSaving = true
            val id = editingId ?: "custom_${(100..999).random()}"
            val finalConfig = editingConfig.copy(name = editingName).normalized()
            locationsRepository.saveLocation(id, finalConfig)
            locationsRepository.setActiveLocationId(id)
            loadLocations()
            delay(600)
            onComplete()
            isSaving = false
        }
    }

    fun deleteLocation(id: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            locationsRepository.deleteLocation(id)
            loadLocations()
            onComplete()
        }
    }

    private companion object {
        const val LOCATION_PING_ATTEMPTS = 2
        const val LOCATION_PING_TIMEOUT_MS = 30_000L
        const val LOCATION_PING_RETRY_DELAY_MS = 250L
    }
}
