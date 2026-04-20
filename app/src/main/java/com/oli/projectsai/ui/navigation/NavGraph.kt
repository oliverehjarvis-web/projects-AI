package com.oli.projectsai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.oli.projectsai.ui.chat.ChatScreen
import com.oli.projectsai.ui.home.HomeScreen
import com.oli.projectsai.ui.memory.MemoryScreen
import com.oli.projectsai.ui.privacy.PinSetupScreen
import com.oli.projectsai.ui.privacy.PrivacyUnlockScreen
import com.oli.projectsai.ui.privacy.PrivateGateScreen
import com.oli.projectsai.ui.privacy.PrivateProjectsScreen
import com.oli.projectsai.ui.project.ProjectDetailScreen
import com.oli.projectsai.ui.project.ProjectEditScreen
import com.oli.projectsai.ui.settings.GlobalContextScreen
import com.oli.projectsai.ui.settings.ModelManagementScreen
import com.oli.projectsai.ui.settings.SettingsScreen
import com.oli.projectsai.ui.transcription.TranscriptionScreen

object Routes {
    const val HOME = "home"
    const val PROJECT_DETAIL = "project/{projectId}"
    const val PROJECT_EDIT = "project/edit?projectId={projectId}"
    const val CHAT = "chat/{chatId}"
    const val NEW_CHAT = "chat/new/{projectId}?quickActionId={quickActionId}"
    const val MEMORY = "memory/{projectId}"
    const val SETTINGS = "settings"
    const val MODEL_MANAGEMENT = "settings/model"
    const val GLOBAL_CONTEXT = "settings/global-context"
    const val TRANSCRIPTION = "transcription"
    const val PRIVATE_GATE = "private/gate"
    const val PRIVATE_SETUP = "private/setup"
    const val PRIVATE_UNLOCK = "private/unlock"
    const val PRIVATE_PROJECTS = "private/projects"

    fun projectDetail(projectId: Long) = "project/$projectId"
    fun projectEdit(projectId: Long? = null) = "project/edit?projectId=${projectId ?: -1}"
    fun chat(chatId: Long) = "chat/$chatId"
    fun newChat(projectId: Long, quickActionId: Long? = null) =
        "chat/new/$projectId?quickActionId=${quickActionId ?: -1}"
    fun memory(projectId: Long) = "memory/$projectId"
}

@Composable
fun ProjectsAINavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onProjectClick = { navController.navigate(Routes.projectDetail(it)) },
                onNewProject = { navController.navigate(Routes.projectEdit()) },
                onNewChat = { projectId -> navController.navigate(Routes.newChat(projectId)) },
                onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                onTranscribeClick = { navController.navigate(Routes.TRANSCRIPTION) }
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
                onModelManagement = { navController.navigate(Routes.MODEL_MANAGEMENT) },
                onGlobalContext = { navController.navigate(Routes.GLOBAL_CONTEXT) },
                onPrivate = { navController.navigate(Routes.PRIVATE_GATE) }
            )
        }

        composable(Routes.PRIVATE_GATE) {
            PrivateGateScreen(
                onNeedSetup = {
                    navController.navigate(Routes.PRIVATE_SETUP) {
                        popUpTo(Routes.PRIVATE_GATE) { inclusive = true }
                    }
                },
                onNeedUnlock = {
                    navController.navigate(Routes.PRIVATE_UNLOCK) {
                        popUpTo(Routes.PRIVATE_GATE) { inclusive = true }
                    }
                },
                onAlreadyUnlocked = {
                    navController.navigate(Routes.PRIVATE_PROJECTS) {
                        popUpTo(Routes.PRIVATE_GATE) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PRIVATE_SETUP) {
            PinSetupScreen(
                onNavigateBack = { navController.popBackStack() },
                onSetupComplete = {
                    navController.navigate(Routes.PRIVATE_PROJECTS) {
                        popUpTo(Routes.PRIVATE_SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PRIVATE_UNLOCK) {
            PrivacyUnlockScreen(
                onNavigateBack = { navController.popBackStack() },
                onUnlocked = {
                    navController.navigate(Routes.PRIVATE_PROJECTS) {
                        popUpTo(Routes.PRIVATE_UNLOCK) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.PRIVATE_PROJECTS) {
            PrivateProjectsScreen(
                onNavigateBack = { navController.popBackStack() },
                onLocked = {
                    navController.popBackStack(Routes.SETTINGS, inclusive = false)
                },
                onProjectClick = { navController.navigate(Routes.projectDetail(it)) },
                onNewProject = { navController.navigate(Routes.projectEdit()) },
                onNewChat = { projectId -> navController.navigate(Routes.newChat(projectId)) }
            )
        }

        composable(Routes.MODEL_MANAGEMENT) {
            ModelManagementScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.GLOBAL_CONTEXT) {
            GlobalContextScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.TRANSCRIPTION) {
            TranscriptionScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToModelManagement = { navController.navigate(Routes.MODEL_MANAGEMENT) }
            )
        }
    }
}
