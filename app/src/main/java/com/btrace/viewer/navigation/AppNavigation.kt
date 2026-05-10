package com.btrace.viewer.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.btrace.viewer.ui.monitor.MonitorScreen
import com.btrace.viewer.ui.settings.SettingsScreen

sealed class Screen(val route: String) {
    // 监控页内部按 MonitoringState 分屏:IDLE 时渲染应用选择 UI、其它状态渲染事件流。
    // 这样"选应用 → 监控"这条任务流被收敛成一个目的地的状态机,杜绝了
    // 监控中用户还能切到应用页造成的流程外状态漂移。
    object Monitor : Screen("monitor")
    object Settings : Screen("settings")
}

@Composable
fun AppNavigation(
    navController: NavHostController
) {
    NavHost(navController = navController, startDestination = Screen.Monitor.route) {
        composable(Screen.Monitor.route) {
            MonitorScreen()
        }
        composable(Screen.Settings.route) {
            SettingsScreen()
        }
    }
}
