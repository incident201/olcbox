package org.olcbox.app.ui.features.home

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.olcbox.app.ui.components.StartButton
import org.olcbox.app.ui.features.home.components.HomeScreenAppBar
import org.olcbox.app.ui.features.home.components.LocationSelectorScreen
import org.olcbox.app.ui.features.home.components.LogsSheet
import org.olcbox.app.ui.features.home.components.RelayStatus
import org.olcbox.app.ui.features.locations.LocationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeScreenViewModel,
    locationViewModel: LocationViewModel,
    scrollState: ScrollState,
    onToggleClick: () -> Unit = { viewModel.ToggleVpn() },
    onImportFileRequested: () -> Unit = {},
    onImportFromClipboardRequested: () -> Unit = { /* ... */ },
    onCopyConfigRequested: () -> Unit = { viewModel.onCopyFullConfigClicked() },
    onSaveLogsRequested: (onSaved: (String) -> Unit, onError: (String) -> Unit) -> Unit = { _, _ -> },
    showAppSettingsButton: Boolean = false,
    onAppSettingsClick: () -> Unit = {},
    onOpenLocationSettings: (String?) -> Unit,
    onAddLocation: () -> Unit
) {
    var isLogsSheetOpen by remember { mutableStateOf(false) }

    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pingsState = locationViewModel.pingsState

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            HomeScreenAppBar(
                onHistoryClick = { isLogsSheetOpen = true },
                showAppSettingsButton = showAppSettingsButton,
                onAppSettingsClick = onAppSettingsClick,
                onImportFileClick = onImportFileRequested,
                onImportClipboardClick = {
                    onImportFromClipboardRequested()
                    scope.launch { snackbarHostState.showSnackbar("Imported from clipboard") }
                },
                onExportClipboardClick = {
                    onCopyConfigRequested()
                    scope.launch { snackbarHostState.showSnackbar("Config copied") }
                },
                hasSubscriptions = locationViewModel.locations.any { !it.subscriptionUrl.isNullOrBlank() },
                onRefreshSubscriptionsClick = {
                    viewModel.refreshSubscriptions { updatedCount ->
                        locationViewModel.loadLocations()
                        viewModel.restartVpnIfRunning()
                        val message = if (updatedCount > 0) {
                            "Subscriptions updated: $updatedCount"
                        } else {
                            "No subscriptions to update"
                        }
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 32.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RelayStatus(isActive = state.isVpnConnected)
            Spacer(modifier = Modifier.height(16.dp))

            StartButton(
                isActive = state.isVpnConnected,
                isLoading = state.isVpnLoading,
                enabled = state.isVpnLoading || state.isVpnConnected || state.canStartVpn,
                onClick = { onToggleClick() }
            )

            Spacer(modifier = Modifier.height(32.dp))

            LocationSelectorScreen(
                onRefreshClick = {
                    scope.launch {
                        locationViewModel.refreshPings { viewModel.checkConnectionFor(it) }
                    }
                },
                locations = locationViewModel.locations,
                selectedLocationId = locationViewModel.selectedLocationId,
                pingsState = pingsState,
                onLocationSelected = { id ->
                    locationViewModel.selectLocation(id) {
                        viewModel.loadCurrentConfig()
                        viewModel.restartVpnIfRunning()
                    }
                },
                onLocationSettingsClick = { id ->
                    onOpenLocationSettings(id)
                },
                onAddLocationClick = {
                    onAddLocation()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }

        if (isLogsSheetOpen) {
            val logs by viewModel.logs.collectAsState()
            LogsSheet(
                logs = logs,
                onSaveClick = {
                    onSaveLogsRequested(
                        { message -> scope.launch { snackbarHostState.showSnackbar(message) } },
                        { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                    )
                },
                onDismiss = { isLogsSheetOpen = false }
            )
        }
    }
}
