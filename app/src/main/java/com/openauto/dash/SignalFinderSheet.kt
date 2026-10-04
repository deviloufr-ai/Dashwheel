package com.openauto.dash

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AirlineSeatReclineNormal
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Settings, Car, Car signals: the Signal Finder. What Dashwheel reads from
 * the car and from where; Find teaches it one more by doing the action
 * ([SignalFinder]); the raw sniffer shows every value as it changes.
 */

private sealed interface SignalScreen {
    data object List : SignalScreen
    data class Capture(val target: CarSignal) : SignalScreen
    data class Assign(val target: CarSignal, val candidate: SignalCandidate) : SignalScreen
    data object Sniffer : SignalScreen
}

@Composable
internal fun SignalFinderSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var screen by remember { mutableStateOf<SignalScreen>(SignalScreen.List) }
    // Everything is listened to while the sheet is open: the counts, the live test, the sniffer.
    DisposableEffect(Unit) {
        SignalSources.start(context)
        onDispose {
            SignalFinder.stop()
            SignalSources.stop()
        }
    }
    when (val s = screen) {
        SignalScreen.List -> SignalList(
            onDismiss = onDismiss,
            onFind = { SignalFinder.start(context, it); screen = SignalScreen.Capture(it) },
            onSniffer = { screen = SignalScreen.Sniffer }
        )
        is SignalScreen.Capture -> SignalCapture(
            target = s.target,
            onBack = { SignalFinder.stop(); screen = SignalScreen.List },
            onDismiss = onDismiss,
            onPick = { screen = SignalScreen.Assign(s.target, it) }
        )
        is SignalScreen.Assign -> SignalAssign(
            target = s.target,
            candidate = s.candidate,
            onBack = { screen = SignalScreen.Capture(s.target) },
            onDismiss = onDismiss,
            onAssigned = { SignalFinder.stop(); screen = SignalScreen.List }
        )
        SignalScreen.Sniffer -> SignalSniffer(onBack = { screen = SignalScreen.List }, onDismiss = onDismiss)
    }
}

// --- The list -------------------------------------------------------------------------------------

@Composable
private fun SignalList(onDismiss: () -> Unit, onFind: (CarSignal) -> Unit, onSniffer: () -> Unit) {
    val context = LocalContext.current
    val mapped by LearnedSignals.mapped.collectAsState()
    val values by SignalSources.values.collectAsState()
    val canReadable by SignalSources.canReadable.collectAsState()
    val doors by McuReader.doorState.collectAsState()
    val body by CarBox.body.collectAsState()
    val camera by LearnedSignals.cameraId.collectAsState()
    var picking by remember { mutableStateOf(false) }

    SettingsSheet(
        title = stringResource(R.string.signals_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.signals_raw), primary = false, onClick = onSniffer) }
    ) {
        Text(stringResource(R.string.signals_intro), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        SettingsSection(stringResource(R.string.signals_sources))
        for (source in SignalSource.entries) {
            val count = values.keys.count { it.startsWith(source.prefix + ":") }
            val detail = when {
                source == SignalSource.CAN && !canReadable -> stringResource(R.string.signals_needs_root)
                else -> stringResource(R.string.signals_values, count)
            }
            SourceLine(sourceIcon(source), stringResource(sourceTitle(source)), detail, live = count > 0)
        }

        SettingsSection(stringResource(R.string.signals_section))
        for (signal in CarSignal.entries) {
            val probe = mapped[signal]
            val builtIn = when {
                // Read again whenever the doors change; only the car's own count, not a learned one.
                signal.isDoor -> doors.let { McuReader.ownDoorsKnown }
                signal == CarSignal.DRIVER_BELT -> body != null
                else -> CarBox.available
            }
            val detail = when {
                probe != null -> stringResource(R.string.signals_learned, describe(context, probe))
                builtIn -> stringResource(R.string.signals_builtin)
                else -> stringResource(R.string.signals_missing)
            }
            SignalRow(
                icon = signalIcon(signal),
                title = stringResource(signal.title),
                detail = detail,
                found = probe != null || builtIn,
                onFind = { onFind(signal) },
                onForget = if (probe != null) ({ LearnedSignals.forget(context, signal) }) else null
            )
        }

        SettingsSection(stringResource(R.string.signals_camera))
        SettingsRow(
            Icons.Filled.Videocam,
            stringResource(R.string.signals_camera),
            camera?.let { stringResource(R.string.signals_camera_chosen, it) } ?: stringResource(R.string.signals_camera_rom)
        ) { picking = true }
    }

    if (picking) CameraPicker(onDismiss = { picking = false })
}

@Composable
private fun SourceLine(icon: ImageVector, title: String, detail: String, live: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.size(10.dp).clip(DashShape.Pill).background(if (live) DashColors.Good else DashColors.Line))
        Spacer(Modifier.width(12.dp))
        Icon(icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, color = DashColors.TextPrimary, modifier = Modifier.weight(1f))
        Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SignalRow(icon: ImageVector, title: String, detail: String, found: Boolean, onFind: () -> Unit, onForget: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = DashSize.Bar).clip(DashShape.Medium).background(DashColors.CardHi).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = if (found) DashColors.Good else DashColors.Warning, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold)
            Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onForget != null) {
            Spacer(Modifier.width(8.dp))
            SheetButton(stringResource(R.string.signals_forget), primary = false, onClick = onForget)
        }
        Spacer(Modifier.width(8.dp))
        SheetButton(stringResource(if (found) R.string.signals_find_again else R.string.signals_find), primary = !found, onClick = onFind)
    }
}

/** The cameras the unit has: the reverse view opens the one picked, else the car app's. */
@Composable
private fun CameraPicker(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val cameras = remember { cameraList(context) }
    val chosen by LearnedSignals.cameraId.collectAsState()
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.signals_camera), color = DashColors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceLine(stringResource(R.string.signals_camera_rom), selected = chosen == null) {
                    LearnedSignals.setCamera(context, null); onDismiss()
                }
                for ((id, size) in cameras) {
                    ChoiceLine(stringResource(R.string.signals_camera_item, id, size), selected = chosen == id) {
                        LearnedSignals.setCamera(context, id); onDismiss()
                    }
                }
                if (cameras.isEmpty()) Text(stringResource(R.string.signals_camera_none), color = DashColors.TextSecondary)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.signals_close), color = DashColors.Accent) } }
    )
}

@Composable
private fun ChoiceLine(text: String, selected: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = DashSize.TouchPrimary)
            .clip(DashShape.Medium)
            .background(if (selected) DashColors.Accent.copy(alpha = 0.18f) else DashColors.CardHi)
            .clickable(role = Role.RadioButton) { tap(); onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(text, color = DashColors.TextPrimary, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Each camera's id and its largest picture ("1920×1080"). */
private fun cameraList(context: Context): List<Pair<String, String>> = runCatching {
    val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    manager.cameraIdList.map { id ->
        val map = runCatching { manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }.getOrNull()
        val biggest = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)?.maxByOrNull { it.width * it.height }
        id to (biggest?.let { "${it.width}×${it.height}" } ?: "?")
    }
}.getOrDefault(emptyList())

// --- Capture --------------------------------------------------------------------------------------

@Composable
private fun SignalCapture(target: CarSignal, onBack: () -> Unit, onDismiss: () -> Unit, onPick: (SignalCandidate) -> Unit) {
    val context = LocalContext.current
    val state by SignalFinder.state.collectAsState()
    val s = state
    SettingsSheet(
        title = stringResource(target.title),
        onDismiss = onDismiss,
        onStepBack = onBack,
        actions = {
            SheetButton(stringResource(R.string.signals_restart), primary = false) { SignalFinder.start(context, target) }
            if (s != null && s.step != SignalFinder.Step.DONE && s.step != SignalFinder.Step.STILL) {
                SheetButton(stringResource(R.string.signals_done)) { SignalFinder.next() }
            }
        }
    ) {
        if (s == null) return@SettingsSheet
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for (round in 1..SignalFinder.ROUNDS) {
                val now = s.step != SignalFinder.Step.DONE && s.step != SignalFinder.Step.STILL && round == s.round
                val done = round <= s.done
                val tone = when { done -> DashColors.Good; now -> DashColors.Warning; else -> DashColors.Line }
                Column(
                    Modifier.weight(1f).clip(DashShape.Medium).background(tone.copy(alpha = 0.16f))
                        .border(if (now) 2.dp else 0.dp, if (now) tone else tone.copy(alpha = 0f), DashShape.Medium)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.signals_round, round, SignalFinder.ROUNDS), color = if (done || now) tone else DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(if (done) R.string.signals_round_done else if (now) R.string.signals_round_now else R.string.signals_round_todo),
                        color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        val prompt = when (s.step) {
            SignalFinder.Step.STILL -> stringResource(R.string.signals_say_still)
            SignalFinder.Step.ON -> stringResource(target.onPrompt)
            SignalFinder.Step.OFF -> stringResource(target.offPrompt)
            SignalFinder.Step.DONE -> stringResource(
                if (s.candidates.any { it.matched == SignalFinder.ROUNDS }) R.string.signals_say_found else R.string.signals_say_none
            )
        }
        Text(prompt, color = DashColors.TextPrimary, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.signals_capture_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(DashShape.Pill).background(if (s.step == SignalFinder.Step.DONE) DashColors.Line else DashColors.Critical))
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.signals_listening, s.values), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        SettingsSection(stringResource(R.string.signals_results))
        if (s.candidates.isEmpty()) {
            Text(stringResource(R.string.signals_results_none), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        for (c in s.candidates) CandidateRow(c, onUse = { onPick(c) })
    }
}

@Composable
private fun CandidateRow(c: SignalCandidate, onUse: () -> Unit) {
    val context = LocalContext.current
    val full = c.matched == c.rounds
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(DashShape.Medium).background(DashColors.CardHi).padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(DashShape.Pill).background((if (full) DashColors.Good else DashColors.Warning).copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.signals_score, c.matched, c.rounds), color = if (full) DashColors.Good else DashColors.Warning, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(14.dp))
        Text(
            describe(context, c.probe), color = DashColors.TextPrimary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        SheetButton(stringResource(R.string.signals_use), primary = full, onClick = onUse)
    }
}

// --- Assign ---------------------------------------------------------------------------------------

@Composable
private fun SignalAssign(target: CarSignal, candidate: SignalCandidate, onBack: () -> Unit, onDismiss: () -> Unit, onAssigned: () -> Unit) {
    val context = LocalContext.current
    val values by SignalSources.values.collectAsState()
    val on = candidate.probe.isOn(values[candidate.probe.key])
    SettingsSheet(
        title = stringResource(target.title),
        onDismiss = onDismiss,
        onStepBack = onBack,
        actions = {
            SheetButton(stringResource(R.string.signals_not_this), primary = false, onClick = onBack)
            SheetButton(stringResource(R.string.signals_assign)) {
                LearnedSignals.assign(context, target, candidate.probe)
                onAssigned()
            }
        }
    ) {
        Text(describe(context, candidate.probe), color = DashColors.TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.signals_matched, candidate.matched, candidate.rounds), color = DashColors.TextSecondary)
        SettingsSection(stringResource(R.string.signals_try))
        val tone = when (on) { true -> DashColors.Warning; false -> DashColors.Good; null -> DashColors.Line }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clip(DashShape.Medium).background(tone.copy(alpha = 0.16f)).padding(horizontal = 18.dp, vertical = 16.dp)
        ) {
            Icon(signalIcon(target), contentDescription = null, tint = tone, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(when (on) { true -> R.string.signals_live_on; false -> R.string.signals_live_off; null -> R.string.signals_live_unknown }),
                color = DashColors.TextPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold
            )
        }
        Text(stringResource(R.string.signals_try_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Raw sniffer ----------------------------------------------------------------------------------

@Composable
private fun SignalSniffer(onBack: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(SignalSource.CAN) }
    var onlyChanges by remember { mutableStateOf(true) }
    var paused by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val changedAt = remember { mutableStateMapOf<String, Long>() }
    val log = remember { ArrayDeque<String>() }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val sent = stringResource(R.string.signals_sent)
    val notSent = stringResource(R.string.signals_not_sent)

    LaunchedEffect(Unit) {
        var previous: Map<String, String>? = null
        SignalSources.values.collect { v ->
            val before = previous
            if (before != null) for ((k, value) in v) {
                val old = before[k]
                // One heard for the first time is news too: a message sent only when a door opens.
                if (old != value) {
                    changedAt[k] = System.currentTimeMillis()
                    log.addLast("${MillisClock.format(System.currentTimeMillis())}  $k  ${old ?: "(new)"} -> $value")
                    while (log.size > LOG_LINES) log.removeFirst()
                }
            }
            previous = v
            if (!paused) shown = v
        }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); now = System.currentTimeMillis() }
    }

    SettingsSheet(
        title = stringResource(R.string.signals_raw),
        onDismiss = onDismiss,
        onStepBack = onBack,
        actions = {
            SheetButton(stringResource(if (paused) R.string.signals_resume else R.string.signals_pause), primary = false) { paused = !paused }
            SheetButton(stringResource(R.string.signals_mark), primary = false) {
                log.addLast("${MillisClock.format(System.currentTimeMillis())}  ---- mark ----")
            }
            SheetButton(stringResource(R.string.signals_send)) {
                val text = recording(SignalSources.values.value, log.toList())
                scope.launch {
                    val ok = PhoneLink.sendLog("Dashwheel signals", text) == true
                    Toast.makeText(context, if (ok) sent else notSent, Toast.LENGTH_LONG).show()
                }
            }
        }
    ) {
        SegmentedSwitch(
            options = SignalSource.entries,
            chosen = source,
            icon = ::sourceIcon,
            title = { stringResource(sourceShort(it)) },
            onChoose = { source = it }
        )
        SwitchRow(stringResource(R.string.signals_only_changes), stringResource(R.string.signals_only_changes_detail), checked = onlyChanges) { onlyChanges = it }
        val rows = shown.entries
            .filter { it.key.startsWith(source.prefix + ":") && (!onlyChanges || it.key in changedAt) }
            .sortedWith(compareByDescending<Map.Entry<String, String>> { changedAt[it.key] ?: 0L }.thenBy { it.key })
            .take(SHOWN_ROWS)
        if (rows.isEmpty()) {
            Text(stringResource(if (onlyChanges) R.string.signals_sniffer_still else R.string.signals_sniffer_empty), color = DashColors.TextSecondary)
        }
        for ((key, value) in rows) {
            val at = changedAt[key]
            val fresh = at != null && now - at < FRESH_MS
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(DashShape.Small).background(if (fresh) DashColors.Warning.copy(alpha = 0.14f) else DashColors.CardHi).padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(SignalSource.name(key), color = DashColors.TextPrimary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.4f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                Text(value, color = if (fresh) DashColors.Warning else DashColors.TextSecondary, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (at == null) "" else if (now - at < 1_500) stringResource(R.string.signals_just_now) else stringResource(R.string.signals_ago, ((now - at) / 1000).toInt()),
                    color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Text(stringResource(R.string.signals_sniffer_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

private const val LOG_LINES = 3_000
private const val SHOWN_ROWS = 150
private const val FRESH_MS = 2_000L

private object MillisClock {
    private val format = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
    @Synchronized fun format(at: Long): String = format.format(java.util.Date(at))
}

/** The sniffer's recording for the phone: the changes as they came, the marks, and every value at the end. */
private fun recording(values: Map<String, String>, log: List<String>): String = buildString {
    append("Dashwheel signal recording\n\nChanges:\n")
    log.forEach { append(it).append('\n') }
    append("\nValues now:\n")
    values.toSortedMap().forEach { (k, v) -> append(k).append(" = ").append(v).append('\n') }
}

// --- Words and icons ------------------------------------------------------------------------------

/** A probe in words: where it is and the value that means on. */
internal fun describe(context: Context, probe: SignalProbe): String {
    val name = SignalSource.name(probe.key).let { n ->
        // A message's action is long; its last word says enough.
        if (SignalSource.of(probe.key) == SignalSource.BROADCAST) n.substringAfterLast('.') else n
    }
    val where = when (SignalSource.of(probe.key)) {
        SignalSource.CAN -> context.getString(R.string.signals_probe_can, name)
        SignalSource.SHARED -> context.getString(R.string.signals_probe_shared, name)
        SignalSource.FLAG -> context.getString(R.string.signals_probe_flag, name)
        SignalSource.SETTING -> context.getString(R.string.signals_probe_setting, name)
        SignalSource.BROADCAST, null -> context.getString(R.string.signals_probe_message, name)
    }
    return when {
        probe.bit >= 0 -> context.getString(R.string.signals_probe_bit, where, probe.byteIndex, probe.bit)
        probe.byteIndex >= 0 -> context.getString(R.string.signals_probe_byte, where, probe.byteIndex, probe.onValue)
        else -> context.getString(R.string.signals_probe_whole, where, probe.onValue)
    }
}

private fun sourceTitle(s: SignalSource): Int = when (s) {
    SignalSource.CAN -> R.string.signals_source_can
    SignalSource.SHARED -> R.string.signals_source_shared
    SignalSource.FLAG -> R.string.signals_source_flags
    SignalSource.SETTING -> R.string.signals_source_settings
    SignalSource.BROADCAST -> R.string.signals_source_messages
}

private fun sourceShort(s: SignalSource): Int = when (s) {
    SignalSource.CAN -> R.string.signals_tab_can
    SignalSource.SHARED -> R.string.signals_tab_shared
    SignalSource.FLAG -> R.string.signals_tab_flags
    SignalSource.SETTING -> R.string.signals_tab_settings
    SignalSource.BROADCAST -> R.string.signals_tab_messages
}

private fun sourceIcon(s: SignalSource): ImageVector = when (s) {
    SignalSource.CAN -> Icons.Filled.Memory
    SignalSource.SHARED -> Icons.Filled.DirectionsCar
    SignalSource.FLAG -> Icons.Filled.Flag
    SignalSource.SETTING -> Icons.Filled.Tune
    SignalSource.BROADCAST -> Icons.Filled.Campaign
}

private fun signalIcon(s: CarSignal): ImageVector = when (s) {
    CarSignal.REVERSE -> Icons.Filled.SwapVert
    CarSignal.HEADLIGHTS -> Icons.Filled.Highlight
    CarSignal.DRIVER_BELT -> Icons.Filled.AirlineSeatReclineNormal
    else -> Icons.Filled.SensorDoor
}
