package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GokuDarkColorScheme = darkColorScheme(
    primary = ArcCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = ArcCyanContainer,
    onPrimaryContainer = ArcCyan,
    secondary = GokuAmber,
    onSecondary = ObsidianVoid,
    secondaryContainer = GokuAmberContainer,
    onSecondaryContainer = GokuAmberSoft,
    tertiary = EmeraldOnline,
    onTertiary = ObsidianVoid,
    tertiaryContainer = EmeraldContainer,
    onTertiaryContainer = EmeraldOnline,
    error = CrimsonEmergency,
    onError = TextPrimary,
    errorContainer = CrimsonContainer,
    onErrorContainer = CrimsonEmergency,
    background = ObsidianVoid,
    onBackground = TextPrimary,
    surface = DeepHudSurface,
    onSurface = TextPrimary,
    surfaceVariant = ElevatedHudCard,
    onSurfaceVariant = TextSecondary,
    outline = HudOutline,
    outlineVariant = HudOutlineSubtle
)

@Composable
fun GokuAgentTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GokuDarkColorScheme,
        typography = GokuTypography,
        content = content
    )
}
