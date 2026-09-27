package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/*
 * Tyre warnings in Dashwheel's design ([AlertStyle]), from the TPMS sensors
 * ([Tyres]): a leak, a pressure too low or too high, a tyre too hot. Up while
 * something is wrong; a tap hides it until something else goes wrong; said
 * out loud once per new problem when chosen. The TPMS app's own window can't
 * be switched off from outside: its settings can ("show UI").
 */

object TyreAlertOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var window: AlertWindow? = null

    /** What's wrong, tyre by tyre. */
    val problems: StateFlow<Map<TyrePos, TyreProblem>> =
        Tyres.tyres.map { tyres -> tyres.mapNotNull { (pos, t) -> tyreProblem(t)?.let { pos to it } }.toMap() }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The problems the driver tapped away: hidden until another one comes. */
    private val dismissed = MutableStateFlow<Map<TyrePos, TyreProblem>>(emptyMap())

    /** The tyres to show, or null when there's no warning: the picker's made-up ones while it is tried. */
    val alert: StateFlow<Map<TyrePos, Tyre>?> =
        combine(RomPopups.replaced, Tyres.tyres, problems, dismissed, AlertPreview.tyres) { replaced, tyres, problems, hidden, preview ->
            preview ?: tyres.takeIf {
                RomPopups.Kind.TYRES in replaced && problems.isNotEmpty() && !problems.all { (pos, p) -> hidden[pos] == p }
            }
        }.combine(CarBox.reversing) { tyres, reversing -> tyres.takeIf { !reversing || AlertPreview.tyres.value != null } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    val style: StateFlow<AlertStyle?> =
        combine(alert, AlertStyleStore.styles, UnitSignals.projectionOnScreen) { tyres, styles, projected ->
            tyres?.let { if (projected) AlertStyle.PILL else styles.of(AlertKind.TYRES) }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    fun start(context: Context) {
        if (started) return
        started = true
        val app = AppLanguage.wrap(context.applicationContext)
        // Each new problem said once (while it lasts), when tyre alerts are on.
        scope.launch {
            var said: Map<TyrePos, TyreProblem> = emptyMap()
            problems.collect { now ->
                if (now.isEmpty()) dismissed.value = emptyMap()
                if (RomPopups.Kind.TYRES in RomPopups.replaced.value) {
                    (now - said.filter { (pos, p) -> now[pos] == p }.keys).forEach { (pos, p) -> AlertVoice.sayTyre(app, pos, p) }
                }
                said = now
            }
        }
        scope.launch {
            style.collect { style ->
                val w = window ?: AlertWindow(app, "tyres", AlertKind.TYRES.cardAt.gravity).also { window = it }
                if (style != null && w.canShow()) w.show(style) { TyreAlertContent(style) } else window?.hide()
            }
        }
    }

    internal fun dismiss() {
        if (AlertPreview.tyres.value != null) return AlertPreview.stop()
        dismissed.value = problems.value
    }
}

@Composable
private fun TyreAlertContent(style: AlertStyle) {
    val live by TyreAlertOverlay.alert.collectAsState()
    val tyres = rememberLast(live) ?: return
    TyreAlert(tyres, style)
}

@Composable
private fun TyreAlert(tyres: Map<TyrePos, Tyre>, style: AlertStyle) {
    val unit by Tyres.unit.collectAsState()
    val tap = rememberTapFeedback()
    val hide = Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_close)) { tap(); TyreAlertOverlay.dismiss() }
    val worst = tyres.entries.mapNotNull { (pos, t) -> tyreProblem(t)?.let { pos to it } }.minByOrNull { it.second.ordinal }
    val headline = worst?.let { (pos, p) -> "${stringResource(pos.labelRes)}: ${stringResource(p.labelRes)}" } ?: stringResource(R.string.car_tyres_alert)
    when (style) {
        AlertStyle.PILL -> Surface(
            color = DashColors.Card.copy(alpha = 1f), shape = DashShape.Pill, shadowElevation = 6.dp,
            modifier = Modifier.border(1.dp, DashColors.Critical.copy(alpha = 0.6f), DashShape.Pill).then(hide)
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.TireRepair, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(headline, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.widthIn(max = 380.dp))
            }
        }
        AlertStyle.BANNER -> Surface(
            color = DashColors.Card.copy(alpha = 1f), shape = DashShape.Medium, shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).border(1.dp, DashColors.Line, DashShape.Medium).then(hide)
        ) {
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(40.dp).background(DashColors.Critical, DashShape.Pill))
                Spacer(Modifier.width(14.dp))
                Icon(Icons.Filled.TireRepair, contentDescription = null, tint = DashColors.Critical, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Text(headline, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                worst?.let { (pos, _) -> tyres[pos]?.let { Text(formatPressure(it.kPa, unit), color = DashColors.Critical, style = MaterialTheme.typography.titleMedium) } }
            }
        }
        AlertStyle.PANEL -> Surface(color = DashColors.Card.copy(alpha = 1f), shadowElevation = 12.dp, modifier = Modifier.fillMaxSize().then(hide)) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(DashColors.Line))
                Column(Modifier.fillMaxSize().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.car_tyres_alert).uppercase(), color = DashColors.Critical, letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(16.dp))
                    TyreGrid(tyres, unit, Modifier.weight(1f).fillMaxWidth(), large = true)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.alert_tap_to_hide), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        AlertStyle.FULL -> FullScreenModal(onTap = { tap(); TyreAlertOverlay.dismiss() }) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(headline, color = DashColors.Critical, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(20.dp))
                TyreGrid(tyres, unit, Modifier.widthIn(max = 520.dp).fillMaxWidth(), large = true)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.alert_tap_to_hide), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> SolidCard(hide) {
            Column(Modifier.padding(16.dp).width(300.dp)) {
                Text(headline, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                TyreGrid(tyres, unit, Modifier.fillMaxWidth(), large = false)
            }
        }
    }
}

/**
 * The four tyres as the car stands, front on top: pressure in the TPMS app's
 * unit and temperature, the one with a problem framed in red and named.
 */
@Composable
internal fun TyreGrid(tyres: Map<TyrePos, Tyre>, unit: PressureUnit, modifier: Modifier = Modifier, large: Boolean) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (large) 14.dp else 8.dp)) {
        listOf(TyrePos.FRONT_LEFT to TyrePos.FRONT_RIGHT, TyrePos.REAR_LEFT to TyrePos.REAR_RIGHT).forEach { (left, right) ->
            Row(Modifier.fillMaxWidth().weight(1f, fill = large), horizontalArrangement = Arrangement.spacedBy(if (large) 14.dp else 8.dp)) {
                TyreCell(left, tyres[left], unit, Modifier.weight(1f).then(if (large) Modifier.fillMaxHeight() else Modifier), large)
                TyreCell(right, tyres[right], unit, Modifier.weight(1f).then(if (large) Modifier.fillMaxHeight() else Modifier), large)
            }
        }
    }
}

@Composable
private fun TyreCell(pos: TyrePos, tyre: Tyre?, unit: PressureUnit, modifier: Modifier, large: Boolean) {
    val problem = tyre?.let(::tyreProblem)
    val fill = if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi
    Column(
        modifier = modifier
            .itemFill(fill, DashShape.Medium)
            .then(if (problem != null) Modifier.border(2.dp, DashColors.Critical, DashShape.Medium) else Modifier)
            .padding(horizontal = 12.dp, vertical = if (large) 12.dp else 8.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(pos.labelRes), color = DashColors.TextSecondary, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        Text(
            tyre?.let { formatPressure(it.kPa, unit) } ?: "--",
            color = if (problem != null) DashColors.Critical else DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = if (large) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium
        )
        val note = when {
            tyre == null -> null
            tyre.noSignal -> stringResource(R.string.car_tyres_no_signal)
            problem != null -> stringResource(problem.labelRes)
            tyre.sensorBatteryLow -> stringResource(R.string.car_tyres_sensor_battery)
            else -> "${tyre.celsius} °C"
        }
        val noteColor = when {
            problem != null -> DashColors.Critical
            tyre != null && !tyre.noSignal && tyre.sensorBatteryLow -> DashColors.Warning
            else -> DashColors.TextSecondary
        }
        note?.let { Text(it, color = noteColor, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
    }
}

internal val TyrePos.labelRes: Int
    get() = when (this) {
        TyrePos.FRONT_LEFT -> R.string.vehicle_door_front_left
        TyrePos.FRONT_RIGHT -> R.string.vehicle_door_front_right
        TyrePos.REAR_LEFT -> R.string.vehicle_door_rear_left
        TyrePos.REAR_RIGHT -> R.string.vehicle_door_rear_right
        TyrePos.SPARE -> R.string.car_tyres_spare
    }

internal val TyreProblem.labelRes: Int
    get() = when (this) {
        TyreProblem.LEAK -> R.string.car_tyres_leak
        TyreProblem.LOW -> R.string.car_tyres_low
        TyreProblem.HIGH -> R.string.car_tyres_high
        TyreProblem.HOT -> R.string.car_tyres_hot
    }

/** The Tyres tile: the four tyres, from the TPMS sensors. */
@Composable
internal fun TyresCard(modifier: Modifier = Modifier) {
    val tyres by Tyres.tyres.collectAsState()
    val unit by Tyres.unit.collectAsState()
    Card(modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TileHeader(stringResource(R.string.car_tyres_title))
            if (tyres.isEmpty()) {
                Text(stringResource(R.string.car_tyres_waiting), color = DashColors.Muted, style = MaterialTheme.typography.bodyMedium)
            } else {
                TyreGrid(tyres, unit, Modifier.fillMaxWidth().weight(1f), large = true)
            }
        }
    }
}

/** The launcher's own tyre popup, for when the overlay window isn't allowed. */
@Composable
internal fun TyreAlertHost() {
    val tyres by TyreAlertOverlay.alert.collectAsState()
    val style by TyreAlertOverlay.style.collectAsState()
    val t = tyres ?: return
    val s = style ?: return
    if (android.provider.Settings.canDrawOverlays(androidx.compose.ui.platform.LocalContext.current)) return
    AlertPopup(s, AlertKind.TYRES.cardAt.alignment) { TyreAlert(t, s) }
}
