package com.karplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Modifier as UiModifier
import kotlin.math.roundToInt

/**
 * A labelled integer input that combines:
 *  - a ‘−’ focusable button decrementing by [step]
 *  - a numeric text field (TvAware: on TV the IME stays hidden until OK)
 *  - a ‘+’ focusable button incrementing by [step]
 *  - an optional Slider underneath
 *
 * On Android TV, the slider is awkward to drive with a D-pad. We default
 * to hiding it there and rely on the ± buttons / text input, both of
 * which are first-class focusable targets on a remote.
 *
 * @param valueRange Clamp range applied to input (text field + buttons).
 * @param sliderRange Slider's range. Defaults to [valueRange]; pass a narrower
 *     range when the slider should only cover a useful subset of the full
 *     allowable values (e.g. Latency: input up to 8000 ms, slider 20..1000).
 * @param showSlider Force-disable the slider (TV by default).
 */
@Composable
internal fun NumericStepperField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    step: Int = 1,
    sliderRange: IntRange = valueRange,
    showSlider: Boolean = !isTvDevice(),
    keyboardType: KeyboardType = KeyboardType.Number,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    fun apply(next: Int) {
        val c = next.coerceIn(valueRange.first, valueRange.last)
        text = c.toString()
        onValueChange(c)
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FocusableOutlinedButton(
                onClick = { apply(value - step) },
                contentPadding = PaddingValues(0.dp),
                modifier = UiModifier.size(44.dp)
            ) { Text("−", fontSize = 18.sp) }

            TvAwareTextField(
                value = text,
                onValueChange = { raw ->
                    val cleaned = raw.filter { it.isDigit() }.take(6)
                    text = cleaned
                    cleaned.toIntOrNull()?.let {
                        onValueChange(it.coerceIn(valueRange.first, valueRange.last))
                    }
                },
                label = { Text(label) },
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = UiModifier.weight(1f)
            )

            FocusableOutlinedButton(
                onClick = { apply(value + step) },
                contentPadding = PaddingValues(0.dp),
                modifier = UiModifier.size(44.dp)
            ) { Text("+", fontSize = 18.sp) }
        }

        if (showSlider) {
            Slider(
                value = value.coerceIn(sliderRange.first, sliderRange.last).toFloat(),
                onValueChange = { apply(it.roundToInt()) },
                valueRange = sliderRange.first.toFloat()..sliderRange.last.toFloat()
            )
        }
    }
}
