package com.karplayer.ui

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * True when the current device exposes the Leanback UI feature — i.e. it is
 * an Android TV / Google TV. Used to skip touch-only affordances (swipe HUDs,
 * brightness control) and to give the form fields explicit default focus.
 */
@Composable
internal fun isTvDevice(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}
