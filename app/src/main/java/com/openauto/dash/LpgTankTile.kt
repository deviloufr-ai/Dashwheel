package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PropaneTank
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The LPG tank: the level worked out since the last full fill, the litres
 * and distance left, and Full to tap after each fill. Right after Full it asks
 * the litres the pump showed (steppers, no keyboard); they teach the tile what
 * the car really uses, and Skip is fine.
 */
@Composable
internal fun LpgTankCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by LpgTank.settings.collectAsState()
    val state by LpgTank.state.collectAsState()
    val reading by LpgTank.reading.collectAsState()
    val units by Units.current.collectAsState()
    var setup by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    if (setup) LpgTankSheet(onDismiss = { setup = false })
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.SpaceBetween) {
            TileHeader(stringResource(R.string.lpg_title)) {
                if (settings.enabled && settings.mode == LpgMode.MANUAL) {
                    LpgButton(stringResource(if (state.onLpg) R.string.lpg_on_lpg else R.string.lpg_on_petrol), Modifier) { LpgTank.toggleFuel(context) }
                }
            }
            val r = reading
            when {
                !settings.enabled -> {
                    Text(stringResource(R.string.lpg_off), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    LpgButton(stringResource(R.string.lpg_set_up), Modifier.fillMaxWidth(), primary = true) { setup = true }
                }
                asking -> Pumped(
                    suggested = LpgTank.suggestedLiters(),
                    tankL = settings.tankL,
                    onSave = { LpgTank.setPumped(context, it); asking = false },
                    onSkip = { asking = false }
                )
                r == null -> {
                    Text(stringResource(R.string.lpg_first), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    LpgButton(stringResource(R.string.lpg_full), Modifier.fillMaxWidth(), primary = true) { LpgTank.fillUp(context) }
                }
                else -> {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${r.percent} %",
                            color = if (r.low) DashColors.Warning else DashColors.TextPrimary,
                            fontWeight = FontWeight.Black, style = MaterialTheme.typography.headlineMedium
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                stringResource(R.string.lpg_left, String.format(Locale.getDefault(), "%.0f", r.liters)),
                                color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                stringResource(R.string.lpg_range, units.distance(r.rangeKm).roundToInt(), units.distanceUnit),
                                color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    LevelBar(r.percent / 100f, r.low)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            useText(r, units),
                            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                            modifier = Modifier.weight(1f)
                        )
                        LpgButton(stringResource(R.string.lpg_full), Modifier.width(96.dp), primary = true) {
                            LpgTank.fillUp(context)
                            asking = true
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun useText(r: LpgReading, units: UnitSystem): String {
    val use = units.economyText(r.useL100)
    return stringResource(if (r.learned) R.string.lpg_use_learned else R.string.lpg_use, use)
}

@Composable
private fun LevelBar(fraction: Float, low: Boolean) {
    Box(Modifier.fillMaxWidth().height(10.dp).clip(DashShape.Small).background(DashColors.CardHi)) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(DashShape.Small)
                .background(if (low) DashColors.Warning else DashColors.Accent)
        )
    }
}

/** The litres the pump showed, starting from what the estimate says went in. */
@Composable
private fun Pumped(suggested: Double, tankL: Double, onSave: (Double) -> Unit, onSkip: () -> Unit) {
    var liters by remember { mutableIntStateOf(suggested.roundToInt().coerceAtLeast(1)) }
    val max = tankL.roundToInt().coerceAtLeast(1)
    Text(stringResource(R.string.lpg_pumped), color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        LpgButton("-", Modifier.width(DashSize.Touch)) { liters = (liters - 1).coerceAtLeast(1) }
        Text("$liters L", color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        LpgButton("+", Modifier.width(DashSize.Touch)) { liters = (liters + 1).coerceAtMost(max) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(DashSpace.Sm)) {
        LpgButton(stringResource(R.string.lpg_skip), Modifier.weight(1f)) { onSkip() }
        LpgButton(stringResource(R.string.lpg_save), Modifier.weight(1f), primary = true) { onSave(liters.toDouble()) }
    }
}

@Composable
private fun LpgButton(label: String, modifier: Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier.height(40.dp).clip(DashShape.Medium).background(if (primary) DashColors.Accent else DashColors.CardHi)
            .clickable(role = Role.Button) { tap(); onClick() }.padding(horizontal = DashSpace.Sm),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (primary) DashColors.OnAccent else DashColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
    }
}

@Composable
internal fun lpgTankFace(): WidgetFace {
    val context = LocalContext.current
    val settings by LpgTank.settings.collectAsState()
    val reading by LpgTank.reading.collectAsState()
    val units by Units.current.collectAsState()
    val r = reading
    val full = stringResource(R.string.lpg_full)
    return WidgetFace(
        icon = Icons.Filled.PropaneTank,
        title = BuiltinKind.LPG_TANK.label,
        value = r?.percent?.toString() ?: "--",
        unit = "%",
        caption = when {
            !settings.enabled -> stringResource(R.string.lpg_off)
            r == null -> stringResource(R.string.lpg_first)
            else -> stringResource(R.string.lpg_left, String.format(Locale.getDefault(), "%.0f", r.liters)) + ", " +
                stringResource(R.string.lpg_range, units.distance(r.rangeKm).roundToInt(), units.distanceUnit)
        },
        fraction = r?.let { it.percent / 100f },
        alert = r?.low == true,
        stats = listOfNotNull(
            r?.let { FaceStat(stringResource(R.string.lpg_use_label), units.economyText(it.useL100)) },
            r?.costPer100?.let { FaceStat(stringResource(R.string.widgets_fuel_per100), String.format(Locale.getDefault(), "%.2f %s", it, CarProfileStore.current.currency)) }
        ),
        actions = if (settings.enabled) listOf(FaceAction(Icons.Filled.PropaneTank, full, { LpgTank.fillUpHandsFree(context) }, primary = true)) else emptyList()
    )
}

/**
 * Settings → Car → LPG tank: on or off, the tank, the use, the price, and
 * when the car counts as running on LPG. Every change is kept as it's made.
 */
@Composable
internal fun LpgTankSheet(onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    val context = LocalContext.current
    val saved by LpgTank.settings.collectAsState()
    val state by LpgTank.state.collectAsState()
    val u = LocalUnits.current
    var draft by remember { mutableStateOf(saved) }
    fun edit(s: LpgSettings) {
        draft = s
        LpgTank.saveSettings(context, s)
    }
    SettingsSheet(
        title = stringResource(R.string.lpg_kind),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.ai_done), onClick = onDismiss) }
    ) {
        Text(stringResource(R.string.lpg_explanation), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        SwitchRow(stringResource(R.string.lpg_enable), stringResource(R.string.lpg_enable_detail), draft.enabled) { edit(draft.copy(enabled = it)) }
        if (!draft.enabled) return@SettingsSheet

        // Each number with its own one-line hint underneath, not one paragraph for the three.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                LpgNumber(stringResource(R.string.lpg_tank_size), draft.tankL, Modifier.fillMaxWidth()) { it?.takeIf { v -> v >= 1 }?.let { v -> edit(draft.copy(tankL = v)) } }
                Text(stringResource(R.string.lpg_tank_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
                LpgNumber(stringResource(R.string.lpg_use_setting), draft.useL100, Modifier.fillMaxWidth()) { edit(draft.copy(useL100 = it?.takeIf { v -> v > 0 })) }
                Text(stringResource(R.string.lpg_use_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
                LpgNumber(stringResource(R.string.lpg_price), draft.pricePerL, Modifier.fillMaxWidth()) { edit(draft.copy(pricePerL = it?.takeIf { v -> v > 0 })) }
                Text(stringResource(R.string.lpg_price_hint), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }

        Label(stringResource(R.string.lpg_mode_label))
        ChoiceRow(LpgMode.entries, draft.mode, {
            context.getString(
                when (it) {
                    LpgMode.AUTO -> R.string.lpg_mode_auto
                    LpgMode.ALWAYS -> R.string.lpg_mode_always
                    LpgMode.MANUAL -> R.string.lpg_mode_manual
                }
            )
        }) { edit(draft.copy(mode = it)) }
        when (draft.mode) {
            LpgMode.AUTO -> {
                Text(stringResource(R.string.lpg_mode_auto_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                LpgNumber(stringResource(R.string.lpg_switch_temp, u.tempUnit), u.temp(draft.switchC), Modifier.fillMaxWidth(), decimals = false) {
                    it?.let { shown -> edit(draft.copy(switchC = (if (u.fahrenheit) (shown - 32) / 1.8 else shown).roundToInt())) }
                }
            }
            LpgMode.ALWAYS -> Unit
            LpgMode.MANUAL -> Text(stringResource(R.string.lpg_mode_manual_detail), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        Label(stringResource(R.string.lpg_level))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.filledAt == 0L) stringResource(R.string.lpg_never_filled)
                else stringResource(R.string.lpg_last_fill, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(state.filledAt))),
                color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)
            )
            SheetButton(stringResource(R.string.lpg_full_now)) { LpgTank.fillUp(context) }
        }
    }
}

/** A number field; blank means "not set". Keeps what's typed ("0," on the way to "0,99") and reports numbers only. */
@Composable
private fun LpgNumber(label: String, value: Number?, modifier: Modifier, decimals: Boolean = true, onChange: (Double?) -> Unit) {
    var text by remember {
        mutableStateOf(
            when {
                value == null -> ""
                decimals -> String.format(Locale.getDefault(), "%.2f", value.toDouble()).trimEnd('0').trimEnd(',', '.')
                else -> value.toLong().toString()
            }
        )
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            val cleaned = typed.trim().replace(',', '.')
            if (cleaned.isEmpty()) onChange(null) else cleaned.toDoubleOrNull()?.let { onChange(it) }
        },
        label = { Text(label, maxLines = 1) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimals) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier,
        colors = fieldColors()
    )
}

/** The Settings → Car row: the level once known, else what the page is for. */
@Composable
internal fun LpgSettingsRow(onClick: () -> Unit) {
    val settings by LpgTank.settings.collectAsState()
    val reading by LpgTank.reading.collectAsState()
    val detail = reading?.let { "${it.percent} %" } ?: stringResource(if (settings.enabled) R.string.lpg_first else R.string.lpg_settings_detail)
    SettingsRow(Icons.Filled.PropaneTank, stringResource(R.string.lpg_kind), detail, onClick)
}
