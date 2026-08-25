package com.hermes.node.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hermes.node.ui.screens.DashboardScreen
import com.hermes.node.ui.screens.LogsScreen
import com.hermes.node.ui.screens.SettingsScreen
import com.hermes.node.ui.theme.DarkBorder
import com.hermes.node.ui.theme.DarkSurface
import com.hermes.node.ui.theme.HermesCyan
import com.hermes.node.viewmodel.ServerViewModel

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hermes.node.engine.BootstrapExtractor

@Composable
fun HermesApp(
    navController: NavHostController = rememberNavController(),
    context: Context = LocalContext.current,
    viewModel: ServerViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val extractor = BootstrapExtractor(context.applicationContext)
                return ServerViewModel(extractor) as T
            }
        }
    )
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                Screen.items.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationBarItem(
                        icon = { Icon(imageVector = screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = DarkSurface,
                            selectedTextColor = HermesCyan,
                            indicatorColor = HermesCyan,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    state = uiState,
                    onToggleServer = viewModel::onToggleServer,
                    onRetryBootstrap = viewModel::triggerBootstrap,
                    onRepairRuntime = viewModel::onRepairRuntime
                )
            }
            composable(Screen.Logs.route) {
                LogsScreen(
                    logs = uiState.logs,
                    onClearLogs = viewModel::onClearLogs
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    state = uiState,
                    onUpdateProvider = viewModel::onUpdateProvider,
                    onUpdateApiKey = viewModel::onUpdateApiKey,
                    onUpdateTelegramToken = viewModel::onUpdateTelegramToken,
                    onUpdateCustomModel = viewModel::onUpdateCustomModel,
                    onUpdateCustomBaseUrl = viewModel::onUpdateCustomBaseUrl,
                    onUpdateAutoStart = viewModel::onUpdateAutoStart,
                    onUpdatePublicTunnel = viewModel::onUpdatePublicTunnel
                )
            }
        }
    }
}
