package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** The value of an extra reading as shown: "42 %", "Regenerating", "312 °C". */
@Composable
internal fun extraValueText(reading: ExtraReading, value: Double): String = when (reading) {
    ExtraReading.REGEN_ACTIVE -> stringResource(if (value >= 0.5) R.string.explore_regen_yes else R.string.explore_regen_no)
    else -> String.format(Locale.getDefault(), if (value == Math.rint(value)) "%.0f %s" else "%.1f %s", value, reading.unit).trim()
}

/**
 * EXPERIMENTAL "Advanced readings": what the car has confirmed, a Search
 * button that asks the AI and tries every candidate on the adapter, and each
 * candidate's fate.
 */
@Composable
internal fun PidExplorerDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by PidExplorer.state.collectAsState()
    val readings by PidExplorer.readings.collectAsState()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    var failure by remember { mutableStateOf<String?>(null) }
    var waited by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.searching) {
        waited = 0
        while (state.searching) {
            delay(1_000)
            waited++
        }
    }

    SettingsSheet(
        title = stringResource(R.string.explore_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.explore_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)

        Label(stringResource(R.string.explore_verified))
        if (state.verified.isEmpty()) {
            Text(stringResource(R.string.explore_none_verified), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        state.verified.forEach { c ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(c.reading.labelRes), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOf(c.label, c.formula).joinToString(" · ") +
                            " · " + stringResource(if (c.fromAi) R.string.explore_source_ai else R.string.explore_source_standard),
                        color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                val v = readings[c.reading]
                Text(
                    v?.let { extraValueText(c.reading, it.value) } ?: "--",
                    color = if (v != null) DashColors.Good else DashColors.Muted, fontWeight = FontWeight.Bold
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !state.searching && connection == ObdConnectionState.CONNECTED,
                onClick = {
                    failure = null
                    scope.launch { PidExplorer.search().onFailure { failure = it.message } }
                },
                colors = buttonColors()
            ) { Text(stringResource(R.string.explore_search)) }
            Spacer(Modifier.size(12.dp))
            when {
                state.searching -> {
                    CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.explore_searching, waited), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                connection != ObdConnectionState.CONNECTED ->
                    Text(stringResource(R.string.vehicle_obd_not_connected), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                failure != null || state.error != null ->
                    Text(failure ?: state.error.orEmpty(), color = DashColors.Warning, style = MaterialTheme.typography.bodySmall)
                state.searched && state.results.none { it.verdict == ProbeVerdict.OK } ->
                    Text(stringResource(R.string.explore_nothing_found), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (state.results.isNotEmpty()) {
            Label(stringResource(R.string.explore_results))
            state.results.forEach { r ->
                val ok = r.verdict == ProbeVerdict.OK
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(r.candidate.reading.labelRes) + " · " + r.candidate.label,
                        color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(r.verdict.labelRes) + (r.value?.takeIf { ok || r.verdict == ProbeVerdict.IMPLAUSIBLE || r.verdict == ProbeVerdict.UNSTABLE }
                            ?.let { " (" + String.format(Locale.getDefault(), "%.1f", it) + ")" } ?: ""),
                        color = if (ok) DashColors.Good else DashColors.Muted, style = MaterialTheme.typography.labelMedium, maxLines = 1
                    )
                }
                // What the adapter actually said: "NO DATA", "CAN ERROR", "?"... tells a silent car from a refused command.
                if (!ok) {
                    Text(
                        r.reply?.replace(WHITESPACE, " ")?.trim()?.ifEmpty { null } ?: stringResource(R.string.explore_silent),
                        color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (state.verified.isNotEmpty()) {
            TextButton(onClick = { PidExplorer.forget() }) { Text(stringResource(R.string.explore_forget), color = DashColors.Muted) }
        }
    }
}

private val ProbeVerdict.labelRes: Int
    get() = when (this) {
        ProbeVerdict.NO_ANSWER -> R.string.explore_v_no_answer
        ProbeVerdict.REFUSED -> R.string.explore_v_refused
        ProbeVerdict.UNREADABLE -> R.string.explore_v_unreadable
        ProbeVerdict.IMPLAUSIBLE -> R.string.explore_v_implausible
        ProbeVerdict.UNSTABLE -> R.string.explore_v_unstable
        ProbeVerdict.OK -> R.string.explore_v_ok
    }

private val WHITESPACE = Regex("\\s+")
