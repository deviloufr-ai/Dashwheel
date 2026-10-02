package com.openauto.dash

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Settings, My car, Car picture (EXPERIMENTAL): the driver's own car in place
 * of the drawn one ([MyCarLook]). Picked from a file (a USB stick through
 * Android's file picker) or sent from the phone app; then shown or not, in
 * one of three styles, with a preview of the result.
 */
@Composable
internal fun MyCarLookSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val look by MyCarLook.imported.collectAsState()
    val on by MyCarLook.on.collectAsState()
    val style by MyCarLook.style.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ready = stringResource(R.string.mycar_ready)
    val failed = stringResource(R.string.mycar_failed)

    fun import(read: () -> Result<CarLook>) {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { read() }
            busy = false
            Toast.makeText(context, if (result.isSuccess) ready else failed, Toast.LENGTH_SHORT).show()
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import { MyCarLook.importUri(context, uri) }
    }
    val noCar = stringResource(R.string.mycar_photos_no_car)
    val openPhotos = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            busy = true
            scope.launch {
                val result = withContext(Dispatchers.Default) { CarPhotoImport.import(context, uris) }
                busy = false
                Toast.makeText(context, if (result.isSuccess) ready else noCar, Toast.LENGTH_LONG).show()
            }
        }
    }
    val pickPhotos = {
        try {
            openPhotos.launch(arrayOf("image/*"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.mycar_no_picker, MyCarLook.fallbackFile(context).path), Toast.LENGTH_LONG).show()
        }
    }
    val pick = {
        try {
            open.launch(arrayOf("image/*", "application/zip", "application/octet-stream"))
        } catch (e: ActivityNotFoundException) {
            // No file picker on this unit: a fixed file in the app's own folder.
            val file = MyCarLook.fallbackFile(context)
            if (file.exists()) import { runCatching { MyCarLook.importBytes(context, file.readBytes()) } }
            else Toast.makeText(context, context.getString(R.string.mycar_no_picker, file.path), Toast.LENGTH_LONG).show()
        }
    }

    SettingsSheet(
        title = stringResource(R.string.mycar_title),
        onDismiss = onDismiss,
        actions = {
            if (look != null) SheetButton(stringResource(R.string.mycar_remove), primary = false) { removing = true }
            SheetButton(stringResource(R.string.mycar_pick)) { if (!busy) pick() }
        }
    ) {
        Text(stringResource(R.string.mycar_experimental), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        val shown = look?.portrait
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(DashShape.Medium)
                .background(DashColors.CardHi),
            contentAlignment = Alignment.Center
        ) {
            when {
                busy -> Text(stringResource(R.string.mycar_working), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                shown != null -> {
                    val ink = DashColors.Accent
                    val warn = DashColors.Warning
                    Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                        drawCarView(shown, style, fitCar(shown, Rect(Offset.Zero, size)), ink, warn)
                    }
                }
                else -> Text(
                    stringResource(R.string.mycar_empty), color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp)
                )
            }
        }

        look?.let { l ->
            val views = listOfNotNull(
                l.side?.let { stringResource(R.string.mycar_view_side) },
                l.top?.let { stringResource(R.string.mycar_view_top) },
                l.top?.takeIf { it.parts.isNotEmpty() }?.let { stringResource(R.string.mycar_view_open) },
                l.hero?.let { stringResource(R.string.mycar_view_hero) }
            )
            Text(stringResource(R.string.mycar_views, views.joinToString(", ")), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)

            SettingsSection(stringResource(R.string.mycar_show_title))
            SegmentedSwitch(
                options = listOf(false, true),
                chosen = on,
                icon = { if (it) Icons.Filled.Image else Icons.Filled.DirectionsCar },
                title = { stringResource(if (it) R.string.mycar_show_mine else R.string.mycar_show_drawn) },
                onChoose = { MyCarLook.setOn(context, it) }
            )

            SettingsSection(stringResource(R.string.mycar_style_title))
            SegmentedSwitch(
                options = CarLookStyle.entries,
                chosen = style,
                icon = {
                    when (it) {
                        CarLookStyle.PHOTO -> Icons.Filled.Image
                        CarLookStyle.TINTED -> Icons.Filled.Palette
                        CarLookStyle.OUTLINE -> Icons.Filled.Gesture
                    }
                },
                title = { stringResource(it.titleRes) },
                onChoose = { MyCarLook.setStyle(context, it) }
            )
            SwitchHint(stringResource(style.hintRes))
            Text(stringResource(R.string.mycar_where), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                stringResource(R.string.mycar_photos_hint), color = DashColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f)
            )
            SheetButton(stringResource(R.string.mycar_photos), primary = false) { if (!busy) pickPhotos() }
        }
        Text(stringResource(R.string.mycar_phone_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }

    if (removing) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { removing = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.mycar_remove_title), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.mycar_remove_body), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    MyCarLook.remove(context)
                }) { Text(stringResource(R.string.mycar_remove), color = DashColors.Critical) }
            },
            dismissButton = {
                TextButton(onClick = { removing = false }) { Text(stringResource(R.string.mycar_cancel), color = DashColors.Muted) }
            }
        )
    }
}
