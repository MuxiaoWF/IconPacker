package com.muxiao.iconpacker.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.muxiao.iconpacker.ui.build.BuildScreen
import com.muxiao.iconpacker.ui.editor.IconEditorScreen
import com.muxiao.iconpacker.ui.editor.IconEditorTarget
import com.muxiao.iconpacker.ui.mapping.MappingScreen
import com.muxiao.iconpacker.ui.packlist.PackListScreen
import com.muxiao.iconpacker.ui.settings.SettingsScreen
import com.muxiao.iconpacker.ServiceLocator

/**
 * 应用导航图（单 Activity + Navigation Compose）。
 *
 * 路由：
 * - home                    项目列表（FR-02）
 * - settings                全局默认值设置
 * - mapping/{packId}        映射工作台 + 图标导入 + 图标库（FR-03 / FR-04）
 * - editor/{packId}/{slot}  单图标编辑（FR-05）
 * - build/{packId}          打包与安装引导（FR-06 / FR-07）
 */
@Composable
fun IconPackerApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            PackListScreen(
                onOpenPack = { packId -> navController.navigate("mapping/$packId") },
                onOpenSettings = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = ServiceLocator.settings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "mapping/{packId}",
            arguments = listOf(navArgument("packId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val packId = backStackEntry.arguments?.getLong("packId") ?: 0L
            MappingScreen(
                packId = packId,
                onBuild = { navController.navigate("build/$packId") },
                onBack = { navController.popBackStack() },
                onEditIcon = { slot -> navController.navigate("editor/$packId/$slot") },
            )
        }
        composable(
            route = "editor/{packId}/{slot}",
            arguments = listOf(
                navArgument("packId") { type = NavType.LongType },
                navArgument("slot") { type = NavType.IntType },
            ),
        ) { backStackEntry ->
            val packId = backStackEntry.arguments?.getLong("packId") ?: 0L
            val slot = backStackEntry.arguments?.getInt("slot") ?: 0
            IconEditorScreen(
                target = IconEditorTarget.Slot(packId, slot),
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = "build/{packId}",
            arguments = listOf(navArgument("packId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val packId = backStackEntry.arguments?.getLong("packId") ?: 0L
            BuildScreen(
                packId = packId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
