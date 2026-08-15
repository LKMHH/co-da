package com.coda.workbench.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

// 圆角上限 8dp（视觉稿 §1.3）；状态标签圆角 6dp（§1.4 small 槽位）
private val CodaShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
)

@Composable
fun CodaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = CodaTypography,
        shapes = CodaShapes,
        content = content,
    )
}
