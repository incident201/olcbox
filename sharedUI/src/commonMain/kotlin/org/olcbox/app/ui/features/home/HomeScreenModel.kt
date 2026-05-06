package org.olcbox.app.ui.features.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.data.exporter.LogExporter
import org.olcbox.app.data.importer.ConfigImporter
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.repository.LocationsRepository
import org.olcbox.app.ui.features.locations.LocationItem
import org.olcbox.app.vpn.VpnManager
import org.olcbox.app.vpn.VpnStatus

class HomeScreenViewModel(
    private val vpnManager: VpnManager,
    private val locationsRepository: LocationsRepository,
    private val configImporter: ConfigImporter,
    private val logExporter: LogExporter
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeScreenState(
            isVpnConnected = false,
            isVpnLoading = false,
            selectedLocation = null,
            configData = LocationConfig(),
            shouldShowConfigInvalidReminder = false,
            canStartVpn = false,
            startBlockedReason = "Add a location first"
        )
    )
    val state get() = _state.asStateFlow()
    val logs get() = vpnManager.logs

    init {
        loadCurrentConfig()

        viewModelScope.launch {
            vpnManager.status.collect { status ->
                _state.update {
                    when (status) {
                        VpnStatus.Connected -> it.copy(isVpnConnected = true, isVpnLoading = false)
                        VpnStatus.Connecting -> it.copy(isVpnConnected = false, isVpnLoading = true)
                        VpnStatus.Reconnecting -> it.copy(isVpnConnected = true, isVpnLoading = true)
                        VpnStatus.Stopping -> it.copy(isVpnConnected = false, isVpnLoading = false)
                        VpnStatus.Disconnected -> it.copy(isVpnConnected = false, isVpnLoading = false)
                        is VpnStatus.Error -> it.copy(isVpnConnected = false, isVpnLoading = false)
                    }
                }
            }
        }
    }

    fun loadCurrentConfig() {
        viewModelScope.launch {
            val active = locationsRepository.getActiveLocation()
            if (active == null) {
                _state.update {
                    it.copy(
                        selectedLocation = null,
                        configData = LocationConfig(),
                        canStartVpn = false,
                        startBlockedReason = "Add a location first"
                    )
                }
                return@launch
            }

            val normalized = active.location
            val locationItem = LocationItem(
                storageId = active.storageId,
                fullName = normalized.displayName(),
                config = normalized,
                subscriptionUrl = active.subscriptionUrl
            )

            _state.update {
                it.copy(
                    configData = normalized,
                    selectedLocation = locationItem,
                    canStartVpn = normalized.isComplete(),
                    startBlockedReason = if (normalized.isComplete()) null else "Complete active location first"
                )
            }
        }
    }

    suspend fun performPing(): Long? {
        return vpnManager.ping(_state.value.configData)
    }

    suspend fun performPingFor(config: LocationConfig): Long? {
        return vpnManager.ping(config)
    }

    suspend fun checkConnectionFor(config: LocationConfig): Long? {
        return vpnManager.checkConnection(config)
    }

    fun startVpnContinuation() {
        _state.update { it.copy(isVpnLoading = true) }
    }

    fun ToggleVpn() {
        val status = vpnManager.status.value
        if (_state.value.isVpnLoading ||
            status is VpnStatus.Connecting ||
            status is VpnStatus.Reconnecting
        ) {
            viewModelScope.launch {
                vpnManager.stopVpn()
                _state.update { it.copy(isVpnConnected = false, isVpnLoading = false) }
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isVpnLoading = true) }
            try {
                if (_state.value.isVpnConnected || vpnManager.status.value is VpnStatus.Connected) {
                    vpnManager.stopVpn()
                } else {
                    val active = locationsRepository.getActiveLocation()
                    if (active == null || !active.location.isComplete()) {
                        _state.update {
                            it.copy(
                                isVpnLoading = false,
                                canStartVpn = false,
                                startBlockedReason = "Add a valid location first"
                            )
                        }
                        return@launch
                    }
                    vpnManager.startVpn()
                }
            } catch (e: Exception) {
                _state.update { it.copy(isVpnLoading = false) }
            }
        }
    }

    fun restartVpnIfRunning() {
        when (vpnManager.status.value) {
            VpnStatus.Connected,
            VpnStatus.Connecting,
            VpnStatus.Reconnecting -> viewModelScope.launch {
                _state.update { it.copy(isVpnLoading = true) }
                vpnManager.startVpn()
            }

            VpnStatus.Disconnected,
            VpnStatus.Stopping,
            is VpnStatus.Error -> Unit
        }
    }

    fun onServerChanged(value: String) = updateLocationConfig { it.copy(id = value) }
    fun onPasswordChanged(value: String) = updateLocationConfig { it.copy(key = value) }
    fun onSniChanged(value: String) = Unit

    private fun updateLocationConfig(block: (LocationConfig) -> LocationConfig) {
        _state.update { it.copy(configData = block(it.configData)) }
    }

    fun onConfigConfirmed() {
        if (isUserConfigValid()) {
            viewModelScope.launch {
                val selectedId = locationsRepository.getActiveLocationId()
                if (!selectedId.isNullOrBlank()) {
                    locationsRepository.saveLocation(selectedId, _state.value.configData)
                }
            }
        } else {
            _state.update { it.copy(shouldShowConfigInvalidReminder = true) }
        }
    }

    fun onConfigInvalidReminderDismissed() {
        _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
    }

    fun onCopyFullConfigClicked() {
        viewModelScope.launch {
            configImporter.copyToClipboard(locationsRepository.exportBundle())
        }
    }

    fun suggestedLogsFileName(): String = "olcbox-logs.txt"

    fun onSaveLogsToFile(
        target: Any,
        onSaved: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val content = buildLogsExport(logs.value)
            logExporter.writeLogs(target, content)
                .onSuccess { savedPath ->
                    onSaved(
                        if (savedPath.isBlank() || savedPath == "Logs saved") {
                            "Logs saved"
                        } else {
                            "Logs saved to $savedPath"
                        }
                    )
                }
                .onFailure { error ->
                    onError(error.message ?: "Failed to save logs")
                }
        }
    }

    fun onPasteFromClipboard(onComplete: () -> Unit = {}) {
        configImporter.getFromClipboard()?.let { text ->
            onImportFullConfig(text, onComplete)
        }
    }

    fun onFileSelected(fileSource: Any, onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            configImporter.readTextFromSource(fileSource)?.let { text ->
                onImportFullConfig(text, onComplete)
            }
        }
    }

    private fun isUserConfigValid(): Boolean {
        return _state.value.configData.isComplete()
    }

    fun onRawConfigImported(rawText: String) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            locationsRepository.importText(rawText)
            _state.update { it.copy(shouldShowConfigInvalidReminder = false) }
            loadCurrentConfig()
        }
    }

    fun onImportFullConfig(rawText: String, onComplete: () -> Unit = {}) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    locationsRepository.importText(rawText)
                }
                loadCurrentConfig()
                onComplete()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        canStartVpn = false,
                        startBlockedReason = e.message ?: "Import failed"
                    )
                }
            }
        }
    }

    fun refreshSubscriptions(
        onComplete: (updatedCount: Int) -> Unit = {}
    ) {
        viewModelScope.launch {
            val updatedCount = locationsRepository.refreshSubscriptions()
            loadCurrentConfig()
            onComplete(updatedCount)
        }
    }

    private fun buildLogsExport(logs: List<String>): String {
        return buildString {
            appendLine("Olcbox application logs")
            appendLine("Entries: ${logs.size}")
            appendLine()
            logs.forEachIndexed { index, line ->
                appendLine("${index + 1}. $line")
            }
        }
    }
}

data class HomeScreenState(
    val isVpnConnected: Boolean,
    val isVpnLoading: Boolean = false,
    val selectedLocation: LocationItem?,
    val configData: LocationConfig,
    val shouldShowConfigInvalidReminder: Boolean,
    val canStartVpn: Boolean,
    val startBlockedReason: String?
)
