package com.cookbook.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

/** The bottom-bar tabs: Home · Recipes · Plan · Shopping · Pantry. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home(Screen.Home.route, "Home", Icons.Outlined.Home),
    Recipes(Screen.Recipes.route, "Recipes", Icons.AutoMirrored.Outlined.MenuBook),
    Plan(Screen.Plan.route, "Plan", Icons.Outlined.CalendarMonth),
    Shopping(Screen.Shopping.route, "Shopping", Icons.Outlined.ShoppingCart),
    Pantry(Screen.Pantry.route, "Pantry", Icons.Outlined.Kitchen),
}
