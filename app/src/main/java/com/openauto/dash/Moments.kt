package com.openauto.dash

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import com.openauto.dash.vehicle.ignitionFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/*
 * Moments: Home follows the drive. Each moment of a drive (the engine just
 * started, a route being guided, an hour at the wheel, parked, a new fault)
 * has its own Home, shown in Home's place while that moment lasts; the
 * dashboards beside Home stay as they are. Nothing is tapped: the moments
 * come and go with the car, never while the driver is touching the screen,
 * and the Home key brings the driver's own Home back.
 */

/**
 * One moment of a drive and the Home it shows: [page] is laid out for the
 * screen by [TemplatePlacer], as a template's page is, until the driver
 * gives it a dashboard of their own.
 */
internal enum class Moment(
    @StringRes val titleRes: Int,
    @StringRes val showsRes: Int,
    @StringRes val startsRes: Int,
    @StringRes val endsRes: Int,
    val icon: ImageVector,
    val defaultOn: Boolean,
    val page: TemplatePage
) {
    ENGINE_ON(
        R.string.moment_engine_on, R.string.moment_engine_on_shows, R.string.moment_engine_on_starts, R.string.moment_engine_on_ends,
        Icons.Filled.DirectionsCar, true,
        TemplatePage(listOf(BuiltinKind.WEATHER, BuiltinKind.CALENDAR, BuiltinKind.RANGE, BuiltinKind.WARMUP, BuiltinKind.HOME_WORK), dock = true)
    ),
    GUIDING(
        R.string.moment_guiding, R.string.moment_guiding_shows, R.string.moment_guiding_starts, R.string.moment_guiding_ends,
        Icons.Filled.Navigation, true,
        TemplatePage(listOf(BuiltinKind.NAVMAP, BuiltinKind.NAVIGATION, BuiltinKind.MEDIA), dock = true)
    ),
    LONG_DRIVE(
        R.string.moment_long, R.string.moment_long_shows, R.string.moment_long_starts, R.string.moment_long_ends,
        Icons.Filled.Timer, false,
        TemplatePage(listOf(BuiltinKind.NAVMAP, BuiltinKind.RANGE, BuiltinKind.BREAK_TIMER, BuiltinKind.FUEL_PRICES), dock = true)
    ),
    PARKED(
        R.string.moment_parked, R.string.moment_parked_shows, R.string.moment_parked_starts, R.string.moment_parked_ends,
        Icons.Filled.LocalParking, true,
        TemplatePage(listOf(BuiltinKind.OBD_DTC, BuiltinKind.BATTERY, BuiltinKind.SERVICE, BuiltinKind.NOTIFICATIONS, BuiltinKind.TRIP), dock = true)
    ),
    FAULT(
        R.string.moment_fault, R.string.moment_fault_shows, R.string.moment_fault_starts, R.string.moment_fault_ends,
        Icons.Filled.Warning, true,
        TemplatePage(listOf(BuiltinKind.OBD_DTC, BuiltinKind.TELEMETRY, BuiltinKind.SERVICE), dock = true)
    );

    companion object {
        /** The moments of a drive in their order, as the Moments screen draws them; [FAULT] can come at any time. */
        val DRIVE = listOf(ENGINE_ON, GUIDING, LONG_DRIVE, PARKED)

        /** Which one shows when several are on at once: a fault first, then the route, then the rest. */
        val PRIORITY = listOf(FAULT, GUIDING, LONG_DRIVE, PARKED, ENGINE_ON)

        /** The moment Home shows among those [active] now, of those the driver left [on]; null for their own Home. */
        fun pick(active: Set<Moment>, on: Set<Moment>): Moment? = PRIORITY.firstOrNull { it in active && it in on }
    }
}

/** An app runs in this tile (in a window, or inside it): it keeps its place, so a moment never takes its page. */
internal fun DashboardItem.holdsAnApp(): Boolean = when (this) {
    is DashboardItem.AppWindow -> true
    is DashboardItem.BuiltinWidget -> kind == BuiltinKind.MAPS_INSIDE || kind == BuiltinKind.PIP_ANCHOR
    else -> false
}

/** An hour at the wheel, short stops included, makes a long drive. */
internal const val LONG_DRIVE_MS = 60 * 60_000L

/** How long the engine-on Home stays when the car does not set off. */
internal const val ENGINE_ON_MAX_MS = 10 * 60_000L

/** A dashboard started this soon after the unit booted counts as the engine starting. */
private const val ENGINE_ON_BOOT_MS = 3 * 60_000L

/** How long a new fault keeps its Home up. */
internal const val FAULT_SHOW_MS = 5 * 60_000L

/** A touch this recent holds a moment back: the screen never changes under a finger. */
internal const val MOMENT_TOUCH_QUIET_MS = 8_000L

/** What the driver set: Moments on at all, each moment on or off, and the Homes they chose. */
internal data class MomentSettings(
    val master: Boolean = false,
    val on: Set<Moment> = Moment.entries.filter { it.defaultOn }.toSet(),
    /** A moment's own dashboard, by [MomentStore.layoutKey]; a moment missing here shows its [Moment.page]. */
    val layouts: Map<String, List<DashboardItem>> = emptyMap()
) {
    /** The moments that can show: none while Moments is off. */
    val live: Set<Moment> get() = if (master) on else emptySet()
}

internal object MomentStore {

    private const val PREFS = "moments"
    private const val KEY_MASTER = "on"
    private const val KEY_ON = "moments_on"
    private const val KEY_LAYOUT = "layout_"

    private val _settings = MutableStateFlow(MomentSettings())
    val settings: StateFlow<MomentSettings> = _settings.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val on = p.getString(KEY_ON, null)?.split(',')?.mapNotNull { n -> Moment.entries.firstOrNull { it.name == n } }?.toSet()
        val layouts = p.all.mapNotNull { (key, value) ->
            if (!key.startsWith(KEY_LAYOUT) || value !is String) return@mapNotNull null
            val name = key.removePrefix(KEY_LAYOUT)
            DashboardStore.parsePages(value, "moment_$name")?.firstOrNull()?.let { name to it }
        }.toMap()
        _settings.value = MomentSettings(
            master = p.getBoolean(KEY_MASTER, false),
            on = on ?: MomentSettings().on,
            layouts = layouts
        )
        prefs = p
    }

    fun setMaster(on: Boolean) {
        _settings.value = _settings.value.copy(master = on)
        prefs?.edit()?.putBoolean(KEY_MASTER, on)?.apply()
    }

    fun setOn(moment: Moment, on: Boolean) {
        val set = if (on) _settings.value.on + moment else _settings.value.on - moment
        _settings.value = _settings.value.copy(on = set)
        prefs?.edit()?.putString(KEY_ON, set.joinToString(",") { it.name })?.apply()
    }

    /** Where a moment's own dashboard is kept for a screen direction ([variant] is DashboardStore's: "" or "_v"). */
    fun layoutKey(moment: Moment, variant: String): String = moment.name + variant

    /** [moment] shows [items] from now on (a copy of a dashboard); null puts its own [Moment.page] back. */
    fun setLayout(moment: Moment, variant: String, items: List<DashboardItem>?) {
        val key = layoutKey(moment, variant)
        val layouts = if (items == null) _settings.value.layouts - key else _settings.value.layouts + (key to items)
        _settings.value = _settings.value.copy(layouts = layouts)
        prefs?.edit()?.apply {
            if (items == null) remove(KEY_LAYOUT + key) else putString(KEY_LAYOUT + key, DashboardStore.serializePages(listOf(items), "moment_$key"))
        }?.apply()
    }

    /**
     * A dashboard copied for a moment: its own tiles, without the apps in
     * windows and the system widgets, which belong to one place on screen
     * and stay on their dashboard.
     */
    fun copyable(items: List<DashboardItem>): List<DashboardItem> =
        items.filter { it !is DashboardItem.AppWindow && it !is DashboardItem.SystemWidget }

    /** What [moment] shows on [screen]: the driver's copy, else its own page laid out there. */
    fun itemsFor(settings: MomentSettings, moment: Moment, variant: String, screen: TemplateScreen): List<DashboardItem> =
        settings.layouts[layoutKey(moment, variant)] ?: TemplatePlacer.page(moment.page, screen)
}

/**
 * The moments going on now, from the car: the engine just on (until the car
 * sets off, or [ENGINE_ON_MAX_MS]), a route being guided, an hour at the
 * wheel (until parked), parked (after a drive, a stop of [PARKED_HOLD_MS]),
 * and a new fault (for [FAULT_SHOW_MS]). Read only while Moments is on.
 */
@Composable
internal fun rememberActiveMoments(enabled: Boolean): State<Set<Moment>> {
    val active = remember { mutableStateOf(emptySet<Moment>()) }
    if (enabled) MomentFeeds(active) else LaunchedEffect(Unit) { active.value = emptySet() }
    return active
}

/** Keeps [active] up to date from the car while composed. */
@Composable
private fun MomentFeeds(active: androidx.compose.runtime.MutableState<Set<Moment>>) {
    // The unit boots with the key: a start this fresh is the engine starting. Turning
    // Moments on later in a drive is not.
    var engineOnAt by remember {
        mutableLongStateOf(if (SystemClock.elapsedRealtime() < ENGINE_ON_BOOT_MS) System.currentTimeMillis() else 0L)
    }
    var faultAt by remember { mutableLongStateOf(0L) }
    fun set(moment: Moment, on: Boolean) {
        active.value = if (on) active.value + moment else active.value - moment
    }
    UseLocationFeed()
    // Switched on again (the unit wakes with the key): the engine-on Home, once more.
    LaunchedEffect(Unit) {
        ignitionFlow().drop(1).collect { if (it == true) engineOnAt = System.currentTimeMillis() }
    }
    LaunchedEffect(engineOnAt) {
        if (engineOnAt == 0L) return@LaunchedEffect
        set(Moment.ENGINE_ON, true)
        set(Moment.PARKED, false)
        delay(ENGINE_ON_MAX_MS)
        set(Moment.ENGINE_ON, false)
    }
    LaunchedEffect(Unit) {
        var driveFrom = 0L
        carSpeedKmh().setOffOrStopped().collectLatest { moving ->
            if (moving) {
                set(Moment.ENGINE_ON, false)
                set(Moment.PARKED, false)
                if (driveFrom == 0L) driveFrom = System.currentTimeMillis()
                // Short stops keep the drive going: the hour counts from the first start.
                delay((driveFrom + LONG_DRIVE_MS - System.currentTimeMillis()).coerceAtLeast(0L))
                set(Moment.LONG_DRIVE, true)
            } else if (driveFrom != 0L) {
                // A stop after a drive must last; standing still at the start is no stop at all.
                delay(PARKED_HOLD_MS)
                driveFrom = 0L
                set(Moment.LONG_DRIVE, false)
                set(Moment.PARKED, true)
            }
        }
    }
    LaunchedEffect(Unit) {
        NavDirections.state.map { it.active }.distinctUntilChanged().collect { set(Moment.GUIDING, it) }
    }
    LaunchedEffect(Unit) {
        AiMechanic.newFaultAt.drop(1).collect { if (it > 0L) faultAt = it }
    }
    LaunchedEffect(faultAt) {
        if (faultAt == 0L) return@LaunchedEffect
        set(Moment.FAULT, true)
        delay(FAULT_SHOW_MS)
        set(Moment.FAULT, false)
    }
}
