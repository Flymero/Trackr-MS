@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.ui.screens.me

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trackr.core.designsystem.resources.Res as DsRes
import com.trackr.core.designsystem.resources.action_cancel
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.feature.me.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/** RGB fields, HEX and the graphical controls share one draft; cancel never changes the applied seed. */
@Composable
internal fun ThemeColorPickerDialog(rgb: Int, onDismiss: () -> Unit, onColor: (Int) -> Unit) {
    val initial = remember(rgb) { rgbToHsv(rgb) }
    var hue by rememberSaveable { mutableStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableStateOf(initial[2]) }
    var hex by rememberSaveable { mutableStateOf(rgbHex(rgb)) }
    var red by rememberSaveable { mutableStateOf((rgb shr 16 and 255).toString()) }
    var green by rememberSaveable { mutableStateOf((rgb shr 8 and 255).toString()) }
    var blue by rememberSaveable { mutableStateOf((rgb and 255).toString()) }
    val chosen = Color.hsv(hue, saturation, brightness)
    val valid = parseThemeHex(hex) != null && listOf(red, green, blue).all { parseRgbChannel(it) != null }

    fun updateFromHsv(h: Float, s: Float, v: Float) {
        hue = h
        saturation = s
        brightness = v
        val value = Color.hsv(h, s, v).toArgb() and 0xFFFFFF
        hex = rgbHex(value)
        red = (value shr 16 and 255).toString()
        green = (value shr 8 and 255).toString()
        blue = (value and 255).toString()
    }

    fun updateFromRgb(value: Int) {
        val hsv = rgbToHsv(value)
        // Preserve hue for black / gray so moving back into the palette keeps the user's hue choice.
        if (hsv[1] > 0f) hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    fun updateChannel(channel: Int, text: String) {
        when (channel) { 0 -> red = text; 1 -> green = text; else -> blue = text }
        val r = parseRgbChannel(red) ?: return
        val g = parseRgbChannel(green) ?: return
        val b = parseRgbChannel(blue) ?: return
        val value = (r shl 16) or (g shl 8) or b
        hex = rgbHex(value)
        updateFromRgb(value)
    }

    AlertDialog(
        modifier = Modifier.testTag("theme_color_picker"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.theme_pick_color)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceM),
            ) {
                SaturationValuePalette(hue, saturation, brightness) { s, v -> updateFromHsv(hue, s, v) }
                val hueLabel = stringResource(Res.string.theme_hue)
                Slider(
                    value = hue,
                    onValueChange = { updateFromHsv(it, saturation, brightness) },
                    valueRange = 0f..359.99f,
                    modifier = Modifier.fillMaxWidth().testTag("theme_picker_hue").semantics { contentDescription = hueLabel },
                    track = {
                        Box(Modifier.fillMaxWidth().height(16.dp).background(
                            Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) }),
                            CircleShape,
                        ))
                    },
                )
                // Bounded field widths avoid a weighted TextField remeasurement loop in AlertDialog.
                Row(horizontalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceM)) {
                    Surface(
                        color = chosen, shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.size(56.dp).testTag("theme_picker_preview").semantics { stateDescription = hex },
                    ) {}
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text
                            parseThemeHex(text)?.let { value ->
                                updateFromRgb(value)
                                red = (value shr 16 and 255).toString()
                                green = (value shr 8 and 255).toString()
                                blue = (value and 255).toString()
                            }
                        },
                        label = { Text(stringResource(Res.string.theme_hex)) },
                        singleLine = true,
                        isError = parseThemeHex(hex) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.width(160.dp).testTag("theme_picker_hex"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)) {
                    val descriptions = listOf(
                        stringResource(Res.string.theme_red_channel),
                        stringResource(Res.string.theme_green_channel),
                        stringResource(Res.string.theme_blue_channel),
                    )
                    listOf(red, green, blue).forEachIndexed { channel, text ->
                        val name = listOf("R", "G", "B")[channel]
                        val description = descriptions[channel]
                        OutlinedTextField(
                            value = text, onValueChange = { updateChannel(channel, it) },
                            label = { Text(name) }, singleLine = true,
                            isError = parseRgbChannel(text) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(72.dp).testTag("theme_picker_$name").semantics { contentDescription = description },
                        )
                    }
                }
                if (parseThemeHex(hex) == null) {
                    Text(stringResource(Res.string.theme_hex_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (listOf(red, green, blue).any { parseRgbChannel(it) == null }) {
                    Text(stringResource(Res.string.theme_rgb_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parseThemeHex(hex)?.let(onColor) }, enabled = valid,
                modifier = Modifier.testTag("theme_picker_confirm"),
            ) { Text(stringResource(Res.string.theme_use_color)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("theme_picker_cancel")) {
                Text(stringResource(DsRes.string.action_cancel))
            }
        },
    )
}

/** Specialized 2D color input; dialogs, fields, hue slider and actions remain standard M3 components. */
@Composable
private fun SaturationValuePalette(hue: Float, saturation: Float, brightness: Float, onChange: (Float, Float) -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val label = stringResource(Res.string.theme_color_area)
    val description = stringResource(Res.string.theme_color_area_state, (saturation * 100).roundToInt(), (brightness * 100).roundToInt())
    val moreSaturation = stringResource(Res.string.theme_increase_saturation)
    val lessSaturation = stringResource(Res.string.theme_decrease_saturation)
    val moreBrightness = stringResource(Res.string.theme_increase_brightness)
    val lessBrightness = stringResource(Res.string.theme_decrease_brightness)
    Surface(shape = MaterialTheme.shapes.medium) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f).testTag("theme_picker_palette")
            .semantics {
                contentDescription = label
                stateDescription = description
                customActions = listOf(
                    CustomAccessibilityAction(moreSaturation) { onChange((saturation + 0.05f).coerceAtMost(1f), brightness); true },
                    CustomAccessibilityAction(lessSaturation) { onChange((saturation - 0.05f).coerceAtLeast(0f), brightness); true },
                    CustomAccessibilityAction(moreBrightness) { onChange(saturation, (brightness + 0.05f).coerceAtMost(1f)); true },
                    CustomAccessibilityAction(lessBrightness) { onChange(saturation, (brightness - 0.05f).coerceAtLeast(0f)); true },
                )
            }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                    Key.DirectionLeft -> { onChange((saturation - 0.01f).coerceAtLeast(0f), brightness); true }
                    Key.DirectionRight -> { onChange((saturation + 0.01f).coerceAtMost(1f), brightness); true }
                    Key.DirectionUp -> { onChange(saturation, (brightness + 0.01f).coerceAtMost(1f)); true }
                    Key.DirectionDown -> { onChange(saturation, (brightness - 0.01f).coerceAtLeast(0f)); true }
                    else -> false
                }
            }.focusable()
            .pointerInput(Unit) {
                fun choose(position: Offset) {
                    latestChange((position.x / size.width).coerceIn(0f, 1f), (1f - position.y / size.height).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    choose(down.position)
                    down.consume()
                    drag(down.id) { change -> choose(change.position); change.consume() }
                }
            },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val radius = 8.dp.toPx()
            val marker = Offset(
                (saturation * size.width).coerceIn(radius, size.width - radius),
                ((1f - brightness) * size.height).coerceIn(radius, size.height - radius),
            )
            drawCircle(Color.Black.copy(alpha = 0.5f), radius, marker, style = Stroke(4.dp.toPx()))
            drawCircle(Color.White, radius, marker, style = Stroke(2.dp.toPx()))
        }
    }
}

private fun parseRgbChannel(text: String): Int? = text.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 0..255 }

private fun rgbToHsv(rgb: Int): FloatArray {
    val color = Color(0xFF000000L or rgb.toLong())
    val max = maxOf(color.red, color.green, color.blue)
    val min = minOf(color.red, color.green, color.blue)
    val delta = max - min
    val hue = if (delta == 0f) 0f else when (max) {
        color.red -> 60f * (((color.green - color.blue) / delta) % 6f)
        color.green -> 60f * ((color.blue - color.red) / delta + 2f)
        else -> 60f * ((color.red - color.green) / delta + 4f)
    }.let { (it + 360f) % 360f }
    return floatArrayOf(hue, if (max == 0f) 0f else delta / max, max)
}
