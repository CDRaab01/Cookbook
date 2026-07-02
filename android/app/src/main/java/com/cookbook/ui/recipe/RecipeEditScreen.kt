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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecipeEditScreen(
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
    viewModel: RecipeEditViewModel = hiltViewModel(),
) {
    val draft by viewModel.draft.collectAsState()
    val saveState by viewModel.saveState.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val colors = CookbookTheme.colors

    LaunchedEffect(saveState) {
        when (val s = saveState) {
            is UiState.Success -> onSaved(s.data)
            is UiState.Error -> {
                snackbar.showSnackbar(s.message)
                viewModel.clearSaveError()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (viewModel.recipeId == null) "New recipe" else "Edit recipe",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
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
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (loading) {
            Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(color = colors.heat.base) }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { v -> viewModel.update { it.copy(description = v) } },
                    label = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = draft.imageUrl,
                    onValueChange = { v -> viewModel.update { it.copy(imageUrl = v) } },
                    label = { Text("Image URL (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                TagEditor(
                    tags = draft.tags,
                    onAdd = viewModel::addTag,
                    onRemove = viewModel::removeTag,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = draft.servings,
                        onValueChange = { v -> viewModel.update { it.copy(servings = v) } },
                        label = { Text("Servings") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.prepMinutes,
                        onValueChange = { v -> viewModel.update { it.copy(prepMinutes = v) } },
                        label = { Text("Prep min") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.cookMinutes,
                        onValueChange = { v -> viewModel.update { it.copy(cookMinutes = v) } },
                        label = { Text("Cook min") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                }
            }

            item { SectionHeader("Ingredients", channel = colors.heat.base) }
            itemsIndexed(draft.ingredients) { index, ingredient ->
                IngredientEditor(
                    ingredient = ingredient,
                    canRemove = draft.ingredients.size > 1,
                    onChange = { transform -> viewModel.updateIngredient(index, transform) },
                    onRemove = { viewModel.removeIngredient(index) },
                )
            }
            item {
                PulseButton(
                    text = "Add ingredient",
                    onClick = viewModel::addIngredient,
                    tonal = true,
                    compact = true,
                    leadingIcon = {
                        Icon(Icons.Outlined.Add, contentDescription = null, Modifier.width(18.dp))
                    },
                )
            }

            item { SectionHeader("Steps", channel = colors.plum.base) }
            itemsIndexed(draft.steps) { index, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = step,
                        onValueChange = { viewModel.updateStep(index, it) },
                        label = { Text("Step ${index + 1}") },
                        modifier = Modifier.weight(1f),
                    )
                    if (draft.steps.size > 1) {
                        IconButton(onClick = { viewModel.removeStep(index) }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Remove step")
                        }
                    }
                }
            }
            item {
                PulseButton(
                    text = "Add step",
                    onClick = viewModel::addStep,
                    tonal = true,
                    compact = true,
                    channel = colors.plum.base,
                    onChannel = colors.plum.on,
                    dimChannel = colors.plum.dim,
                    leadingIcon = {
                        Icon(Icons.Outlined.Add, contentDescription = null, Modifier.width(18.dp))
                    },
                )
            }

            item { Spacer(Modifier.height(4.dp)) }
            item {
                PulseButton(
                    text = if (saveState is UiState.Loading) "Saving…" else "Save recipe",
                    onClick = viewModel::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = saveState !is UiState.Loading,
                )
            }
        }
    }
}

/** Free-text tags: type + Add (or IME done); chips remove on tap of ×. */
@Composable
private fun TagEditor(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val colors = CookbookTheme.colors
    var input by remember { androidx.compose.runtime.mutableStateOf("") }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Tags (weeknight, grill…)") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onDone = {
                        onAdd(input)
                        input = ""
                    },
                ),
            )
            Spacer(Modifier.width(8.dp))
            PulseButton(
                text = "Add",
                onClick = {
                    onAdd(input)
                    input = ""
                },
                tonal = true,
                compact = true,
                channel = colors.plum.base,
                onChannel = colors.plum.on,
                dimChannel = colors.plum.dim,
                enabled = input.isNotBlank(),
            )
        }
        if (tags.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(tags) { _, tag ->
                    androidx.compose.material3.InputChip(
                        selected = false,
                        onClick = { onRemove(tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove $tag",
                                modifier = Modifier.width(16.dp),
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IngredientEditor(
    ingredient: IngredientDraft,
    canRemove: Boolean,
    onChange: ((IngredientDraft) -> IngredientDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = CookbookTheme.colors
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = ingredient.name,
                onValueChange = { v -> onChange { it.copy(name = v) } },
                label = { Text("Ingredient") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = ingredient.quantity,
                onValueChange = { v -> onChange { it.copy(quantity = v) } },
                label = { Text("Qty") },
                modifier = Modifier.width(80.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = ingredient.unit,
                onValueChange = { v -> onChange { it.copy(unit = v) } },
                label = { Text("Unit") },
                modifier = Modifier.width(88.dp),
                singleLine = true,
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove ingredient")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.lazy.LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(CATEGORY_ORDER) { _, category ->
                FilterChip(
                    selected = ingredient.category == category,
                    onClick = {
                        onChange {
                            it.copy(category = if (it.category == category) null else category)
                        }
                    },
                    label = { Text(categoryLabel(category)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.fresh.dim,
                        selectedLabelColor = colors.fresh.base,
                    ),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
