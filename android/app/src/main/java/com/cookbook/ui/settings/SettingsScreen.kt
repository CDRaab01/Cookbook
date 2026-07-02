package com.cookbook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.ui.theme.CookbookTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = CookbookTheme.colors
    val serverUrl by viewModel.serverUrl.collectAsState()
    val serverVersion by viewModel.serverVersion.collectAsState()
    val userLabel by viewModel.userLabel.collectAsState()
    val migrating by viewModel.migrating.collectAsState()
    val migrationStatus by viewModel.migrationStatus.collectAsState()
    var editedUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(migrationStatus) {
        migrationStatus?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMigrationStatus()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { androidx.compose.material3.SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionHeader("Account", channel = colors.heat.base)
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        userLabel ?: "Signed in",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Sign out",
                        onClick = { viewModel.logout(onLoggedOut) },
                        tonal = true,
                        compact = true,
                    )
                }
            }

            SectionHeader("Server", channel = colors.info.base)
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    OutlinedTextField(
                        value = editedUrl,
                        onValueChange = { editedUrl = it },
                        label = { Text("Server URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Save",
                        onClick = { viewModel.setServerUrl(editedUrl) },
                        tonal = true,
                        compact = true,
                        channel = colors.info.base,
                        onChannel = colors.info.on,
                        dimChannel = colors.info.dim,
                        enabled = editedUrl.isNotBlank() && editedUrl != serverUrl,
                    )
                }
            }

            SectionHeader("Library & data", channel = colors.fresh.base)
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Bring your saved recipes over from Plate. Safe to re-run — recipes " +
                            "already imported are skipped.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = if (migrating) "Importing…" else "Import recipes from Plate",
                        onClick = viewModel::migrateFromPlate,
                        tonal = true,
                        compact = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                        enabled = !migrating,
                    )
                }
            }

            SectionHeader("About", channel = colors.plum.base)
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Caption("App version")
                    Text(viewModel.appVersion, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(4.dp))
                    Caption("Server version")
                    Text(
                        serverVersion ?: "Unreachable",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (serverVersion == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}
