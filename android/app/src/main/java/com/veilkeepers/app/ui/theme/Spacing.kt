package com.veilkeepers.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * VeilKeepers spacing tokens (spec.md §27): one small ladder used everywhere
 * so gutters, card padding and section rhythm stay consistent across screens
 * instead of ad-hoc magic numbers.
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}
