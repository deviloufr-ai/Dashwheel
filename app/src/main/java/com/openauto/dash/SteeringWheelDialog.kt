package com.openauto.dash

import android.view.KeyEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Learning tool for the head unit's steering wheel (or remote) buttons: press
 * one, then pick what it does. A learned button runs straight from
 * MainActivity's key dispatch (SteeringWheelStore.kt); this file is only the
 * screen that teaches it.
 */

private enum class WheelStep { LIST, LISTENING, PICK_ACTION, PICK_APP }

@Composable
internal fun SteeringWheelDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val mappings by SteeringWheelStore.mappings.collectAsState()
    val captured by SteeringWheelStore.captured.collectAsState()

    var step by remember { mutableStateOf(WheelStep.LIST) }
    var editingKey by remember { mutableStateOf<WheelKey?>(null) }

    // A key arrived while listening: go straight to picking what it should do.
    LaunchedEffect(captured) {
        captured?.let {
            editingKey = it
            step = WheelStep.PICK_ACTION
            SteeringWheelStore.consumeCaptured()
        }
    }

    // However the screen goes away, stop waiting for a key so a press
    // afterwards doesn't get silently swallowed by SteeringWheelStore.
    DisposableEffect(Unit) {
        onDispose { SteeringWheelStore.stopListening() }
    }

    fun backToList() {
        SteeringWheelStore.stopListening()
        step = WheelStep.LIST
    }

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (step != WheelStep.LIST) {
                    IconButton(onClick = ::backToList, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.wheel_back), tint = DashColors.TextPrimary)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(stringResource(R.string.wheel_title), color = DashColors.TextPrimary)
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                when (step) {
                    WheelStep.LIST -> WheelList(
                        mappings = mappings,
                        onLearn = {
                            SteeringWheelStore.startListening()
                            step = WheelStep.LISTENING
                        },
                        onEdit = { key ->
                            editingKey = key
                            step = WheelStep.PICK_ACTION
                        },
                        onRemove = { key -> SteeringWheelStore.remove(key) }
                    )
                    WheelStep.LISTENING -> WheelListening(onCancel = ::backToList)
                    WheelStep.PICK_ACTION, WheelStep.PICK_APP -> editingKey?.let { key ->
                        WheelActionPicker(
                            keyLabel = wheelKeyLabel(key),
                            hasExisting = mappings.any { it.key.id == key.id },
                            onPick = { action ->
                                SteeringWheelStore.assign(key, WheelAssignment.Preset(action))
                                step = WheelStep.LIST
                            },
                            onPickApp = { step = WheelStep.PICK_APP },
                            onRemove = {
                                SteeringWheelStore.remove(key)
                                step = WheelStep.LIST
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_done), color = DashColors.Accent) }
        }
    )

    if (step == WheelStep.PICK_APP) {
        val apps = remember { AppLauncher.loadApps(context) }
        AppPickerDialog(
            apps = apps,
            onPick = { app ->
                editingKey?.let { key -> SteeringWheelStore.assign(key, WheelAssignment.LaunchApp(app.packageName, app.label)) }
                step = WheelStep.LIST
            },
            onDismiss = { step = WheelStep.PICK_ACTION }
        )
    }
}

@Composable
private fun WheelList(
    mappings: List<WheelMapping>,
    onLearn: () -> Unit,
    onEdit: (WheelKey) -> Unit,
    onRemove: (WheelKey) -> Unit
) {
    // Without the accessibility service a key only reaches Dashwheel while it
    // is in front; polled, it isn't observable and the user may just have turned it on.
    var accessibilityOn by remember { mutableStateOf(SplitLauncher.isSystemSplitAvailable()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            accessibilityOn = SplitLauncher.isSystemSplitAvailable()
        }
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.wheel_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onLearn, colors = buttonColors(), modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.wheel_learn_button))
        }
        if (!accessibilityOn && mappings.isNotEmpty()) AccessibilityHint(stringResource(R.string.wheel_accessibility_anywhere))
        HorizontalDivider(color = DashColors.Line)
        if (mappings.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.wheel_empty), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            mappings.sortedBy { it.key.label }.forEach { mapping ->
                WheelMappingRow(mapping, onClick = { onEdit(mapping.key) }, onRemove = { onRemove(mapping.key) })
            }
        }
    }
}

@Composable
private fun WheelMappingRow(mapping: WheelMapping, onClick: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).itemFill(DashColors.CardHi, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            when (val a = mapping.assignment) {
                is WheelAssignment.Preset ->
                    Icon(a.action.icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(20.dp))
                is WheelAssignment.LaunchApp -> {
                    val icon = remember(a.packageName) { loadAppIcon(context, a.packageName) }
                    if (icon != null) AppIcon(icon = icon, size = 26.dp)
                    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(wheelKeyLabel(mapping.key), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(assignmentSummary(mapping.assignment), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { tap(); onRemove() }) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.wheel_remove), tint = DashColors.Muted)
        }
    }
}

/** [WheelKey.label], with the name of a raw, unnamed button in the chosen language. */
@Composable
private fun wheelKeyLabel(key: WheelKey): String = when {
    key.canKey != null -> stringResource(R.string.wheel_can_button, key.canHex.orEmpty())
    key.keyCode == KeyEvent.KEYCODE_UNKNOWN -> stringResource(R.string.wheel_raw_button, key.scanCode)
    // The head unit's own keys (HeadUnitKeys): by the name on the button.
    key.keyCode >= HeadUnitKeys.FIRST_VENDOR_KEY -> HeadUnitKeys.nameRes[key.keyCode]?.let { stringResource(it) }
        ?: HeadUnitKeys.customNumber(key.keyCode)?.let { stringResource(R.string.wheel_unit_key_n, it) }
        ?: stringResource(R.string.wheel_unit_key_code, key.keyCode)
    else -> key.label
}

@Composable
private fun assignmentSummary(assignment: WheelAssignment): String = when (assignment) {
    is WheelAssignment.Preset -> stringResource(assignment.action.labelRes)
    is WheelAssignment.LaunchApp -> assignment.appLabel
}

/**
 * The "press a button now" screen: a pulsing badge so it's obvious the app is
 * waiting, plus a way out. Once a CAN press is seen it asks for the same one
 * again ([SteeringWheelStore.candidate]) before taking it as a button.
 */
@Composable
private fun WheelListening(onCancel: () -> Unit) {
    val candidate by SteeringWheelStore.candidate.collectAsState()
    val transition = rememberInfiniteTransition(label = "wheel-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label = "wheel-pulse-scale"
    )
    // A dialog is its own window: while it's up, keys go to it, not to
    // MainActivity.dispatchKeyEvent. So this screen takes focus and hands
    // them to the store itself.
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onPreviewKeyEvent { SteeringWheelStore.onKeyEvent(context, it.nativeKeyEvent) }
            .focusable()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier.size(88.dp).scale(scale).clip(CircleShape).background(DashColors.AccentBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.TouchApp, contentDescription = null, tint = DashColors.OnAccent, modifier = Modifier.size(40.dp))
        }
        Text(
            stringResource(if (candidate != null) R.string.wheel_confirm_title else R.string.wheel_listening_title),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.titleMedium
        )
        val seen = candidate
        if (seen != null) {
            Text(
                stringResource(R.string.wheel_confirm_detail, wheelKeyLabel(seen)),
                color = DashColors.TextPrimary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DashShape.Small)
                    .background(DashColors.Accent.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        } else {
            Text(
                stringResource(R.string.wheel_listening_detail),
                color = DashColors.TextSecondary,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        // The CAN stream is read through root: where there is none, there is
        // nothing to report about it (see PrivilegedShell).
        if (shellAccess().root) CanStatus()
        MonitorPanel()
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.dash_cancel)) }
    }
}

@Composable
private fun WheelActionPicker(
    keyLabel: String,
    hasExisting: Boolean,
    onPick: (SteeringWheelAction) -> Unit,
    onPickApp: () -> Unit,
    onRemove: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.wheel_assign_prompt, keyLabel),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        AddChoiceRow(icon = Icons.Filled.Apps, label = stringResource(R.string.wheel_choose_app), onClick = onPickApp)
        // Not observable: re-read so the hint goes away on coming back from system settings.
        var accessibilityOn by remember { mutableStateOf(SplitLauncher.isSystemSplitAvailable()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                accessibilityOn = SplitLauncher.isSystemSplitAvailable()
            }
        }
        WheelActionGroup.entries.forEach { group ->
            Spacer(Modifier.height(8.dp))
            SettingsSection(stringResource(group.labelRes))
            if (group == WheelActionGroup.SYSTEM && !accessibilityOn) AccessibilityHint(stringResource(R.string.wheel_accessibility_hint))
            SteeringWheelAction.entries.filter { it.group == group }.forEach { action ->
                WheelActionRow(action, unavailable = action.needsAccessibility && !accessibilityOn, onClick = { onPick(action) })
            }
        }
        if (hasExisting) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRemove) {
                Text(stringResource(R.string.wheel_remove_binding), color = DashColors.Critical)
            }
        }
    }
}

/** One action to pick. [unavailable]: still pickable (it works once Accessibility is on), but says so. */
@Composable
private fun WheelActionRow(action: SteeringWheelAction, unavailable: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(action.icon, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(action.labelRes), color = DashColors.TextPrimary)
            if (unavailable) {
                Text(stringResource(R.string.wheel_needs_accessibility), color = DashColors.Warning, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Why to turn Dashwheel on under Accessibility, with a way there: back, recents,
 * notifications, split screen go through the service, and it is what puts a
 * learned key in front of whatever app is showing.
 */
@Composable
private fun AccessibilityHint(text: String) {
    val context = LocalContext.current
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Small)
            .background(DashColors.Warning.copy(alpha = 0.12f))
            .clickable { tap(); SplitLauncher.openAccessibilitySettings(context) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Accessibility, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text,
            color = DashColors.TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.wheel_accessibility_enable), color = DashColors.Warning, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
    }
}

/**
 * Whether the car's CAN data can be read (root): on MCU units that's where the
 * wheel buttons are, so saying it can't is better than recording nothing.
 * Given a few seconds to start before saying so.
 */
@Composable
private fun CanStatus() {
    val entries by McuReader.entries.collectAsState()
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(4_000)
        waited = true
    }
    val live = entries.isNotEmpty()
    if (!live && !waited) return
    Text(
        stringResource(if (live) R.string.wheel_listening_can_live else R.string.wheel_listening_can_off),
        color = if (live) DashColors.Good else DashColors.Warning,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall
    )
}

/**
 * Everything the unit shows while waiting (WheelMonitor): where this
 * firmware's wheel buttons surface. A key or CAN line can be tapped to learn
 * it when the press-and-release wasn't recognised by itself.
 */
@Composable
private fun MonitorPanel() {
    val lines by WheelMonitor.lines.collectAsState()
    val tap = rememberTapFeedback()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.wheel_monitor_title),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            stringResource(if (lines.any { it.learn != null }) R.string.wheel_monitor_hint else R.string.wheel_monitor_empty),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
        lines.forEach { line ->
            val learn = line.learn
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DashShape.Small)
                    .then(
                        if (learn != null) Modifier
                            .background(DashColors.Accent.copy(alpha = 0.12f))
                            .clickable { tap(); SteeringWheelStore.learnFromMonitor(learn) }
                        else Modifier
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    line.source.name,
                    color = if (learn != null) DashColors.Accent else DashColors.Muted,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(48.dp)
                )
                Text(
                    line.text,
                    color = DashColors.TextPrimary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
