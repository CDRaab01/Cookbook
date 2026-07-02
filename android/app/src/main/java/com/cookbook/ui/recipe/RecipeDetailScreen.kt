package com.cookbook.ui.recipe

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import com.cookbook.data.remote.IngredientOut
import com.cookbook.data.remote.RecipeOut
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

/** Order categories the way you walk a store; uncategorized last. */
internal val CATEGORY_ORDER =
    listOf("produce", "meat", "dairy", "bakery", "frozen", "pantry", "other")

internal fun categoryLabel(category: String?): String =
    (category ?: "other").replaceFirstChar { it.uppercase() }

/** "2 lb Chicken breast" / "Salt (to taste)" — quantity formatting shared by detail + list rows. */
internal fun formatQuantity(quantity: Double?, unit: String?): String? {
    if (quantity == null) return unit
    val q = if (quantity % 1.0 == 0.0) quantity.toInt().toString() else quantity.toString()
    return if (unit == null) q else "$q $unit"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeDetailScreen(
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: RecipeDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.recipe.collectAsState()
    val error by viewModel.error.collectAsState()
    val addConflict by viewModel.addConflict.collectAsState()
    val nutrition by viewModel.nutrition.collectAsState()
    val plateLogStatus by viewModel.plateLogStatus.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickScale by remember { mutableStateOf(false) }
    var lastScale by remember { mutableStateOf(1.0) }
    var showLogDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(Unit) { viewModel.deleted.collect { onBack() } }
    LaunchedEffect(Unit) {
        viewModel.addedToList.collect { snackbar.showSnackbar("Added to your shopping list") }
    }
    LaunchedEffect(plateLogStatus) {
        plateLogStatus?.let {
            snackbar.showSnackbar(it)
            viewModel.clearPlateLogStatus()
        }
    }
    LaunchedEffect(error) {
        error?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(viewModel.recipeId) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit recipe")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete recipe")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            is UiState.Loading, UiState.Idle -> {
                Column(
                    Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(color = CookbookTheme.colors.heat.base) }
            }
            is UiState.Error -> {
                Column(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is UiState.Success -> RecipeDetailBody(
                recipe = s.data,
                onAddToList = { pickScale = true },
                nutrition = nutrition,
                onEstimateNutrition = viewModel::estimateNutrition,
                onLogToPlate = { showLogDialog = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showLogDialog) {
        val servings = (state as? UiState.Success)?.data?.servings ?: 1
        LogToPlateDialog(
            recipeServings = servings,
            onLog = { meal, servingsEaten ->
                showLogDialog = false
                viewModel.logToPlate(java.time.LocalDate.now(), meal, servingsEaten)
            },
            onDismiss = { showLogDialog = false },
        )
    }

    if (pickScale) {
        ScalePickerDialog(
            onPick = { scale ->
                pickScale = false
                lastScale = scale
                viewModel.addToList(scale)
            },
            onDismiss = { pickScale = false },
        )
    }

    if (addConflict) {
        AlertDialog(
            onDismissRequest = viewModel::dismissAddConflict,
            title = { Text("Already on the list") },
            text = { Text("This recipe's items are still unchecked on your shopping list. Add them again to double up?") },
            confirmButton = {
                TextButton(onClick = { viewModel.addToList(lastScale, force = true) }) {
                    Text("Add again")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissAddConflict) { Text("Skip") }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete recipe?") },
            text = { Text("This removes it from your book. Items already on your shopping list stay.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RecipeDetailBody(
    recipe: RecipeOut,
    onAddToList: () -> Unit,
    nutrition: UiState<com.cookbook.data.remote.RecipeNutritionOut>,
    onEstimateNutrition: () -> Unit,
    onLogToPlate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CookbookTheme.colors
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column {
                Text(recipe.name, style = MaterialTheme.typography.headlineMedium)
                if (!recipe.description.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        recipe.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    MetaStat(value = "${recipe.servings}", label = "servings", color = colors.info.base)
                    recipe.prepMinutes?.let {
                        Spacer(Modifier.width(20.dp))
                        MetaStat(value = "$it", label = "prep min", color = colors.plum.base)
                    }
                    recipe.cookMinutes?.let {
                        Spacer(Modifier.width(20.dp))
                        MetaStat(value = "$it", label = "cook min", color = colors.heat.base)
                    }
                }
            }
        }

        item {
            PulseButton(
                text = "Add to shopping list",
                onClick = onAddToList,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item { SectionHeader("Ingredients", channel = colors.heat.base) }
        item {
            PanelCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val grouped = recipe.ingredients.groupBy { it.category ?: "other" }
                    CATEGORY_ORDER.filter { grouped.containsKey(it) }.forEach { category ->
                        Caption(categoryLabel(category), color = colors.fresh.base)
                        grouped.getValue(category).forEach { IngredientRow(it) }
                    }
                }
            }
        }

        item { SectionHeader("Nutrition · via Plate", channel = colors.fresh.base) }
        item {
            NutritionCard(
                nutrition = nutrition,
                servings = recipe.servings,
                onEstimate = onEstimateNutrition,
                onLogToPlate = onLogToPlate,
            )
        }

        if (recipe.steps.isNotEmpty()) {
            item { SectionHeader("Steps", channel = colors.plum.base) }
            item {
                PanelCard(modifier = Modifier.fillMaxWidth()) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        recipe.steps.forEach { step ->
                            Row {
                                DataText(
                                    "${step.order + 1}",
                                    style = CookbookTheme.dataType.numeralLarge,
                                    color = colors.plum.base,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    step.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The Plate-powered macro panel: idle → an estimate CTA; loaded → per-serving macros in the
 * shared channel colors, match coverage, and the log-to-diary action. Estimates only — the
 * caption says so, and unmatched ingredients are counted, never guessed.
 */
@Composable
private fun NutritionCard(
    nutrition: UiState<com.cookbook.data.remote.RecipeNutritionOut>,
    servings: Int,
    onEstimate: () -> Unit,
    onLogToPlate: () -> Unit,
) {
    val colors = CookbookTheme.colors
    PanelCard(modifier = Modifier.fillMaxWidth()) {
        when (nutrition) {
            UiState.Idle -> {
                Column {
                    Text(
                        "Estimate calories and macros by matching ingredients against Plate's food database.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Estimate nutrition",
                        onClick = onEstimate,
                        tonal = true,
                        compact = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                    )
                }
            }
            is UiState.Loading -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(
                        color = colors.fresh.base,
                        modifier = Modifier.width(24.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Asking Plate…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            is UiState.Error -> {
                Column {
                    Text(
                        nutrition.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PulseButton(
                        text = "Retry",
                        onClick = onEstimate,
                        tonal = true,
                        compact = true,
                    )
                }
            }
            is UiState.Success -> {
                val n = nutrition.data
                Column {
                    Caption("Per serving · estimate")
                    Spacer(Modifier.height(8.dp))
                    Row {
                        MacroStat("${n.perServing.kcal.toInt()}", "kcal", colors.heat.base)
                        Spacer(Modifier.width(20.dp))
                        MacroStat("${n.perServing.proteinG.toInt()}", "protein g", colors.fresh.base)
                        Spacer(Modifier.width(20.dp))
                        MacroStat("${n.perServing.carbsG.toInt()}", "carbs g", colors.info.base)
                        Spacer(Modifier.width(20.dp))
                        MacroStat("${n.perServing.fatG.toInt()}", "fat g", colors.plum.base)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${n.matchedCount} of ${n.totalCount} ingredients matched · " +
                            "${n.totals.kcal.toInt()} kcal for all $servings servings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    PulseButton(
                        text = "Log to Plate diary",
                        onClick = onLogToPlate,
                        tonal = true,
                        compact = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroStat(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        DataText(value, style = CookbookTheme.dataType.dataSmall, color = color)
        Caption(label)
    }
}

/** Meal + servings-eaten picker for "Log to Plate diary". Logs to today. */
@Composable
private fun LogToPlateDialog(
    recipeServings: Int,
    onLog: (meal: String, servingsEaten: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    var meal by remember { mutableStateOf("dinner") }
    var servingsText by remember { mutableStateOf("1") }
    val servingsEaten = servingsText.toDoubleOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log to Plate diary") },
        text = {
            Column {
                Caption("Meal")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    listOf("breakfast", "lunch", "dinner", "snack").forEach { option ->
                        androidx.compose.material3.FilterChip(
                            selected = meal == option,
                            onClick = { meal = option },
                            label = { Text(option.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = servingsText,
                    onValueChange = { servingsText = it },
                    label = { Text("Servings eaten (of $recipeServings)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onLog(meal, servingsEaten ?: 1.0) },
                enabled = servingsEaten != null && servingsEaten > 0,
            ) { Text("Log it") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Servings multiplier for "Add to shopping list" — ½× to 3× as quick chips. */
@Composable
private fun ScalePickerDialog(
    onPick: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to shopping list") },
        text = {
            Column {
                Text(
                    "How much are you making?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0.5 to "½×", 1.0 to "1×", 2.0 to "2×", 3.0 to "3×").forEach { (scale, label) ->
                        PulseButton(
                            text = label,
                            onClick = { onPick(scale) },
                            tonal = scale != 1.0,
                            compact = true,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun MetaStat(value: String, label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        DataText(value, style = CookbookTheme.dataType.dataSmall, color = color)
        Spacer(Modifier.width(4.dp))
        Caption(label)
    }
}

@Composable
private fun IngredientRow(ingredient: IngredientOut) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val qty = formatQuantity(ingredient.quantity, ingredient.unit)
        if (qty != null) {
            DataText(
                qty,
                style = CookbookTheme.dataType.numeral,
                color = CookbookTheme.colors.heat.base,
                modifier = Modifier.width(76.dp),
            )
        } else {
            Spacer(Modifier.width(76.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(ingredient.name, style = MaterialTheme.typography.bodyLarge)
            if (!ingredient.note.isNullOrBlank()) {
                Text(
                    ingredient.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
