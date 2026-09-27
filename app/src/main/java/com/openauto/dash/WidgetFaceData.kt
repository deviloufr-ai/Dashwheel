package com.openauto.dash

import android.Manifest
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.provider.CalendarContract
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocation
import androidx.compose.material.icons.filled.Adjust
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.DirectionsCarFilled
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SensorDoor
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.TireRepair
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Live readings for the designed tiles. Each built-in widget turns the feeds
 * its standard tile uses into a [WidgetFace]. A design never steps aside for
 * the standard tile: while a reading is missing (adapter off, access not
 * granted, nothing playing...) the face shows "--", says why, and carries the
 * button that fixes it, so the chosen design is always the one on screen.
 */

/** The live face of [kind]; null only for [FRAMED_KINDS], which keep their own content. */
@Composable
internal fun rememberWidgetFace(kind: BuiltinKind, env: SkinTileEnv): WidgetFace? = when (kind) {
    BuiltinKind.TELEMETRY -> telemetryFace(env)
    BuiltinKind.SPEED_HUD -> speedFace(env)
    BuiltinKind.MEDIA -> mediaFace(env)
    BuiltinKind.NAVIGATION -> directionsFace(env)
    BuiltinKind.CLOCK -> clockFace()
    BuiltinKind.WEATHER -> weatherFace()
    BuiltinKind.RANGE -> rangeFace(env)
    BuiltinKind.OBD_ALL -> obdAllFace(env)
    BuiltinKind.OBD_DTC -> faultCodesFace(env)
    BuiltinKind.DOORS -> doorsFace()
    BuiltinKind.CAN_MON -> canMonitorFace()
    BuiltinKind.COMPASS -> compassFace()
    BuiltinKind.TRIP -> tripFace()
    BuiltinKind.GFORCE -> gForceFace()
    BuiltinKind.PARKING -> parkingFace()
    BuiltinKind.CALENDAR -> agendaFace()
    BuiltinKind.QUICK_DIAL -> quickDialFace()
    BuiltinKind.NOTIFICATIONS -> notificationsFace(env)
    BuiltinKind.AUDIO -> audioFace()
    BuiltinKind.FILTER_CARE -> filterFace()
    BuiltinKind.WARMUP -> warmupFace(env)
    BuiltinKind.BATTERY -> batteryFace(env)
    BuiltinKind.ECO_DRIVE -> ecoFace()
    BuiltinKind.FUEL_TO_DEST -> fuelToDestFace()
    BuiltinKind.BREAK_TIMER -> breakFace()
    BuiltinKind.SERVICE -> serviceFace()
    BuiltinKind.FUEL_PRICES -> fuelPricesFace()
    BuiltinKind.CAR_STATUS -> carStatusFace()
    BuiltinKind.TYRES -> tyresFace()
    // Live views and the spec sheet: they keep their content and get the design's frame (DesignFrame).
    BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MY_CAR -> null
}

/**
 * Widgets framed rather than redrawn: live views (map, docked window) and
 * the car's spec sheet, whose tap opens the car settings.
 */
internal val FRAMED_KINDS = setOf(BuiltinKind.NAVMAP, BuiltinKind.PIP_ANCHOR, BuiltinKind.MY_CAR)

private fun fmt(pattern: String, vararg args: Any): String = String.format(Locale.getDefault(), pattern, *args)

/** A reading that isn't there yet: "--", why, and the button that gets it. */
private fun idleFace(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    caption: String,
    unit: String = "",
    action: FaceAction? = null,
    fullCircle: Boolean = false
) = WidgetFace(
    icon = icon, title = title, value = "--", unit = unit, caption = caption,
    fraction = 0f, fullCircle = fullCircle, actions = listOfNotNull(action)
)

/** The adapter isn't connected: say so, and offer Connect (off while a connection is under way). */
@Composable
private fun obdIdle(kind: BuiltinKind, env: SkinTileEnv, unit: String): WidgetFace = idleFace(
    kindIcon(kind), kind.label,
    stringResource(if (env.obdConnection == ObdConnectionState.CONNECTING) R.string.dash_obd_connecting else R.string.vehicle_obd_not_connected),
    unit,
    FaceAction(Icons.Filled.Bluetooth, stringResource(R.string.info_connect), primary = true, enabled = env.obdConnection.isIdle, onClick = env.onConnectObd)
)

/** Opens the system screen that grants notification access (media, directions, notifications). */
@Composable
private fun grantAccessAction(): FaceAction {
    val context = LocalContext.current
    return FaceAction(Icons.Filled.LockOpen, stringResource(R.string.info_grant_access), primary = true,
        onClick = { CarMediaController.openNotificationAccessSettings(context) })
}

// --- Vehicle -----------------------------------------------------------------------

/** The engine readings as small gauges (twin dials, shift lights, gauge bank). */
@Composable
private fun engineGauges(d: ObdData, rpmFirst: Boolean): List<FaceGauge> {
    val speed = FaceGauge(stringResource(R.string.vehicle_speed), d.speedKmh.toString(), "km/h", d.speedKmh / 220f)
    val rpm = FaceGauge(stringResource(R.string.vehicle_rpm), d.rpm.toString(), "rpm", d.rpm / 7000f)
    return (if (rpmFirst) listOf(rpm, speed) else listOf(speed, rpm)) + listOf(
        FaceGauge(stringResource(R.string.vehicle_coolant), d.coolantTempC.toString(), "°C", d.coolantTempC / 130f),
        FaceGauge(stringResource(R.string.vehicle_battery), fmt("%.1f", d.voltage), "V", batteryFraction(d.voltage)),
        FaceGauge(stringResource(R.string.vehicle_load), d.engineLoadPct.toString(), "%", d.engineLoadPct / 100f),
        FaceGauge(stringResource(R.string.vehicle_throttle), d.throttlePct.toString(), "%", d.throttlePct / 100f)
    )
}

@Composable
private fun telemetryFace(env: SkinTileEnv): WidgetFace {
    if (env.obdConnection != ObdConnectionState.CONNECTED) return obdIdle(BuiltinKind.TELEMETRY, env, "km/h")
    val d = env.obdData
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.TELEMETRY.label,
        value = d.speedKmh.toString(), unit = "km/h",
        caption = "${d.rpm} rpm",
        fraction = d.speedKmh / 220f,
        alert = d.speedKmh >= SPEED_WARNING_KMH || d.coolantTempC >= 110,
        number = d.speedKmh.toFloat(),
        gauges = engineGauges(d, rpmFirst = false),
        stats = listOf(
            FaceStat(stringResource(R.string.vehicle_rpm), d.rpm.toString()),
            FaceStat(stringResource(R.string.vehicle_coolant), "${d.coolantTempC} °C"),
            FaceStat(stringResource(R.string.vehicle_battery), fmt("%.1f V", d.voltage)),
            FaceStat(stringResource(R.string.vehicle_load), "${d.engineLoadPct} %")
        )
    )
}

@Composable
private fun obdAllFace(env: SkinTileEnv): WidgetFace {
    if (env.obdConnection != ObdConnectionState.CONNECTED) return obdIdle(BuiltinKind.OBD_ALL, env, "rpm")
    val d = env.obdData
    return WidgetFace(
        icon = Icons.Filled.Sensors,
        title = BuiltinKind.OBD_ALL.label,
        value = d.rpm.toString(), unit = "rpm",
        caption = "${d.speedKmh} km/h",
        fraction = d.rpm / 7000f,
        gauges = engineGauges(d, rpmFirst = true),
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.vehicle_speed), "${d.speedKmh} km/h"),
            FaceStat(stringResource(R.string.vehicle_coolant), "${d.coolantTempC} °C"),
            FaceStat(stringResource(R.string.vehicle_intake_air), "${d.intakeTempC} °C"),
            FaceStat(stringResource(R.string.vehicle_throttle), "${d.throttlePct} %"),
            FaceStat(stringResource(R.string.vehicle_engine_load), "${d.engineLoadPct} %"),
            FaceStat(stringResource(R.string.vehicle_battery), fmt("%.1f V", d.voltage)),
            d.fuelLevelPct.takeIf { it > 0 }?.let { FaceStat(stringResource(R.string.vehicle_fuel_level), "$it %") }
        )
    )
}

@Composable
private fun rangeFace(env: SkinTileEnv): WidgetFace {
    var showRangeFinder by remember { mutableStateOf(false) }
    if (showRangeFinder) RangeFinderDialog(onDismiss = { showRangeFinder = false })
    val fuel = rememberFuel(env.obdData, env.obdConnection) ?: return idleFace(
        Icons.Filled.LocalGasStation, BuiltinKind.RANGE.label, stringResource(R.string.design_range_unknown), "km",
        FaceAction(Icons.Filled.Tune, stringResource(R.string.vehicle_find_range_signal), primary = true, onClick = { showRangeFinder = true })
    )
    return WidgetFace(
        icon = Icons.Filled.LocalGasStation,
        title = BuiltinKind.RANGE.label,
        value = fuel.rangeKm.toString(), unit = "km",
        caption = fmt("%d %% · %.1f L", fuel.percent, fuel.liters),
        fraction = fuel.percent / 100f,
        alert = fuel.percent <= 10,
        severity = if (fuel.percent <= 10) 2 else if (fuel.percent <= 20) 1 else 0,
        sign = SignKind.FUEL,
        scale = "E" to "F",
        stats = listOf(
            FaceStat(stringResource(R.string.vehicle_fuel), "${fuel.percent} %"),
            FaceStat(stringResource(R.string.vehicle_in_tank), fmt("%.1f L", fuel.liters)),
            FaceStat(stringResource(R.string.vehicle_avg_use), fmt("%.1f L/100", fuel.avgUse))
        )
    )
}

@Composable
private fun faultCodesFace(env: SkinTileEnv): WidgetFace {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ai by AiMechanic.state.collectAsState()
    val lamp by ObdBluetoothManager.lamp.collectAsState()
    val pending by ObdBluetoothManager.pending.collectAsState()
    var busy by remember { mutableStateOf(false) }
    val connected = env.obdConnection == ObdConnectionState.CONNECTED
    val scan = FaceAction(Icons.Filled.Search, stringResource(R.string.vehicle_scan), primary = true, enabled = connected && !busy, onClick = {
        busy = true
        scope.launch {
            ObdBluetoothManager.readTroubleCodes().onSuccess { AiMechanic.report(it, announce = false) }
            busy = false
        }
    })
    val codes = ai.codes ?: return if (connected) {
        idleFace(Icons.Filled.Warning, BuiltinKind.OBD_DTC.label, stringResource(R.string.design_no_scan_yet), action = scan)
    } else obdIdle(BuiltinKind.OBD_DTC, env, "")
    val titles = remember(codes, context) { codes.map { ObdCodes.describe(it).localizedTitle(context) } }
    val lampOn = lamp?.on == true
    return WidgetFace(
        icon = Icons.Filled.Warning,
        title = BuiltinKind.OBD_DTC.label,
        value = codes.size.toString(),
        unit = stringResource(R.string.design_codes_unit),
        caption = when {
            codes.isNotEmpty() -> "${codes.first()} · ${titles.first()}"
            lampOn -> stringResource(R.string.vehicle_lamp_on)
            else -> stringResource(R.string.ai_no_codes)
        },
        fraction = if (codes.isEmpty()) 0f else 1f,
        alert = codes.isNotEmpty() || lampOn,
        severity = if (codes.isNotEmpty()) 2 else if (lampOn) 1 else 0,
        rows = codes.mapIndexed { i, c ->
            FaceRow("$c ${titles[i]}", if (c in pending) stringResource(R.string.vehicle_pending) else "", alert = true)
        },
        stats = listOfNotNull(lamp?.let { FaceStat(stringResource(R.string.design_engine_lamp), stringResource(if (it.on) R.string.design_on else R.string.design_off)) }),
        actions = listOf(
            scan,
            FaceAction(Icons.Filled.DeleteSweep, stringResource(R.string.vehicle_clear), enabled = connected && !busy && codes.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    ObdBluetoothManager.clearTroubleCodes().onSuccess { AiMechanic.cleared() }
                    busy = false
                }
            })
        )
    )
}

@Composable
private fun doorsFace(): WidgetFace {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val doors by McuReader.doorState.collectAsState()
    val d = doors ?: return idleFace(Icons.Filled.SensorDoor, BuiltinKind.DOORS.label, stringResource(R.string.vehicle_waiting_mcu))
    val open = stringResource(R.string.vehicle_door_open_caps)
    val closed = stringResource(R.string.vehicle_door_closed)
    val all = listOf(
        stringResource(R.string.vehicle_door_front_left) to d.frontLeft,
        stringResource(R.string.vehicle_door_front_right) to d.frontRight,
        stringResource(R.string.vehicle_door_rear_left) to d.rearLeft,
        stringResource(R.string.vehicle_door_rear_right) to d.rearRight,
        stringResource(R.string.vehicle_door_tailgate) to d.tailgate,
        stringResource(R.string.vehicle_door_bonnet) to d.bonnet
    )
    val openCount = all.count { it.second }
    return WidgetFace(
        icon = Icons.Filled.SensorDoor,
        title = BuiltinKind.DOORS.label,
        value = openCount.toString(),
        unit = stringResource(R.string.design_doors_open_unit),
        caption = if (d.anyOpen) all.filter { it.second }.joinToString(" · ") { it.first } else stringResource(R.string.vehicle_doors_all_closed),
        fraction = openCount / all.size.toFloat(),
        alert = d.anyOpen,
        severity = if (d.anyOpen) 1 else 0,
        doors = listOf(d.frontLeft, d.frontRight, d.rearLeft, d.rearRight, d.tailgate, d.bonnet),
        // Open doors first, so every design shows them before the closed ones.
        rows = all.sortedByDescending { it.second }.map { (name, isOpen) -> FaceRow(name, if (isOpen) open else closed, alert = isOpen) }
    )
}

@Composable
private fun canMonitorFace(): WidgetFace {
    DisposableEffect(Unit) {
        McuReader.start()
        onDispose { McuReader.stop() }
    }
    val entries by McuReader.entries.collectAsState()
    val now by rememberWallClock(500L)
    if (entries.isEmpty()) return idleFace(Icons.Filled.Sensors, BuiltinKind.CAN_MON.label, stringResource(R.string.vehicle_waiting_mcu), stringResource(R.string.design_can_ids))
    val changed = entries.count { now - it.changedAt < 1_000L }
    return WidgetFace(
        icon = Icons.Filled.Sensors,
        title = BuiltinKind.CAN_MON.label,
        value = entries.size.toString(),
        unit = stringResource(R.string.design_can_ids),
        caption = stringResource(R.string.design_can_changed, changed),
        fraction = changed / entries.size.toFloat(),
        stats = listOf(FaceStat(stringResource(R.string.design_can_changed_label), changed.toString())),
        rows = entries.sortedByDescending { it.changedAt }.take(4).map {
            FaceRow("${it.key}  ${it.hex}", fmt("%.1f s", ((now - it.changedAt).coerceAtLeast(0L)) / 1000f))
        }
    )
}

// --- Driving -------------------------------------------------------------------------

@Composable
private fun speedFace(env: SkinTileEnv): WidgetFace {
    val speed = rememberSpeedKmh(env.obdData, env.obdConnection)
    val source = when {
        env.obdConnection == ObdConnectionState.CONNECTED -> "OBD"
        speed != null -> "GPS"
        else -> stringResource(R.string.info_speed_no_signal)
    }
    return WidgetFace(
        icon = Icons.Filled.Speed,
        title = BuiltinKind.SPEED_HUD.label,
        value = speed?.toString() ?: "--", unit = "km/h",
        caption = source,
        fraction = (speed ?: 0) / 200f,
        alert = (speed ?: 0) >= SPEED_WARNING_KMH,
        number = speed?.toFloat(),
        sign = SignKind.SPEED
    )
}

@Composable
private fun compassFace(): WidgetFace {
    UseLocationFeed()
    val location by LocationFeed.location.collectAsState()
    val heading by LocationFeed.headingDeg.collectAsState()
    val h = heading
    return WidgetFace(
        icon = Icons.Filled.Explore,
        title = BuiltinKind.COMPASS.label,
        value = h?.let { stringResource(cardinalRes(it)) } ?: "--",
        unit = h?.let { "${it.roundToInt()}°" } ?: "",
        caption = if (h == null) stringResource(R.string.info_compass_no_heading) else "",
        fraction = h?.let { ((it % 360f) + 360f) % 360f / 360f },
        angle = h,
        fullCircle = true,
        compass = true,
        stats = listOf(
            FaceStat(stringResource(R.string.info_compass_altitude), location?.takeIf { it.hasAltitude() }?.let { "${it.altitude.roundToInt()} m" } ?: "--"),
            FaceStat(stringResource(R.string.info_compass_gps_speed), location?.let { "${(it.speed * 3.6f).roundToInt()} km/h" } ?: "--"),
            FaceStat(stringResource(R.string.info_compass_accuracy), location?.let { "±${it.accuracy.roundToInt()} m" } ?: "--")
        )
    )
}

@Composable
private fun tripFace(): WidgetFace {
    UseLocationFeed()
    val trip by LocationFeed.trip.collectAsState()
    // Re-read each second so the elapsed time keeps moving while parked.
    rememberWallClock(1_000L).longValue
    val km = trip.distanceM / 1000.0
    val since = stringResource(R.string.info_trip_since, remember(trip.startedAt) { formatClock(trip.startedAt) })
    return WidgetFace(
        icon = Icons.Filled.Timeline,
        title = BuiltinKind.TRIP.label,
        value = if (km < 100) fmt("%.1f", km) else km.roundToInt().toString(), unit = "km",
        caption = since,
        reach = since,
        stats = listOf(
            FaceStat(stringResource(R.string.info_trip_time), formatDuration(trip.elapsedMs)),
            FaceStat(stringResource(R.string.info_trip_average), "${trip.avgSpeedKmh.roundToInt()} km/h"),
            FaceStat(stringResource(R.string.info_trip_top), "${trip.maxSpeedKmh.roundToInt()} km/h")
        ),
        actions = listOf(FaceAction(Icons.Filled.Refresh, stringResource(R.string.info_reset), onClick = { LocationFeed.resetTrip() }))
    )
}

@Composable
private fun gForceFace(): WidgetFace {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        GForceFeed.acquire(context)
        onDispose { GForceFeed.release() }
    }
    val g by GForceFeed.g.collectAsState()
    val total = sqrt(g.lateral * g.lateral + g.longitudinal * g.longitudinal)
    // Each axis formatted once and reused (caption and stats), ~15 times a second.
    val lateral = fmt("%+.2f", g.lateral)
    val longitudinal = fmt("%+.2f", g.longitudinal)
    return WidgetFace(
        icon = Icons.Filled.Adjust,
        title = BuiltinKind.GFORCE.label,
        value = fmt("%.2f", total), unit = "g",
        caption = "$lateral / $longitudinal g",
        fraction = total / 1.2f,
        alert = total >= 1f,
        point = g.lateral to g.longitudinal,
        peak = maxOf(g.peakLateral, g.peakLongitudinal),
        stats = listOf(
            FaceStat(stringResource(R.string.info_gforce_lateral), lateral),
            FaceStat(stringResource(R.string.info_gforce_accel_brake), longitudinal),
            FaceStat(stringResource(R.string.info_gforce_peaks), fmt("%.2f / %.2f", g.peakLateral, g.peakLongitudinal))
        ),
        actions = listOf(FaceAction(Icons.Filled.Refresh, stringResource(R.string.info_gforce_reset_peaks), onClick = { GForceFeed.resetPeaks() }))
    )
}

@Composable
private fun parkingFace(): WidgetFace {
    val context = LocalContext.current
    UseLocationFeed()
    LaunchedEffect(Unit) { ParkingStore.load(context) }
    val spot by ParkingStore.spot.collectAsState()
    val location by LocationFeed.location.collectAsState()
    // "Parked 5 min ago" moves by the minute.
    rememberWallClock(60_000L).longValue
    val s = spot ?: return idleFace(
        Icons.Filled.LocalParking, BuiltinKind.PARKING.label,
        stringResource(if (location != null) R.string.info_parking_prompt else R.string.info_waiting_gps),
        action = FaceAction(Icons.Filled.AddLocation, stringResource(R.string.info_parking_save), primary = true,
            enabled = location != null, onClick = { location?.let { ParkingStore.save(context, it) } })
    )
    val here = location
    val (dist, bearing) = remember(here, s) {
        if (here == null) null to null else {
            val results = FloatArray(2)
            Location.distanceBetween(here.latitude, here.longitude, s.lat, s.lng, results)
            results[0] to results[1]
        }
    }
    val text = dist?.let { formatDistance(it) } ?: "--"
    val ago = formatAgo(s.savedAt)
    val heading by LocationFeed.headingDeg.collectAsState()
    return WidgetFace(
        icon = Icons.Filled.LocalParking,
        title = BuiltinKind.PARKING.label,
        value = text.substringBefore(' '), unit = text.substringAfter(' ', ""),
        caption = if (bearing != null) stringResource(R.string.info_parking_parked_dir, ago, stringResource(cardinalRes(bearing)))
        else stringResource(R.string.info_parking_parked, ago),
        // Closer is fuller: the last 2 km count down to the car.
        fraction = dist?.let { 1f - (it / 2000f).coerceIn(0f, 1f) },
        // The way to the car relative to where the car points now (straight up = ahead).
        angle = bearing?.let { ((it - (heading ?: 0f)) % 360f + 360f) % 360f },
        sign = SignKind.PARKING,
        actions = listOf(
            FaceAction(Icons.Filled.DirectionsWalk, stringResource(R.string.info_parking_walk), primary = true, onClick = { walkTo(context, s) }),
            FaceAction(Icons.Filled.Close, stringResource(R.string.info_clear), onClick = { ParkingStore.clear(context) })
        )
    )
}

// --- Navigation and media ------------------------------------------------------------------

@Composable
private fun directionsFace(env: SkinTileEnv): WidgetFace {
    val nav by NavDirections.state.collectAsState()
    if (!env.hasMediaAccess) {
        return idleFace(Icons.Filled.TurnRight, BuiltinKind.NAVIGATION.label, stringResource(R.string.info_directions_access_title), action = grantAccessAction())
    }
    if (!nav.active) {
        return idleFace(
            Icons.Filled.TurnRight, BuiltinKind.NAVIGATION.label, stringResource(R.string.info_directions_idle_title),
            action = FaceAction(Icons.Filled.Navigation, stringResource(R.string.info_directions_open_maps), primary = true,
                onClick = { openNavigationApp(env.context, nav) })
        )
    }
    val (value, unit) = nav.distanceParts
    val time = stringResource(R.string.design_nav_time)
    val distance = stringResource(R.string.design_nav_distance)
    val arrival = stringResource(R.string.design_nav_arrival)
    val art = remember(nav.icon) { nav.icon?.asImageBitmap() }
    return WidgetFace(
        icon = Icons.Filled.TurnRight,
        title = BuiltinKind.NAVIGATION.label,
        value = value.ifEmpty { "--" }, unit = unit,
        caption = nav.instruction,
        stats = nav.etaParts.map { part ->
            val label = when {
                ARRIVAL_TIME.containsMatchIn(part) -> arrival
                NavState.DISTANCE.containsMatchIn(part) -> distance
                else -> time
            }
            FaceStat(label, part)
        },
        art = art,
        sign = SignKind.DIRECTIONS,
        // Closeness to the turn over its last kilometre (the turn card's bar).
        fraction = value.replace(',', '.').toFloatOrNull()?.let { v ->
            val metres = when (unit) { "km" -> v * 1000f; "mi" -> v * 1609f; "ft" -> v * 0.3048f; "yd" -> v * 0.9144f; else -> v }
            1f - (metres / 1000f).coerceIn(0f, 1f)
        },
        onClick = { openNavigationApp(env.context, nav) }
    )
}

/** An ETA part that is a clock time ("14:05", "14h05"): the arrival. */
private val ARRIVAL_TIME = Regex("""^\d{1,2}[:h.]\d{2}""")

@Composable
private fun mediaFace(env: SkinTileEnv): WidgetFace {
    val state = env.mediaState
    if (!env.hasMediaAccess) {
        return idleFace(Icons.Filled.MusicNote, stringResource(R.string.info_now_playing), stringResource(R.string.info_media_access_needed), action = grantAccessAction())
            .let { WidgetFace(it.icon, it.title, stringResource(R.string.info_nothing_playing), caption = it.caption, textValue = true, fraction = 0f, actions = it.actions) }
    }
    val positionMs = rememberMediaPosition(state, env.mediaController)
    val fraction = if (state.durationMs > 0L) (positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else null
    val art = remember(state.artwork) { state.artwork?.asImageBitmap() }
    val controller = env.mediaController
    return WidgetFace(
        icon = Icons.Filled.MusicNote,
        title = stringResource(R.string.info_now_playing),
        value = state.title.ifBlank { stringResource(R.string.info_nothing_playing) },
        textValue = true,
        caption = state.artist,
        fraction = fraction,
        art = art,
        active = state.isPlaying,
        stats = if (state.durationMs > 0L) listOf(
            FaceStat(stringResource(R.string.design_elapsed), formatTrackTime(positionMs)),
            FaceStat(stringResource(R.string.design_length), formatTrackTime(state.durationMs))
        ) else emptyList(),
        actions = listOf(
            FaceAction(Icons.Filled.SkipPrevious, stringResource(R.string.info_media_previous), onClick = { controller.previous() }),
            FaceAction(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                stringResource(R.string.info_media_play_pause), primary = true, onClick = { controller.playPause() }
            ),
            FaceAction(Icons.Filled.SkipNext, stringResource(R.string.info_media_next), onClick = { controller.next() })
        )
    )
}

@Composable
private fun audioFace(): WidgetFace {
    val context = LocalContext.current
    val audio = remember { MediaVolume.audio(context) }
    val onUnit by MediaVolume.onUnit.collectAsState()
    val max = remember(onUnit) { MediaVolume.max(audio) }
    // Follows the hardware knob and other apps, like the standard audio tile.
    var volume by rememberMusicVolume(audio)
    val byKeys by MediaVolume.byKeys.collectAsState()
    val unavailable by MediaVolume.unavailable.collectAsState()
    LaunchedEffect(Unit) { MediaVolume.check(audio) }
    // Whether anything plays: the level meter only bounces then.
    var playing by remember { mutableStateOf(audio.isMusicActive) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            playing = audio.isMusicActive
        }
    }
    // Where the volume keys are pressed instead (MediaVolume), the level isn't known.
    val muted = !byKeys && volume == 0
    fun act(change: () -> Unit) {
        change()
        volume = MediaVolume.level(audio)
    }
    return WidgetFace(
        icon = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
        title = BuiltinKind.AUDIO.label,
        value = if (byKeys) "\u2013" else (volume * 100f / max).roundToInt().toString(), unit = if (byKeys) "" else "%",
        caption = stringResource(
            when {
                !byKeys -> R.string.design_media_volume
                unavailable -> R.string.info_audio_unavailable
                else -> R.string.info_audio_by_keys
            }
        ),
        fraction = if (byKeys) 0.5f else volume / max.toFloat(),
        alert = muted || (byKeys && unavailable),
        active = playing,
        actions = listOf(
            FaceAction(
                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                stringResource(if (muted) R.string.info_audio_unmute else R.string.info_audio_mute),
                primary = true,
                onClick = { act { MediaVolume.toggleMute(context) } }
            ),
            FaceAction(Icons.Filled.Remove, stringResource(R.string.design_volume_down), onClick = { act { MediaVolume.lower(context) } }),
            FaceAction(Icons.Filled.Add, stringResource(R.string.design_volume_up), onClick = { act { MediaVolume.raise(context) } })
        )
    )
}

// --- Info ------------------------------------------------------------------------------------

@Composable
private fun clockFace(): WidgetFace {
    val context = LocalContext.current
    val now = rememberNow(1_000L)
    val locale = Locale.getDefault()
    val timeFmt = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val dateFmt = remember(locale) { SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale) }
    val cal = remember(now) { Calendar.getInstance().apply { time = now } }
    val h = cal.get(Calendar.HOUR_OF_DAY)
    val m = cal.get(Calendar.MINUTE)
    val s = cal.get(Calendar.SECOND)
    return WidgetFace(
        icon = Icons.Filled.Schedule,
        title = BuiltinKind.CLOCK.label,
        value = timeFmt.format(now),
        caption = dateFmt.format(now).replaceFirstChar { it.uppercase() },
        fraction = s / 60f,
        fullCircle = true,
        clock = Triple(h, m, s),
        onClick = { openClockApp(context) }
    )
}

@Composable
private fun weatherFace(): WidgetFace {
    val location by LocationFeed.location.collectAsState()
    val w = rememberWeather() ?: return idleFace(
        Icons.Filled.WbSunny, BuiltinKind.WEATHER.label,
        stringResource(if (location == null) R.string.info_waiting_gps else R.string.info_weather_loading), "°C"
    )
    return WidgetFace(
        icon = weatherIcon(w.code),
        title = BuiltinKind.WEATHER.label,
        value = w.tempC.roundToInt().toString(), unit = "°C",
        caption = w.condition,
        // -10 °C .. 40 °C across the gauge.
        fraction = ((w.tempC + 10.0) / 50.0).toFloat().coerceIn(0f, 1f),
        scale = "-10°" to "40°",
        weatherCode = w.code,
        stats = listOfNotNull(
            FaceStat(stringResource(R.string.design_feels_like), "${w.feelsC.roundToInt()}°"),
            FaceStat(stringResource(R.string.design_wind), "${w.windKmh.roundToInt()} km/h"),
            if (!w.hiC.isNaN()) FaceStat(stringResource(R.string.design_low_high), "${w.loC.roundToInt()}° / ${w.hiC.roundToInt()}°") else null
        )
    )
}

@Composable
private fun agendaFace(): WidgetFace {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.READ_CALENDAR)
    val allowed = perm.granted
    var events by remember { mutableStateOf<List<AgendaEvent>?>(null) }
    LaunchedEffect(allowed) {
        if (!allowed) return@LaunchedEffect
        while (true) {
            events = withContext(Dispatchers.IO) { loadAgenda(context) }
            delay(5 * 60_000)
        }
    }
    val openCalendar = {
        context.launchSafely(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()))
        Unit
    }
    if (!allowed) {
        return idleFace(Icons.Filled.Event, BuiltinKind.CALENDAR.label, stringResource(R.string.info_agenda_needs_access),
            action = FaceAction(Icons.Filled.LockOpen, stringResource(R.string.info_agenda_allow), primary = true, onClick = perm.request))
    }
    val list = events.orEmpty()
    if (list.isEmpty()) {
        return idleFace(Icons.Filled.Event, BuiltinKind.CALENDAR.label, stringResource(R.string.info_agenda_empty),
            action = FaceAction(Icons.Filled.Event, stringResource(R.string.info_open), onClick = openCalendar))
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dayFmt = remember { SimpleDateFormat("EEE HH:mm", Locale.getDefault()) }
    val noTitle = stringResource(R.string.info_agenda_no_title)
    val allDay = stringResource(R.string.info_agenda_all_day)
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    fun whenText(e: AgendaEvent): String = when {
        e.allDay -> allDay
        Calendar.getInstance().apply { timeInMillis = e.begin }.get(Calendar.DAY_OF_YEAR) == today -> timeFmt.format(Date(e.begin))
        else -> dayFmt.format(Date(e.begin))
    }
    val next = list.first()
    val now = System.currentTimeMillis()
    val span = (next.begin - now).coerceAtLeast(0L)
    return WidgetFace(
        icon = Icons.Filled.Event,
        title = BuiltinKind.CALENDAR.label,
        value = whenText(next), textValue = next.allDay || !whenText(next).first().isDigit(),
        caption = next.title.ifBlank { noTitle } + if (next.location.isNotBlank()) " · ${next.location}" else "",
        // The next event's approach over the coming 3 hours.
        fraction = 1f - (span / (3 * 3_600_000f)).coerceIn(0f, 1f),
        rows = list.map { e -> FaceRow(e.title.ifBlank { noTitle }, whenText(e), alert = e.begin <= now) },
        events = list.filter { !it.allDay }.map { FaceEvent(it.begin, it.end, it.title.ifBlank { noTitle }) },
        onClick = openCalendar
    )
}

@Composable
private fun quickDialFace(): WidgetFace {
    val context = LocalContext.current
    val perm = rememberPermission(Manifest.permission.READ_CONTACTS)
    val allowed = perm.granted
    var favourites by remember { mutableStateOf<List<Favourite>>(emptyList()) }
    LaunchedEffect(allowed) {
        if (allowed) favourites = withContext(Dispatchers.IO) { loadFavourites(context) }
    }
    val dialer = FaceAction(Icons.Filled.Dialpad, stringResource(R.string.info_quickdial_dialer), onClick = { context.launchSafely(Intent(Intent.ACTION_DIAL)) })
    if (!allowed) {
        return idleFace(Icons.Filled.Call, BuiltinKind.QUICK_DIAL.label, stringResource(R.string.info_quickdial_needs_access),
            action = FaceAction(Icons.Filled.LockOpen, stringResource(R.string.info_quickdial_allow), primary = true, onClick = perm.request))
            .let { WidgetFace(it.icon, it.title, "--", caption = it.caption, actions = it.actions + dialer) }
    }
    if (favourites.isEmpty()) {
        return idleFace(Icons.Filled.Call, BuiltinKind.QUICK_DIAL.label, stringResource(R.string.info_quickdial_empty), action = dialer)
    }
    fun dial(f: Favourite) {
        f.number?.let { context.launchSafely(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$it"))) }
    }
    fun initials(name: String) = name.split(' ').take(2).mapNotNull { it.firstOrNull()?.uppercase() }.joinToString("")
    val first = favourites.first()
    return WidgetFace(
        icon = Icons.Filled.Call,
        title = BuiltinKind.QUICK_DIAL.label,
        value = first.name.substringBefore(' '), textValue = true,
        caption = first.number.orEmpty(),
        rows = favourites.map { f -> FaceRow(f.name, f.number.orEmpty(), badge = initials(f.name), onClick = { dial(f) }) },
        stats = favourites.drop(1).take(3).map { FaceStat(initials(it.name), it.name.substringBefore(' ')) },
        actions = listOf(
            FaceAction(Icons.Filled.Call, stringResource(R.string.design_call_first, first.name.substringBefore(' ')), primary = true,
                enabled = first.number != null, onClick = { dial(first) }),
            dialer
        )
    )
}

@Composable
private fun notificationsFace(env: SkinTileEnv): WidgetFace {
    if (!env.hasMediaAccess) {
        return idleFace(Icons.Filled.Notifications, BuiltinKind.NOTIFICATIONS.label, stringResource(R.string.info_notif_needs_access), action = grantAccessAction())
    }
    val items by NotificationFeed.items.collectAsState()
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val latest = items.firstOrNull()
    return WidgetFace(
        icon = Icons.Filled.Notifications,
        title = BuiltinKind.NOTIFICATIONS.label,
        value = items.size.toString(),
        unit = stringResource(R.string.design_notif_new_unit),
        caption = latest?.let { n -> listOf(n.title.ifEmpty { n.appLabel }, n.text).filter { it.isNotEmpty() }.joinToString(": ") }
            ?: stringResource(R.string.info_notif_empty),
        fraction = (items.size / 10f).coerceIn(0f, 1f),
        rows = items.map { n ->
            FaceRow(
                n.title.ifEmpty { n.appLabel }, timeFmt.format(Date(n.postedAt)), onClick = { runCatching { n.contentIntent?.send() } },
                onDismiss = { NotificationFeed.remove(n.key) }, key = n.key
            )
        },
        stats = latest?.let { listOf(FaceStat(stringResource(R.string.design_latest), timeFmt.format(Date(it.postedAt)))) }.orEmpty(),
        events = items.take(6).map { FaceEvent(it.postedAt, null, it.title.ifEmpty { it.appLabel }) },
        actions = if (items.isEmpty()) emptyList() else listOf(
            FaceAction(Icons.Filled.ClearAll, stringResource(R.string.info_clear), onClick = { NotificationFeed.dismissAll() })
        )
    )
}

/** Stand-in reading for the design picker, for a kind with no face of its own. */
@Composable
internal fun sampleFace(kind: BuiltinKind): WidgetFace = WidgetFace(
    icon = kindIcon(kind),
    title = kind.label,
    value = when (kind) {
        BuiltinKind.CLOCK -> "12:30"
        BuiltinKind.MEDIA, BuiltinKind.QUICK_DIAL, BuiltinKind.CALENDAR -> kind.label
        else -> "88"
    },
    textValue = kind == BuiltinKind.MEDIA || kind == BuiltinKind.QUICK_DIAL || kind == BuiltinKind.CALENDAR,
    caption = kind.blurb,
    fraction = 0.6f,
    fullCircle = kind == BuiltinKind.COMPASS || kind == BuiltinKind.CLOCK,
    clock = if (kind == BuiltinKind.CLOCK) Triple(12, 30, 0) else null,
    compass = kind == BuiltinKind.COMPASS
)

// --- Car care (CarCareTiles.kt) -------------------------------------------------------

@Composable
private fun filterFace(): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    if (!car.particleFilter) return idleFace(kindIcon(BuiltinKind.FILTER_CARE), BuiltinKind.FILTER_CARE.label, stringResource(R.string.car_filter_none))
    val streak = care.filter.shortStreak
    val call = filterCall(streak)
    val drive = care.drive
    val last = care.filter.lastLongAt
    val now = System.currentTimeMillis()
    // The real soot load, when the experimental reading finder got the car to give it up.
    val extra by PidExplorer.readings.collectAsState()
    val soot = extra[ExtraReading.SOOT_LOAD]?.value
    return WidgetFace(
        icon = kindIcon(BuiltinKind.FILTER_CARE),
        title = BuiltinKind.FILTER_CARE.label,
        value = streak.toString(),
        unit = stringResource(R.string.car_filter_short_unit),
        stats = listOfNotNull(
            soot?.let { FaceStat(stringResource(R.string.explore_soot_load), extraValueText(ExtraReading.SOOT_LOAD, it)) },
            extra[ExtraReading.DPF_TEMP]?.let { FaceStat(stringResource(R.string.explore_dpf_temp), extraValueText(ExtraReading.DPF_TEMP, it.value)) },
            extra[ExtraReading.REGEN_ACTIVE]?.let { FaceStat(stringResource(R.string.explore_regen), extraValueText(ExtraReading.REGEN_ACTIVE, it.value)) }
        ),
        caption = stringResource(call.text),
        alert = call.level > 0,
        severity = call.level,
        fraction = drive?.let { it.hotFastMs.toFloat() / CareRules.LONG_DRIVE_MS },
        rows = listOfNotNull(
            drive?.let {
                FaceRow(stringResource(R.string.car_filter_this_drive, (it.hotFastMs / 60_000).toInt(), (CareRules.LONG_DRIVE_MS / 60_000).toInt()), "")
            },
            FaceRow(
                if (last > 0) stringResource(R.string.car_filter_last_long, android.text.format.DateUtils.getRelativeTimeSpanString(last, now, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
                else stringResource(R.string.car_filter_last_long_never),
                ""
            )
        )
    )
}

@Composable
private fun warmupFace(env: SkinTileEnv): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    // The coolant moves a degree at a time: follow it, not every OBD sample.
    val t by remember(env) { derivedStateOf { env.obdData.coolantTempC } }
    if (env.obdConnection != ObdConnectionState.CONNECTED) return obdIdle(BuiltinKind.WARMUP, env, "°C")
    if (t == 0) return idleFace(kindIcon(BuiltinKind.WARMUP), BuiltinKind.WARMUP.label, stringResource(R.string.car_waiting_obd), "°C")
    val call = warmupCall(t, car)
    // Ticks each second only while a drive runs, for its "running for" line.
    val runningFor = care.drive?.let { d ->
        val now by rememberWallClock(1_000L)
        stringResource(R.string.car_running_for, formatDuration(now - d.startedAt))
    }
    return WidgetFace(
        icon = kindIcon(BuiltinKind.WARMUP),
        title = BuiltinKind.WARMUP.label,
        value = t.toString(), unit = "°C",
        caption = stringResource(call.text, car.coldRpmLimit),
        alert = call.level > 0,
        severity = call.level,
        fraction = t.toFloat() / car.hotC,
        scale = "0°" to "${car.hotC}°",
        rows = listOfNotNull(runningFor?.let { FaceRow(it, "") })
    )
}

@Composable
private fun batteryFace(env: SkinTileEnv): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val v = env.obdData.voltage
    if (env.obdConnection != ObdConnectionState.CONNECTED) return obdIdle(BuiltinKind.BATTERY, env, "V")
    if (v < LiveWatch.MIN_PLAUSIBLE_V || v > LiveWatch.MAX_PLAUSIBLE_V) {
        return idleFace(kindIcon(BuiltinKind.BATTERY), BuiltinKind.BATTERY.label, stringResource(R.string.car_waiting_obd), "V")
    }
    val running = env.obdData.rpm > LiveWatch.RUNNING_RPM
    val call = batteryCall(v, running)
    return WidgetFace(
        icon = kindIcon(BuiltinKind.BATTERY),
        title = BuiltinKind.BATTERY.label,
        value = fmt("%.1f", v), unit = "V",
        caption = stringResource(call.text),
        alert = call.level > 0,
        severity = call.level,
        fraction = batteryCareFraction(v).coerceIn(0f, 1f),
        rows = listOf(
            FaceRow(
                car.batteryAh?.let { stringResource(R.string.car_battery_capacity, it) }
                    ?: stringResource(if (running) R.string.car_battery_hint_running else R.string.car_battery_hint_off),
                ""
            )
        )
    )
}

@Composable
private fun ecoFace(): WidgetFace {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val drive = care.drive ?: care.lastDrive
        ?: return idleFace(kindIcon(BuiltinKind.ECO_DRIVE), BuiltinKind.ECO_DRIVE.label, stringResource(R.string.car_eco_empty), "/ 100")
    val score = drive.ecoScore
    val liters = drive.distanceKm * car.typicalUse / 100
    val call = ecoCall(score)
    return WidgetFace(
        icon = kindIcon(BuiltinKind.ECO_DRIVE),
        title = stringResource(if (care.drive != null) R.string.car_eco_title else R.string.car_eco_title_last),
        value = score?.toString() ?: "--", unit = "/ 100",
        caption = stringResource(call.text),
        alert = call.level >= 2,
        severity = call.level,
        fraction = score?.let { it / 100f },
        stats = listOfNotNull(
            drive.sweetPercent?.let { FaceStat(stringResource(R.string.car_eco_band, car.sweetBand.first, car.sweetBand.last), "$it %") },
            FaceStat(stringResource(R.string.car_eco_hard), stringResource(R.string.car_eco_hard_value, drive.hardAccel, drive.hardBrake)),
            if (car.gearbox == GearboxType.ROBOTISED) FaceStat(stringResource(R.string.car_eco_clutch), drive.clutchHolds.toString()) else null,
            FaceStat(
                stringResource(R.string.car_eco_fuel, fmt("%.1f", drive.distanceKm)),
                stringResource(R.string.car_eco_fuel_value, fmt("%.1f", liters), fmt("%.2f", liters * car.fuelPrice), car.currency)
            )
        )
    )
}

@Composable
private fun fuelToDestFace(): WidgetFace {
    val trip = rememberFuelToDest()
        ?: return idleFace(kindIcon(BuiltinKind.FUEL_TO_DEST), BuiltinKind.FUEL_TO_DEST.label, stringResource(R.string.car_fuel_dest_no_range), "km")
    val range = trip.rangeKm
    val verdict = trip.verdict
    val icon = kindIcon(BuiltinKind.FUEL_TO_DEST)
    val title = BuiltinKind.FUEL_TO_DEST.label
    if (verdict == null) {
        return WidgetFace(icon = icon, title = title, value = range.toString(), unit = "km", caption = stringResource(R.string.car_fuel_dest_no_nav), sign = SignKind.FUEL)
    }
    val call = fuelCall(verdict)
    val km = trip.km
    return WidgetFace(
        icon = icon, title = title,
        value = trip.spareKm.toString(),
        unit = stringResource(R.string.car_fuel_dest_spare_unit),
        caption = stringResource(call.text),
        alert = call.level > 0,
        severity = call.level,
        sign = SignKind.FUEL,
        scale = "E" to "F",
        reach = "$range km",
        marker = "${km.toInt()} km",
        fraction = trip.usedFraction,
        rows = listOf(FaceRow(stringResource(R.string.car_fuel_dest_detail, range, km.toInt()), ""))
    )
}

@Composable
private fun breakFace(): WidgetFace {
    val care by CarCare.state.collectAsState()
    val due = CareRules.FIRST_BREAK_MIN * 60_000L
    val driving = care.rest.drivingMs
    return WidgetFace(
        icon = kindIcon(BuiltinKind.BREAK_TIMER),
        title = BuiltinKind.BREAK_TIMER.label,
        value = formatDuration(driving),
        caption = stringResource(if (driving >= due) R.string.car_break_due else R.string.car_break_ok),
        alert = driving >= due,
        severity = if (driving >= due) 2 else if (driving >= due - 20 * 60_000L) 1 else 0,
        sign = SignKind.REST,
        fraction = driving.toFloat() / due,
        rows = listOf(FaceRow(stringResource(R.string.car_break_hint), ""))
    )
}

// --- Servicing and fuel prices ------------------------------------------------------------------

@Composable
private fun serviceFace(): WidgetFace {
    val state by Maintenance.state.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (editing) UpkeepDialog(onDismiss = { editing = false })
    val now = System.currentTimeMillis()
    val dues = remember(state) { state.statuses(now) }
    val first = dues.firstOrNull { it.stage != UpkeepStage.UNKNOWN }
    val odo = state.odometer
    val caption = when {
        odo == null -> stringResource(R.string.upkeep_no_odometer)
        first == null -> stringResource(R.string.upkeep_needs_dates)
        first.stage == UpkeepStage.OK -> stringResource(R.string.upkeep_all_good)
        else -> upkeepLine(first)
    }
    // How far into its interval the next item is.
    val fraction = first?.let { d ->
        val interval = state.plan.firstOrNull { it.kind == d.kind }
        when {
            d.kmLeft != null && interval?.everyKm != null -> 1f - (d.kmLeft.toFloat() / interval.everyKm)
            d.daysLeft != null && interval?.everyMonths != null -> 1f - (d.daysLeft.toFloat() / (interval.everyMonths * 30f))
            else -> null
        }?.coerceIn(0f, 1f)
    }
    return WidgetFace(
        icon = kindIcon(BuiltinKind.SERVICE),
        title = BuiltinKind.SERVICE.label,
        value = odo?.let { formatKm(it.nowKm) } ?: "--", unit = "km",
        caption = caption,
        fraction = fraction,
        alert = first?.stage == UpkeepStage.DUE || first?.stage == UpkeepStage.SOON,
        rows = dues.map { FaceRow(stringResource(it.kind.labelRes), upkeepLeft(it), alert = it.stage == UpkeepStage.DUE) },
        onClick = { editing = true }
    )
}

@Composable
private fun fuelPricesFace(): WidgetFace {
    val context = LocalContext.current
    val icon = kindIcon(BuiltinKind.FUEL_PRICES)
    val title = BuiltinKind.FUEL_PRICES.label
    val perm = rememberPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    if (!perm.granted) {
        return idleFace(icon, title, stringResource(R.string.fuel_allow_location), "€/L",
            FaceAction(Icons.Filled.LockOpen, stringResource(R.string.fuel_allow_location), primary = true, onClick = perm.request))
    }
    val error by FuelPriceRepo.error.collectAsState()
    val location by LocationFeed.location.collectAsState()
    val nearby = rememberFuelNearby()
        ?: return idleFace(icon, title, stringResource(
            when {
                error != null -> R.string.fuel_error
                location == null -> R.string.info_waiting_gps
                else -> R.string.fuel_loading
            }
        ), "€/L")
    if (nearby.ranked.isEmpty()) return idleFace(icon, title, stringResource(R.string.fuel_none, FuelPrices.RADIUS_KM), "€/L")
    val best = nearby.ranked.first()
    return WidgetFace(
        icon = icon, title = title,
        value = FuelPrices.formatPrice(best.price), unit = "€/L",
        caption = stringResource(R.string.fuel_cheapest, nearby.grade.label) + " · " + best.station.name.ifBlank { best.station.town } +
            " · " + FuelPrices.formatDistance(best.distanceKm),
        stats = listOf(
            FaceStat(nearby.grade.label, FuelPrices.formatPriceWithCurrency(best.price)),
            FaceStat(stringResource(R.string.fuel_navigate), FuelPrices.formatDistance(best.distanceKm))
        ),
        rows = nearby.ranked.take(6).map { r ->
            FaceRow(
                FuelPrices.formatPriceWithCurrency(r.price) + " · " + listOf(r.station.name, r.station.town).filter { it.isNotBlank() }.joinToString(", "),
                FuelPrices.formatDistance(r.distanceKm),
                onClick = { navigateTo(context, r.station.lat, r.station.lng, r.station.label) }
            )
        },
        onClick = { navigateTo(context, best.station.lat, best.station.lng, best.station.label) }
    )
}

@Composable
private fun carStatusFace(): WidgetFace {
    val body by CarBox.body.collectAsState()
    val b = body ?: return idleFace(Icons.Filled.DirectionsCarFilled, BuiltinKind.CAR_STATUS.label, stringResource(R.string.car_status_waiting))
    val rows = carStatusRows(b)
    val lights = lightsOn(b)
    return WidgetFace(
        icon = Icons.Filled.DirectionsCarFilled,
        title = BuiltinKind.CAR_STATUS.label,
        value = b.odometer?.let { NumberFormat.getIntegerInstance().format(it.toLong()) } ?: "--",
        unit = stringResource(R.string.car_status_km),
        caption = rows.first().second,
        alert = CarLight.HAZARD in lights,
        severity = if (CarLight.HAZARD in lights) 1 else 0,
        rows = rows.map { (label, value) -> FaceRow(label, value) }
    )
}

@Composable
private fun tyresFace(): WidgetFace {
    val tyres by Tyres.tyres.collectAsState()
    val unit by Tyres.unit.collectAsState()
    if (tyres.isEmpty()) return idleFace(Icons.Filled.TireRepair, BuiltinKind.TYRES.label, stringResource(R.string.car_tyres_waiting))
    val order = listOf(TyrePos.FRONT_LEFT, TyrePos.FRONT_RIGHT, TyrePos.REAR_LEFT, TyrePos.REAR_RIGHT)
    val problems = tyres.mapNotNull { (pos, t) -> tyreProblem(t)?.let { pos to it } }
    val lowest = tyres.filterKeys { it in order }.values.minByOrNull { it.kPa }
    val wheels = order.map { pos ->
        tyres[pos]?.let { t ->
            val problem = tyreProblem(t)
            val value = listOfNotNull(problem?.let { stringResource(it.labelRes) }, formatPressure(t.kPa, unit)).joinToString(" · ")
            FaceRow(stringResource(pos.labelRes), value, alert = problem != null)
        }
    }
    val rows = wheels.filterNotNull()
    val shown = lowest?.let { formatPressure(it.kPa, unit) }
    return WidgetFace(
        icon = Icons.Filled.TireRepair,
        title = BuiltinKind.TYRES.label,
        value = shown?.substringBefore(' ') ?: "--",
        unit = shown?.substringAfter(' ').orEmpty(),
        caption = problems.firstOrNull()?.let { (pos, p) -> "${stringResource(pos.labelRes)}: ${stringResource(p.labelRes)}" }
            ?: stringResource(R.string.car_tyres_ok),
        alert = problems.isNotEmpty(),
        severity = if (problems.isNotEmpty()) 2 else 0,
        rows = rows,
        wheels = wheels
    )
}
