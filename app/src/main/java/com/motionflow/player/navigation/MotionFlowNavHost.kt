package com.motionflow.player.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.motionflow.player.feature.home.HomeScreen
import com.motionflow.player.feature.settings.SettingsScreen

/**
 * Hosts the MotionFlow navigation graph.
 *
 * Destinations receive lambdas instead of the [NavHostController] itself, so feature code stays
 * unaware of the graph and remains easy to preview and test.
 */
@Composable
fun MotionFlowNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = MotionFlowDestination.HOME.route,
        modifier = modifier,
    ) {
        composable(route = MotionFlowDestination.HOME.route) {
            HomeScreen(
                onOpenSettings = {
                    navController.navigate(MotionFlowDestination.SETTINGS.route)
                },
            )
        }
        composable(route = MotionFlowDestination.SETTINGS.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.navigateUp()
                },
            )
        }
    }
}
