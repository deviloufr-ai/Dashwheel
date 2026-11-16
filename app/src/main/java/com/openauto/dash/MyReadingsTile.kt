package com.openauto.dash

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.launch
import java.util.Locale

/** "12.4 L/h", or "--" with the unit when there is no value. */
internal fun myReadingText(r: MyReading, value: Double?): String {
    val number = value?.let { String.format(Locale.getDefault(), "%.${r.decimals}f", it) } ?: "--"
    return if (r.unit.isBlank()) number else "$number ${r.unit}"
}

/** The tile: each reading put on it, name over value. */
@Composable
internal fun MyReadingsCard(modifier: Modifier = Modifier) {
    val list by MyReadings.list.collectAsState()
    val values by MyReadings.values.collectAsState()
    val shown = list.filter { it.onTile }
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
            TileHeader(stringResource(R.string.mine_title))
            if (shown.isEmpty()) {
                Text(stringResource(R.string.mine_tile_empty), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // Two to a row: the name small, the value large enough to read at a glance.
            shown.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(DashSpace.Lg)) {
                    pair.forEach { r ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                            Text(r.name, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                myReadingText(r, values[r.key]),
                                color = if (values[r.key] != null) DashColors.TextPrimary else DashColors.Muted,
                                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The designed faces: the first reading as the headline, the next ones as stats. */
@Composable
internal fun myReadingsFace(): WidgetFace {
    val list by MyReadings.list.collectAsState()
    val values by MyReadings.values.collectAsState()
    val shown = list.filter { it.onTile }
    val first = shown.firstOrNull()
    return WidgetFace(
        icon = Icons.Filled.Calculate,
        title = first?.name ?: BuiltinKind.MY_READINGS.label,
        value = first?.let { r -> values[r.key]?.let { String.format(Locale.getDefault(), "%.${r.decimals}f", it) } } ?: "--",
        unit = first?.unit.orEmpty(),
        caption = if (first == null) stringResource(R.string.mine_tile_empty) else "",
        stats = shown.drop(1).take(3).map { FaceStat(it.name, myReadingText(it, values[it.key])) }
    )
}

/** What the editor holds: the reading as typed, field by field. */
private data class Draft(
    val kind: MyReadingKind,
    val name: String = "",
    val unit: String = "",
    val key: String = "",
    /** The key was typed, not made from the name. */
    val keyTyped: Boolean = false,
    val formula: String = "",
    val request: String = "",
    val header: String = "",
    val reply: String = "",
    val session: String = "",
    val decimals: Int = 1,
    val onTile: Boolean = true,
    val canId: String = "",
    val feeds: ReadingFeed? = null
) {
    fun reading(taken: Set<String>): MyReading = MyReading(
        key = if (keyTyped) key.trim().lowercase(Locale.ROOT) else MyReadingRules.keyFor(name, taken),
        name = name.trim(), unit = unit.trim(), kind = kind, formula = formula.trim(),
        request = when (kind) {
            MyReadingKind.PID -> MyReadingRules.cleanHex(request)
            MyReadingKind.CAN -> MyReadingRules.cleanHex(canId)
            else -> ""
        },
        header = MyReadingRules.cleanHex(header).takeIf { kind == MyReadingKind.PID && it.isNotEmpty() },
        replyAddress = MyReadingRules.cleanHex(reply).takeIf { kind == MyReadingKind.PID && it.isNotEmpty() },
        session = MyReadingRules.cleanHex(session).takeIf { kind == MyReadingKind.PID && it.isNotEmpty() },
        decimals = decimals, onTile = onTile, feeds = feeds
    )

    companion object {
        fun of(r: MyReading) = Draft(
            r.kind, r.name, r.unit, r.key, keyTyped = true, formula = r.formula,
            request = if (r.kind == MyReadingKind.PID) r.request else "",
            header = r.header.orEmpty(), reply = r.replyAddress.orEmpty(), session = r.session.orEmpty(),
            decimals = r.decimals, onTile = r.onTile,
            canId = if (r.kind == MyReadingKind.CAN) r.request else "", feeds = r.feeds
        )
    }
}

/** Settings → Car → My readings: the list with live values, and the editor behind each. */
@Composable
internal fun MyReadingsSheet(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    val context = LocalContext.current
    val list by MyReadings.list.collectAsState()
    val values by MyReadings.values.collectAsState()
    // The reading being edited and the key it had (null: a new one).
    var editing by remember { mutableStateOf<Pair<Draft, String?>?>(null) }
    val open = editing
    if (open != null) {
        ReadingEditor(open.first, open.second, list, onClose = { editing = null }, onDismiss = onDismiss)
        return
    }
    SettingsSheet(
        title = stringResource(R.string.mine_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.mine_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        if (list.isEmpty()) Text(stringResource(R.string.mine_none), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        list.forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { editing = Draft.of(r) to r.key },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            r.key,
                            when (r.kind) {
                                MyReadingKind.PID -> listOfNotNull(r.header, r.request).joinToString(" ") + ": " + r.formula
                                MyReadingKind.CAN -> "CAN ${r.request}: ${r.formula}"
                                else -> r.formula
                            }
                        ).joinToString(" = ") + (r.feeds?.let { " → " + context.getString(it.label) }.orEmpty()),
                        color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(myReadingText(r, values[r.key]), color = if (values[r.key] != null) DashColors.Good else DashColors.Muted, fontWeight = FontWeight.Bold)
                if (r.kind == MyReadingKind.TOTAL) {
                    TextButton(onClick = { MyReadings.resetTotal(r.key) }) { Text(stringResource(R.string.mine_reset), color = DashColors.AccentInk) }
                }
            }
        }

        Label(stringResource(R.string.mine_add))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MyReadingKind.entries.forEach { kind ->
                SheetButton(kindLabel(context, kind), primary = false) { editing = Draft(kind) to null }
            }
        }
        // Offered to start with: added twice they would only come back as lpg_use_2 and so on.
        if (list.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mine_examples_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SheetButton(stringResource(R.string.mine_add_examples), primary = false) {
                val names = listOf(R.string.mine_ex_maf, R.string.mine_ex_lpg_lh, R.string.mine_ex_lpg_used, R.string.mine_ex_lpg_l100).map { context.getString(it) }
                MyReadings.addAll(MyReadingRules.examples(names, list.map { it.key }.toSet()))
            }
        }
        // A VW group diesel's own engine readings, offered until they are in the list.
        if (list.none { it.request == MyReadingRules.VW_BOOST_REQUEST }) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mine_vw_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SheetButton(stringResource(R.string.mine_add_vw), primary = false) {
                val names = listOf(R.string.mine_vw_boost, R.string.mine_vw_boost_target, R.string.mine_vw_oil, R.string.mine_vw_torque, R.string.mine_vw_power).map { context.getString(it) }
                MyReadings.addAll(MyReadingRules.vwDiesel(names, list.map { it.key }.toSet()))
            }
        }
    }
}

private fun kindLabel(context: android.content.Context, kind: MyReadingKind): String = context.getString(
    when (kind) {
        MyReadingKind.PID -> R.string.mine_kind_pid
        MyReadingKind.FORMULA -> R.string.mine_kind_formula
        MyReadingKind.TOTAL -> R.string.mine_kind_total
        MyReadingKind.CAN -> R.string.mine_kind_can
    }
)

/** One reading's page: what it is, how it's worked out, Test, Save. */
@Composable
private fun ReadingEditor(start: Draft, original: String?, list: List<MyReading>, onClose: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val connection by ObdBluetoothManager.connectionState.collectAsState()
    val can by UsbCan.state.collectAsState()
    var d by remember { mutableStateOf(start) }
    var feedsOpen by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var tested by remember { mutableStateOf<MyReadings.Test?>(null) }
    val others = list.filter { it.key != original }
    val reading = d.reading(others.map { it.key }.toSet())

    fun check(): Boolean {
        val p = MyReadingRules.problem(reading, others)
        val unknown = if (reading.readsBytes) emptyList() else MyReadingRules.unknownNames(reading.formula, others)
        problem = p?.let { context.getString(it.text) } ?: unknown.firstOrNull()?.let { context.getString(R.string.mine_err_unknown, it) }
        return problem == null
    }

    SettingsSheet(
        title = if (original == null) stringResource(R.string.mine_new) else d.name.ifBlank { stringResource(R.string.mine_title) },
        onDismiss = onDismiss,
        onStepBack = onClose,
        actions = {
            if (original != null) SheetButton(stringResource(R.string.mine_delete), primary = false) {
                MyReadings.delete(original)
                onClose()
            }
            SheetButton(stringResource(R.string.mine_save)) {
                if (check()) {
                    MyReadings.save(reading, original)
                    onClose()
                }
            }
        }
    ) {
        Label(stringResource(R.string.mine_type))
        ChoiceRow(MyReadingKind.entries, d.kind, { kindLabel(context, it) }) { d = d.copy(kind = it); tested = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.mine_name), d.name, Modifier.weight(2f)) { d = d.copy(name = it) }
            Field(stringResource(R.string.mine_unit), d.unit, Modifier.weight(1f)) { d = d.copy(unit = it) }
        }
        Field(stringResource(R.string.mine_key), if (d.keyTyped) d.key else reading.key, Modifier.fillMaxWidth(), code = true) {
            d = d.copy(key = it, keyTyped = true)
        }

        if (d.kind == MyReadingKind.PID) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.mine_request), d.request, Modifier.weight(1f), code = true) { d = d.copy(request = it) }
                Field(stringResource(R.string.mine_header), d.header, Modifier.weight(1f), code = true) { d = d.copy(header = it) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.mine_reply), d.reply, Modifier.weight(1f), code = true) { d = d.copy(reply = it) }
                Field(stringResource(R.string.mine_session), d.session, Modifier.weight(1f), code = true) { d = d.copy(session = it) }
            }
            Text(stringResource(R.string.mine_hint_address), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        if (d.kind == MyReadingKind.CAN) {
            Field(stringResource(R.string.mine_can_id), d.canId, Modifier.fillMaxWidth(), code = true) { d = d.copy(canId = it) }
            Text(stringResource(R.string.mine_hint_can_id), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Field(stringResource(R.string.mine_formula), d.formula, Modifier.fillMaxWidth(), code = true) { d = d.copy(formula = it) }
        Text(
            stringResource(
                when (d.kind) {
                    MyReadingKind.PID -> R.string.mine_hint_pid
                    MyReadingKind.FORMULA -> R.string.mine_hint_formula
                    MyReadingKind.TOTAL -> R.string.mine_hint_total
                    MyReadingKind.CAN -> R.string.mine_hint_can
                }
            ),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
        )
        if (d.kind == MyReadingKind.FORMULA || d.kind == MyReadingKind.TOTAL) {
            Text(
                stringResource(R.string.mine_names, (MyReadingRules.BUILT_IN + others.map { it.key }).joinToString(", ")),
                color = DashColors.Muted, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace
            )
        }

        Label(stringResource(R.string.mine_decimals))
        ChoiceRow(listOf(0, 1, 2, 3), d.decimals, { it.toString() }) { d = d.copy(decimals = it) }
        SwitchRow(stringResource(R.string.mine_on_tile), stringResource(R.string.mine_on_tile_detail), d.onTile) { d = d.copy(onTile = it) }

        // What it stands for in the car: shown by the gauges, the car status and the alerts as the car's own.
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { feedsOpen = !feedsOpen },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.mine_feeds), color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.mine_feeds_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            Text(d.feeds?.let { stringResource(it.label) } ?: stringResource(R.string.mine_feeds_none), color = DashColors.AccentInk, fontWeight = FontWeight.SemiBold)
        }
        if (feedsOpen) {
            ChoiceRow(listOf<ReadingFeed?>(null) + ReadingFeed.entries, d.feeds, { f -> f?.let { context.getString(it.label) } ?: context.getString(R.string.mine_feeds_none) }, perRow = 2) {
                d = d.copy(feeds = it)
                feedsOpen = false
            }
        }
        d.feeds?.let { f ->
            Text(
                stringResource(if (f.onOff) R.string.mine_feeds_on_off else R.string.mine_feeds_units),
                color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                enabled = !testing && (d.kind != MyReadingKind.PID || connection == ObdConnectionState.CONNECTED) &&
                    (d.kind != MyReadingKind.CAN || can == UsbCanState.RECEIVING),
                colors = buttonColors(),
                onClick = {
                    tested = null
                    if (!check()) return@Button
                    testing = true
                    scope.launch {
                        tested = MyReadings.test(reading, list)
                        testing = false
                    }
                }
            ) { Text(stringResource(R.string.mine_test)) }
            Spacer(Modifier.width(12.dp))
            when {
                testing -> CircularProgressIndicator(color = DashColors.Accent, modifier = Modifier.size(22.dp))
                d.kind == MyReadingKind.PID && connection != ObdConnectionState.CONNECTED ->
                    Text(stringResource(R.string.vehicle_obd_not_connected), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                d.kind == MyReadingKind.CAN && can != UsbCanState.RECEIVING ->
                    Text(stringResource(R.string.mine_can_not_receiving), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
        problem?.let { Text(it, color = DashColors.Warning, style = MaterialTheme.typography.bodySmall) }
        tested?.let { t ->
            if (reading.readsBytes) {
                Text(
                    stringResource(R.string.mine_answer, t.reply?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null } ?: stringResource(R.string.mine_no_answer)),
                    color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace
                )
            }
            val r = t.result
            Text(
                when {
                    r.value != null -> stringResource(R.string.mine_value, myReadingText(reading, r.value))
                    r.problem == Formula.Problem.UNKNOWN -> stringResource(R.string.mine_err_unknown, r.name.orEmpty())
                    r.problem == Formula.Problem.NO_VALUE && r.name != null -> stringResource(R.string.mine_err_no_value, r.name)
                    r.problem == Formula.Problem.NO_VALUE -> stringResource(R.string.mine_err_no_reading)
                    r.problem == Formula.Problem.MATH -> stringResource(R.string.mine_err_math)
                    else -> stringResource(R.string.mine_err_syntax)
                },
                color = if (r.value != null) DashColors.Good else DashColors.Warning, fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** A one-line text field; [code] for addresses and formulas: no capitals forced, no autocorrect. */
@Composable
private fun Field(label: String, value: String, modifier: Modifier, code: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        textStyle = if (code) MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace) else MaterialTheme.typography.bodyLarge,
        keyboardOptions = if (code) KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false) else KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = modifier,
        colors = fieldColors()
    )
}
