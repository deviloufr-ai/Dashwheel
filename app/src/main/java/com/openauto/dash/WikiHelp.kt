package com.openauto.dash

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/*
 * Help where the confusion happens: a small "?" in the dialogs that need
 * explaining (pairing the phone, the OBD adapter, the AI mechanic, PMPatch3)
 * and a Help row in About. Each opens the matching page of the public wiki,
 * as a QR code for the phone and a button for this screen.
 */

internal const val WIKI_URL = "https://github.com/deviloufr-ai/Dashwheel/wiki"

/** A page of the wiki; [slug] null is the wiki's home. */
internal enum class WikiPage(val slug: String?) {
    HOME(null),
    PHONE_COMPANION("Phone-Link-and-Companion"),
    OBD_ADAPTER("Car-Data-(OBD-and-CANbox)"),
    AI_MECHANIC("AI-Mechanic-and-Gemini"),
    ROOT_PMPATCH("Root-and-PMPatch3");

    val url: String get() = if (slug == null) WIKI_URL else "$WIKI_URL/$slug"
}

/** The "?" of a dialog's title: opens the help for [page]. */
@Composable
internal fun HelpButton(page: WikiPage, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(R.string.help_title)
    IconButton(onClick = { open = true }, modifier = modifier.size(DashSize.Touch)) {
        Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = label, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
    }
    if (open) WikiHelpDialog(page) { open = false }
}

/** A dialog title with the "?" at its end. */
@Composable
internal fun TitleWithHelp(title: String, page: WikiPage) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = DashColors.TextPrimary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        HelpButton(page)
    }
}

/**
 * The help for [page]: its address as a QR code the phone scans, and a
 * button that opens it on this screen.
 */
@Composable
internal fun WikiHelpDialog(page: WikiPage, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.help_title), color = DashColors.TextPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.help_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth())
                QrCode(page.url, Modifier.size(180.dp).clip(DashShape.Small))
                Text(stringResource(R.string.about_scan), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.launchSafely(Intent(Intent.ACTION_VIEW, Uri.parse(page.url)))
                onDismiss()
            }) { Text(stringResource(R.string.help_open_here), color = DashColors.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) }
        }
    )
}
