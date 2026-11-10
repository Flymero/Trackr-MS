@file:OptIn(ExperimentalMaterial3Api::class)

package com.trackr.core.designsystem.component

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
import com.trackr.core.designsystem.resources.*
import com.trackr.core.designsystem.theme.TrackrTheme
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * 通用选色面板（主题色、收藏分组的自定义颜色共用）：色板 + 色相条 + HEX + RGB。
 *
 * RGB fields, HEX and the graphical controls share one draft; cancel never changes the applied color.
 * [rgb] / [onColor] 都是不带透明度的 0xRRGGBB。
 */
@Composable
fun ColorPickerDialog(rgb: Int, onDismiss: () -> Unit, onColor: (Int) -> Unit) {
    val initial = remember(rgb) { rgbToHsv(rgb) }
    var hue by rememberSaveable { mutableStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableStateOf(initial[2]) }
    var hex by rememberSaveable { mutableStateOf(rgbHex(rgb)) }
    var red by rememberSaveable { mutableStateOf((rgb shr 16 and 255).toString()) }
    var green by rememberSaveable { mutableStateOf((rgb shr 8 and 255).toString()) }
    var blue by rememberSaveable { mutableStateOf((rgb and 255).toString()) }
    val chosen = Color.hsv(hue, saturation, brightness)
    val valid = parseRgbHex(hex) != null && listOf(red, green, blue).all { parseRgbChannel(it) != null }

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
        modifier = Modifier.testTag("color_picker"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.color_picker_pick_color)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceM),
            ) {
                SaturationValuePalette(hue, saturation, brightness) { s, v -> updateFromHsv(hue, s, v) }
                val hueLabel = stringResource(Res.string.color_picker_hue)
                Slider(
                    value = hue,
                    onValueChange = { updateFromHsv(it, saturation, brightness) },
                    valueRange = 0f..359.99f,
                    modifier = Modifier.fillMaxWidth().testTag("color_picker_hue").semantics { contentDescription = hueLabel },
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
                        modifier = Modifier.size(56.dp).testTag("color_picker_preview").semantics { stateDescription = hex },
                    ) {}
                    OutlinedTextField(
                        value = hex,
                        onValueChange = { text ->
                            hex = text
                            parseRgbHex(text)?.let { value ->
                                updateFromRgb(value)
                                red = (value shr 16 and 255).toString()
                                green = (value shr 8 and 255).toString()
                                blue = (value and 255).toString()
                            }
                        },
                        label = { Text(stringResource(Res.string.color_picker_hex)) },
                        singleLine = true,
                        isError = parseRgbHex(hex) == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.width(160.dp).testTag("color_picker_hex"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(TrackrTheme.dimens.spaceS)) {
                    val descriptions = listOf(
                        stringResource(Res.string.color_picker_red_channel),
                        stringResource(Res.string.color_picker_green_channel),
                        stringResource(Res.string.color_picker_blue_channel),
                    )
                    listOf(red, green, blue).forEachIndexed { channel, text ->
                        val name = listOf("R", "G", "B")[channel]
                        val description = descriptions[channel]
                        OutlinedTextField(
                            value = text, onValueChange = { updateChannel(channel, it) },
                            label = { Text(name) }, singleLine = true,
                            isError = parseRgbChannel(text) == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(72.dp).testTag("color_picker_$name").semantics { contentDescription = description },
                        )
                    }
                }
                if (parseRgbHex(hex) == null) {
                    Text(stringResource(Res.string.color_picker_hex_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (listOf(red, green, blue).any { parseRgbChannel(it) == null }) {
                    Text(stringResource(Res.string.color_picker_rgb_error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parseRgbHex(hex)?.let(onColor) }, enabled = valid,
                modifier = Modifier.testTag("color_picker_confirm"),
            ) { Text(stringResource(Res.string.color_picker_use_color)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("color_picker_cancel")) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

/** Specialized 2D color input; dialogs, fields, hue slider and actions remain standard M3 components. */
@Composable
private fun SaturationValuePalette(hue: Float, saturation: Float, brightness: Float, onChange: (Float, Float) -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val label = stringResource(Res.string.color_picker_color_area)
    val description = stringResource(Res.string.color_picker_color_area_state, (saturation * 100).roundToInt(), (brightness * 100).roundToInt())
    val moreSaturation = stringResource(Res.string.color_picker_increase_saturation)
    val lessSaturation = stringResource(Res.string.color_picker_decrease_saturation)
    val moreBrightness = stringResource(Res.string.color_picker_increase_brightness)
    val lessBrightness = stringResource(Res.string.color_picker_decrease_brightness)
    Surface(shape = MaterialTheme.shapes.medium) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1.35f).testTag("color_picker_palette")
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

/** 0xRRGGBB -> `#RRGGBB` */
fun rgbHex(rgb: Int): String = "#" + rgb.toString(16).padStart(6, '0').uppercase()

/** `#RRGGBB` / `RRGGBB` -> 0xRRGGBB；格式不对给 null */
fun parseRgbHex(text: String): Int? = text.removePrefix("#").takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c.lowercaseChar() in 'a'..'f' } }?.toIntOrNull(16)?.takeIf { it in 0..0xFFFFFF }
