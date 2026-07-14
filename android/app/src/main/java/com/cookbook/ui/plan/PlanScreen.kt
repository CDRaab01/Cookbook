package com.cookbook.ui.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.data.remote.MEAL_SLOTS
import com.cookbook.data.remote.PlanEntryOut
import com.cookbook.data.remote.RecipeSummaryOut
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Weekly meal planner (v0.3): assign recipes (or free-text notes) to a week, then send the
 * whole week's recipes to a shopping list in one merged add — decide → shop → cook, closed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(
    onOpenRecipe: (String) -> Unit,
    viewModel: PlanViewModel = hiltViewModel(),
) {
    val weekStart by viewModel.weekStart.collectAsState()
    val state by viewModel.entries.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val error by viewModel.error.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val colors = CookbookTheme.colors
    var assigningSlot by remember { mutableStateOf<Pair<LocalDate, String>?>(null) }

    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); viewModel.clearError() }
    }
    LaunchedEffect(Unit) {
        viewModel.sentToList.collect { count ->
            snackbar.showSnackbar(if (count > 0) "Sent to your shopping list ($count items)" else "Sent to your shopping list")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Plan", style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = viewModel::goToPreviousWeek) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Previous week")
                }
                Column(
                    Modifier.weight(1f).clickable { viewModel.goToThisWeek() },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fmt = DateTimeFormatter.ofPattern("MMM d")
                    Text(
                        "${weekStart.format(fmt)} – ${weekStart.plusDays(6).format(fmt)}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Caption("tap for this week")
                }
                IconButton(onClick = viewModel::goToNextWeek) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Next week")
                }
            }

            when (val s = state) {
                is UiState.Loading, UiState.Idle -> Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = colors.heat.base) }
                is UiState.Error -> Box(
                    Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(s.message, color = MaterialTheme.colorScheme.error) }
                is UiState.Success -> WeekList(
                    weekStart = weekStart,
                    entries = s.data,
                    onSlotTap = { date, slot -> assigningSlot = date to slot },
                    onRemove = viewModel::removeEntry,
                    onOpenRecipe = onOpenRecipe,
                    onSetEaten = viewModel::setEaten,
                    modifier = Modifier.weight(1f),
                )
            }

            PulseButton(
                text = "Send this week to shopping list",
                onClick = viewModel::sendWeekToList,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }

    assigningSlot?.let { (date, slot) ->
        AssignDialog(
            date = date,
            slot = slot,
            recipes = recipes,
            onPickRecipe = { recipeId ->
                viewModel.addRecipe(date, slot, recipeId)
                assigningSlot = null
            },
            onNote = { note ->
                viewModel.addNote(date, slot, note)
                assigningSlot = null
            },
            onDismiss = { assigningSlot = null },
        )
    }
}

@Composable
private fun WeekList(
    weekStart: LocalDate,
    entries: List<PlanEntryOut>,
    onSlotTap: (LocalDate, String) -> Unit,
    onRemove: (String) -> Unit,
    onOpenRecipe: (String) -> Unit,
    onSetEaten: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byDate = entries.groupBy { it.date }
    val dayFmt = DateTimeFormatter.ofPattern("EEEE, MMM d")
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items((0..6).map { weekStart.plusDays(it.toLong()) }) { date ->
            val iso = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val dayEntries = byDate[iso].orEmpty()
            Column {
                Text(date.format(dayFmt), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                MEAL_SLOTS.forEach { slot ->
                    val entry = dayEntries.find { it.slot == slot }
                    SlotRow(
                        slot = slot,
                        entry = entry,
                        onTap = { if (entry == null) onSlotTap(date, slot) },
                        onRemove = { entry?.let { onRemove(it.id) } },
                        onOpenRecipe = onOpenRecipe,
                        onSetEaten = { eaten -> entry?.let { onSetEaten(it.id, eaten) } },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SlotRow(
    slot: String,
    entry: PlanEntryOut?,
    onTap: () -> Unit,
    onRemove: () -> Unit,
    onOpenRecipe: (String) -> Unit,
    onSetEaten: (Boolean) -> Unit = {},
) {
    val colors = CookbookTheme.colors
    PanelCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        // Empty slots tap-to-plan with the panel's press-scale; filled slots keep their inner
        // recipe-name tap + remove button, so the whole card is not a button then.
        onClick = if (entry == null) onTap else null,
        tint = if (entry != null) colors.heat.dim else null,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                slot.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = colors.heat.base,
                modifier = Modifier.width(84.dp),
            )
            val eaten = entry?.eaten == true
            // Eaten meals read as done: struck through and dimmed.
            val nameDecoration = if (eaten) TextDecoration.LineThrough else null
            val nameColor = if (eaten) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified
            when {
                entry == null -> Text(
                    "Tap to plan",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entry.recipeId != null -> Text(
                    entry.recipeName ?: "Recipe",
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = nameDecoration,
                    color = nameColor,
                    modifier = Modifier.weight(1f).clickable { onOpenRecipe(entry.recipeId) },
                )
                else -> Text(
                    entry.note ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = nameDecoration,
                    color = nameColor,
                    modifier = Modifier.weight(1f),
                )
            }
            if (entry != null) {
                // The recipe/note Text above already has weight(1f), so it fills the row and pushes
                // these buttons to the end. (A Spacer(weight(0f)) used to sit here and crashed on
                // recipe entries — Compose's Modifier.weight requires a value > 0.)
                IconButton(onClick = { onSetEaten(!entry.eaten) }) {
                    Icon(
                        if (entry.eaten) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                        contentDescription = if (entry.eaten) "Eaten — tap to undo" else "Mark as eaten",
                        tint = if (entry.eaten) colors.fresh.base else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AssignDialog(
    date: LocalDate,
    slot: String,
    recipes: List<RecipeSummaryOut>,
    onPickRecipe: (String) -> Unit,
    onNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Plan ${slot} — ${date.format(DateTimeFormatter.ofPattern("MMM d"))}") },
        text = {
            Column {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Recipe") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Note") })
                }
                Spacer(Modifier.height(8.dp))
                if (tab == 0) {
                    LazyColumn(Modifier.height(280.dp)) {
                        items(recipes) { recipe ->
                            Text(
                                recipe.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickRecipe(recipe.id) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                } else {
                    androidx.compose.material3.OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        placeholder = { Text("Leftovers, pizza night…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (tab == 1) {
                TextButton(onClick = { onNote(note) }, enabled = note.isNotBlank()) { Text("Save") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
