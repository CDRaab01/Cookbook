package com.cookbook.ui.navigation

/** All navigation routes. Args are appended as path segments where needed. */
sealed class Screen(val route: String) {
    data object Gate : Screen("gate")
    data object Login : Screen("login")
    data object Register : Screen("register")
    data object ForgotPassword : Screen("forgot_password")
    data object Recipes : Screen("recipes")
    data object Shopping : Screen("shopping")
    data object Discover : Screen("discover")
    data object Plan : Screen("plan")
    data object Settings : Screen("settings")
    data object RecipeDetail : Screen("recipe_detail") {
        const val ARG = "recipeId"
        val routeWithArg = "$route/{$ARG}"
        fun withId(id: String) = "$route/$id"
    }
    data object RecipeEdit : Screen("recipe_edit") {
        const val ARG = "recipeId"
        val routeWithArg = "$route?$ARG={$ARG}"
        fun withId(id: String?) = if (id == null) route else "$route?$ARG=$id"
    }
    data object CookMode : Screen("cook") {
        const val ARG = "recipeId"
        val routeWithArg = "$route/{$ARG}"
        fun withId(id: String) = "$route/$id"
    }
}
