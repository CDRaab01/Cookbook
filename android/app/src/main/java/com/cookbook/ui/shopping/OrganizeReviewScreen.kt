package com.cookbook.ui.shopping

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.categoryLabel
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton

/**
 * Review what the local model would re-file, and apply the moves you agree with.
 *
 * The whole reason this screen exists rather than the app just re-filing quietly: background
 * classification only touches items nobody has placed, but a whole-list pass can propose moving
 * something you put where it is on purpose. So every move is a checkbox, and nothing is written
 * until Apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeReviewScreen(
    listId: String,
    onBack: () -> Unit,
    onApplied: () -> Unit,
    viewModel: OrganizeReviewViewModel = hiltViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsState()
    val accepted by viewModel.accepted.collectAsState()
    val applying by viewModel.applying.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(listId) { viewModel.open(listId) }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Organize list") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.setAll(accepted.size != suggestions.size) },
                        enabled = suggestions.isNotEmpty(),
                    ) { Text(if (accepted.size == suggestions.size) "None" else "All") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "The local AI thinks these are in the wrong aisle. Untick anything you'd rather " +
                    "leave alone — nothing changes until you apply.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(suggestions, key = { it.itemId }) { suggestion ->
                    PanelCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = suggestion.itemId in accepted,
                                onCheckedChange = { viewModel.toggle(suggestion.itemId) },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    suggestion.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Caption(categoryLabel(suggestion.currentCategory))
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowForward,
                                        contentDescription = "becomes",
                                        modifier = Modifier.height(14.dp).width(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Caption(
                                        categoryLabel(suggestion.suggestedCategory),
                                        color = CookbookTheme.colors.heat.base,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(16.dp)) {
                PulseButton(
                    text = when {
                        applying -> "Applying…"
                        accepted.isEmpty() -> "Nothing selected"
                        else -> "Apply ${accepted.size} change${if (accepted.size == 1) "" else "s"}"
                    },
                    onClick = { viewModel.apply(onApplied) },
                    channel = CookbookTheme.colors.heat.base,
                    onChannel = CookbookTheme.colors.heat.on,
                    dimChannel = CookbookTheme.colors.heat.dim,
                )
            }
        }
    }
}
