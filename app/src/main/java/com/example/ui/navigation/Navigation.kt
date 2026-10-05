package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Home", Icons.Outlined.Home)
    object Receivers : Screen("receivers", "Receivers", Icons.Outlined.Podcasts)
    object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)

    companion object {
        val items = listOf(Home, Receivers, Settings)
    }
}
