package com.veilkeepers.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.veilkeepers.app.R
import com.veilkeepers.app.security.AutoLockPolicy
import com.veilkeepers.app.ui.components.EmptyState
import com.veilkeepers.app.ui.components.SectionHeader
import com.veilkeepers.app.ui.components.VaultItemRow
import com.veilkeepers.app.ui.components.VaultTopBar
import com.veilkeepers.app.ui.theme.Spacing
import com.veilkeepers.app.ui.theme.VeilSerif
import com.veilkeepers.app.vault.VaultUiState
import com.veilkeepers.app.vault.search.SearchEngine
import com.veilkeepers.app.vault.search.SearchUiState

/**
 * Home screen (spec.md §18): a Scaffold with the brand top bar (settings +
 * lock actions), a "+" FAB for a new item (§18.3), a two-column category grid
 * with item counts, a Recent list, dismissible warning banners, and a local
 * search entry point. Sprint 7: a non-blank [searchQuery] cross-fades the
 * grid/recents for results matched LOCALLY over the decrypted items — the query
 * never leaves the process (docs/security/local-search.md).
 * Stateless — all data in, all events out.
 */
@Composable
fun VaultHomeScreen(
    state: VaultUiState.Loaded,
    seedWarning: String?,
    autoLockPolicy: AutoLockPolicy,
    biometricEnabled: Boolean,
    biometricSettingAvailable: Boolean,
    settingsNotice: String?,
    searchQuery: String,
    searchState: SearchUiState,
    onSearchQueryChange: (String) -> Unit,
    onAutoLockPolicyChange: (AutoLockPolicy) -> Unit,
    onEnableBiometric: () -> Unit,
    onDisableBiometric: () -> Unit,
    onOpenCategory: (categoryId: Long?) -> Unit,
    onOpenItem: (itemId: Long) -> Unit,
    onNewItem: () -> Unit,
    onCreateCategory: (name: String) -> Unit,
    onLockAndSignOut: () -> Unit,
    onDismissHasMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewCategory by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var seedWarningDismissed by remember { mutableStateOf(false) }
    val uncategorizedCount = state.items.count { it.categoryId == null }
    val searching = searchQuery.trim().isNotEmpty()

    val uncategorizedLabel = stringResource(R.string.label_uncategorized)
    val settingsLabel = stringResource(R.string.home_settings)
    val lockLabel = stringResource(R.string.home_lock_sign_out)
    val addItemLabel = stringResource(R.string.cd_add_item)
    val clearSearchLabel = stringResource(R.string.cd_clear_search)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            VaultTopBar(
                title = stringResource(R.string.brand_title),
                subtitle = stringResource(R.string.home_subtitle),
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = settingsLabel,
                        )
                    }
                    IconButton(onClick = onLockAndSignOut) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = lockLabel,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewItem,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(imageVector = Icons.Outlined.Add, contentDescription = addItemLabel)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = Spacing.lg),
        ) {
            val showHasMore = state.hasMoreWarning && !state.hasMoreDismissed
            AnimatedVisibility(visible = showHasMore) {
                WarningBanner(
                    text = stringResource(R.string.home_has_more_warning),
                    onDismiss = onDismissHasMore,
                )
            }
            val showSeed = seedWarning != null && !seedWarningDismissed
            AnimatedVisibility(visible = showSeed) {
                if (seedWarning != null) {
                    WarningBanner(
                        text = seedWarning,
                        onDismiss = { seedWarningDismissed = true },
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        // Decorative: the placeholder names the field.
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = clearSearchLabel,
                            )
                        }
                    }
                },
                supportingText = { Text(stringResource(R.string.home_search_local_note)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
            )
            Spacer(Modifier.height(Spacing.sm))

            Crossfade(
                targetState = searching,
                modifier = Modifier.weight(1f),
                label = "homeContent",
            ) { isSearching ->
                if (isSearching) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SearchResultsSection(
                            searchState = searchState,
                            categoryNameFor = { id -> categoryNameFor(state, id, uncategorizedLabel) },
                            onOpenItem = onOpenItem,
                        )
                        Spacer(Modifier.height(96.dp))
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SectionHeader(stringResource(R.string.section_categories))
                        Spacer(Modifier.height(Spacing.sm))

                        val cards = state.categories + null // trailing null = Uncategorized card
                        cards.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm + 4.dp),
                            ) {
                                pair.forEach { category ->
                                    CategoryCard(
                                        modifier = Modifier.weight(1f),
                                        name = category?.name ?: uncategorizedLabel,
                                        itemCount = category?.itemCount ?: uncategorizedCount,
                                        subdued = category == null,
                                        onClick = { onOpenCategory(category?.id) },
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(Spacing.sm + 4.dp))
                        }

                        if (state.categories.isEmpty()) {
                            Text(
                                text = stringResource(R.string.home_no_categories),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { showNewCategory = true }) {
                            Icon(
                                imageVector = Icons.Outlined.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(R.string.home_new_category))
                        }

                        Spacer(Modifier.height(Spacing.md))
                        SectionHeader(stringResource(R.string.section_recent))
                        Spacer(Modifier.height(Spacing.sm))
                        if (state.recents.isEmpty()) {
                            EmptyState(
                                icon = Icons.Outlined.Inbox,
                                title = stringResource(R.string.home_no_recents),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            state.recents.forEach { item ->
                                VaultItemRow(
                                    item = item,
                                    meta = categoryNameFor(state, item.categoryId, uncategorizedLabel),
                                    onClick = { onOpenItem(item.id) },
                                )
                                Spacer(Modifier.height(Spacing.sm))
                            }
                        }
                        Spacer(Modifier.height(96.dp))
                    }
                }
            }
        }
    }

    if (showNewCategory) {
        CategoryNameDialog(
            title = stringResource(R.string.home_new_category),
            initialName = "",
            onSubmit = onCreateCategory,
            onDismiss = { showNewCategory = false },
        )
    }

    if (showSettings) {
        VaultSettingsDialog(
            autoLockPolicy = autoLockPolicy,
            biometricEnabled = biometricEnabled,
            biometricSettingAvailable = biometricSettingAvailable,
            notice = settingsNotice,
            onAutoLockPolicyChange = onAutoLockPolicyChange,
            onBiometricToggle = { enable ->
                if (enable) onEnableBiometric() else onDisableBiometric()
            },
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * Sprint 6 vault settings (spec.md §24 + §25, spec-1.md §B.8/§B.10):
 * auto-lock policy picker (default Immediately) and the opt-in biometric
 * toggle. Enabling biometrics runs the enrollment prompt with the in-memory
 * VK; disabling wipes the blob + Keystore alias. The explicit "Lock & sign
 * out" action stays in the home top bar, unchanged.
 */
@Composable
internal fun VaultSettingsDialog(
    autoLockPolicy: AutoLockPolicy,
    biometricEnabled: Boolean,
    biometricSettingAvailable: Boolean,
    notice: String?,
    onAutoLockPolicyChange: (AutoLockPolicy) -> Unit,
    onBiometricToggle: (enable: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                SectionHeader(stringResource(R.string.settings_auto_lock))
                Spacer(Modifier.height(Spacing.xs))
                AutoLockPolicy.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onAutoLockPolicyChange(option) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = autoLockPolicy == option,
                            onClick = { onAutoLockPolicyChange(option) },
                        )
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = autoLockLabel(option),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(Modifier.height(Spacing.sm + 4.dp))
                SectionHeader(stringResource(R.string.settings_biometric))
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.unlock_biometric),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!biometricSettingAvailable && !biometricEnabled) {
                            Text(
                                text = stringResource(R.string.settings_biometric_unavailable),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = biometricEnabled,
                        onCheckedChange = onBiometricToggle,
                        enabled = biometricSettingAvailable || biometricEnabled,
                    )
                }

                if (notice != null) {
                    Spacer(Modifier.height(Spacing.sm + 2.dp))
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

/** Display label for an auto-lock option (spec.md §24 wording). */
@Composable
internal fun autoLockLabel(policy: AutoLockPolicy): String = stringResource(
    when (policy) {
        AutoLockPolicy.IMMEDIATELY -> R.string.autolock_immediately
        AutoLockPolicy.ONE_MINUTE -> R.string.autolock_one_minute
        AutoLockPolicy.FIVE_MINUTES -> R.string.autolock_five_minutes
        AutoLockPolicy.FIFTEEN_MINUTES -> R.string.autolock_fifteen_minutes
    },
)

/** Resolves a display name for [categoryId]; null (or unknown) = [fallback]. */
internal fun categoryNameFor(
    state: VaultUiState.Loaded,
    categoryId: Long?,
    fallback: String,
): String = if (categoryId == null) {
    fallback
} else {
    state.categories.firstOrNull { it.id == categoryId }?.name ?: fallback
}

/** Dismissible amber-bordered notice (has_more warning / seeding warning). */
@Composable
internal fun WarningBanner(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.sm + 2.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.sm + 4.dp, top = Spacing.xs, end = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                // Decorative: the adjacent text carries the message.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/** One category tile in the home grid (spec.md §18.3). */
@Composable
internal fun CategoryCard(
    name: String,
    itemCount: Int,
    subdued: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.sm + 4.dp)) {
            Box(
                modifier = Modifier
                    .padding(bottom = Spacing.sm)
                    .width(22.dp)
                    .height(2.dp)
                    .alpha(if (subdued) 0.35f else 1f),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.primary,
                ) {}
            }
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                fontStyle = if (subdued) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = pluralStringResource(R.plurals.item_count, itemCount, itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shared create/rename dialog for categories (used by Home + Category). */
@Composable
internal fun CategoryNameDialog(
    title: String,
    initialName: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.category_name_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * Sprint 7 search results (spec-1.md §F row 7). Rows use the shared
 * [VaultItemRow] — title + masked preview + category meta — and a per-row
 * summary that names WHERE the query matched (title / field label / notes) and
 * NEVER what matched. Secret values stay masked until the user opens the item.
 */
@Composable
internal fun SearchResultsSection(
    searchState: SearchUiState,
    categoryNameFor: (categoryId: Long?) -> String,
    onOpenItem: (itemId: Long) -> Unit,
) {
    when (searchState) {
        is SearchUiState.Idle, is SearchUiState.Loading -> {
            Text(
                text = stringResource(R.string.search_progress),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                fontFamily = VeilSerif,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is SearchUiState.Results -> {
            SectionHeader(stringResource(R.string.section_results))
            Spacer(Modifier.height(Spacing.sm))
            if (searchState.items.isEmpty()) {
                Text(
                    text = stringResource(R.string.search_no_match, searchState.query),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    fontFamily = VeilSerif,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = pluralStringResource(
                        R.plurals.match_count,
                        searchState.items.size,
                        searchState.items.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                searchState.items.forEach { item ->
                    VaultItemRow(
                        item = item,
                        meta = categoryNameFor(item.categoryId),
                        onClick = { onOpenItem(item.id) },
                    )
                    SearchEngine.matchSummary(item, searchState.query)?.let { summary ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = VeilSerif,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }
        }
    }
}
