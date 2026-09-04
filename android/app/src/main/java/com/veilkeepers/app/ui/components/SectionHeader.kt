package com.veilkeepers.app.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

/**
 * Wide-tracked uppercase eyebrow that introduces a section. Marked as a
 * heading in the semantics tree so TalkBack users can jump between sections
 * (Sprint 9 accessibility pass). Canonical replacement for the old inline
 * SectionLabel scattered through the vault screens.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 3.sp,
        color = MaterialTheme.colorScheme.primary,
    )
}
