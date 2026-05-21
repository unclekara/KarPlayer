package com.karplayer.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color

/**
 * Focus styling lives on the container itself instead of a separate border:
 * a focused control flips its background to solid white with black content.
 * That avoids any pixel-rounding mismatch between an external border and
 * the button's actual surface on TV scaling, and reads cleanly on every
 * display.
 */

@Composable
internal fun FocusableButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (focused) focusedColors() else ButtonDefaults.buttonColors(),
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus },
        content = content
    )
}

@Composable
internal fun FocusableOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        ButtonDefaults.ContentPadding,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        colors = if (focused) focusedColors() else ButtonDefaults.outlinedButtonColors(),
        contentPadding = contentPadding,
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus },
        content = content
    )
}

@Composable
internal fun FocusableIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unfocusedContentColor: Color = Color.Unspecified,
    content: @Composable () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val unfocusedColors = if (unfocusedContentColor == Color.Unspecified) {
        IconButtonDefaults.iconButtonColors()
    } else {
        IconButtonDefaults.iconButtonColors(contentColor = unfocusedContentColor)
    }
    IconButton(
        onClick = onClick,
        colors = if (focused) IconButtonDefaults.iconButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black
        ) else unfocusedColors,
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus },
        content = content
    )
}

@Composable
internal fun FocusableFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        colors = if (focused) FilterChipDefaults.filterChipColors(
            containerColor = Color.White,
            labelColor = Color.Black,
            selectedContainerColor = Color.White,
            selectedLabelColor = Color.Black
        ) else FilterChipDefaults.filterChipColors(),
        modifier = modifier.onFocusChanged { focused = it.isFocused || it.hasFocus }
    )
}

@Composable
private fun focusedColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = Color.White,
    contentColor = Color.Black
)
