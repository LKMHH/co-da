package com.coda.workbench.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = CodaActionPrimary,
    onPrimary = Color.White,
    primaryContainer = CodaActionContainer,
    onPrimaryContainer = CodaTextPrimary,
    secondary = CodaStatusInfo,
    onSecondary = Color.White,
    secondaryContainer = CodaActionContainer,
    onSecondaryContainer = CodaTextPrimary,
    tertiary = CodaActionPrimary,
    onTertiary = Color.White,
    background = CodaSurfacePage,
    onBackground = CodaTextPrimary,
    surface = CodaSurfaceContent,
    onSurface = CodaTextPrimary,
    surfaceVariant = CodaSurfacePage,
    onSurfaceVariant = CodaTextSecondary,
    outline = CodaBorderDefault,
    outlineVariant = CodaBorderDefault,
    error = CodaStatusDanger,
    onError = Color.White,
)

// 深色色板冻结稿未定义（视觉稿 §19.2 待技术确认），暂保持最小可用：
private val DarkColors = darkColorScheme(
    primary = CodaActionContainer,
)

@Composable
fun CodaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = CodaTypography,
        content = content,
    )
}
