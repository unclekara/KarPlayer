package com.karplayer.ui

import android.view.KeyEvent
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Drop-in replacement for [OutlinedTextField] that adapts to TV remotes:
 *
 *  - On a phone / tablet it behaves identically to a normal text field —
 *    focus auto-shows the IME, typing works.
 *  - On Android TV (Leanback) the field opens in `readOnly` mode so the
 *    D-pad can traverse the form without the IME popping up at every
 *    stop. Pressing OK / Enter on a focused field switches it into edit
 *    mode (IME shows, typing works). Pressing Done on the IME or losing
 *    focus returns the field to readOnly.
 *
 * Parameters mirror the upstream [OutlinedTextField] subset we actually
 * use across the app.
 */
@Composable
internal fun TvAwareTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: (@Composable () -> Unit)? = null
) {
    val isTv = isTvDevice()
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        keyboardOptions = keyboardOptions,
        keyboardActions = KeyboardActions(onDone = {
            if (isTv) {
                editing = false
                keyboard?.hide()
            }
        }),
        visualTransformation = visualTransformation,
        singleLine = singleLine,
        isError = isError,
        supportingText = supportingText,
        // readOnly suppresses the IME while focused on TV; on phone we
        // always stay editable.
        readOnly = isTv && !editing,
        modifier = modifier
            .onPreviewKeyEvent { ev ->
                if (!isTv || editing) return@onPreviewKeyEvent false
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                if (code == KeyEvent.KEYCODE_DPAD_CENTER ||
                    code == KeyEvent.KEYCODE_ENTER ||
                    code == KeyEvent.KEYCODE_NUMPAD_ENTER
                ) {
                    editing = true
                    keyboard?.show()
                    true
                } else false
            }
            .onFocusChanged {
                // Drop edit mode (and the IME) as soon as the field loses
                // focus, so navigating away from it on TV doesn't leave a
                // stale keyboard up.
                if (isTv && !it.isFocused) {
                    editing = false
                }
            }
    )
}
