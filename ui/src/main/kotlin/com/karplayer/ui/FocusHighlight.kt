package com.karplayer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Bright amber outline that wraps a focused control. The default Material 3
 * focus indicator is too subtle to see on cheap STB displays — this modifier
 * makes it impossible to miss.
 *
 * Applied to Buttons, IconButtons, FilterChips, and similar focusable
 * surfaces. Place it FIRST in the Modifier chain (before any `padding` or
 * `size`) so the border hugs the actual control bounds, not the layout slot.
 */
// Pure white. High enough contrast against the dark background and visible
// even on cheap STB displays, while staying neutral — coloured rings ended
// up looking toy-ish in testing.
@Suppress("NOTHING_TO_INLINE")
internal val FocusHighlightColor: Color = Color.White

@Composable
internal fun Modifier.focusHighlight(
    shape: Shape = RoundedCornerShape(8.dp),
    color: Color = FocusHighlightColor
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { state -> focused = state.isFocused || state.hasFocus }
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) color else Color.Transparent,
            shape = shape
        )
}
