package com.veilkeepers.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * VeilKeepers shapes (spec.md §27): deliberately moderate corner radii so the
 * vault reads as crisp and "notebook-like" rather than the pill-round default
 * Material sample look. Buttons and cards share the same restrained scale.
 */
internal val VeilShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp),
)
