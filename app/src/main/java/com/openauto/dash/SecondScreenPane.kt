package com.openauto.dash

import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/*
 * Settings → Display → Second screen: the display's link, the paired
 * displays, what it shows (off, the cluster and its pages, or an app) and how
 * sharp.
 */

@Composable
internal fun SecondScreenSheet(onDismiss: () -> Unit) {
    SettingsSheet(
        title = stringResource(R.string.settings_section_second_screen),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.second_screen_experimental), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        SecondScreenSettings()
    }
}

/** The Display pane's line for the second screen: off, connected, or what it waits for. */
@Composable
internal fun secondScreenSummary(): String {
    val state by DisplayLink.state.collectAsState()
    val config by SecondScreenStore.config.collectAsState()
    val s = state
    return when {
        s == DisplayLinkState.Unpaired -> stringResource(R.string.second_screen_status_unpaired)
        config.mode == SecondScreenMode.OFF -> stringResource(R.string.second_screen_mode_off)
        s is DisplayLinkState.Connected -> stringResource(R.string.second_screen_status_connected, s.display.name)
        else -> stringResource(R.string.second_screen_status_searching)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondScreenSettings() {
    val context = LocalContext.current
    val state by DisplayLink.state.collectAsState()
    val displays by DisplayLink.displays.collectAsState()
    val config by SecondScreenStore.config.collectAsState()
    val status by SecondScreenController.status.collectAsState()
    var choosingApp by remember { mutableStateOf(false) }
    fun update(change: (SecondScreenConfig) -> SecondScreenConfig) = SecondScreenStore.update(context, change)

    // Pairing without the phone app: the display's pairing.txt, from a USB stick.
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString(throwOnInvalidSequence = false) } }.getOrNull()
        val line = text?.lineSequence()?.map { it.trim() }?.firstOrNull { it.startsWith("dashwheel://") }
        val ok = line != null && DisplayLink.pair(context, line)
        val name = DisplayLink.displays.value.lastOrNull()?.name.orEmpty()
        Toast.makeText(
            context,
            if (ok) context.getString(R.string.second_screen_import_done, name) else context.getString(R.string.second_screen_import_failed),
            Toast.LENGTH_LONG
        ).show()
    }

    val connected = state as? DisplayLinkState.Connected
    val (title, detail) = when (val s = state) {
        is DisplayLinkState.Connected -> stringResource(R.string.second_screen_status_connected, s.display.name) to
            stringResource(R.string.second_screen_size, s.display.width, s.display.height)
        DisplayLinkState.Searching -> stringResource(R.string.second_screen_status_searching) to stringResource(R.string.second_screen_status_searching_detail)
        DisplayLinkState.Unpaired -> stringResource(R.string.second_screen_status_unpaired) to stringResource(R.string.second_screen_status_unpaired_detail)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (connected != null) Icons.Filled.CheckCircle else Icons.Filled.Tv, contentDescription = null,
            tint = if (connected != null) DashColors.Good else DashColors.TextSecondary, modifier = Modifier.size(24.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            if (connected != null) {
                statusLine(status)?.let { Text(it, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall) }
            } else {
                DisplayAttemptLine()
            }
        }
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))

    displays.forEach { display ->
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(display.name, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.phone_paired_on, DateFormat.getDateInstance().format(Date(display.pairedAt))),
                    color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { DisplayLink.forget(context, display.id) }) {
                Text(stringResource(R.string.phone_forget), color = DashColors.Accent)
            }
        }
        HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(horizontal = 12.dp))
    }
    SettingsRow(Icons.Filled.FileOpen, stringResource(R.string.second_screen_import), stringResource(R.string.second_screen_import_detail)) {
        importFile.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
    }

    Spacer(Modifier.padding(top = 12.dp))
    SettingsSection(stringResource(R.string.second_screen_show))
    ChoiceRow(
        options = listOf(
            SecondScreenMode.OFF to stringResource(R.string.second_screen_mode_off),
            SecondScreenMode.CLUSTER to stringResource(R.string.second_screen_mode_cluster),
            SecondScreenMode.APP to stringResource(R.string.second_screen_mode_app)
        ),
        selected = { it == config.mode },
        onPick = { mode -> update { it.copy(mode = mode) } }
    )

    when (config.mode) {
        SecondScreenMode.CLUSTER -> {
            Text(
                stringResource(R.string.second_screen_pages), color = DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp)
            )
            ChoiceRow(
                options = ClusterPage.entries.map { it to stringResource(pageName(it)) },
                selected = { it in config.pages },
                onPick = { page ->
                    update { c ->
                        val pages = if (page in c.pages) c.pages - page else ClusterPage.entries.filter { it in c.pages || it == page }
                        if (pages.isEmpty()) c else c.copy(pages = pages, page = if (c.page in pages) c.page else pages.first())
                    }
                }
            )
            SettingsToggle(
                Icons.Filled.HighQuality, stringResource(R.string.second_screen_video),
                stringResource(R.string.second_screen_video_detail), config.video
            ) { on -> update { it.copy(video = on) } }
        }
        SecondScreenMode.APP -> {
            if (!SecondScreenRules.appsMovable(android.os.Build.VERSION.SDK_INT)) {
                Text(
                    stringResource(R.string.second_screen_blocked_apps),
                    color = DashColors.Warning, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            val appName = config.appPackage?.let { pkg -> remember(pkg) { appLabel(context, pkg) } }
            SettingsRow(Icons.Filled.Apps, stringResource(R.string.second_screen_app), appName ?: stringResource(R.string.second_screen_app_none)) {
                choosingApp = true
            }
            SettingsToggle(
                Icons.Filled.EventSeat, stringResource(R.string.second_screen_rear_seat),
                stringResource(R.string.second_screen_rear_seat_detail), config.rearSeat
            ) { on -> update { it.copy(rearSeat = on) } }
        }
        SecondScreenMode.OFF -> Unit
    }

    if (config.mode != SecondScreenMode.OFF) {
        Text(
            stringResource(R.string.second_screen_quality) + " · " + stringResource(R.string.second_screen_quality_detail),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp)
        )
        ChoiceRow(
            options = SecondScreenRules.STREAM_HEIGHTS.map { it to "${it}p" },
            selected = { it == config.maxHeight },
            onPick = { h -> update { it.copy(maxHeight = h) } }
        )
    }

    Spacer(Modifier.padding(top = 12.dp))
    SecondScreenKeysSection()

    if (choosingApp) {
        AppChooserDialog(
            onPick = { pkg -> update { it.copy(appPackage = pkg) }; choosingApp = false },
            onDismiss = { choosingApp = false }
        )
    }
}

internal fun pageName(page: ClusterPage): Int = when (page) {
    ClusterPage.DRIVE -> R.string.second_screen_page_drive
    ClusterPage.MEDIA -> R.string.second_screen_page_media
    ClusterPage.NAV -> R.string.second_screen_page_nav
    ClusterPage.OBD -> R.string.second_screen_page_obd
}

/** What the display is being sent, and why not what was asked, if so. */
@Composable
private fun statusLine(status: SecondScreenStatus): String? {
    val block = when (status.block) {
        SecondScreenBlock.NONE -> null
        SecondScreenBlock.NO_APP_CHOSEN -> stringResource(R.string.second_screen_blocked_no_app)
        SecondScreenBlock.NO_VIDEO -> stringResource(R.string.second_screen_blocked_no_video)
        SecondScreenBlock.VIDEO_WHILE_MOVING -> stringResource(R.string.second_screen_blocked_moving)
        SecondScreenBlock.APPS_NOT_SUPPORTED -> stringResource(R.string.second_screen_blocked_apps)
    }
    val output = when (status.output) {
        SecondScreenOutput.NONE -> null
        SecondScreenOutput.VIDEO_CLUSTER, SecondScreenOutput.VIDEO_APP -> stringResource(R.string.second_screen_output_video, status.kbps)
        SecondScreenOutput.DATA -> stringResource(R.string.second_screen_output_data)
    }
    return listOfNotNull(output, block).joinToString(" · ").ifEmpty { null }
}

private fun appLabel(context: android.content.Context, pkg: String): String =
    runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

@Composable
private fun DisplayAttemptLine() {
    val line = DisplayLink.lastAttempt.collectAsState().value ?: return
    Text(
        line,
        color = DashColors.Muted,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
        maxLines = 2
    )
}

/** A row of choices, like the language list's options but side by side. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: (T) -> Boolean, onPick: (T) -> Unit) {
    val tap = rememberTapFeedback()
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val on = selected(value)
            val shape = DashShape.Medium
            Text(
                label,
                color = if (on) DashColors.TextPrimary else DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .border(if (on) 2.dp else 1.dp, if (on) DashColors.Accent else DashColors.CardHi, shape)
                    .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (on) 0.65f else 0.35f), shape)
                    .clickable { tap(); onPick(value) }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

/** The launchable apps, to pick the one the second screen shows. */
@Composable
private fun AppChooserDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) }.filter { it.packageName != context.packageName }
    }
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.second_screen_app_none), color = DashColors.TextPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(apps, key = { it.packageName }) { app ->
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(app.packageName) }.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(app.icon, 36.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(app.label, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) } }
    )
}

/** Settings → Second screen → the steering-wheel keys that turn the cluster's page. */
@Composable
private fun SecondScreenKeysSection() {
    val context = LocalContext.current
    val config by SecondScreenStore.config.collectAsState()
    var learning by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.second_screen_keys))
    val learnt = config.pageKeys.sorted().joinToString(", ") { KeyEvent.keyCodeToString(it).removePrefix("KEYCODE_") }
    SettingsRow(
        Icons.Filled.Keyboard, stringResource(R.string.second_screen_keys_learn),
        if (config.pageKeys.isEmpty()) stringResource(R.string.second_screen_keys_none) else stringResource(R.string.second_screen_keys_count, learnt)
    ) { learning = true }
    if (config.pageKeys.isNotEmpty()) {
        SettingsRow(Icons.Filled.Clear, stringResource(R.string.second_screen_keys_clear), null) {
            SecondScreenStore.update(context) { it.copy(pageKeys = emptySet()) }
        }
    }
    SettingsToggle(
        Icons.Filled.SkipNext, stringResource(R.string.second_screen_media_keys),
        stringResource(R.string.second_screen_media_keys_detail), config.mediaKeysTurnPages
    ) { on -> SecondScreenStore.update(context) { it.copy(mediaKeysTurnPages = on) } }
    SettingsToggle(
        Icons.Filled.Swipe, stringResource(R.string.second_screen_bar_swipe),
        stringResource(R.string.second_screen_bar_swipe_detail), config.barSwipeTurnsPages
    ) { on -> SecondScreenStore.update(context) { it.copy(barSwipeTurnsPages = on) } }

    if (learning) {
        DisposableEffect(Unit) {
            SecondScreenController.keys.learning = { code ->
                SecondScreenStore.update(context) { it.copy(pageKeys = it.pageKeys + code) }
                learning = false
            }
            onDispose { SecondScreenController.keys.learning = null }
        }
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { learning = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.second_screen_keys_learn), color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.second_screen_keys_learn_prompt), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.second_screen_keys_learn_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { learning = false }) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) } }
        )
    }
}
