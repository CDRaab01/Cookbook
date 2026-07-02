package com.cookbook.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.cookbook.ui.theme.CookbookTheme

/**
 * PULSE-flavored bottom navigation: flat panel surface, heat-channel selection. Only shown on the
 * top-level tabs (the nav host hides it on auth + detail screens).
 */
@Composable
fun CookbookBottomBar(
    currentRoute: String?,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val colors = CookbookTheme.colors
    NavigationBar(containerColor = colors.panel) {
        TopLevelDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.heat.base,
                    selectedTextColor = colors.heat.base,
                    indicatorColor = colors.heat.dim,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
