package com.coda.workbench.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = CodaBlue,
    onPrimary = CodaSurface,
    primaryContainer = CodaBlueContainer,
    onPrimaryContainer = CodaInk,
    surface = CodaSurface,
    background = CodaSurface,
)

private val DarkColors = darkColorScheme(
    primary = ColorTokens.DarkPrimary,
)

private object ColorTokens {
    val DarkPrimary = CodaBlueContainer
}

@Composable
fun CodaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = CodaTypography,
        content = content,
    )
}
