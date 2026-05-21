package com.karplayer.ui

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

private const val AUTHOR_EMAIL = "karabatoff@gmail.com"

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val version = remember(ctx) {
        runCatching {
            val pi: PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
            else @Suppress("DEPRECATION") pi.versionCode.toLong()
            "${pi.versionName} (build $code)"
        }.getOrDefault("unknown")
    }

    val openMail: () -> Unit = {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$AUTHOR_EMAIL"))
        // The mailto intent fails on devices without a configured email client
        // (common on bare-bones TV boxes). Wrap in runCatching so a missing
        // resolver doesn't crash the app — the email is plainly visible
        // anyway for the user to copy by other means.
        runCatching { ctx.startActivity(intent) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About KarPlayer SRT") },
        text = {
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Low-latency Android SRT receiver.", fontWeight = FontWeight.Medium)
                Text("Version: $version")
                Text("Built on libsrt 1.5.4 (mbedtls) + Media3.")
                Text("Author: Alexander Karabatov")

                Text(
                    text = thanksLine(prefix = "If you'd like to thank the author, drop a line to "),
                    modifier = Modifier.clickable(onClick = openMail)
                )
                Text(
                    text = thanksLine(prefix = "Хотите поблагодарить автора — напишите на "),
                    modifier = Modifier.clickable(onClick = openMail)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

/** Builds a sentence where the email at the end is underlined to read as a
 *  link, with the rest of the text in normal weight. */
private fun thanksLine(prefix: String): AnnotatedString = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.Light)) { append(prefix) }
    withStyle(
        SpanStyle(
            textDecoration = TextDecoration.Underline,
            fontWeight = FontWeight.Normal
        )
    ) { append(AUTHOR_EMAIL) }
}
