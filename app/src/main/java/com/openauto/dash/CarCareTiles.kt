package com.openauto.dash

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Car-care tiles, tailored by the car profile: particle filter, warm-up,
 * battery, eco driving, fuel to destination, break reminder and the spec sheet.
 * The spoken side lives in CarCare; these only show the same state.
 */

/** How a reading stands: drives the status line's colour. */
private enum class Tone { GOOD, INFO, CAUTION, BAD }

private val Tone.color: Color
    get() = when (this) {
        Tone.GOOD -> DashColors.Good
        Tone.INFO -> DashColors.Accent
        Tone.CAUTION -> DashColors.Warning
        Tone.BAD -> DashColors.Critical
    }

private val CareCall.tone: Tone
    get() = when {
        neutral -> Tone.INFO
        level >= 2 -> Tone.BAD
        level == 1 -> Tone.CAUTION
        else -> Tone.GOOD
    }

// --- What each reading comes to (shared with the designed faces) ------------------

/**
 * What a car-care reading comes to: its status line and how urgent it is
 * (0 fine, 1 needs attention, 2 act now). [neutral] marks an in-between state
 * (warming up, too early to score) the cards show in the accent colour.
 * The cards here and the designed faces (WidgetFaceData.kt) both read these,
 * so the two can never disagree.
 */
internal class CareCall(@StringRes val text: Int, val level: Int, val neutral: Boolean = false)

/** [imperial]: the drive it asks for is above 40 mph rather than 60 km/h. */
internal fun filterCall(shortStreak: Int, imperial: Boolean = false): CareCall = when {
    shortStreak >= 6 -> CareCall(R.string.car_filter_needs_drive_now, 2)
    shortStreak >= CareRules.FILTER_WARN_STREAK ->
        CareCall(if (imperial) R.string.units_car_filter_needs_drive_mph else R.string.car_filter_needs_drive, 1)
    else -> CareCall(R.string.car_filter_ok, 0)
}

/** The cold line takes the car's cold rev limit as its argument (the others take none). */
internal fun warmupCall(coolantC: Int, car: CarProfile): CareCall = when {
    coolantC < car.coldC -> CareCall(R.string.car_warmup_cold, 1)
    coolantC < car.hotC - 10 -> CareCall(R.string.car_warmup_warming, 0, neutral = true)
    else -> CareCall(R.string.car_warmup_warm, 0)
}

/** Charging while the engine runs, charge left while it's off. */
internal fun batteryCall(volts: Double, running: Boolean): CareCall = if (running) when {
    volts >= LiveWatch.CHARGE_CLEAR_V -> CareCall(R.string.car_battery_charging, 0)
    volts >= LiveWatch.NOT_CHARGING_V -> CareCall(R.string.car_battery_charging_low, 1)
    else -> CareCall(R.string.car_battery_not_charging, 2)
} else when {
    volts >= 12.6 -> CareCall(R.string.car_battery_full, 0)
    volts >= LiveWatch.BATTERY_CLEAR_V -> CareCall(R.string.car_battery_good, 0)
    volts >= LiveWatch.WEAK_BATTERY_V -> CareCall(R.string.car_battery_low, 1)
    else -> CareCall(R.string.car_battery_weak, 2)
}

/**
 * The battery-care meter: 11.5 V (flat) to 14.8 V (charging hard). Tighter
 * than the OBD tile's plain voltage bar ([batteryFraction], 11 to 15 V) on
 * purpose, so resting and charging levels spread across the whole meter.
 */
internal fun batteryCareFraction(volts: Double): Float = ((volts - 11.5) / (14.8 - 11.5)).toFloat()

internal fun ecoCall(score: Int?): CareCall = when {
    score == null -> CareCall(R.string.car_eco_too_early, 0, neutral = true)
    score >= 80 -> CareCall(R.string.car_eco_smooth, 0)
    score >= 60 -> CareCall(R.string.car_eco_fair, 1)
    else -> CareCall(R.string.car_eco_harsh, 2)
}

internal fun fuelCall(verdict: FuelVerdict): CareCall = when (verdict) {
    FuelVerdict.ENOUGH -> CareCall(R.string.car_fuel_dest_enough, 0)
    FuelVerdict.TIGHT -> CareCall(R.string.car_fuel_dest_tight, 1)
    FuelVerdict.SHORT -> CareCall(R.string.car_fuel_dest_short, 2)
}

/** The car's range against the distance left to drive; [verdict] is null without a route. */
internal class FuelToDest(val rangeKm: Int, val toGoKm: Double?) {
    val verdict: FuelVerdict? = CareRules.fuelVerdict(rangeKm, toGoKm)
    val km: Double get() = toGoKm ?: 0.0
    val spareKm: Int get() = (rangeKm - km).toInt()
    /** How much of the range the trip uses; 0 while the range reads 0, rather than 0 / 0. */
    val usedFraction: Float get() = if (rangeKm > 0) (km / rangeKm).toFloat().coerceIn(0f, 1f) else 0f
}

/** Range and route for the fuel-to-destination card and face; null until a range is known. */
@Composable
internal fun rememberFuelToDest(): FuelToDest? {
    val nav by NavDirections.state.collectAsState()
    val canFuel by McuReader.fuelPercent.collectAsState()
    val canRange by McuReader.rangeKm.collectAsState()
    // Only the fuel level counts here, not every OBD sample.
    val obd = ObdBluetoothManager.data.collectAsState()
    val obdFuel by remember { derivedStateOf { obd.value.fuelLevelPct } }
    val car by CarProfileStore.profile.collectAsState()
    val range = remember(canFuel, canRange, obdFuel, car) { carFuelInfo(canFuel, obdFuel, canRange)?.rangeKm } ?: return null
    return FuelToDest(range, if (nav.active) CareRules.remainingKm(nav.eta) else null)
}

// --- Card pieces ----------------------------------------------------------------

@Composable
private fun CareCard(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TileHeader(title)
            content()
        }
    }
}

/** A big reading with its unit, e.g. "54" "°C". */
@Composable
private fun Reading(value: String, unit: String = "", dimmed: Boolean = false) {
    Row(verticalAlignment = Alignment.Bottom) {
        HeroNumber(text = value, size = 40, dimmed = dimmed)
        if (unit.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(unit, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em,
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Status(text: String, tone: Tone) {
    Text(text, color = tone.color, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, color = DashColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
}

/** "Running for 12m 30s": ticks each second on its own, so the rest of its card stays put. */
@Composable
private fun RunningFor(startedAt: Long) {
    val now by rememberWallClock(1_000L)
    Hint(stringResource(R.string.car_running_for, formatDuration(now - startedAt)))
}

@Composable
private fun Meter(fraction: Float, color: Color) {
    val shape = RoundedCornerShape(3.dp)
    Row(modifier = Modifier.fillMaxWidth().height(6.dp).clip(shape).background(DashColors.CardHi)) {
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0f) Spacer(Modifier.weight(f).fillMaxHeight().background(color, shape))
        if (f < 1f) Spacer(Modifier.weight(1f - f))
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = DashColors.TextPrimary) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(8.dp))
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium)
    }
}

private fun decimal(v: Double, digits: Int = 1): String = String.format(Locale.getDefault(), "%.${digits}f", v)

// --- Particle filter ------------------------------------------------------------

@Composable
internal fun FilterCareCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    CareCard(stringResource(R.string.car_filter_title), modifier) {
        if (!car.particleFilter) {
            Hint(stringResource(R.string.car_filter_none))
            return@CareCard
        }
        val streak = care.filter.shortStreak
        val call = filterCall(streak, LocalUnits.current.imperial)
        Reading(streak.toString(), stringResource(R.string.car_filter_short_unit))
        Status(stringResource(call.text), call.tone)
        // The real soot load, when the experimental reading finder got the car to give it up.
        val extra by PidExplorer.readings.collectAsState()
        val watch by CarCare.filterWatch.collectAsState()
        // A regeneration the car reports, while the reading is still there (it goes when the adapter drops).
        if (watch.regenActive && extra[ExtraReading.REGEN_ACTIVE] != null) Status(stringResource(R.string.car_filter_regen), Tone.INFO)
        extra[ExtraReading.SOOT_LOAD]?.let { soot ->
            Meter((soot.value / 100).toFloat(), if (soot.value >= FilterRules.SOOT_HIGH_PCT) DashColors.Warning else DashColors.Accent)
            Hint(stringResource(R.string.explore_soot_status, soot.value.roundToInt()))
        }
        care.drive?.let { d ->
            val minutes = (d.hotFastMs / 60_000).toInt()
            Meter(d.hotFastMs.toFloat() / CareRules.LONG_DRIVE_MS, if (d.filterFriendly) DashColors.Good else DashColors.Accent)
            Hint(stringResource(R.string.car_filter_this_drive, minutes, (CareRules.LONG_DRIVE_MS / 60_000).toInt()))
        }
        val last = care.filter.lastLongAt
        if (last > 0) {
            // Minutes are the finest this line shows, so a tick each minute will do.
            val now by rememberWallClock(60_000L)
            Hint(stringResource(R.string.car_filter_last_long, DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS).toString()))
        } else {
            Hint(stringResource(R.string.car_filter_last_long_never))
        }
        val additive = extra[ExtraReading.ADDITIVE_LEVEL]?.value
        if (additive != null && additive < FilterRules.ADDITIVE_LOW_PCT) {
            Status(stringResource(R.string.car_filter_additive_low, additive.roundToInt()), Tone.CAUTION)
        } else if (car.filterAdditive) Hint(stringResource(R.string.car_filter_additive))
    }
}

// --- Engine warm-up -------------------------------------------------------------

@Composable
internal fun WarmupCard(obd: ObdData, connected: Boolean, modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    CareCard(stringResource(R.string.car_warmup_title), modifier) {
        val t = obd.coolantTempC
        val u = LocalUnits.current
        if (!connected || t == 0) {
            Reading("--", u.tempUnit, dimmed = true)
            Hint(stringResource(R.string.car_waiting_obd))
            return@CareCard
        }
        Reading(u.temp(t).toString(), u.tempUnit)
        val call = warmupCall(t, car)
        Meter(t.toFloat() / car.hotC, call.tone.color)
        Status(stringResource(call.text, car.coldRpmLimit), call.tone)
        care.drive?.let { RunningFor(it.startedAt) }
    }
}

// --- Battery --------------------------------------------------------------------

@Composable
internal fun BatteryCard(obd: ObdData, connected: Boolean, modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val watch by BatteryWatch.state.collectAsState()
    CareCard(stringResource(R.string.car_battery_title), modifier) {
        val v = obd.voltage
        if (!connected || v < LiveWatch.MIN_PLAUSIBLE_V || v > LiveWatch.MAX_PLAUSIBLE_V) {
            Reading("--", "V", dimmed = true)
            Hint(stringResource(R.string.car_waiting_obd))
            return@CareCard
        }
        Reading(decimal(v), "V")
        val running = obd.rpm > LiveWatch.RUNNING_RPM
        val call = batteryCall(v, running)
        Meter(batteryCareFraction(v), call.tone.color)
        Status(stringResource(call.text), call.tone)
        // The trip's range, from the engine computer's readings once running: a
        // spike or dip too short to alert still shows here.
        val lo = watch.tripMin
        val hi = watch.tripMax
        if (lo != null && hi != null) Hint(stringResource(R.string.car_battery_trip_range, decimal(lo), decimal(hi)))
        Hint(
            car.batteryAh?.let { stringResource(R.string.car_battery_capacity, it) }
                ?: stringResource(if (running) R.string.car_battery_hint_running else R.string.car_battery_hint_off)
        )
    }
}

// --- Eco driving ----------------------------------------------------------------

@Composable
internal fun EcoDriveCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    val care by CarCare.state.collectAsState()
    val drive = care.drive ?: care.lastDrive
    CareCard(stringResource(if (care.drive != null || drive == null) R.string.car_eco_title else R.string.car_eco_title_last), modifier) {
        if (drive == null) {
            Reading("--", "/ 100", dimmed = true)
            Hint(stringResource(R.string.car_eco_empty))
            return@CareCard
        }
        val score = drive.ecoScore
        Row(verticalAlignment = Alignment.CenterVertically) {
            Reading(score?.toString() ?: "--", "/ 100", dimmed = score == null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val call = ecoCall(score)
                Status(stringResource(call.text), call.tone)
            }
        }
        drive.sweetPercent?.let {
            InfoRow(stringResource(R.string.car_eco_band, car.sweetBand.first, car.sweetBand.last), "$it %")
        }
        InfoRow(stringResource(R.string.car_eco_hard), stringResource(R.string.car_eco_hard_value, drive.hardAccel, drive.hardBrake))
        if (car.gearbox == GearboxType.ROBOTISED) {
            InfoRow(
                stringResource(R.string.car_eco_clutch), drive.clutchHolds.toString(),
                if (drive.clutchHolds > 0) DashColors.Warning else DashColors.TextPrimary
            )
        }
        // What the drive burned at the car's usual consumption, and what it cost.
        val liters = drive.distanceKm * car.typicalUse / 100
        val u = LocalUnits.current
        InfoRow(
            stringResource(if (u.imperial) R.string.units_eco_fuel_mi else R.string.car_eco_fuel, decimal(u.distance(drive.distanceKm))),
            stringResource(R.string.car_eco_fuel_value, decimal(liters), decimal(liters * car.fuelPrice, 2), car.currency)
        )
    }
}

// --- Fuel to destination --------------------------------------------------------

@Composable
internal fun FuelToDestCard(modifier: Modifier = Modifier) {
    val trip = rememberFuelToDest()
    val u = LocalUnits.current
    CareCard(stringResource(R.string.car_fuel_dest_title), modifier) {
        if (trip == null) {
            Reading("--", u.distanceUnit, dimmed = true)
            Hint(stringResource(R.string.car_fuel_dest_no_range))
            return@CareCard
        }
        val verdict = trip.verdict
        if (verdict == null) {
            Reading(u.distance(trip.rangeKm).toString(), u.distanceUnit)
            Hint(stringResource(R.string.car_fuel_dest_no_nav))
            return@CareCard
        }
        val call = fuelCall(verdict)
        Reading(
            u.distance(trip.spareKm).toString(),
            stringResource(if (u.imperial) R.string.units_fuel_dest_spare_unit_mi else R.string.car_fuel_dest_spare_unit)
        )
        Meter(trip.usedFraction, call.tone.color)
        Status(stringResource(call.text), call.tone)
        Hint(
            stringResource(
                if (u.imperial) R.string.units_fuel_dest_detail_mi else R.string.car_fuel_dest_detail,
                u.distance(trip.rangeKm), u.distance(trip.km).toInt()
            )
        )
    }
}

// --- Break reminder -------------------------------------------------------------

@Composable
internal fun BreakCard(modifier: Modifier = Modifier) {
    val care by CarCare.state.collectAsState()
    val due = CareRules.FIRST_BREAK_MIN * 60_000L
    val driving = care.rest.drivingMs
    CareCard(stringResource(R.string.car_break_title), modifier) {
        Reading(formatDuration(driving))
        val tone = when {
            driving >= due -> Tone.BAD
            driving >= due - 20 * 60_000L -> Tone.CAUTION
            else -> Tone.GOOD
        }
        Meter(driving.toFloat() / due, tone.color)
        Status(stringResource(if (driving >= due) R.string.car_break_due else R.string.car_break_ok), tone)
        Hint(stringResource(R.string.car_break_hint))
    }
}

// --- My car ---------------------------------------------------------------------

@Composable
internal fun MyCarCard(modifier: Modifier = Modifier) {
    val car by CarProfileStore.profile.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.car_unknown)
    val lock = LocalDriveLock.current
    val mine by MyCarLook.shown.collectAsState()
    val style by MyCarLook.style.collectAsState()
    val picture = mine?.portrait
    if (picture != null) {
        MyCarPicture(car, picture, style, modifier) { lock.whenParked { editing = true } }
        if (editing) CarSettingsDialog(onDismiss = { editing = false })
        return
    }
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().clickable { lock.whenParked { editing = true } }.padding(DashSpace.Lg),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TileHeader(stringResource(R.string.car_my_car_title)) {
                Text(stringResource(car.source.labelRes), color = DashColors.Muted, style = MaterialTheme.typography.labelSmall)
            }
            Text(car.name, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
            InfoRow(stringResource(R.string.car_spec_engine), car.engine.ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_gearbox), gearboxText(car).ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_oil), listOfNotNull(car.oilCapacityL?.let { decimal(it, 2) + " L" }, car.oilSpec.ifBlank { null }).joinToString(" · ").ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_tyres), tyreText(car).ifBlank { unknown })
            InfoRow(stringResource(R.string.car_spec_service), serviceText(car).ifBlank { unknown })
            if (car.source == SpecSource.PRESET) Hint(stringResource(R.string.car_my_car_fetch_hint))
        }
    }
    if (editing) CarSettingsDialog(onDismiss = { editing = false })
}

/** The My car tile with the driver's own car (MyCarLook.kt): its name and engine over the picture, which takes the rest. */
@Composable
private fun MyCarPicture(car: CarProfile, picture: CarView, style: CarLookStyle, modifier: Modifier, onOpen: () -> Unit) {
    val ink = DashColors.Accent
    val warn = DashColors.Warning
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().clickable(onClick = onOpen).padding(DashSpace.Lg)) {
            TileHeader(stringResource(R.string.car_my_car_title))
            Text(car.name, color = DashColors.TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge)
            car.engine.takeIf { it.isNotBlank() }?.let {
                Text(it, color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
            Canvas(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp)) {
                drawCarView(picture, style, fitCar(picture, Rect(Offset.Zero, size)), ink, warn)
            }
        }
    }
}

internal val SpecSource.labelRes: Int
    get() = when (this) {
        SpecSource.PRESET -> R.string.car_source_preset
        SpecSource.AI -> R.string.car_source_ai
        SpecSource.USER -> R.string.car_source_user
    }

@Composable
internal fun gearboxText(car: CarProfile): String = listOfNotNull(
    car.gearboxName.ifBlank { null },
    car.gears?.let { stringResource(R.string.car_gears, it) },
    stringResource(car.gearbox.labelRes)
).joinToString(" · ")

internal val GearboxType.labelRes: Int
    get() = when (this) {
        GearboxType.MANUAL -> R.string.car_gearbox_manual
        GearboxType.ROBOTISED -> R.string.car_gearbox_robotised
        GearboxType.AUTOMATIC -> R.string.car_gearbox_automatic
        GearboxType.DUAL_CLUTCH -> R.string.car_gearbox_dual_clutch
        GearboxType.CVT -> R.string.car_gearbox_cvt
    }

internal val FuelType.labelRes: Int
    get() = when (this) {
        FuelType.DIESEL -> R.string.car_fuel_diesel
        FuelType.PETROL -> R.string.car_fuel_petrol
        FuelType.HYBRID -> R.string.car_fuel_hybrid
        FuelType.LPG -> R.string.car_fuel_lpg
    }

private fun tyreText(car: CarProfile): String {
    val pressure = if (car.tyreFrontBar != null || car.tyreRearBar != null) {
        "${car.tyreFrontBar?.let { decimal(it) } ?: "?"} / ${car.tyreRearBar?.let { decimal(it) } ?: "?"} bar"
    } else null
    return listOfNotNull(car.tyreSize.ifBlank { null }, pressure).joinToString(" · ")
}

@Composable
private fun serviceText(car: CarProfile): String = listOfNotNull(
    car.serviceKm?.let { LocalUnits.current.let { u -> String.format(Locale.getDefault(), "%,d %s", u.distance(it), u.distanceUnit) } },
    car.serviceMonths?.let { stringResource(R.string.car_months, it) }
).joinToString(" / ")

/**
 * The car's own state from its CAN box ([CarBox]): the lights, the mileage,
 * the trip and the consumption, as the car's trip computer counts them. A
 * value the car doesn't send is left out.
 */
@Composable
internal fun CarStatusCard(modifier: Modifier = Modifier) {
    val body by CarBox.body.collectAsState()
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(DashSpace.Lg), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            TileHeader(stringResource(R.string.car_status_title))
            val b = body
            if (b == null) {
                Hint(stringResource(R.string.car_status_waiting))
            } else {
                carStatusRows(b).forEach { (label, value) -> InfoRow(label, value) }
            }
        }
    }
}

/** The Car status rows: label and value, only those the car reports. */
@Composable
internal fun carStatusRows(b: CarBody): List<Pair<String, String>> {
    val lights = lightsOn(b).map { stringResource(it.labelRes) }
    val u = LocalUnits.current
    val km = if (u.imperial) u.distanceUnit else stringResource(R.string.car_status_km)
    return listOfNotNull(
        stringResource(R.string.car_status_lights) to (lights.joinToString(", ").ifEmpty { stringResource(R.string.car_status_lights_off) }),
        b.odometer?.let { stringResource(R.string.car_status_odometer) to "${NumberFormat.getIntegerInstance().format(u.distance(it.toDouble()).toLong())} $km" },
        b.trip1?.let { stringResource(R.string.car_status_trip) to "${decimal(u.distance(it.toDouble()), 1)} $km" },
        b.instantConsumption?.let {
            stringResource(R.string.car_status_consumption) to
                if (u.economy == Economy.L_PER_100KM) "${decimal(it.toDouble(), 1)} L/100 ${stringResource(R.string.car_status_km)}"
                else "${u.economy(it.toDouble())?.let { e -> decimal(e, 1) } ?: "--"} ${u.economyUnit}"
        },
        b.range?.let { stringResource(R.string.car_status_range) to "${u.distance(it.toDouble()).toInt()} $km" },
        stringResource(R.string.car_status_parking_brake) to stringResource(if (b.handbrake) R.string.car_status_on else R.string.car_status_off)
    )
}

internal val CarLight.labelRes: Int
    get() = when (this) {
        CarLight.HAZARD -> R.string.car_status_hazard
        CarLight.MAIN_BEAM -> R.string.car_status_main_beam
        CarLight.DIPPED -> R.string.car_status_dipped
        CarLight.SIDELIGHTS -> R.string.car_status_sidelights
        CarLight.FRONT_FOG -> R.string.car_status_front_fog
        CarLight.REAR_FOG -> R.string.car_status_rear_fog
    }
