package com.cookbook.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.cookbook.ui.discover.DiscoverScreen
import com.cookbook.ui.recipe.RecipeDetailScreen
import com.cookbook.ui.recipe.RecipeEditScreen
import com.cookbook.ui.recipe.RecipeListScreen
import com.cookbook.ui.settings.SettingsScreen
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

@Composable
fun CookbookNavHost(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

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
                    onNavigate = { destination ->
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Gate.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Screen.Gate.route) {
                val gateViewModel: GateViewModel = hiltViewModel()
                LaunchedEffect(Unit) {
                    val target = if (gateViewModel.isSignedIn()) {
                        Screen.Recipes.route
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
                        navController.navigate(Screen.Recipes.route) {
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
                        navController.navigate(Screen.Recipes.route) {
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

            composable(Screen.Recipes.route) {
                RecipeListScreen(
                    onRecipeClick = { id ->
                        navController.navigate(Screen.RecipeDetail.withId(id))
                    },
                    onAddRecipe = { navController.navigate(Screen.RecipeEdit.withId(null)) },
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
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onLoggedOut = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                )
            }
            composable(Screen.Shopping.route) {
                ShoppingScreen()
            }
            composable(Screen.Discover.route) {
                DiscoverScreen(
                    onImported = { id ->
                        navController.navigate(Screen.RecipeDetail.withId(id))
                    },
                )
            }
        }
    }
}
