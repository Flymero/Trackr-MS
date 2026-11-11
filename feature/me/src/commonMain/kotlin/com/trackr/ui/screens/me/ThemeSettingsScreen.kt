@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.trackr.ui.screens.me

import com.trackr.core.designsystem.resources.color_picker_pick_color
import com.trackr.core.designsystem.component.ColorPickerDialog
import com.trackr.core.designsystem.component.parseRgbHex
import com.trackr.core.designsystem.component.rgbHex
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res as DsRes
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_bus
import com.trackr.core.designsystem.resources.ic_check
import com.trackr.core.designsystem.resources.ic_person
import com.trackr.core.designsystem.resources.ic_qr_code
import com.trackr.core.designsystem.resources.ic_route
import com.trackr.core.designsystem.resources.transit_code
import com.trackr.core.designsystem.theme.*
import com.trackr.core.model.*
import com.trackr.feature.me.resources.*
import com.trackr.ui.components.arrival.ArrivalTrailing
import com.trackr.ui.components.arrival.ArrivalUi
import com.trackr.ui.components.arrival.LineBadge
import com.trackr.core.domain.arrival.ArrivalTone
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/** Only input drafts live here; every applied preference comes from the repository. */
@Composable
fun ThemeSettingsScreen(
    state: ThemeSettingsUiState,
    onEvent: (ThemeSettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
    systemColors: SystemThemeColors? = rememberSystemThemeColors(),
) {
    val dimens = TrackrTheme.dimens
    val colors = state.colors
    val nativeSystem = colors.source == ThemeColorSource.System && colors.followSystem && systemColors != null
    val seed = themeSeedColor(colors, systemColors)
    val enabled = !state.elderMode
    val styleName = stringResource(styleLabel(colors.style))
    val seedName = when (colors.source) {
        ThemeColorSource.System -> stringResource(if (nativeSystem) Res.string.theme_follow else systemSeedLabel(colors.systemSeed))
        ThemeColorSource.Preset -> stringResource(seedLabel(colors.preset))
        ThemeColorSource.Custom -> stringResource(Res.string.theme_custom)
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.theme_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(ThemeSettingsEvent.Back) }) {
                        Icon(vectorResource(DsRes.drawable.ic_back), stringResource(DsRes.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("theme_settings"),
            contentPadding = PaddingValues(dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spaceXl),
        ) {
            item("preview") {
                Column(verticalArrangement = Arrangement.spacedBy(dimens.spaceM)) {
                    Text(if (nativeSystem) seedName else "$seedName · $styleName", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ThemePreview()
                }
            }
            item("mode") {
                ThemeSection(stringResource(Res.string.theme_mode)) {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = state.mode == mode,
                                onClick = { onEvent(ThemeSettingsEvent.SetMode(mode)) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                                modifier = Modifier.testTag("theme_mode_${mode.name}"),
                            ) { Text(stringResource(modeLabel(mode))) }
                        }
                    }
                }
            }
            if (state.elderMode) item("elder_notice") {
                Text(stringResource(Res.string.theme_elder), style = MaterialTheme.typography.bodyMedium)
            }
            item("colors") {
                ThemeSection(stringResource(Res.string.theme_colors), if (nativeSystem) null else rgbHex(seed.toArgb() and 0xFFFFFF)) {
                    PrimaryTabRow(selectedTabIndex = colors.source.ordinal) {
                        ThemeColorSource.entries.forEach { source ->
                            Tab(
                                selected = colors.source == source,
                                onClick = { onEvent(ThemeSettingsEvent.SetSource(source)) },
                                enabled = enabled,
                                modifier = Modifier.testTag("theme_source_${source.name}"),
                                text = { Text(stringResource(sourceLabel(source))) },
                            )
                        }
                    }
                    when (colors.source) {
                        ThemeColorSource.Preset -> SeedChoices(
                            choices = ThemeSeed.entries.toList(),
                            color = { Color(0xFF000000L or it.rgb.toLong()) },
                            label = { stringResource(seedLabel(it)) },
                            isSelected = { it == colors.preset },
                            enabled = enabled,
                            onSelect = { onEvent(ThemeSettingsEvent.SetPreset(it)) },
                        )
                        ThemeColorSource.System -> if (systemColors == null) {
                            Text(stringResource(Res.string.theme_system_unavailable), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            OutlinedCard(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                ListItem(
                                    selected = colors.followSystem,
                                    onClick = { onEvent(ThemeSettingsEvent.FollowSystem) },
                                    enabled = enabled,
                                    modifier = Modifier.semantics { role = Role.RadioButton },
                                    supportingContent = { Text(stringResource(Res.string.theme_follow_desc)) },
                                    leadingContent = { RadioButton(selected = colors.followSystem, onClick = null, enabled = enabled) },
                                    colors = ListItemDefaults.colors(containerColor = if (colors.followSystem) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                                ) { Text(stringResource(Res.string.theme_follow)) }
                            }
                            Text(stringResource(Res.string.theme_system_pick), style = MaterialTheme.typography.bodySmall)
                            SeedChoices(
                                choices = SystemThemeSeed.entries.toList(),
                                color = systemColors::seed,
                                label = { stringResource(systemSeedLabel(it)) },
                                isSelected = { !colors.followSystem && it == colors.systemSeed },
                                enabled = enabled,
                                onSelect = { onEvent(ThemeSettingsEvent.SetSystemSeed(it)) },
                            )
                        }
                        ThemeColorSource.Custom -> CustomColorField(colors.customRgb, enabled) {
                            onEvent(ThemeSettingsEvent.SetCustomColor(it))
                        }
                    }
                }
            }
            if (nativeSystem) item("system_style") {
                Card(
                    modifier = Modifier.testTag("theme_system_managed"),
                ) {
                    Column(Modifier.fillMaxWidth().padding(dimens.cardPadding), verticalArrangement = Arrangement.spacedBy(dimens.spaceXs)) {
                        Text(stringResource(Res.string.theme_style_managed), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(Res.string.theme_style_managed_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else item("style") {
                ThemeSection(stringResource(Res.string.theme_style), styleName) {
                    BoxWithConstraints {
                        val columns = if (maxWidth >= 300.dp && !state.elderMode) 3 else 2
                        val styles = ThemeColorStyle.entries.toList()
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                            styles.chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(dimens.spaceS)) {
                                    row.forEach { style ->
                                        StyleChoice(style, seed, style == colors.style, enabled, Modifier.weight(1f)) {
                                            onEvent(ThemeSettingsEvent.SetStyle(style))
                                        }
                                    }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                    Text(stringResource(styleDescription(colors.style)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item("saved") {
                Text(stringResource(Res.string.theme_saved), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ThemeSection(title: String, value: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceM)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (value != null) Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun <T> SeedChoices(
    choices: List<T>, color: (T) -> Color, label: @Composable (T) -> String,
    isSelected: (T) -> Boolean, enabled: Boolean, onSelect: (T) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = (maxWidth / TrackrTheme.dimens.minTouchTarget).toInt().coerceIn(1, choices.size)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)) {
            choices.chunked(columns).forEach { row ->
                Row {
                    row.forEach { choice ->
                        val selected = isSelected(choice)
                        val swatch = color(choice)
                        val name = label(choice)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledIconButton(
                                onClick = { onSelect(choice) }, enabled = enabled,
                                modifier = Modifier.testTag("theme_seed_$choice").semantics { this.selected = selected; role = Role.RadioButton; contentDescription = name },
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = swatch, contentColor = if (swatch.luminance() > 0.4f) Color.Black else Color.White),
                            ) {
                                if (selected) Icon(vectorResource(DsRes.drawable.ic_check), null) else Spacer(Modifier.size(24.dp).semantics { })
                            }
                            Text(name, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StyleChoice(style: ThemeColorStyle, seed: Color, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val dark = TrackrTheme.isDark
    val scheme = remember(seed, style, dark) { generatedThemeColorScheme(seed, style, dark) }
    OutlinedCard(
        onClick = onClick, enabled = enabled,
        modifier = modifier.testTag("theme_style_${style.name}").semantics { this.selected = selected; role = Role.RadioButton },
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(TrackrTheme.dimens.spaceS), verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)) {
            // Decorative palette specimen. No stock component represents a miniature generated color scheme.
            Surface(color = scheme.surface, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.height(52.dp).padding(6.dp)) {
                    Surface(color = scheme.secondaryContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.fillMaxWidth(0.5f).height(4.dp).background(scheme.onSecondaryContainer, MaterialTheme.shapes.small))
                            Box(Modifier.width(24.dp).height(10.dp).background(scheme.primary, MaterialTheme.shapes.small))
                        }
                    }
                    Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(16.dp).background(scheme.tertiary, MaterialTheme.shapes.small))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(styleLabel(style)), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (selected) Icon(vectorResource(DsRes.drawable.ic_check), null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ThemePreview() {
    val dims = TrackrTheme.dimens
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(dims.cardPadding), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.theme_preview_stop), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(Res.string.theme_preview), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Card(Modifier.padding(horizontal = dims.spaceS), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow)) {
            Column(Modifier.padding(dims.spaceM), verticalArrangement = Arrangement.spacedBy(dims.spaceS)) {
                SuggestionChip(onClick = {}, enabled = false, label = { Text(stringResource(Res.string.theme_preview_favorites)) }, colors = SuggestionChipDefaults.suggestionChipColors(disabledContainerColor = scheme.secondaryContainer, disabledLabelColor = scheme.onSecondaryContainer))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        LineBadge("M204")
                        Text(stringResource(Res.string.theme_preview_towards), style = MaterialTheme.typography.bodySmall)
                    }
                    ArrivalTrailing(ArrivalUi(stringResource(Res.string.theme_preview_arriving), stringResource(Res.string.theme_preview_distance), ArrivalTone.Go))
                }
                HorizontalDivider(color = scheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(Res.string.theme_preview_other), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(stringResource(Res.string.theme_preview_last), style = MaterialTheme.typography.bodySmall, color = TrackrTheme.colors.warn)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(dims.spaceS)) {
                    Button(onClick = {}, enabled = false, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(disabledContainerColor = scheme.primary, disabledContentColor = scheme.onPrimary)) {
                        Text(stringResource(Res.string.theme_preview_track))
                    }
                    FilledTonalButton(onClick = {}, enabled = false, colors = ButtonDefaults.filledTonalButtonColors(disabledContainerColor = scheme.tertiaryContainer, disabledContentColor = scheme.onTertiaryContainer)) {
                        Icon(vectorResource(DsRes.drawable.ic_qr_code), null, Modifier.size(16.dp))
                        Spacer(Modifier.width(dims.spaceXs))
                        Text(stringResource(DsRes.string.transit_code))
                    }
                }
            }
        }
        NavigationBar(containerColor = scheme.surfaceContainerLow, windowInsets = WindowInsets(0.dp)) {
            listOf(DsRes.drawable.ic_bus to Res.string.theme_preview_bus, DsRes.drawable.ic_route to Res.string.theme_preview_route, DsRes.drawable.ic_person to Res.string.theme_preview_me).forEachIndexed { i, (icon, label) ->
                NavigationBarItem(selected = i == 0, enabled = false, onClick = {}, icon = { Icon(vectorResource(icon), null) }, label = { Text(stringResource(label)) }, colors = NavigationBarItemDefaults.colors(disabledIconColor = scheme.onSurfaceVariant, disabledTextColor = scheme.onSurfaceVariant))
            }
        }
    }
}


@Composable
private fun CustomColorField(rgb: Int, enabled: Boolean, onColor: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(rgbHex(rgb)) }
    var picker by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rgb) { if (parseRgbHex(text) != rgb) text = rgbHex(rgb) }
    val pickColorLabel = stringResource(DsRes.string.color_picker_pick_color)
    Row(horizontalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceM), verticalAlignment = Alignment.Bottom) {
        OutlinedButton(
            onClick = { picker = true }, enabled = enabled,
            shape = MaterialTheme.shapes.small,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(maxOf(56.dp, TrackrTheme.dimens.minTouchTarget))
                .testTag("theme_pick_color").semantics { contentDescription = pickColorLabel },
        ) {
            Surface(color = Color(0xFF000000L or rgb.toLong()), shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.size(28.dp)) {}
        }
        OutlinedTextField(
            value = text,
            onValueChange = { value -> text = value; parseRgbHex(value)?.let(onColor) },
            enabled = enabled, singleLine = true,
            modifier = Modifier.weight(1f).testTag("theme_hex"),
            label = { Text(stringResource(Res.string.theme_hex)) },
            shape = MaterialTheme.shapes.small,
            isError = parseRgbHex(text) == null,
        )
    }
    if (parseRgbHex(text) == null) Text(stringResource(Res.string.theme_hex_error), modifier = Modifier.testTag("theme_hex_error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    if (picker) ColorPickerDialog(rgb, onDismiss = { picker = false }, onColor = { onColor(it); picker = false })
}

private fun modeLabel(mode: ThemeMode): StringResource = when (mode) {
    ThemeMode.System -> Res.string.me_theme_system
    ThemeMode.Light -> Res.string.me_theme_light
    ThemeMode.Dark -> Res.string.me_theme_dark
}
private fun sourceLabel(source: ThemeColorSource): StringResource = when (source) {
    ThemeColorSource.System -> Res.string.theme_system
    ThemeColorSource.Preset -> Res.string.theme_preset
    ThemeColorSource.Custom -> Res.string.theme_custom
}
private fun seedLabel(seed: ThemeSeed): StringResource = when (seed) {
    ThemeSeed.Blue -> Res.string.theme_blue
    ThemeSeed.Teal -> Res.string.theme_teal
    ThemeSeed.Purple -> Res.string.theme_purple
    ThemeSeed.Rose -> Res.string.theme_rose
    ThemeSeed.Orange -> Res.string.theme_orange
    ThemeSeed.Gray -> Res.string.theme_gray
}
private fun systemSeedLabel(seed: SystemThemeSeed): StringResource = when (seed) {
    SystemThemeSeed.Primary -> Res.string.theme_primary
    SystemThemeSeed.Secondary -> Res.string.theme_secondary
    SystemThemeSeed.Tertiary -> Res.string.theme_tertiary
}
private fun styleLabel(style: ThemeColorStyle): StringResource = when (style) {
    ThemeColorStyle.TonalSpot -> Res.string.theme_tonal
    ThemeColorStyle.Neutral -> Res.string.theme_neutral
    ThemeColorStyle.Vibrant -> Res.string.theme_vibrant
    ThemeColorStyle.Expressive -> Res.string.theme_expressive
    ThemeColorStyle.Fidelity -> Res.string.theme_fidelity
    ThemeColorStyle.Content -> Res.string.theme_content
    ThemeColorStyle.Monochrome -> Res.string.theme_monochrome
    ThemeColorStyle.Rainbow -> Res.string.theme_rainbow
    ThemeColorStyle.FruitSalad -> Res.string.theme_fruit
}
private fun styleDescription(style: ThemeColorStyle): StringResource = when (style) {
    ThemeColorStyle.TonalSpot -> Res.string.theme_tonal_desc
    ThemeColorStyle.Neutral -> Res.string.theme_neutral_desc
    ThemeColorStyle.Vibrant -> Res.string.theme_vibrant_desc
    ThemeColorStyle.Expressive -> Res.string.theme_expressive_desc
    ThemeColorStyle.Fidelity -> Res.string.theme_fidelity_desc
    ThemeColorStyle.Content -> Res.string.theme_content_desc
    ThemeColorStyle.Monochrome -> Res.string.theme_monochrome_desc
    ThemeColorStyle.Rainbow -> Res.string.theme_rainbow_desc
    ThemeColorStyle.FruitSalad -> Res.string.theme_fruit_desc
}
