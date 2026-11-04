package com.agentphone.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentphone.app.core.AppLanguage
import com.agentphone.app.domain.model.ThemeMode
import com.agentphone.app.platform.CaptureCoordinator
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.ShareInbox
import com.agentphone.app.platform.UiActionBus
import com.agentphone.app.ui.AppNavigation
import com.agentphone.app.ui.AppViewModel
import com.agentphone.app.ui.LocalWindowSizeClass
import com.agentphone.app.ui.components.AgentLogo
import com.agentphone.app.ui.host.CaptureHost
import com.agentphone.app.ui.host.IntentHost
import com.agentphone.app.ui.host.PermissionHost
import com.agentphone.app.ui.onboarding.OnboardingScreen
import com.agentphone.app.ui.theme.AgentPhoneTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private var newChatRequest by mutableStateOf(false)

    @Inject
    lateinit var permissions: PermissionCoordinator

    @Inject
    lateinit var capture: CaptureCoordinator

    @Inject
    lateinit var actions: UiActionBus

    @Inject
    lateinit var shareInbox: ShareInbox

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        newChatRequest = intent.getBooleanExtra(EXTRA_NEW_CHAT, false)
        shareInbox.accept(intent)
        setContent {
            val appViewModel: AppViewModel = hiltViewModel()
            val settings by appViewModel.settings.collectAsStateWithLifecycle()
            val sharedPayload by shareInbox.payload.collectAsStateWithLifecycle()
            val darkTheme = when (settings?.themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                null -> isSystemInDarkTheme()
            }
            val windowSizeClass = calculateWindowSizeClass(this)
            CompositionLocalProvider(LocalWindowSizeClass provides windowSizeClass) {
                AgentPhoneTheme(
                    darkTheme = darkTheme,
                    dynamicColor = settings?.dynamicColor ?: true,
                    amoled = settings?.amoled ?: false,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (settings == null) {
                            SplashScreen()
                        } else if (!settings!!.onboardingComplete) {
                            OnboardingScreen()
                        } else {
                            AppNavigation(
                                sharedPayload = sharedPayload,
                                onShareConsumed = shareInbox::consume,
                                startNewChat = newChatRequest,
                                onNewChatConsumed = { newChatRequest = false },
                            )
                        }
                    }
                    PermissionHost(permissions)
                    CaptureHost(capture)
                    IntentHost(actions)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newChatRequest = intent.getBooleanExtra(EXTRA_NEW_CHAT, false)
        shareInbox.accept(intent)
    }

    companion object {
        const val EXTRA_NEW_CHAT = "com.agentphone.app.extra.NEW_CHAT"
    }
}

@Composable
private fun SplashScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AgentLogo(size = 88)
            Spacer(Modifier.height(18.dp))
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}
