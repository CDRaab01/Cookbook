package com.cookbook.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cookbook.data.local.TokenStore
import com.cookbook.ui.auth.AuthViewModel
import com.cookbook.ui.auth.ForgotPasswordScreen
import com.cookbook.ui.auth.LoginScreen
import com.cookbook.ui.auth.RegisterScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import design.pulse.ui.components.EmptyState
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

            // Top-level tabs. Placeholder bodies until their phases land (CLAUDE.md §7).
            composable(Screen.Recipes.route) {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "Your recipe book is empty",
                    subtitle = "Recipes arrive in Phase 2.",
                )
            }
            composable(Screen.Shopping.route) {
                EmptyState(
                    icon = Icons.Outlined.ShoppingCart,
                    title = "Nothing on the list",
                    subtitle = "The shopping list arrives in Phase 3.",
                )
            }
            composable(Screen.Discover.route) {
                EmptyState(
                    icon = Icons.Outlined.Search,
                    title = "Discover recipes",
                    subtitle = "External search arrives in Phase 5.",
                )
            }
        }
    }
}
