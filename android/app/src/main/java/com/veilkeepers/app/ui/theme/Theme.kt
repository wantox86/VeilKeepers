package com.veilkeepers.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The "behind the veil" dark scheme: near-black violet surfaces lit by
 * candlelight amber. Container tones are pinned explicitly so TopAppBar /
 * Card elevation reads correctly instead of falling back to tonal defaults.
 */
private val DarkColors = darkColorScheme(
    primary = VeilDarkPrimary,
    onPrimary = VeilDarkOnPrimary,
    primaryContainer = VeilDarkPrimaryContainer,
    onPrimaryContainer = VeilDarkOnPrimaryContainer,
    inversePrimary = VeilLightPrimary,
    secondary = VeilDarkSecondary,
    onSecondary = VeilDarkOnSecondary,
    secondaryContainer = VeilDarkSurfaceVariant,
    onSecondaryContainer = VeilDarkOnSurface,
    tertiary = VeilDarkTertiary,
    onTertiary = VeilDarkOnSecondary,
    background = VeilDarkBackground,
    onBackground = VeilDarkOnSurface,
    surface = VeilDarkSurface,
    onSurface = VeilDarkOnSurface,
    surfaceVariant = VeilDarkSurfaceVariant,
    onSurfaceVariant = VeilDarkOnSurfaceVariant,
    surfaceContainerLowest = VeilDarkBackground,
    surfaceContainerLow = VeilDarkSurface,
    surfaceContainer = VeilDarkSurfaceContainer,
    surfaceContainerHigh = VeilDarkSurfaceVariant,
    surfaceContainerHighest = VeilDarkSurfaceVariant,
    inverseSurface = VeilDarkOnSurface,
    inverseOnSurface = VeilDarkBackground,
    error = VeilDarkError,
    onError = VeilDarkErrorContainer,
    errorContainer = VeilDarkErrorContainer,
    onErrorContainer = VeilDarkOnErrorContainer,
    outline = VeilDarkOutline,
    outlineVariant = VeilDarkOutlineVariant,
    scrim = Color.Black,
)

/**
 * The light counterpart: warm cream paper, ink-violet text and a deep amber
 * primary so amber-on-light clears WCAG AA. The bright candlelight amber
 * survives as the dark-mode primary and as the light primary container.
 */
private val LightColors = lightColorScheme(
    primary = VeilLightPrimary,
    onPrimary = VeilLightOnPrimary,
    primaryContainer = VeilLightPrimaryContainer,
    onPrimaryContainer = VeilLightOnPrimaryContainer,
    inversePrimary = VeilDarkPrimary,
    secondary = VeilLightSecondary,
    onSecondary = VeilLightOnSecondary,
    secondaryContainer = VeilLightSurfaceVariant,
    onSecondaryContainer = VeilLightOnSurface,
    tertiary = VeilLightTertiary,
    onTertiary = VeilLightOnPrimary,
    background = VeilLightBackground,
    onBackground = VeilLightOnSurface,
    surface = VeilLightSurface,
    onSurface = VeilLightOnSurface,
    surfaceVariant = VeilLightSurfaceVariant,
    onSurfaceVariant = VeilLightOnSurfaceVariant,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = VeilLightSurface,
    surfaceContainer = VeilLightSurfaceContainer,
    surfaceContainerHigh = VeilLightSurfaceVariant,
    surfaceContainerHighest = VeilLightSurfaceVariant,
    inverseSurface = VeilLightOnSurface,
    inverseOnSurface = VeilLightBackground,
    error = VeilLightError,
    onError = VeilLightOnPrimary,
    errorContainer = VeilLightErrorContainer,
    onErrorContainer = VeilLightOnErrorContainer,
    outline = VeilLightOutline,
    outlineVariant = VeilLightOutlineVariant,
    scrim = Color.Black,
)

/**
 * Applies the VeilKeepers brand theme: our own palette + tuned typography +
 * moderate shapes. [dynamicColor] (Material You) is available but defaults to
 * OFF — the brand identity wins on both light and dark (Sprint 9 decision #1).
 *
 * @param darkTheme follows the system setting by default.
 */
@Composable
fun VeilKeepersTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = VeilTypography,
        shapes = VeilShapes,
        content = content,
    )
}
