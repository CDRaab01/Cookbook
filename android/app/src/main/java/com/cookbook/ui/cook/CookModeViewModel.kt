package com.cookbook.ui.cook

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cookbook.data.remote.RecipeOut
import com.cookbook.data.repository.RecipeRepository
import com.cookbook.ui.navigation.Screen
import com.cookbook.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One running step timer. Anchored to elapsedRealtime (the Spotter drift-free rule) so
 * remaining time is recomputed, never counted — backgrounding can't drift it. */
data class StepTimer(
    val stepOrder: Int,
    val totalSeconds: Int,
    val endElapsedMs: Long,
    val remainingSeconds: Int,
    val finished: Boolean = false,
)

@HiltViewModel
class CookModeViewModel @Inject constructor(
    private val recipeRepository: RecipeRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val recipeId: String = checkNotNull(savedStateHandle[Screen.CookMode.ARG])

    /** Target servings chosen on the recipe-detail screen; 0 = cook at the recipe's own scale. */
    val targetServings: Int = savedStateHandle[Screen.CookMode.ARG_SERVINGS] ?: 0

    /** Ingredient multiplier for the chosen servings (1.0 when unscaled or the base is unknown). */
    fun scaleFor(recipe: RecipeOut): Double =
        if (targetServings > 0) targetServings.toDouble() / recipe.servings.coerceAtLeast(1) else 1.0

    private val _recipe = MutableStateFlow<UiState<RecipeOut>>(UiState.Loading)
    val recipe: StateFlow<UiState<RecipeOut>> = _recipe

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep

    private val _completedSteps = MutableStateFlow<Set<Int>>(emptySet())
    val completedSteps: StateFlow<Set<Int>> = _completedSteps

    private val _timer = MutableStateFlow<StepTimer?>(null)
    val timer: StateFlow<StepTimer?> = _timer

    /** One-shot: a timer just hit zero (the screen vibrates + shows which step). */
    private val _timerDone = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val timerDone: SharedFlow<Int> = _timerDone

    private var tickJob: Job? = null

    init {
        viewModelScope.launch {
            _recipe.value = try {
                UiState.Success(recipeRepository.getRecipe(recipeId).value)
            } catch (e: Exception) {
                UiState.Error(e.message ?: "Couldn't load the recipe")
            }
        }
    }

    fun goToStep(index: Int) {
        val steps = (_recipe.value as? UiState.Success)?.data?.steps ?: return
        _currentStep.value = index.coerceIn(0, (steps.size - 1).coerceAtLeast(0))
    }

    fun toggleStepDone(index: Int) {
        _completedSteps.value =
            if (index in _completedSteps.value) _completedSteps.value - index
            else _completedSteps.value + index
    }

    /** Mark the current step done and advance (the main "next" gesture). */
    fun completeAndAdvance() {
        val steps = (_recipe.value as? UiState.Success)?.data?.steps ?: return
        val index = _currentStep.value
        _completedSteps.value = _completedSteps.value + index
        if (index < steps.size - 1) _currentStep.value = index + 1
    }

    fun startTimer(stepOrder: Int, totalSeconds: Int) {
        val end = SystemClock.elapsedRealtime() + totalSeconds * 1000L
        _timer.value = StepTimer(stepOrder, totalSeconds, end, totalSeconds)
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                val current = _timer.value ?: break
                val remainingMs = current.endElapsedMs - SystemClock.elapsedRealtime()
                if (remainingMs <= 0) {
                    _timer.value = current.copy(remainingSeconds = 0, finished = true)
                    _timerDone.tryEmit(current.stepOrder)
                    break
                }
                _timer.value = current.copy(remainingSeconds = ((remainingMs + 999) / 1000).toInt())
                delay(250)
            }
        }
    }

    fun cancelTimer() {
        tickJob?.cancel()
        _timer.value = null
    }
}
