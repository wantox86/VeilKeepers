package com.veilkeepers.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * VeilKeepers brand palette (spec.md §27/§28): "behind the veil" — candlelight
 * amber on a near-black violet for dark mode, and a warm cream/ink pairing for
 * light mode. Both schemes keep body text at or above WCAG AA (4.5:1) against
 * their backgrounds.
 *
 * Dynamic color (Material You) is intentionally NOT the source of these values
 * — the brand identity wins (Sprint 9 decision #1).
 */

// ---- Dark: near-black violet + candlelight amber ------------------------
internal val VeilDarkBackground = Color(0xFF121019)
internal val VeilDarkSurface = Color(0xFF1A1723)
internal val VeilDarkSurfaceVariant = Color(0xFF262130)
internal val VeilDarkSurfaceContainer = Color(0xFF201C2B)
internal val VeilDarkOnSurface = Color(0xFFE7E1F2)
internal val VeilDarkOnSurfaceVariant = Color(0xFFB3ABC7)
internal val VeilDarkOutline = Color(0xFF4A4358)
internal val VeilDarkOutlineVariant = Color(0xFF332E3D)
internal val VeilDarkPrimary = Color(0xFFE8B04B)
internal val VeilDarkOnPrimary = Color(0xFF2B1D00)
internal val VeilDarkPrimaryContainer = Color(0xFF4A3410)
internal val VeilDarkOnPrimaryContainer = Color(0xFFF3D69A)
internal val VeilDarkSecondary = Color(0xFFCDBBD8)
internal val VeilDarkOnSecondary = Color(0xFF2F2438)
internal val VeilDarkTertiary = Color(0xFF9FB6C8)
internal val VeilDarkError = Color(0xFFF2A0A0)
internal val VeilDarkErrorContainer = Color(0xFF4A1F1F)
internal val VeilDarkOnErrorContainer = Color(0xFFFFD9D9)

// ---- Light: warm cream + ink violet + deep amber ------------------------
// Primary is a deep amber so amber-on-light text/buttons clear AA; the bright
// candlelight amber lives on as the dark-mode primary and as a container tone.
internal val VeilLightBackground = Color(0xFFFBF7F0)
internal val VeilLightSurface = Color(0xFFFFFDF8)
internal val VeilLightSurfaceVariant = Color(0xFFF0E9DE)
internal val VeilLightSurfaceContainer = Color(0xFFF5EFE4)
internal val VeilLightOnSurface = Color(0xFF1C1A22)
internal val VeilLightOnSurfaceVariant = Color(0xFF4A4453)
internal val VeilLightOutline = Color(0xFFC9C2D4)
internal val VeilLightOutlineVariant = Color(0xFFE4DCCC)
internal val VeilLightPrimary = Color(0xFF7A4E00)
internal val VeilLightOnPrimary = Color(0xFFFFFFFF)
internal val VeilLightPrimaryContainer = Color(0xFFF6DCA6)
internal val VeilLightOnPrimaryContainer = Color(0xFF3A2600)
internal val VeilLightSecondary = Color(0xFF5C4A6B)
internal val VeilLightOnSecondary = Color(0xFFFFFFFF)
internal val VeilLightTertiary = Color(0xFF33526B)
internal val VeilLightError = Color(0xFF9A2B22)
internal val VeilLightErrorContainer = Color(0xFFFBE1DC)
internal val VeilLightOnErrorContainer = Color(0xFF43100B)

/** Adaptive launcher background tone (mirrored in res/values/colors.xml). */
internal val VeilLauncherBackground = Color(0xFF121019)
