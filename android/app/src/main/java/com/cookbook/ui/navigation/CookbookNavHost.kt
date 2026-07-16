package com.cookbook.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cookbook.data.local.TokenStore
import com.cookbook.ui.auth.AuthViewModel
import com.cookbook.ui.auth.ForgotPasswordScreen
import com.cookbook.ui.auth.LoginScreen
import com.cookbook.ui.auth.RegisterScreen
import com.cookbook.ui.cook.CookModeScreen
import com.cookbook.ui.discover.DiscoverScreen
import com.cookbook.ui.home.HomeScreen
import com.cookbook.ui.pantry.PantryConfirmScreen
import com.cookbook.ui.pantry.PantryScreen
import com.cookbook.ui.pantry.PantrySuggestionsScreen
import com.cookbook.ui.plan.PlanScreen
import com.cookbook.ui.recipe.RecipeDetailScreen
import com.cookbook.ui.recipe.RecipeEditScreen
import com.cookbook.ui.recipe.RecipeListScreen
import com.cookbook.ui.settings.SettingsScreen
import com.cookbook.ui.settings.StaplesEditorScreen
import com.cookbook.ui.shopping.ShoppingScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Resolves the auth gate: signed in → tabs, otherwise → login. */
@HiltViewModel
class GateViewModel @Inject constructor(
    private val tokenStore: TokenStore,
) : ViewModel() {
    suspend fun isSignedIn(): Boolean = tokenStore.currentAccessToken() != null
}

/** Launcher-shortcut targets (the `cookbook://shortcut/<target>` last path segment). */
private const val SHORTCUT_SHOPPING = "shopping"
private const val SHORTCUT_ADD_ITEM = "add-item"
private const val SHORTCUT_PANTRY_SCAN = "pantry-scan"

@Composable
fun CookbookNavHost(
    navController: NavHostController = rememberNavController(),
    shortcutTarget: String? = null,
    onShortcutHandled: () -> Unit = {},
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

    // Shared tab navigation (bottom-bar semantics) so Home's shortcuts switch tabs cleanly.
    val goTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // A tapped launcher shortcut must survive the auth gate: if the user is signed out it lands on
    // login first and the target is honored only once they reach the signed-in graph. Latch the
    // activity's transient value into saveable state, then clear the source so a config change or
    // warm re-launch doesn't re-fire it.
    var pendingShortcut by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(shortcutTarget) {
        if (shortcutTarget != null) {
            pendingShortcut = shortcutTarget
            onShortcutHandled()
        }
    }
    // One-shot signals threaded into the target tab's screen once we navigate there.
    var openAddItem by remember { mutableStateOf(false) }
    var autoScanPantry by remember { mutableStateOf(false) }

    // Honor the pending shortcut only after the auth gate resolves to the signed-in graph (Home is
    // the entry point in both the already-signed-in and just-logged-in paths).
    LaunchedEffect(currentRoute, pendingShortcut) {
        val target = pendingShortcut ?: return@LaunchedEffect
        if (currentRoute != Screen.Home.route) return@LaunchedEffect
        when (target) {
            SHORTCUT_SHOPPING -> goTab(Screen.Shopping.route)
            SHORTCUT_ADD_ITEM -> {
                openAddItem = true
                goTab(Screen.Shopping.route)
            }
            SHORTCUT_PANTRY_SCAN -> {
                autoScanPantry = true
                goTab(Screen.Pantry.route)
            }
        }
        pendingShortcut = null
    }

    // Forced logout (rejected refresh token anywhere in the app) bounces to login.
    val authViewModel: AuthViewModel = hiltViewModel()
    LaunchedEffect(Unit) {
        authViewModel.logoutEvents.collect {
            navController.navigate(Screen.Login.route) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (isTopLevel) {
                CookbookBottomBar(
                    currentRoute = currentRoute,
                    onNavigate = { destination -> goTab(destination.route) },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Gate.route,
            // Without this, each screen's own inner Scaffold/TopAppBar re-applies the same
            // system-bar insets a second time — the double-gap bug Plate/Spotter hit first.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable(Screen.Gate.route) {
                val gateViewModel: GateViewModel = hiltViewModel()
                LaunchedEffect(Unit) {
                    val target = if (gateViewModel.isSignedIn()) {
                        Screen.Home.route
                    } else {
                        Screen.Login.route
                    }
                    navController.navigate(target) {
                        popUpTo(Screen.Gate.route) { inclusive = true }
                    }
                }
            }

            composable(Screen.Login.route) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onNavigateToRegister = { navController.navigate(Screen.Register.route) },
                    onNavigateToForgotPassword = {
                        navController.navigate(Screen.ForgotPassword.route)
                    },
                )
            }
            composable(Screen.Register.route) {
                RegisterScreen(
                    onRegisterSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onNavigateToLogin = { navController.popBackStack() },
                )
            }
            composable(Screen.ForgotPassword.route) {
                ForgotPasswordScreen(
                    onResetSuccess = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    },
                    onNavigateToLogin = { navController.popBackStack() },
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    onOpenRecipe = { id -> navController.navigate(Screen.RecipeDetail.withId(id)) },
                    onGoToRecipes = { goTab(Screen.Recipes.route) },
                    onGoToShopping = { goTab(Screen.Shopping.route) },
                    onGoToPlan = { goTab(Screen.Plan.route) },
                )
            }
            composable(Screen.Recipes.route) {
                RecipeListScreen(
                    onRecipeClick = { id ->
                        navController.navigate(Screen.RecipeDetail.withId(id))
                    },
                    onAddRecipe = { navController.navigate(Screen.RecipeEdit.withId(null)) },
                    onDiscover = { navController.navigate(Screen.Discover.route) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                )
            }
            composable(
                Screen.RecipeDetail.routeWithArg,
                arguments = listOf(navArgument(Screen.RecipeDetail.ARG) { type = NavType.StringType }),
            ) {
                RecipeDetailScreen(
                    onBack = { navController.popBackStack() },
                    onEdit = { id -> navController.navigate(Screen.RecipeEdit.withId(id)) },
                    onDuplicated = { id ->
                        navController.navigate(Screen.RecipeDetail.withId(id))
                    },
                    onStartCooking = { id, servings ->
                        navController.navigate(Screen.CookMode.withId(id, servings))
                    },
                )
            }
            composable(
                Screen.RecipeEdit.routeWithArg,
                arguments = listOf(
                    navArgument(Screen.RecipeEdit.ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val editingExisting = entry.arguments?.getString(Screen.RecipeEdit.ARG) != null
                RecipeEditScreen(
                    onBack = { navController.popBackStack() },
                    onSaved = { id ->
                        if (editingExisting) {
                            // Detail is on the back stack beneath the editor; pop back and let
                            // its resume-reload show the fresh data.
                            navController.popBackStack()
                        } else {
                            navController.navigate(Screen.RecipeDetail.withId(id)) {
                                popUpTo(Screen.Recipes.route)
                            }
                        }
                    },
                )
            }
            composable(
                Screen.CookMode.routeWithArg,
                arguments = listOf(
                    navArgument(Screen.CookMode.ARG) { type = NavType.StringType },
                    navArgument(Screen.CookMode.ARG_SERVINGS) {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                ),
            ) {
                CookModeScreen(onExit = { navController.popBackStack() })
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onOpenStaples = { navController.navigate(Screen.StaplesEditor.route) },
                    onOpenAisleOrder = { navController.navigate(Screen.AisleOrder.route) },
                )
            }
            composable(Screen.Pantry.route) {
                PantryScreen(
                    onScanConfirm = { navController.navigate(Screen.PantryConfirm.route) },
                    onSuggestions = { navController.navigate(Screen.PantrySuggestions.route) },
                    onEditStaples = { navController.navigate(Screen.StaplesEditor.route) },
                    autoScan = autoScanPantry,
                    onAutoScanConsumed = { autoScanPantry = false },
                )
            }
            composable(Screen.PantryConfirm.route) {
                PantryConfirmScreen(
                    onBack = { navController.popBackStack() },
                    onConfirmed = { navController.popBackStack() },
                )
            }
            composable(Screen.PantrySuggestions.route) {
                PantrySuggestionsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenRecipe = { id -> navController.navigate(Screen.RecipeDetail.withId(id)) },
                    onImported = { id -> navController.navigate(Screen.RecipeDetail.withId(id)) },
                )
            }
            composable(Screen.StaplesEditor.route) {
                StaplesEditorScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.AisleOrder.route) {
                com.cookbook.ui.settings.AisleOrderScreen(onBack = { navController.popBackStack() })
            }
            composable(Screen.Shopping.route) {
                ShoppingScreen(
                    openAddItem = openAddItem,
                    onAddItemConsumed = { openAddItem = false },
                )
            }
            composable(Screen.Discover.route) {
                DiscoverScreen(
                    onBack = { navController.popBackStack() },
                    onImported = { id ->
                        navController.navigate(Screen.RecipeDetail.withId(id))
                    },
                    onOpenPhotoDraft = {
                        navController.navigate(Screen.RecipeEdit.withId(null))
                    },
                )
            }
            composable(Screen.Plan.route) {
                PlanScreen(
                    onOpenRecipe = { id -> navController.navigate(Screen.RecipeDetail.withId(id)) },
                )
            }
        }
    }
}
