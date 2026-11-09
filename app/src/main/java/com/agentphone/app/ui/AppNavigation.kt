package com.agentphone.app.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.agentphone.app.platform.SharedPayload
import com.agentphone.app.ui.share.ShareViewModel
import com.agentphone.app.ui.about.AboutScreen
import com.agentphone.app.ui.audit.AuditScreen
import com.agentphone.app.ui.autoreply.AutoReplyScreen
import com.agentphone.app.ui.away.AwayDraftsScreen
import com.agentphone.app.ui.away.AwayLogScreen
import com.agentphone.app.ui.away.AwayRulesScreen
import com.agentphone.app.ui.away.AwayScreen
import com.agentphone.app.ui.chat.ChatScreen
import com.agentphone.app.ui.chats.ConversationsScreen
import com.agentphone.app.ui.models.ModelPickerScreen
import com.agentphone.app.ui.providers.ProviderEditorScreen
import com.agentphone.app.ui.providers.ProvidersScreen
import com.agentphone.app.ui.settings.SettingsScreen
import com.agentphone.app.ui.telegram.TelegramScreen
import com.agentphone.app.ui.tools.ToolsScreen

object Routes {
    const val CHATS = "chats"
    const val CHAT = "chat/{chatId}"
    const val PROVIDERS = "providers"
    const val PROVIDER_EDITOR = "provider/{providerId}"
    const val MODELS = "models/{providerId}?chatId={chatId}"
    const val TOOLS = "tools"
    const val AUDIT = "audit"
    const val AUTO_REPLY = "auto_reply"
    const val AWAY = "away"
    const val AWAY_RULES = "away/rules"
    const val AWAY_DRAFTS = "away/drafts"
    const val AWAY_LOG = "away/log"
    const val TELEGRAM = "telegram"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun chat(id: String) = "chat/$id"
    fun providerEditor(id: String?) = "provider/${id ?: "new"}"
    fun models(providerId: String, chatId: String) = "models/$providerId?chatId=$chatId"
}

@Composable
fun AppNavigation(
    sharedPayload: SharedPayload? = null,
    onShareConsumed: () -> Unit = {},
    startNewChat: Boolean = false,
    onNewChatConsumed: () -> Unit = {},
    openRoute: String? = null,
    onRouteConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val shareViewModel: ShareViewModel = hiltViewModel()
    LaunchedEffect(sharedPayload) {
        if (sharedPayload != null) {
            shareViewModel.handle(sharedPayload)
            onShareConsumed()
        }
    }
    LaunchedEffect(startNewChat) {
        if (startNewChat) {
            shareViewModel.startNewChat()
            onNewChatConsumed()
        }
    }
    LaunchedEffect(Unit) {
        shareViewModel.openChat.collect { chatId ->
            navController.navigate(Routes.chat(chatId))
        }
    }
    LaunchedEffect(openRoute) {
        val route = openRoute ?: return@LaunchedEffect
        runCatching {
            navController.navigate(route) { launchSingleTop = true }
        }
        onRouteConsumed()
    }
    NavHost(
        navController = navController,
        startDestination = Routes.CHATS,
        enterTransition = { fadeIn(tween(180)) + slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(220)) },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(140)) + slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(220)) },
    ) {
        composable(Routes.CHATS) {
            ConversationsScreen(
                onOpenChat = { navController.navigate(Routes.chat(it)) },
                onProviders = { navController.navigate(Routes.PROVIDERS) },
                onTools = { navController.navigate(Routes.TOOLS) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            route = Routes.CHAT,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType }),
        ) {
            ChatScreen(
                onBack = { navController.popBackStack() },
                onOpenProviders = { navController.navigate(Routes.PROVIDERS) },
                onOpenModels = { providerId, chatId ->
                    navController.navigate(Routes.models(providerId, chatId))
                },
            )
        }
        composable(Routes.PROVIDERS) {
            ProvidersScreen(
                onBack = { navController.popBackStack() },
                onAdd = { navController.navigate(Routes.providerEditor(null)) },
                onEdit = { navController.navigate(Routes.providerEditor(it)) },
            )
        }
        composable(
            route = Routes.PROVIDER_EDITOR,
            arguments = listOf(navArgument("providerId") { type = NavType.StringType }),
        ) {
            ProviderEditorScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.MODELS,
            arguments = listOf(
                navArgument("providerId") { type = NavType.StringType },
                navArgument("chatId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) {
            ModelPickerScreen(
                onBack = { navController.popBackStack() },
                onSelected = { navController.popBackStack() },
            )
        }
        composable(Routes.TOOLS) {
            ToolsScreen(
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.AUDIT) {
            AuditScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AUTO_REPLY) {
            AutoReplyScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AWAY) {
            AwayScreen(
                onBack = { navController.popBackStack() },
                onRules = { navController.navigate(Routes.AWAY_RULES) },
                onDrafts = { navController.navigate(Routes.AWAY_DRAFTS) },
                onLog = { navController.navigate(Routes.AWAY_LOG) },
                onTelegram = { navController.navigate(Routes.TELEGRAM) },
            )
        }
        composable(Routes.AWAY_RULES) {
            AwayRulesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AWAY_DRAFTS) {
            AwayDraftsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.AWAY_LOG) {
            AwayLogScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TELEGRAM) {
            TelegramScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onProviders = { navController.navigate(Routes.PROVIDERS) },
                onTools = { navController.navigate(Routes.TOOLS) },
                onAudit = { navController.navigate(Routes.AUDIT) },
                onAutoReply = { navController.navigate(Routes.AUTO_REPLY) },
                onAway = { navController.navigate(Routes.AWAY) },
                onTelegram = { navController.navigate(Routes.TELEGRAM) },
                onAbout = { navController.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}
