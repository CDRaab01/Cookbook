package com.cookbook.ui.cook

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.cookbook.data.remote.RecipeOut
import com.cookbook.ui.recipe.formatQuantity
import com.cookbook.ui.theme.CookbookTheme
import com.cookbook.util.UiState
import design.pulse.ui.components.Caption
import design.pulse.ui.components.DataText
import design.pulse.ui.components.PanelCard
import design.pulse.ui.components.ProgressRing
import design.pulse.ui.components.PulseButton
import design.pulse.ui.components.SectionHeader

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Cook mode (v0.3): the phone propped against the backsplash. One step at a time in large type,
 * screen kept awake, tap-to-advance, duration-detected timer chips, ingredients one sheet away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CookModeScreen(
    onExit: () -> Unit,
    viewModel: CookModeViewModel = hiltViewModel(),
) {
    val state by viewModel.recipe.collectAsState()
    val currentStep by viewModel.currentStep.collectAsState()
    val completed by viewModel.completedSteps.collectAsState()
    val timer by viewModel.timer.collectAsState()
    var showIngredients by remember { mutableStateOf(false) }
    val colors = CookbookTheme.colors
    val context = LocalContext.current

    // Keep the screen awake while cooking; restored on leave.
    val activity = remember(context) { context.findActivity() }
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Buzz when a timer lands.
    LaunchedEffect(Unit) {
        viewModel.timerDone.collect {
            val vibrator = androidx.core.content.ContextCompat.getSystemService(
                context, android.os.Vibrator::class.java,
            )
            vibrator?.vibrate(
                android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val recipe = (state as? UiState.Success)?.data
                    Text(
                        recipe?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Outlined.Close, contentDescription = "Exit cook mode")
                    }
                },
                actions = {
                    IconButton(onClick = { showIngredients = true }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.List,
                            contentDescription = "Ingredients",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when (val s = state) {
            is UiState.Loading, UiState.Idle -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator(color = colors.heat.base) }
            is UiState.Error -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { Text(s.message, color = MaterialTheme.colorScheme.error) }
            is UiState.Success -> CookModeBody(
                recipe = s.data,
                currentStep = currentStep,
                completed = completed,
                timer = timer,
                onStep = viewModel::goToStep,
                onToggleDone = viewModel::toggleStepDone,
                onNext = viewModel::completeAndAdvance,
                onStartTimer = viewModel::startTimer,
                onCancelTimer = viewModel::cancelTimer,
                onExit = onExit,
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showIngredients) {
        val recipe = (state as? UiState.Success)?.data
        if (recipe != null) {
            ModalBottomSheet(onDismissRequest = { showIngredients = false }) {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp, end = 20.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { SectionHeader("Ingredients", channel = colors.heat.base) }
                    items(recipe.ingredients) { ing ->
                        Row {
                            val qty = formatQuantity(ing.quantity, ing.unit)
                            if (qty != null) {
                                DataText(
                                    qty,
                                    style = CookbookTheme.dataType.numeral,
                                    color = colors.heat.base,
                                    modifier = Modifier.width(76.dp),
                                )
                            } else {
                                Spacer(Modifier.width(76.dp))
                            }
                            Text(ing.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CookModeBody(
    recipe: RecipeOut,
    currentStep: Int,
    completed: Set<Int>,
    timer: StepTimer?,
    onStep: (Int) -> Unit,
    onToggleDone: (Int) -> Unit,
    onNext: () -> Unit,
    onStartTimer: (Int, Int) -> Unit,
    onCancelTimer: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CookbookTheme.colors
    if (recipe.steps.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("This recipe has no steps yet.", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    val step = recipe.steps[currentStep.coerceIn(0, recipe.steps.size - 1)]
    val allDone = completed.size >= recipe.steps.size

    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        // Step dots: tap to jump; green when done, heat when current.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            itemsIndexed(recipe.steps) { index, _ ->
                StepDot(
                    done = index in completed,
                    current = index == currentStep,
                    onClick = { onStep(index) },
                )
            }
        }

        Caption("Step ${currentStep + 1} of ${recipe.steps.size}")
        Spacer(Modifier.height(12.dp))

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
        ) {
            Text(
                step.text,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.alpha(if (step.order in completed) 0.6f else 1f),
            )
            Spacer(Modifier.height(16.dp))

            val durationSeconds = remember(step.text) {
                StepDurations.firstDurationSeconds(step.text)
            }
            if (timer != null && timer.stepOrder == step.order) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(
                        progress = if (timer.totalSeconds == 0) 0f
                        else 1f - timer.remainingSeconds.toFloat() / timer.totalSeconds,
                        channel = if (timer.finished) colors.fresh.base else colors.heat.base,
                        modifier = Modifier.size(96.dp),
                    ) {
                        DataText(
                            StepDurations.label(timer.remainingSeconds),
                            style = CookbookTheme.dataType.dataSmall,
                            color = if (timer.finished) colors.fresh.base else colors.heat.base,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    PulseButton(
                        text = if (timer.finished) "Done!" else "Cancel timer",
                        onClick = onCancelTimer,
                        tonal = true,
                        compact = true,
                    )
                }
            } else if (durationSeconds != null) {
                PulseButton(
                    text = "Start ${StepDurations.label(durationSeconds)} timer",
                    onClick = { onStartTimer(step.order, durationSeconds) },
                    tonal = true,
                    compact = true,
                    channel = colors.plum.base,
                    onChannel = colors.plum.on,
                    dimChannel = colors.plum.dim,
                    leadingIcon = {
                        Icon(Icons.Outlined.Timer, contentDescription = null, Modifier.size(18.dp))
                    },
                )
            }
        }

        // Primary advance control — thumb-sized, bottom of screen.
        Column(Modifier.padding(vertical = 16.dp)) {
            if (allDone) {
                PulseButton(
                    text = "Finished — exit cook mode",
                    onClick = onExit,
                    modifier = Modifier.fillMaxWidth(),
                    gradient = design.pulse.ui.theme.LocalPulseStructure.current.energyGradient,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PulseButton(
                        text = if (step.order in completed) "Undo done" else "Done",
                        onClick = { onToggleDone(step.order) },
                        tonal = true,
                        channel = colors.fresh.base,
                        onChannel = colors.fresh.on,
                        dimChannel = colors.fresh.dim,
                        leadingIcon = {
                            Icon(Icons.Outlined.Check, contentDescription = null, Modifier.size(18.dp))
                        },
                    )
                    PulseButton(
                        text = if (currentStep == recipe.steps.size - 1) "Done, last step" else "Done, next step",
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDot(done: Boolean, current: Boolean, onClick: () -> Unit) {
    val colors = CookbookTheme.colors
    val color = when {
        done -> colors.fresh.base
        current -> colors.heat.base
        else -> MaterialTheme.colorScheme.outline
    }
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        design.pulse.ui.components.ChannelDot(color = color, size = if (current) 14.dp else 10.dp)
    }
}
