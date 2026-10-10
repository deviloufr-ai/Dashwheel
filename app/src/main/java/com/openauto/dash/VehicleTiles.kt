@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue

/*
 * Vehicle body tiles fed by the MCU / CAN: doors and the CAN monitor.
 */

/** Live door status decoded from the MCU door bitfield (65 / 0C / 38, byte 4). */
@Composable
internal fun DoorsCard(modifier: Modifier = Modifier) {
    val doors by McuReader.doorState.collectAsState()
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.vehicle_doors_title), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(10.dp))
            val d = doors
            if (d == null) {
                Text(stringResource(R.string.vehicle_waiting_mcu), color = DashColors.Muted)
            } else {
                DoorStatusRow(stringResource(R.string.vehicle_door_front_left), d.frontLeft)
                DoorStatusRow(stringResource(R.string.vehicle_door_front_right), d.frontRight)
                DoorStatusRow(stringResource(R.string.vehicle_door_rear_left), d.rearLeft)
                DoorStatusRow(stringResource(R.string.vehicle_door_rear_right), d.rearRight)
                DoorStatusRow(stringResource(R.string.vehicle_door_tailgate), d.tailgate)
                DoorStatusRow(stringResource(R.string.vehicle_door_bonnet), d.bonnet)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(if (d.anyOpen) R.string.vehicle_door_any_open else R.string.vehicle_doors_all_closed),
                    color = if (d.anyOpen) DashColors.Warning else DashColors.Good,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun DoorStatusRow(label: String, open: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
        Text(
            stringResource(if (open) R.string.vehicle_door_open_caps else R.string.vehicle_door_closed),
            color = if (open) DashColors.Warning else DashColors.Good,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Debug widget: live view of the CANbox/MCU stream (needs root). Each row is a
 * cmdId → bytes; a row highlights when its value changes. Open a door / toggle a
 * light and watch which row flips — that's its cmdId, which we then map to state.
 */
@Composable
internal fun CanMonitorCard(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }

    // Two-capture differential: sample many frames while CLOSED, then while OPEN.
    // Keep only rows that are stable within each state (≤2 distinct values) but
    // disjoint between states — that filters out drifting analog sensors and
    // leaves the discrete toggles (door/light/etc.).
    var closed by remember { mutableStateOf<Map<String, Set<String>>?>(null) }
    var opened by remember { mutableStateOf<Map<String, Set<String>>?>(null) }
    var capturing by remember { mutableStateOf<String?>(null) }

    fun capture(which: String) {
        capturing = which
        scope.launch {
            val acc = HashMap<String, MutableSet<String>>()
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                McuReader.entries.value.forEach { e ->
                    acc.getOrPut(e.key) { mutableSetOf() }.add(e.hex)
                }
                delay(70)
            }
            if (which == "A") closed = acc else opened = acc
            capturing = null
        }
    }

    val result = remember(closed, opened) {
        val c = closed
        val o = opened
        if (c == null || o == null) emptyList()
        else (c.keys intersect o.keys).mapNotNull { k ->
            val cs = c.getValue(k)
            val os = o.getValue(k)
            if (cs.size <= 2 && os.size <= 2 && cs.intersect(os).isEmpty()) {
                Triple(k, cs.joinToString(" / "), os.joinToString(" / "))
            } else null
        }.sortedBy { it.first }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg)) {
            Text(stringResource(R.string.vehicle_can_monitor_title), color = DashColors.AccentInk, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text(
                stringResource(R.string.vehicle_can_monitor_help),
                color = DashColors.Muted,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { capture("A") },
                    enabled = capturing == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (closed != null) DashColors.Good else DashColors.Accent,
                        contentColor = DashColors.Background
                    )
                ) { Text(if (capturing == "A") "…" else if (closed != null) stringResource(R.string.vehicle_can_a_closed) else stringResource(R.string.vehicle_capture_a)) }
                Button(
                    onClick = { capture("B") },
                    enabled = capturing == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (opened != null) DashColors.Good else DashColors.Accent,
                        contentColor = DashColors.Background
                    )
                ) { Text(if (capturing == "B") "…" else if (opened != null) stringResource(R.string.vehicle_can_b_open) else stringResource(R.string.vehicle_capture_b)) }
                if (closed != null || opened != null) {
                    TextButton(onClick = { closed = null; opened = null }) {
                        Text(stringResource(R.string.vehicle_reset), color = DashColors.Muted)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                closed == null || opened == null ->
                    Text(stringResource(R.string.vehicle_can_prompt), color = DashColors.Muted)
                result.isEmpty() ->
                    Text(stringResource(R.string.vehicle_can_no_diff), color = DashColors.Muted)
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    lazyColumnItems(result, key = { it.first }) { row ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(DashColors.Accent.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(row.first, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold)
                            Text(stringResource(R.string.vehicle_can_row_closed, row.second), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
                            Text(stringResource(R.string.vehicle_can_row_open, row.third), color = DashColors.AccentInk, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
