package com.agentphone.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightScheme = lightColorScheme(
    primary = Aqua40,
    onPrimary = Color.White,
    primaryContainer = Aqua90,
    onPrimaryContainer = Aqua10,
    inversePrimary = Aqua80,
    secondary = Color(0xFF4A635E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,
    surfaceVariant = Color(0xFFDCE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceTint = Aqua40,
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    error = Error40,
    onError = Color.White,
    errorContainer = Error90,
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    scrim = Color.Black,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD7DBD9),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF1F5F3),
    surfaceContainer = Color(0xFFEBEFED),
    surfaceContainerHigh = Color(0xFFE5E9E7),
    surfaceContainerHighest = Color(0xFFDFE4E1),
)

private val DarkScheme = darkColorScheme(
    primary = Aqua70,
    onPrimary = Aqua10,
    primaryContainer = Aqua30,
    onPrimaryContainer = Aqua90,
    inversePrimary = Aqua40,
    secondary = Color(0xFFB1CCC5),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF324B46),
    onSecondaryContainer = Color(0xFFCCE8E1),
    tertiary = Amber70,
    onTertiary = Amber10,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    background = Neutral6,
    onBackground = Neutral90,
    surface = Neutral6,
    onSurface = Neutral90,
    surfaceVariant = Neutral17,
    onSurfaceVariant = Neutral70,
    surfaceTint = Aqua70,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    error = Error80,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Error90,
    outline = Color(0xFF89938F),
    outlineVariant = Neutral30,
    scrim = Color.Black,
    surfaceBright = Neutral20,
    surfaceDim = Neutral6,
    surfaceContainerLowest = Color(0xFF050607),
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral20,
)

private val AmoledScheme = DarkScheme.copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF070808),
    surfaceContainer = Color(0xFF0C0E10),
    surfaceContainerHigh = Color(0xFF121517),
    surfaceContainerHighest = Color(0xFF181C1E),
)

@Composable
fun AgentPhoneTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    amoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        darkTheme && amoled -> AmoledScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgentTypography,
        shapes = AgentShapes,
        content = content,
    )
}
