package com.oli.cortex.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.oli.cortex.ui.chat.ChatScreen
import com.oli.cortex.ui.home.HomeScreen
import com.oli.cortex.ui.memory.MemoryScreen
import com.oli.cortex.ui.project.ProjectDetailScreen
import com.oli.cortex.ui.project.ProjectEditScreen
import com.oli.cortex.ui.settings.ModelManagementScreen
import com.oli.cortex.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val PROJECT_DETAIL = "project/{projectId}"
    const val PROJECT_EDIT = "project/edit?projectId={projectId}"
    const val CHAT = "chat/{chatId}"
    const val NEW_CHAT = "chat/new/{projectId}?quickActionId={quickActionId}"
    const val MEMORY = "memory/{projectId}"
    const val SETTINGS = "settings"
    const val MODEL_MANAGEMENT = "settings/model"

    fun projectDetail(projectId: Long) = "project/$projectId"
    fun projectEdit(projectId: Long? = null) = "project/edit?projectId=${projectId ?: -1}"
    fun chat(chatId: Long) = "chat/$chatId"
    fun newChat(projectId: Long, quickActionId: Long? = null) =
        "chat/new/$projectId?quickActionId=${quickActionId ?: -1}"
    fun memory(projectId: Long) = "memory/$projectId"
}

@Composable
fun CortexNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onProjectClick = { navController.navigate(Routes.projectDetail(it)) },
                onNewProject = { navController.navigate(Routes.projectEdit()) },
                onNewChat = { projectId -> navController.navigate(Routes.newChat(projectId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(
            Routes.PROJECT_DETAIL,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) {
            ProjectDetailScreen(
                onNavigateBack = { navController.popBackStack() },
                onEditProject = { id -> navController.navigate(Routes.projectEdit(id)) },
                onOpenChat = { chatId -> navController.navigate(Routes.chat(chatId)) },
                onNewChat = { projectId, quickActionId ->
                    navController.navigate(Routes.newChat(projectId, quickActionId))
                },
                onOpenMemory = { projectId -> navController.navigate(Routes.memory(projectId)) }
            )
        }

        composable(
            Routes.PROJECT_EDIT,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType; defaultValue = -1L })
        ) {
            ProjectEditScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.NEW_CHAT,
            arguments = listOf(
                navArgument("projectId") { type = NavType.LongType },
                navArgument("quickActionId") { type = NavType.LongType; defaultValue = -1L }
            )
        ) {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            Routes.MEMORY,
            arguments = listOf(navArgument("projectId") { type = NavType.LongType })
        ) {
            MemoryScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onModelManagement = { navController.navigate(Routes.MODEL_MANAGEMENT) }
            )
        }

        composable(Routes.MODEL_MANAGEMENT) {
            ModelManagementScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
