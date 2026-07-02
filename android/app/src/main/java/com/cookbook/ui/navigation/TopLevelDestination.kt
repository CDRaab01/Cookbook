package com.cookbook.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.graphics.vector.ImageVector

/** The bottom-bar tabs: Recipes · Plan · Shopping · Discover. */
enum class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Recipes(Screen.Recipes.route, "Recipes", Icons.AutoMirrored.Outlined.MenuBook),
    Plan(Screen.Plan.route, "Plan", Icons.Outlined.CalendarMonth),
    Shopping(Screen.Shopping.route, "Shopping", Icons.Outlined.ShoppingCart),
    Discover(Screen.Discover.route, "Discover", Icons.Outlined.Search),
}
