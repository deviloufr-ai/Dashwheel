@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Battery1Bar
import androidx.compose.material.icons.filled.Battery3Bar
import androidx.compose.material.icons.filled.Battery4Bar
import androidx.compose.material.icons.filled.Battery5Bar
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/*
 * Top bar, edit toolbar, page dots and the update banner.
 */

/**
 * Everything a top bar shows and can do; each skin's bar arranges the same model.
 * The OBD readings are a [State], read only where they are drawn ([obdData]),
 * so a new sample does not recompose the whole bar.
 *
 * A data class: the dashboard builds a fresh model on each of its own
 * recompositions, and only one that differs in a value (the callbacks are
 * memoised) makes the bar recompose.
 */
@Stable
internal data class TopBarModel(
    val clock: String,
    val versionName: String,
    val obdConnection: ObdConnectionState,
    val obd: State<ObdData>,
    val editing: Boolean,
    val layout: DashLayout,
    val onLayout: (DashLayout) -> Unit,
    val onApps: () -> Unit,
    val onConnectObd: () -> Unit,
    val onSplit: () -> Unit,
    val onToggleEdit: () -> Unit,
    val onTemplates: () -> Unit,
    val onSystem: () -> Unit,
    val onCheckUpdates: () -> Unit,
    /** Where the updater stands: a dot on ⋮ and a menu row while a newer build is on offer. */
    val update: UpdateStatus,
    /** Installs the update on offer (downloading it first if need be). */
    val onUpdate: () -> Unit,
    /** "Later": stops the update asking until a newer one. */
    val onDismissUpdate: () -> Unit,
    /** Something the launcher can use is still not allowed: the bar shows a pill that opens the setup. */
    val setupPending: Boolean,
    /** Opens the setup on its access step (the pill), or from the start (Settings). */
    val onSetup: (fromStart: Boolean) -> Unit,
    /** Demo mode is running: the menu offers to stop it. */
    val demo: Boolean,
    val onDemo: () -> Unit,
    /** The head unit's status bar is up (an app window is docked) and already shows the time. */
    val merged: Boolean = false,
    val page: Int = 0,
    /** The car is moving and the drive lock is on: arranging and settings wait (DriveLock.kt). */
    val moving: Boolean = false,
    val lockWhileMoving: Boolean = true,
    val onLockWhileMoving: (Boolean) -> Unit = {},
    /** Opens the Settings screen (SettingsScreen.kt). */
    val onSettings: () -> Unit = {}
) {
    val obdData: ObdData get() = obd.value
}

/** The bar from a model built by the caller (shared with the Settings screen). */
@Composable
internal fun TopBar(m: TopBarModel) {
    if (DashColors.Skin == DashSkin.STANDARD) StandardTopBar(m) else SkinTopBar(m)
}

/**
 * Minimal bar: Apps and the layout picker on the left, the clock centred, the
 * OBD link pill and a ⋮ menu on the right. Set-and-forget controls (theme,
 * system install, edit) live in the menu; battery and coolant only appear, as
 * warning pills, when a reading is out of range. The buttons are
 * [DashSize.Bar] tall and reach the screen's bottom edge.
 */
@Composable
internal fun StandardTopBar(m: TopBarModel) {
    // Glass themes float the bar as its own panel over the gradient background;
    // solid themes keep the flat full-width strip.
    val glass = DashColors.Glass
    Surface(color = if (glass) Color.Transparent else DashColors.Bar, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (glass) Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp).then(glassPanel(DashShape.Large))
                    else Modifier
                )
                .padding(horizontal = 6.dp)
        ) {
            // An upright screen, or half of a split one: no room to keep the
            // clock centred, so the left side takes only what its buttons need.
            val narrow = maxWidth < NARROW_BAR
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = if (narrow) Modifier else Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    BarButton(
                        onClick = m.onApps,
                        label = if (narrow) null else stringResource(R.string.dash_apps),
                        description = stringResource(R.string.dash_all_apps)
                    ) {
                        Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(28.dp))
                    }
                    LayoutPicker(m) { open ->
                        BarButton(onClick = open, description = stringResource(R.string.dash_screen_layout, m.layout.title)) {
                            LayoutIcon(m.layout, null, DashColors.TextSecondary, Modifier.size(28.dp))
                        }
                    }
                }

                // A cluster bar (Mistral) puts the speed in the middle, like the car's
                // own central display, and moves the clock to the right; without a
                // speed source it is the plain bar again.
                val speed = if (DashColors.BarStyle == DashBarStyle.CLUSTER) rememberSpeedKmh(m.obdData, m.obdConnection) else null
                val cluster = speed != null
                Box(modifier = Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                    when {
                        cluster -> ClusterReadout(speed ?: 0, m.obdData, m.obdConnection == ObdConnectionState.CONNECTED)
                        // The head unit's status bar shows the time while it is up.
                        !m.merged -> BarClock(m.clock)
                    }
                }

                // ⋮ and the OBD pill get their room first, whatever else is on
                // the bar; the badges go short when they share it.
                BarEnd(modifier = Modifier.weight(1f)) {
                    if (cluster) {
                        Box(Modifier.layoutId(BarRank.CLOCK).padding(end = 10.dp)) {
                            BarClock(m.clock, MaterialTheme.typography.titleMedium)
                        }
                    }
                    if (m.demo) {
                        Box(Modifier.layoutId(BarRank.DEMO).padding(end = 6.dp)) {
                            DemoBadge(onStop = m.onDemo, compact = narrow || m.setupPending)
                        }
                    }
                    Row(modifier = Modifier.layoutId(BarRank.ALERTS), verticalAlignment = Alignment.CenterVertically) {
                        VehicleAlerts(m.obdConnection, m.obd)
                    }
                    Box(Modifier.layoutId(BarRank.PHONE).padding(end = 6.dp)) { PhonePill() }
                    if (m.setupPending) {
                        Box(Modifier.layoutId(BarRank.SETUP).padding(end = 6.dp)) {
                            SetupPill(onClick = { m.onSetup(false) }, compact = narrow || m.demo)
                        }
                    }
                    Box(Modifier.layoutId(BarRank.OBD)) { ObdPill(m.obdConnection, m.onConnectObd) }
                    Box(Modifier.layoutId(BarRank.MORE)) {
                        MorePicker(m) { open ->
                            BarButton(onClick = open, description = stringResource(R.string.dash_more)) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Under this width a bar stops centring its clock and shortens its badges. */
internal val NARROW_BAR = 900.dp

/** Who gets room first at the bar's end ([BarEnd]); lowest first. */
internal object BarRank {
    const val MORE = 0
    const val OBD = 1
    const val CLOCK = 2
    const val ALERTS = 3
    const val DEMO = 4
    const val SETUP = 5
    const val PHONE = 6
}

/**
 * The bar's end, right-aligned in the order written. Room is handed out by
 * [BarRank] (the child's layoutId), so a crowded bar never squeezes ⋮ or the
 * OBD pill: what comes last in rank and no longer fits is left out whole.
 */
@Composable
internal fun BarEnd(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        val maxHeight = constraints.maxHeight
        var left = constraints.maxWidth
        measurables.indices
            .sortedBy { (measurables[it].layoutId as? Int) ?: Int.MAX_VALUE }
            .forEach { i ->
                val rank = (measurables[i].layoutId as? Int) ?: Int.MAX_VALUE
                // The menu and the link pill always show; the rest only where it fits.
                if (rank > BarRank.OBD && (left <= 0 || measurables[i].minIntrinsicWidth(maxHeight) > left)) return@forEach
                val p = measurables[i].measure(Constraints(maxWidth = left.coerceAtLeast(0), maxHeight = maxHeight))
                placeables[i] = p
                left -= p.width
            }
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.maxWidth - left
        val height = (placeables.maxOfOrNull { it?.height ?: 0 } ?: 0).coerceIn(constraints.minHeight, maxHeight)
        layout(width, height) {
            var x = left.coerceAtLeast(0)
            placeables.forEach { p ->
                if (p != null) {
                    p.placeRelative(x, (height - p.height) / 2)
                    x += p.width
                }
            }
        }
    }
}

/**
 * A button of the standard bar: [DashSize.Bar] each way at least, so it can
 * be hit without aiming, with [label] beside the icon where there is room.
 */
@Composable
private fun BarButton(onClick: () -> Unit, description: String, label: String? = null, icon: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .heightIn(min = DashSize.Bar)
            .widthIn(min = DashSize.Bar)
            .clip(DashShape.Medium)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        if (label != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun BarClock(clock: String, style: TextStyle = MaterialTheme.typography.titleLarge) {
    Text(
        text = clock,
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.02).em,
        style = style
    )
}

/**
 * The centre of a cluster bar: the rev counter as a row of segments (the
 * last ones amber, then red), the speed in the hero face under it, and fuel
 * and coolant as short segment bars on either side. Segments only, no
 * needles: the reading is the count of lit cells.
 */
@Composable
private fun ClusterReadout(speedKmh: Int, obd: ObdData, connected: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        SegmentBar(
            label = stringResource(R.string.vehicle_fuel),
            fraction = if (connected && obd.fuelLevelPct > 0) obd.fuelLevelPct / 100f else 0f,
            hot = false,
            lowIsHot = true
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                val lit = if (connected) (obd.rpm / 7000f * TACHO_SEGMENTS).toInt().coerceIn(0, TACHO_SEGMENTS) else 0
                repeat(TACHO_SEGMENTS) { i ->
                    val colour = when {
                        i >= lit -> DashColors.CardHi
                        i >= TACHO_SEGMENTS - 2 -> DashColors.Critical
                        i >= TACHO_SEGMENTS - 4 -> DashColors.Tacho
                        else -> DashColors.Accent
                    }
                    Box(Modifier.size(width = 12.dp, height = 5.dp).clip(RoundedCornerShape(2.dp)).background(colour))
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    speedKmh.toString(),
                    color = DashColors.Accent,
                    fontFamily = DashColors.heroFamily(),
                    fontWeight = DashColors.HeroWeight,
                    fontSize = 40.sp,
                    lineHeight = 40.sp,
                    letterSpacing = (-0.02).em,
                    maxLines = 1
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "KM/H",
                    color = DashColors.TextSecondary,
                    letterSpacing = 0.1.em,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
        SegmentBar(
            label = stringResource(R.string.vehicle_coolant),
            fraction = if (connected) obd.coolantTempC / 120f else 0f,
            hot = obd.coolantTempC >= COOLANT_WARNING_C,
            lowIsHot = false
        )
    }
}

private const val TACHO_SEGMENTS = 14

/** Six segments and a caption; the top segment reads amber when [hot], the bottom one when [lowIsHot] and the level is low. */
@Composable
private fun SegmentBar(label: String, fraction: Float, hot: Boolean, lowIsHot: Boolean) {
    val lit = (fraction * 6f).toInt().coerceIn(0, 6)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            repeat(6) { i ->
                val colour = when {
                    i >= lit -> DashColors.CardHi
                    lowIsHot && lit <= 1 -> DashColors.Tacho
                    hot && i == 5 -> DashColors.Critical
                    else -> DashColors.Accent
                }
                Box(Modifier.size(width = 8.dp, height = 14.dp).clip(RoundedCornerShape(1.dp)).background(colour))
            }
        }
        Text(label.uppercase(), color = DashColors.TextSecondary, letterSpacing = 0.12.em, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/**
 * Layout picker around any [anchor] a skin draws: the anchor gets an `open`
 * callback, the menu offers all three layouts with the current one checked.
 */
@Composable
internal fun LayoutPicker(m: TopBarModel, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Switching layout reloads every page: parked only, like arranging.
    val lock = LocalDriveLock.current
    Box {
        anchor { lock.whenParked { open = true } }
        DashMenu(open, onDismiss = { open = false }) {
            DashLayout.entries.forEach { l ->
                DashMenuItem(
                    text = l.title,
                    leading = { LayoutIcon(l, null, if (l == m.layout) DashColors.Accent else DashColors.TextSecondary) },
                    selected = l == m.layout,
                    onClick = {
                        open = false
                        m.onLayout(l)
                    }
                )
            }
        }
    }
}

/** Icon for a layout: a dashboard, or a split whose solid pane (the map) sits on the docked side. */
@Composable
internal fun LayoutIcon(layout: DashLayout, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    val vertical = ScreenShape.vertical
    Icon(
        imageVector = when {
            layout == DashLayout.GRID -> Icons.Filled.SpaceDashboard
            vertical -> Icons.Filled.HorizontalSplit
            else -> Icons.Filled.VerticalSplit
        },
        contentDescription = contentDescription,
        tint = tint,
        // VerticalSplit draws its solid pane (the map) on the right, HorizontalSplit at the
        // bottom; mirror them for the left dock (on top, upright).
        modifier = modifier.then(
            when {
                layout != DashLayout.MAPS_LEFT -> Modifier
                vertical -> Modifier.scale(scaleX = 1f, scaleY = -1f)
                else -> Modifier.scale(scaleX = -1f, scaleY = 1f)
            }
        )
    )
}

internal fun obdStatusColor(state: ObdConnectionState): Color = when (state) {
    ObdConnectionState.CONNECTED -> DashColors.Good
    ObdConnectionState.CONNECTING -> DashColors.Accent
    ObdConnectionState.ERROR -> DashColors.Critical
    ObdConnectionState.DISCONNECTED -> DashColors.Muted
}

@StringRes
internal fun obdStatusLabelRes(state: ObdConnectionState): Int = when (state) {
    ObdConnectionState.CONNECTED -> R.string.dash_obd_connected
    ObdConnectionState.CONNECTING -> R.string.dash_obd_connecting
    ObdConnectionState.ERROR -> R.string.dash_obd_error
    ObdConnectionState.DISCONNECTED -> R.string.dash_obd_off
}

/** Spoken OBD link state; outside composition use [obdStatusLabelRes]. */
@Composable
internal fun obdStatusLabel(state: ObdConnectionState): String = stringResource(obdStatusLabelRes(state))

/**
 * OBD link as a pill that reads without colour: a dot (hollow when off, lit
 * with a halo when live, pulsing while connecting, with a "!" on error) and
 * the letters OBD. 48 dp tall to tap; tapping while idle connects.
 */
@Composable
internal fun ObdPill(state: ObdConnectionState, onConnect: () -> Unit, modifier: Modifier = Modifier) {
    val color = obdStatusColor(state)
    val label = obdStatusLabel(state)
    val idle = state.isIdle
    val off = state == ObdConnectionState.DISCONNECTED
    val connecting = state == ObdConnectionState.CONNECTING
    val pulse = if (connecting) rememberLoop(900, reverse = true, status = true) else null
    val halo = DashColors.Glow
    val ink = if (off) DashColors.Muted else color
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier
            .heightIn(min = DashSize.TouchPrimary)
            .clip(shape)
            .clickable(enabled = idle, role = Role.Button) { tap(); onConnect() }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(shape)
                .background(if (off) Color.Transparent else color.copy(alpha = 0.14f))
                .border(1.dp, if (off) DashColors.Line else color.copy(alpha = 0.5f), shape)
                .padding(start = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .drawBehind {
                        val r = size.minDimension / 2f
                        val a = pulse?.let { 0.35f + 0.65f * it.value } ?: 1f
                        if (state == ObdConnectionState.CONNECTED && halo > 0f) {
                            drawCircle(
                                Brush.radialGradient(listOf(color.copy(alpha = 0.6f * halo), Color.Transparent), center, r * 2.4f),
                                radius = r * 2.4f
                            )
                        }
                        if (off) drawCircle(ink, radius = r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                        else drawCircle(color.copy(alpha = a), radius = r)
                    }
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.dash_obd_short),
                color = ink,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.1.em,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            if (state == ObdConnectionState.ERROR) {
                Spacer(Modifier.width(4.dp))
                Text("!", color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/**
 * The phone on the head unit's Bluetooth ([UnitSignals]): its battery as five
 * bars and its name, beside the car's alerts in every skin's bar. Nothing
 * while no phone is connected, or on a unit that doesn't say.
 */
@Composable
internal fun PhonePill(modifier: Modifier = Modifier) {
    val phone by UnitSignals.phone.collectAsState()
    val p = phone ?: return
    val shape = DashShape.Pill
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .border(1.dp, DashColors.Line, shape)
            .semantics(mergeDescendants = true) {}
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(18.dp))
        p.battery?.let { level ->
            Spacer(Modifier.width(4.dp))
            // A battery that fills with the phone's level (0..5); red when nearly empty.
            Icon(
                when (level) {
                    0 -> Icons.Filled.BatteryAlert
                    1 -> Icons.Filled.Battery1Bar
                    2 -> Icons.Filled.Battery3Bar
                    3 -> Icons.Filled.Battery4Bar
                    4 -> Icons.Filled.Battery5Bar
                    else -> Icons.Filled.BatteryFull
                },
                contentDescription = null,
                tint = if (level <= 1) DashColors.Critical else DashColors.TextSecondary,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            p.name, color = DashColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.widthIn(max = 120.dp)
        )
    }
}

/**
 * Menu around any [anchor] a skin draws: the things done often (edit,
 * templates, split screen), one door to everything set once, and a way out
 * of the demo while it runs (it starts from Settings → Advanced).
 */
@Composable
internal fun MorePicker(m: TopBarModel, anchor: @Composable (open: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val pick: (() -> Unit) -> () -> Unit = { action ->
        {
            open = false
            action()
        }
    }
    val offered = m.update is UpdateStatus.Available || m.update is UpdateStatus.Ready
    Box {
        anchor { open = true }
        // A newer build waits behind the menu: a dot on its corner says so.
        if (offered) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 12.dp, end = 12.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(DashColors.Accent)
                    .semantics { contentDescription = "" }
            )
        }
        DashMenu(open, onDismiss = { open = false }) {
            if (m.moving) DriveLockRow()
            val parked = !m.moving
            UpdateMenuRow(m.update, parked, onUpdate = pick(m.onUpdate))
            DashMenuItem(
                text = stringResource(if (m.editing) R.string.dash_menu_done_editing else R.string.dash_menu_edit_dashboards),
                leading = { MenuIcon(if (m.editing) Icons.Filled.Done else Icons.Filled.Edit, parked) },
                enabled = parked || m.editing,
                onClick = pick(m.onToggleEdit)
            )
            DashMenuItem(stringResource(R.string.templates_button), leading = { MenuIcon(Icons.Filled.Dashboard, parked) }, enabled = parked, onClick = pick(m.onTemplates))
            DashMenuItem(stringResource(R.string.dash_menu_split_screen), leading = { MenuIcon(Icons.Filled.Splitscreen) }, onClick = pick(m.onSplit))
            DashMenuItem(stringResource(R.string.settings_menu), leading = { MenuIcon(Icons.Filled.Settings, parked) }, enabled = parked, onClick = pick(m.onSettings))
            if (m.demo) {
                HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(vertical = 4.dp))
                DashMenuItem(
                    text = stringResource(R.string.demo_menu_stop),
                    leading = { MenuIcon(Icons.Filled.Stop) },
                    onClick = pick(m.onDemo)
                )
            }
        }
    }
}

/** First row of the menu while the car moves: why the entries under it are greyed out. */
@Composable
private fun DriveLockRow() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.dash_drive_lock_notice),
            color = DashColors.Warning,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(bottom = 4.dp))
}

/** Floats over the pages for a moment after a tap the drive lock held back. */
@Composable
internal fun DriveLockChip(modifier: Modifier = Modifier) {
    val shape = DashShape.Pill
    Row(
        modifier = modifier
            .clip(shape)
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.6f), shape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.DirectionsCar, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.dash_drive_lock_notice),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1
        )
    }
}

/** How long [DriveLockChip] stays up. */
internal const val LOCK_NOTICE_MS = 2_500L

@Composable
private fun DashMenu(open: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    // An auto-hiding bar stays up while its menu is open (BarAutoHide.kt).
    if (open) {
        DisposableEffect(Unit) {
            BarAutoHide.openMenus++
            onDispose { BarAutoHide.openMenus-- }
        }
    }
    // Docked windows are drawn above the bar's pop-ups; one the menu overlaps steps aside meanwhile.
    DropdownMenu(
        expanded = open,
        onDismissRequest = onDismiss,
        modifier = Modifier.keepClearOfWindows(),
        shape = MaterialTheme.shapes.medium,
        containerColor = DashColors.Card.copy(alpha = 1f),
        border = BorderStroke(1.dp, DashColors.Line)
    ) {
        content()
    }
}

@Composable
private fun DashMenuItem(
    text: String,
    leading: @Composable () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    /** In the accent colour without being a choice (no check mark): the update on offer. */
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        // Rows a finger finds in a moving car, not a phone menu's.
        modifier = Modifier.heightIn(min = DashSize.MenuRow),
        text = {
            Text(
                text,
                color = when {
                    !enabled -> DashColors.Muted
                    selected || highlighted -> DashColors.Accent
                    else -> DashColors.TextPrimary
                },
                fontWeight = if (selected || highlighted) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        leadingIcon = leading,
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent) }
        } else null,
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
private fun MenuIcon(icon: ImageVector, enabled: Boolean = true) {
    Icon(icon, contentDescription = null, tint = if (enabled) DashColors.TextSecondary else DashColors.Muted)
}

/**
 * Alert pills on the bar (VehicleAlerts.kt): amber ones while a reading is
 * out of range, red ones for a critical reading, kept until tapped. Nothing
 * shows while everything is normal or OBD is off.
 */
@Composable
internal fun VehicleAlerts(obdConnection: ObdConnectionState, obd: State<ObdData>) {
    val context = LocalContext.current
    val battery = BatteryWatch.state.collectAsState()
    // Re-evaluated on every sample, but only a changed list of alerts recomposes the pills.
    val live by remember(obdConnection, context) {
        derivedStateOf {
            if (obdConnection == ObdConnectionState.CONNECTED) vehicleAlerts(context, obd.value, battery.value) else emptyList()
        }
    }
    // A critical reading goes to the centre, which keeps it until it is tapped.
    LaunchedEffect(live) {
        live.filter { it.level == AlertLevel.CRITICAL }.forEach { AlertCenter.raise(it) }
        AlertCenter.noteWarnings(live.filter { it.level == AlertLevel.WARNING })
    }
    val held = AlertCenter.critical
    held.values.sortedBy { it.key }.forEach { alert ->
        AlertChip(alert, onAcknowledge = { AlertCenter.acknowledge(alert.key) })
    }
    live.filter { it.level == AlertLevel.WARNING && !held.containsKey(it.key) }.forEach { alert ->
        AlertChip(alert, onAcknowledge = null)
    }
}

/** One alert pill: amber for a warning, red for a critical one, which also takes a tap to dismiss. */
@Composable
private fun AlertChip(alert: VehicleAlert, onAcknowledge: (() -> Unit)?) {
    val colour = if (alert.level == AlertLevel.CRITICAL) DashColors.Critical else DashColors.Warning
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .padding(end = 6.dp)
            .heightIn(min = DashSize.Touch)
            .clip(shape)
            .background(colour.copy(alpha = if (alert.level == AlertLevel.CRITICAL) 0.22f else 0.14f))
            .border(1.dp, colour.copy(alpha = if (alert.level == AlertLevel.CRITICAL) 0.7f else 0.45f), shape)
            .then(
                if (onAcknowledge != null) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_dismiss)) { tap(); onAcknowledge() }
                else Modifier
            )
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(alert.icon, contentDescription = null, tint = colour, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            alert.text,
            color = colour,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Shortens first when the bar is tight; the icons keep their size.
            modifier = Modifier.weight(1f, fill = false)
        )
        if (onAcknowledge != null) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Close, contentDescription = null, tint = colour, modifier = Modifier.size(16.dp))
        }
    }
}

/**
 * Floats over the dashboard while [DemoMode] runs, so made-up readings are
 * never taken for the car's; tap to stop. [compact] where the bar is shared
 * with other badges: the dot and "Stop demo".
 */
@Composable
internal fun DemoBadge(onStop: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    Row(
        modifier = modifier
            .heightIn(min = DashSize.Touch)
            .clip(shape)
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Accent.copy(alpha = 0.6f), shape)
            .clickable(role = Role.Button) { tap(); onStop() }
            .padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(DashColors.Accent))
        Spacer(Modifier.width(8.dp))
        if (!compact) {
            Text(
                stringResource(R.string.demo_badge),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
            Spacer(Modifier.width(12.dp))
        }
        Icon(Icons.Filled.Close, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            stringResource(if (compact) R.string.demo_menu_stop else R.string.demo_stop),
            color = DashColors.Accent,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

/**
 * Toolbar shown while arranging: what to do, plus Add / Undo / Reset / Done.
 * Changes save as they happen; Undo walks back through the last edits.
 */
@Composable
internal fun EditBar(
    page: Int,
    canUndo: Boolean,
    /** The page's text size (the zoom its tiles share, or the commonest one). */
    pageZoom: Float,
    onAdd: () -> Unit,
    onUndo: () -> Unit,
    onReset: () -> Unit,
    onTemplates: () -> Unit,
    /** Sets every tile on the page to this zoom. */
    onPageZoom: (Float) -> Unit,
    onDone: () -> Unit
) {
    val glass = DashColors.Glass
    val shape = DashShape.Medium
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp)
            .then(if (glass) glassPanel(shape) else Modifier.clip(shape).background(DashColors.Bar))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(DashColors.Accent)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(R.string.dash_arranging_dashboard, stringResource(DashboardStore.nameRes(page))),
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                stringResource(R.string.dash_arranging_hint),
                color = DashColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall
            )
        }
        TextButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dash_add), color = DashColors.TextPrimary)
        }
        TextButton(onClick = { tap(); onUndo() }, enabled = canUndo) {
            Icon(
                Icons.Filled.Undo, contentDescription = null,
                tint = if (canUndo) DashColors.TextPrimary else DashColors.Muted, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dash_undo), color = if (canUndo) DashColors.TextPrimary else DashColors.Muted)
        }
        TextButton(onClick = onTemplates) {
            Icon(Icons.Filled.Dashboard, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.templates_button), color = DashColors.TextPrimary)
        }
        PageZoomButton(pageZoom, onPageZoom)
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.dash_reset_page), color = DashColors.Critical)
        }
        Button(
            onClick = { tap(); onDone() },
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
            shape = DashShape.Small,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Icon(Icons.Filled.Done, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.dash_done))
        }
    }
}

/**
 * Floats over the pages for a few seconds after a page change, then fades:
 * the seven dashboards as the cross they form, the one on screen filled with
 * the accent, the rest hollow, and its name under it. It takes no room in the
 * layout and no touches.
 */
@Composable
internal fun PageIndicator(current: Int, modifier: Modifier = Modifier) {
    val cell = 12.dp
    val gap = 3.dp
    val accent = DashColors.Accent
    val ink = DashColors.TextSecondary
    Column(
        modifier = modifier
            .clip(DashShape.Medium)
            .background(DashColors.Card.copy(alpha = 0.92f))
            .border(1.dp, DashColors.Line, DashShape.Medium)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = Modifier.size(width = cell * 3 + gap * 2, height = cell * 5 + gap * 4)) {
            val c = cell.toPx()
            val step = (cell + gap).toPx()
            val r = CornerRadius(3.dp.toPx())
            fun draw(page: Int, col: Int, row: Int) {
                val topLeft = Offset(col * step, row * step)
                if (page == current) drawRoundRect(accent, topLeft, Size(c, c), r)
                else drawRoundRect(ink, topLeft, Size(c, c), r, style = Stroke(1.5.dp.toPx()))
            }
            val centreCol = DashboardStore.ROW.indexOf(DashboardStore.CENTER)
            DashboardStore.COLUMN.forEachIndexed { row, page -> draw(page, centreCol, row) }
            DashboardStore.ROW.forEachIndexed { col, page -> if (page != DashboardStore.CENTER) draw(page, col, DashboardStore.COLUMN_HOME) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(DashboardStore.nameRes(current)),
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )
    }
}

/** How long [PageIndicator] stays after a page change. */
internal const val PAGE_INDICATOR_MS = 2_000L


/**
 * The update's row at the top of the ⋮ menu: only while a newer build is on
 * offer, downloading or downloaded. Installing restarts the launcher, so the
 * row waits for the car to be parked.
 */
@Composable
private fun UpdateMenuRow(status: UpdateStatus, parked: Boolean, onUpdate: () -> Unit) {
    val text = when (status) {
        is UpdateStatus.Available -> stringResource(R.string.dash_update_to, status.info.versionName)
        is UpdateStatus.Ready -> stringResource(R.string.dash_update_to, status.info.versionName)
        is UpdateStatus.Downloading -> stringResource(R.string.dash_update_downloading, status.percent)
        else -> return
    }
    val enabled = parked && status !is UpdateStatus.Downloading
    DashMenuItem(
        text = text,
        leading = { MenuIcon(Icons.Filled.SystemUpdate, enabled) },
        highlighted = status !is UpdateStatus.Downloading,
        enabled = enabled,
        onClick = onUpdate
    )
    HorizontalDivider(color = DashColors.Line, modifier = Modifier.padding(vertical = 4.dp))
}

/**
 * On the bar while something the launcher could use is still not allowed
 * (notifications, location, contacts, calendar, the OBD adapter): one pill
 * for all of them, in place of a button on every tile. Opens the setup's
 * access step. [compact] where the bar is tight: the mark alone.
 */
@Composable
internal fun SetupPill(onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    val label = stringResource(R.string.setup_pill)
    Row(
        modifier = modifier
            .heightIn(min = DashSize.Touch)
            .widthIn(min = DashSize.Touch)
            .clip(shape)
            .background(DashColors.Card.copy(alpha = 1f))
            .border(1.dp, DashColors.Warning.copy(alpha = 0.7f), shape)
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Info, contentDescription = null, tint = DashColors.Warning, modifier = Modifier.size(20.dp))
        if (!compact) {
            Spacer(Modifier.width(8.dp))
            Text(
                label,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1
            )
        }
    }
}

/** The whole page's text size: a menu with smaller, the percentage, bigger and back to 100 %. */
@Composable
private fun PageZoomButton(zoom: Float, onZoom: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Icon(Icons.Filled.FormatSize, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.zoom_page), color = DashColors.TextPrimary)
        }
        DashMenu(open, onDismiss = { open = false }) {
            ZoomStepper(zoom, onZoom)
        }
    }
}

/** Smaller, the percentage, bigger, and back to 100 % when it is not there. */
@Composable
internal fun ZoomStepper(zoom: Float, onZoom: (Float) -> Unit) {
    Row(modifier = Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onZoom(zoomStep(zoom, -1)) }, enabled = zoom > ZOOM_MIN, modifier = Modifier.size(DashSize.Touch)) {
            Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.zoom_out), tint = DashColors.TextPrimary)
        }
        Text(
            stringResource(R.string.zoom_percent, (zoom * 100).roundToInt()),
            color = DashColors.TextPrimary, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.width(72.dp)
        )
        IconButton(onClick = { onZoom(zoomStep(zoom, 1)) }, enabled = zoom < ZOOM_MAX, modifier = Modifier.size(DashSize.Touch)) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.zoom_in), tint = DashColors.TextPrimary)
        }
        if (zoom != 1f) {
            TextButton(onClick = { onZoom(1f) }) {
                Text(stringResource(R.string.zoom_reset), color = DashColors.Accent)
            }
        }
    }
}
