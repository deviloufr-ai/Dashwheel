package com.openauto.dash

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Fault codes (OBD Diagnostic Trouble Codes). Scans run by themselves when the
 * adapter connects (see [AiMechanic]); Scan / Clear are here for doing it by
 * hand. The tile is the overview (verdict, one card per code); a code opens
 * the AI mechanic's full explanation. Without a Gemini key each code shows
 * the built-in table's description.
 */
@Composable
internal fun ObdDtcCard(
    connection: ObdConnectionState,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
    onPickDevice: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connected = connection == ObdConnectionState.CONNECTED
    val ai by AiMechanic.state.collectAsState()
    val codes = ai.codes
    val diagnosis = ai.diagnosis
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    // Only whether the engine runs matters here: the card must not redraw on every OBD sample.
    val obd = ObdBluetoothManager.data.collectAsState()
    val engineOff by remember { derivedStateOf { obd.value.rpm == 0 } }
    val pending by ObdBluetoothManager.pending.collectAsState()
    // The AI's advice is written in the mechanic's language; its labels follow it.
    // The setting is re-read as the AI's state moves on (a cheap prefs lookup);
    // the localised resources are rebuilt only when the language itself changes.
    val aiLanguage = remember(ai, context) { AiSettings.load(context).language }
    val aiText = remember(aiLanguage, context) { aiLanguage.resources(context) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<DtcMessage?>(null) }
    // The code whose detail sheet is open.
    var opened by remember { mutableStateOf<String?>(null) }
    // The drive lock (DriveLock.kt) holds back the sheet's reading while the car moves; the voice stays.
    val lockWhileMoving = remember(context) { DriveLockStore.load(context) }
    val moving by rememberMoving(lockWhileMoving, DemoMode.isOn)
    val scanFailed = stringResource(R.string.ai_scan_failed)
    val clearFailed = stringResource(R.string.ai_clear_failed)
    val clearedText = stringResource(R.string.ai_cleared)
    val clearCodes = rememberClearCodes {
        busy = true; message = null
        scope.launch {
            val res = ObdBluetoothManager.clearTroubleCodes()
            busy = false
            res.onSuccess { message = DtcMessage(clearedText, failed = false); AiMechanic.cleared() }
                .onFailure { message = DtcMessage(it.message ?: clearFailed, failed = true) }
        }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.vehicle_fault_codes_title),
                    color = DashColors.Accent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                lamp?.let { LampChip(it) }
            }
            Spacer(Modifier.height(10.dp))

            if (!connected) {
                ObdNotConnected(connection, onConnect, onPickDevice)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true; message = null
                            scope.launch {
                                val r = ObdBluetoothManager.readTroubleCodes()
                                busy = false
                                r.onSuccess { AiMechanic.report(it, announce = false) }
                                    .onFailure { message = DtcMessage(it.message ?: scanFailed, failed = true) }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.Background)
                    ) { Text(stringResource(R.string.vehicle_scan)) }
                    Button(
                        enabled = !busy && codes?.isNotEmpty() == true,
                        onClick = clearCodes,
                        colors = ButtonDefaults.buttonColors(containerColor = DashColors.CardHi, contentColor = DashColors.TextPrimary)
                    ) { Text(stringResource(R.string.vehicle_clear)) }
                }
                if (busy) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DashColors.Accent)
                }
                message?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it.text, color = if (it.failed) DashColors.Warning else DashColors.Good, style = MaterialTheme.typography.bodyMedium)
                }
                // Ignition on, engine off: every dashboard lamp is lit for its self-test,
                // which the "lamp off" badge would otherwise seem to contradict.
                if (engineOff) {
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.vehicle_engine_off_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // The alerts the bar showed lately, so one that came and went can still be read.
            if (AlertCenter.history.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                RecentAlerts()
            }

            // Results stay readable after the adapter drops (engine off, parked).
            if (codes != null) {
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (codes.isEmpty() && lamp?.on == true) {
                        // Never a green "no fault" while the engine computer says its lamp is on.
                        Text(stringResource(R.string.vehicle_lamp_no_codes), color = DashColors.Warning, style = MaterialTheme.typography.bodyLarge)
                    } else if (codes.isEmpty()) {
                        Text(stringResource(R.string.ai_no_codes), color = DashColors.Good, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.vehicle_obd_scope), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                    } else {
                        diagnosis?.let { VerdictBand(it, aiText) }
                        if (ai.thinking) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = DashColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(10.dp))
                                Text(stringResource(R.string.ai_thinking), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        ai.note?.let { note ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    AiMechanic.noteText(context, note),
                                    color = DashColors.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                if (ai.canRetry) {
                                    TextButton(onClick = AiMechanic::refresh) { Text(stringResource(R.string.ai_retry), color = DashColors.Accent) }
                                }
                            }
                        }
                        codes.forEachIndexed { index, code ->
                            val advice = adviceFor(diagnosis, codes, code, index)
                            CodeCard(
                                code = code,
                                advice = advice,
                                severity = diagnosis?.severity,
                                pending = code in pending,
                                aiText = aiText,
                                onOpen = if (advice != null) ({ opened = code }) else null
                            )
                        }
                    }
                }
            }
        }
    }

    opened?.let { code ->
        val index = codes?.indexOf(code) ?: -1
        val advice = if (codes != null && index >= 0) adviceFor(diagnosis, codes, code, index) else null
        // A new scan can take the code away while the sheet is open: then it just closes.
        if (advice != null) FaultDetailSheet(code, advice, diagnosis, codes.orEmpty(), aiText, moving) { opened = null }
    }
}

/**
 * What a Clear button runs. Clearing erases the codes stored in the car's
 * computer for good, and a garage reads them to find a fault: it is asked
 * first, and only parked, so a finger aiming at Scan can't do it.
 */
@Composable
internal fun rememberClearCodes(onClear: () -> Unit): () -> Unit {
    val lock = LocalDriveLock.current
    var asking by remember { mutableStateOf(false) }
    if (asking) {
        ParkedOnly { asking = false }
        ConfirmDialog(
            title = stringResource(R.string.vehicle_clear_confirm_title),
            body = stringResource(R.string.vehicle_clear_confirm_body),
            action = stringResource(R.string.vehicle_clear),
            onConfirm = { asking = false; onClear() },
            onDismiss = { asking = false }
        )
    }
    return { lock.whenParked { asking = true } }
}

/**
 * The AI's advice for [code]. Answers come back in the order asked, and the
 * model sometimes mistypes a code (P1351 for P1352), so position is the
 * fallback; the car's own code is always the one shown.
 */
internal fun adviceFor(diagnosis: Diagnosis?, codes: List<String>, code: String, index: Int): CodeAdvice? {
    diagnosis ?: return null
    return diagnosis.codes.firstOrNull { it.code == code }
        ?: diagnosis.codes.getOrNull(index)?.takeIf { diagnosis.codes.size == codes.size }
}

/** A Scan / Clear outcome; [failed] picks the colour, so it never depends on the wording. */
private class DtcMessage(val text: String, val failed: Boolean)

/** The last three alerts, newest first: time, a dot in the alert's colour, what it said. */
@Composable
private fun RecentAlerts() {
    val timeFmt = rememberTimeFormat()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.vehicle_recent_alerts),
            color = DashColors.TextSecondary,
            letterSpacing = 0.08.em,
            style = MaterialTheme.typography.labelSmall
        )
        AlertCenter.history.take(3).forEach { event ->
            val colour = if (event.level == AlertLevel.CRITICAL) DashColors.Critical else DashColors.Warning
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(timeFmt.format(java.util.Date(event.at)), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
                Spacer(Modifier.width(8.dp))
                Text(event.text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun severityColor(severity: Severity?): Color = when (severity) {
    Severity.OK -> DashColors.Good
    Severity.STOP -> DashColors.Critical
    Severity.SOON, null -> DashColors.Warning
}

private fun severityIcon(severity: Severity): ImageVector = when (severity) {
    Severity.OK -> Icons.Filled.CheckCircle
    Severity.SOON -> Icons.Filled.Warning
    Severity.STOP -> Icons.Filled.Error
}

/** Engine lamp as the engine computer reports it: a small pill beside the title. */
@Composable
private fun LampChip(lamp: EngineLamp) {
    val color = if (lamp.on) DashColors.Warning else DashColors.Good
    Row(
        modifier = Modifier
            .border(1.dp, DashColors.Line, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(if (lamp.on) R.string.vehicle_lamp_on else R.string.vehicle_lamp_off),
            color = if (lamp.on) DashColors.Warning else DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** The AI's overall call: a tinted band with what to do and the sentence it spoke. [aiText]: the mechanic's language. */
@Composable
private fun VerdictBand(d: Diagnosis, aiText: Resources) {
    val color = severityColor(d.severity)
    val label = aiText.getString(
        when (d.severity) {
            Severity.OK -> R.string.ai_verdict_ok
            Severity.SOON -> R.string.ai_verdict_soon
            Severity.STOP -> R.string.ai_verdict_stop
        }
    )
    Surface(
        shape = DashShape.Medium,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(severityIcon(d.severity), contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(d.summary, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                if (d.raisedByRules) {
                    Spacer(Modifier.height(4.dp))
                    Text(aiText.getString(R.string.ai_rules_raised), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                AiDisclaimer(d, aiText)
            }
        }
    }
}

/** Says the advice is the AI's, which model gave it, and that a garage has the last word. */
@Composable
private fun AiDisclaimer(d: Diagnosis, aiText: Resources) {
    val source = d.model.ifBlank { aiText.getString(R.string.ai_disclaimer_gemini) }
    Text(aiText.getString(R.string.ai_disclaimer, source), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
}

/** A code on a coloured badge, the way a garage printout sets it apart. */
@Composable
private fun CodeBadge(code: String, severity: Severity?, large: Boolean = false) {
    val color = severityColor(severity)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .padding(horizontal = if (large) 12.dp else 8.dp, vertical = if (large) 6.dp else 4.dp)
    ) {
        Text(
            code,
            color = if (severity == Severity.STOP) Color.White else Color(0xFF111111),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall
        )
    }
}

/**
 * One code: badge, short title, and the first thing to check. With the AI's
 * advice it opens the detail sheet ([onOpen]); otherwise it shows the
 * built-in table's description.
 */
@Composable
private fun CodeCard(code: String, advice: CodeAdvice?, severity: Severity?, pending: Boolean, aiText: Resources, onOpen: (() -> Unit)?) {
    val builtIn = remember(code) { ObdCodes.describe(code) }
    val title = advice?.meaning?.ifBlank { null } ?: builtIn.localizedTitle()
    val hint = advice?.checkFirst?.ifBlank { null }?.let { aiText.getString(R.string.ai_check_first, it) } ?: builtIn.localizedFix()
    val shape = DashShape.Medium
    val content: @Composable () -> Unit = {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CodeBadge(code, severity)
                if (pending) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.vehicle_pending), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(hint, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (onOpen != null) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.ChevronRight, contentDescription = aiText.getString(R.string.ai_details), tint = DashColors.Accent)
            }
        }
    }
    val color = DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.6f)
    if (onOpen != null) {
        Surface(onClick = onOpen, shape = shape, color = color, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Surface(shape = shape, color = color, modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * Everything the AI mechanic said about one code, set out to be read parked:
 * large type, one heading per question, lists as lists, and two columns on the
 * wide head-unit screen so lines stay short.
 */
@Composable
private fun FaultDetailSheet(
    code: String,
    advice: CodeAdvice,
    diagnosis: Diagnosis?,
    codes: List<String>,
    aiText: Resources,
    moving: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val ask by AskMechanic.state.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AskMechanic.listen(context, code, codes, diagnosis) else AskMechanic.micDenied(context)
    }
    val askQuestion = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AskMechanic.listen(context, code, codes, diagnosis)
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    // Leaving the sheet stops listening, and the answer shown goes with it.
    DisposableEffect(Unit) { onDispose { AskMechanic.cancel() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            shape = DashShape.Large,
            color = DashColors.Card.copy(alpha = 1f),
            // Docked app windows are drawn above dialogs on this unit; the ones under it step aside.
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.94f).keepClearOfWindows()
        ) {
            Column {
                Row(modifier = Modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CodeBadge(code, diagnosis?.severity, large = true)
                    Spacer(Modifier.width(14.dp))
                    Text(
                        advice.meaning.ifBlank { code },
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    AskButton(ask, aiText, onClick = askQuestion)
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = aiText.getString(R.string.ai_close), tint = DashColors.TextSecondary)
                    }
                }
                HorizontalDivider(color = DashColors.Line)
                if (ask != AskMechanic.State.Idle) {
                    AskPanel(ask, aiText, onReplay = { AskMechanic.replay(context) })
                    HorizontalDivider(color = DashColors.Line)
                }

                // What it is and what to look for on the left; what to do about it on the right.
                val understand: @Composable () -> Unit = {
                    diagnosis?.takeIf { it.codes.size > 1 && it.overview.isNotBlank() }?.let {
                        DetailSection(Icons.Filled.Info, aiText.getString(R.string.ai_detail_overview)) { DetailText(it.overview) }
                    }
                    if (advice.explanation.isNotBlank()) {
                        DetailSection(Icons.Filled.Info, aiText.getString(R.string.ai_detail_meaning)) { DetailText(advice.explanation) }
                    }
                    if (advice.symptoms.isNotEmpty()) {
                        DetailSection(Icons.Filled.Visibility, aiText.getString(R.string.ai_detail_symptoms)) { DetailList(advice.symptoms, numbered = false) }
                    }
                    if (advice.causes.isNotEmpty()) {
                        DetailSection(Icons.Filled.Search, aiText.getString(R.string.ai_detail_causes)) { DetailList(advice.causes, numbered = true) }
                    }
                }
                val act: @Composable () -> Unit = {
                    if (advice.checkFirst.isNotBlank()) CheckFirstBox(advice.checkFirst, aiText)
                    if (advice.checks.isNotEmpty()) {
                        DetailSection(Icons.Filled.Checklist, aiText.getString(R.string.ai_detail_checks)) { DetailList(advice.checks, numbered = true) }
                    }
                    if (advice.repair.isNotBlank()) {
                        DetailSection(Icons.Filled.Build, aiText.getString(R.string.ai_detail_repair)) { DetailText(advice.repair) }
                    }
                    if (advice.cost.isNotBlank()) {
                        DetailSection(Icons.Filled.Euro, aiText.getString(R.string.ai_detail_cost)) { DetailText(advice.cost) }
                    }
                    if (advice.diy.isNotBlank()) {
                        DetailSection(Icons.Filled.Handyman, aiText.getString(R.string.ai_detail_diy)) { DetailText(advice.diy) }
                    }
                    if (advice.driving.isNotBlank()) {
                        DetailSection(Icons.Filled.DirectionsCar, aiText.getString(R.string.ai_detail_driving)) { DetailText(advice.driving) }
                    }
                }
                if (moving) {
                    // Too much to read at the wheel: the spoken summary and the Ask button stay, the rest waits for a stop.
                    Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        diagnosis?.let { Text(it.summary, color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium) }
                        Text(aiText.getString(R.string.ai_details_parked), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
                    }
                } else {
                    BoxWithConstraints(Modifier.weight(1f)) {
                        val twoColumns = maxWidth > 760.dp
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                            if (twoColumns) {
                                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { understand() }
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { act() }
                                }
                            } else {
                                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    understand()
                                    act()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Ask the mechanic out loud; while listening it becomes Send. */
@Composable
private fun AskButton(state: AskMechanic.State, aiText: Resources, onClick: () -> Unit) {
    val listening = state == AskMechanic.State.Listening
    Button(
        onClick = onClick,
        enabled = state != AskMechanic.State.Thinking,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (listening) DashColors.Warning else DashColors.Accent,
            contentColor = if (listening) Color.White else DashColors.OnAccent,
            disabledContainerColor = DashColors.CardHi,
            disabledContentColor = DashColors.Muted
        )
    ) {
        Icon(if (listening) Icons.AutoMirrored.Filled.Send else Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(aiText.getString(if (listening) R.string.ai_ask_send else R.string.ai_ask))
    }
}

/** Under the title: listening, thinking, or the question as heard and the answer (which is also spoken). */
@Composable
private fun AskPanel(state: AskMechanic.State, aiText: Resources, onReplay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DashColors.Accent.copy(alpha = 0.08f))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when (state) {
            AskMechanic.State.Listening -> {
                Icon(Icons.Filled.Mic, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(aiText.getString(R.string.ai_ask_listening), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            AskMechanic.State.Thinking -> {
                CircularProgressIndicator(color = DashColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Text(aiText.getString(R.string.ai_ask_thinking), color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
            is AskMechanic.State.Answered -> {
                Icon(Icons.Filled.QuestionAnswer, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (state.exchange.heard.isNotBlank()) {
                        Text(
                            aiText.getString(R.string.ai_ask_you_asked, state.exchange.heard),
                            color = DashColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(state.exchange.answer, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onReplay) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = aiText.getString(R.string.ai_ask_replay), tint = DashColors.Accent)
                }
            }
            AskMechanic.State.NothingHeard ->
                Text(aiText.getString(R.string.ai_ask_nothing_heard), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyLarge)
            is AskMechanic.State.Failed ->
                Text(state.reason, color = DashColors.Warning, style = MaterialTheme.typography.bodyLarge)
            AskMechanic.State.Idle -> Unit
        }
    }
}

@Composable
private fun DetailSection(icon: ImageVector, title: String, body: @Composable () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(6.dp))
        body()
    }
}

@Composable
private fun DetailText(text: String) {
    Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
}

/** One line per item: numbered when the order matters (likelihood, steps), bulleted otherwise. */
@Composable
private fun DetailList(items: List<String>, numbered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, item ->
            Row {
                Text(
                    if (numbered) "${i + 1}." else "•",
                    color = DashColors.Accent,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.width(26.dp)
                )
                Text(item, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The one thing to look at first, set apart so it's found at a glance. */
@Composable
private fun CheckFirstBox(text: String, aiText: Resources) {
    Surface(
        shape = DashShape.Medium,
        color = DashColors.Good.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, DashColors.Good.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = DashColors.Good, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text(aiText.getString(R.string.ai_detail_check_first), color = DashColors.Good, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
