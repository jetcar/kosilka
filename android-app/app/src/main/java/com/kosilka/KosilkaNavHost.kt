package com.kosilka

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.kosilka.feature.debug.DebugScreen
import com.kosilka.feature.history.HistoryScreen
import com.kosilka.feature.home.HomeScreen
import com.kosilka.feature.map.MapScreen
import com.kosilka.feature.schedule.ScheduleScreen
import com.kosilka.feature.zone.ZoneScreen

/**
 * Top-level navigation host placeholder.
 *
 * The full nav graph (HomeScreen, MapScreen, ZoneScreen, ScheduleScreen,
 * HistoryScreen) will be wired here in subsequent tasks.
 */
@Composable
fun KosilkaNavHost(
    navController: NavHostController = rememberNavController()
) {
    val startRoute = Routes.Home

    NavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        composable(Routes.Home) {
            HomeScreen(
                onOpenDebug = { navController.navigate(Routes.Debug) },
                onOpenMap = { navController.navigate(Routes.Map) },
                onOpenZone = { navController.navigate(Routes.Zone) },
                onOpenSchedule = { navController.navigate(Routes.Schedule) },
                onOpenHistory = { navController.navigate(Routes.History) }
            )
        }
        composable(Routes.Map) { MapScreen() }
        composable(Routes.Schedule) { ScheduleScreen() }
        composable(Routes.Zone) { ZoneScreen() }
        composable(Routes.History) { HistoryScreen() }
        composable(Routes.Debug) {
            DebugScreen()
        }
    }
}

private object Routes {
    const val Home = "home"
    const val Map = "map"
    const val Schedule = "schedule"
    const val Zone = "zone"
    const val History = "history"
    const val Debug = "debug"
}
