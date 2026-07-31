package com.cookbook.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.data.remote.HouseholdOut
import com.cookbook.ui.theme.CookbookTheme
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.ProfileHeader
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLoggedOut: () -> Unit,
    onOpenStaples: () -> Unit,
    onOpenAisleOrder: () -> Unit,
    onOpenStores: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val colors = CookbookTheme.colors
    val serverUrl by viewModel.serverUrl.collectAsState()
    val serverVersion by viewModel.serverVersion.collectAsState()
    val userName by viewModel.userName.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val migrating by viewModel.migrating.collectAsState()
    val migrationStatus by viewModel.migrationStatus.collectAsState()
    val household by viewModel.household.collectAsState()
    val householdError by viewModel.householdError.collectAsState()
    val invite by viewModel.invite.collectAsState()
    val sharingAll by viewModel.sharingAll.collectAsState()
    val shareAllResult by viewModel.shareAllResult.collectAsState()
    var editedUrl by remember(serverUrl) { mutableStateOf(serverUrl) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(migrationStatus) {
        migrationStatus?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMigrationStatus()
        }
    }
    LaunchedEffect(shareAllResult) {
        shareAllResult?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissShareAllResult()
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
            ProfileHeader(
                name = userName ?: "Signed in",
                email = userEmail ?: "",
                channel = colors.heat.base,
                channelDim = colors.heat.dim,
            )

            invite?.let {
                SectionHeader("Household invite", channel = colors.fresh.base)
                InviteBanner(
                    invite = it,
                    onAccept = viewModel::acceptInvite,
                    onDecline = viewModel::declineInvite,
                )
            }

            SectionHeader("Account", channel = colors.heat.base)
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                PulseButton(
                    text = "Sign out",
                    onClick = { viewModel.logout(onLoggedOut) },
                    tonal = true,
                    compact = true,
                )
            }

            SectionHeader("Family", channel = colors.fresh.base)
            HouseholdBlock(
                household = household,
                error = householdError,
                sharingAll = sharingAll,
                onAddMember = viewModel::addHouseholdMember,
                onRemoveMember = viewModel::removeHouseholdMember,
                onLeave = viewModel::leaveHousehold,
                onShareAll = viewModel::shareAllRecipes,
            )

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

            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Pantry staples — the ingredients recipes can always assume you have " +
                            "(oil, salt, spices…).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Edit pantry staples",
                        onClick = onOpenStaples,
                        tonal = true,
                        compact = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                    )
                }
            }

            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Stores — each store's real aisles in the order you walk them. Pick one on " +
                            "the shopping list and it regroups into that store's aisles.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Manage stores",
                        onClick = onOpenStores,
                        tonal = true,
                        compact = true,
                        channel = colors.heat.base,
                        onChannel = colors.heat.on,
                        dimChannel = colors.heat.dim,
                    )
                }
            }

            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Aisle order — the category order used when no store is selected; " +
                            "the shopping list groups items in that order.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Edit aisle order",
                        onClick = onOpenAisleOrder,
                        tonal = true,
                        compact = true,
                        channel = colors.heat.base,
                        onChannel = colors.heat.on,
                        dimChannel = colors.heat.dim,
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

/**
 * Settings → Family: share the cookbook + shopping lists with your household. Owner invites by
 * email and can remove members; a member sees the roster and a Leave action. Inline errors surface
 * below. Mirrors the Magpie household Settings block; the server API is identical.
 */
/**
 * The banner shown when someone has invited the caller into their household. Accepting shares the
 * whole cookbook + lists; declining removes the invite. Backed by GET /household/invite +
 * POST /household/{accept,decline}.
 */
@Composable
private fun InviteBanner(
    invite: com.cookbook.data.remote.InviteOut,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    val colors = CookbookTheme.colors
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "${invite.ownerName.ifBlank { invite.ownerEmail }} invited you to share their " +
                    "cookbook and shopping lists. If you accept, you'll both see and edit the same " +
                    "recipes, family recipes, and lists.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseButton(
                    text = "Accept",
                    onClick = onAccept,
                    compact = true,
                    channel = colors.fresh.base,
                    onChannel = colors.fresh.on,
                    dimChannel = colors.fresh.dim,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDecline) { Text("Decline") }
            }
        }
    }
}

@Composable
private fun HouseholdBlock(
    household: HouseholdOut?,
    error: String?,
    sharingAll: Boolean,
    onAddMember: (String) -> Unit,
    onRemoveMember: (String) -> Unit,
    onLeave: () -> Unit,
    onShareAll: () -> Unit,
) {
    val colors = CookbookTheme.colors
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "Share your cookbook and shopping lists with your household — everyone sees and " +
                    "edits the same recipes, family recipes, and lists.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            household?.members?.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            m.name.ifBlank { m.email },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        val suffix = when {
                            m.isOwner -> " · owner"
                            m.status == "pending" -> " · invited (not yet accepted)"
                            else -> ""
                        }
                        Text(
                            "${m.email}$suffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (household.youAreOwner && !m.isOwner) {
                        TextButton(onClick = { onRemoveMember(m.userId) }) {
                            Text(if (m.status == "pending") "Cancel" else "Remove")
                        }
                    }
                }
            }
            // Joining a household shares the lists and plans, but each recipe stays private until
            // its creator opts it in — which nothing used to say. Offer the bulk opt-in right where
            // the household is managed, but only when it would actually do something.
            if (household != null && household.shared && household.unsharedRecipeCount > 0) {
                var confirming by remember { mutableStateOf(false) }
                Spacer(Modifier.height(12.dp))
                val n = household.unsharedRecipeCount
                Text(
                    if (n == 1) {
                        "1 of your recipes is still private — your family can't see it."
                    } else {
                        "$n of your recipes are still private — your family can't see them."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                PulseButton(
                    text = if (sharingAll) "Sharing…" else "Share all my recipes",
                    onClick = { confirming = true },
                    compact = true,
                    tonal = true,
                    enabled = !sharingAll,
                )
                if (confirming) {
                    AlertDialog(
                        onDismissRequest = { confirming = false },
                        title = { Text("Share all your recipes?") },
                        text = {
                            Text(
                                "This makes all $n of your private recipes family recipes — your " +
                                    "household will be able to see and edit them. Only recipes " +
                                    "you created are shared. You can make any one private again " +
                                    "from its recipe page.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                confirming = false
                                onShareAll()
                            }) { Text("Share all") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirming = false }) { Text("Cancel") }
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (household == null || household.youAreOwner) {
                var email by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Share by email") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    PulseButton(
                        text = "Share",
                        onClick = {
                            onAddMember(email)
                            email = ""
                        },
                        compact = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                        enabled = email.isNotBlank(),
                    )
                }
            } else {
                TextButton(onClick = onLeave) { Text("Leave household") }
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
