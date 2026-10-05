package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Home : Screen("home", "Stream", Icons.Outlined.PlayArrow)
    object Receivers : Screen("receivers", "Receivers", Icons.Outlined.Wifi)
    object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)

    companion object {
        val items = listOf(Home, Receivers, Settings)
    }
}
