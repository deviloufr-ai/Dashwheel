package com.openauto.dash

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** Sections of the "Add widget" catalogue; [titleRes] is the section heading. */
enum class WidgetCategory(@StringRes val titleRes: Int) {
    DRIVING(R.string.apps_category_driving),
    NAVIGATION(R.string.apps_category_navigation),
    VEHICLE(R.string.apps_category_vehicle),
    INFO(R.string.apps_category_info),
    APPS(R.string.apps_category_apps)
}

/**
 * The kinds of built-in (app-provided) widgets a dashboard tile can show. These
 * are rendered by our own Compose panels, not by the Android app-widget host.
 * [defaultW] x [defaultH] is the span a fresh tile gets. Names are persisted,
 * so never rename an entry. [labelRes] / [blurbRes] are the picker's display
 * name and one-line description (read them in Compose as `label` / `blurb`).
 */
enum class BuiltinKind(
    @StringRes val labelRes: Int,
    val category: WidgetCategory,
    @StringRes val blurbRes: Int,
    val defaultW: Int = 5,
    val defaultH: Int = 3
) {
    NAVMAP(R.string.apps_kind_navmap, WidgetCategory.NAVIGATION, R.string.apps_kind_navmap_blurb),
    NAVIGATION(R.string.apps_kind_navigation, WidgetCategory.NAVIGATION, R.string.apps_kind_navigation_blurb, 4, 3),
    PIP_ANCHOR(R.string.apps_kind_pip_anchor, WidgetCategory.NAVIGATION, R.string.apps_kind_pip_anchor_blurb, 4, 3),
    // Google Maps itself inside the tile, where the firmware grants system permissions (EmbeddedApp.kt).
    MAPS_INSIDE(R.string.apps_kind_maps_inside, WidgetCategory.NAVIGATION, R.string.apps_kind_maps_inside_blurb),
    MEDIA(R.string.apps_kind_media, WidgetCategory.APPS, R.string.apps_kind_media_blurb),
    TELEMETRY(R.string.apps_kind_telemetry, WidgetCategory.VEHICLE, R.string.apps_kind_telemetry_blurb),
    OBD_DTC(R.string.apps_kind_obd_dtc, WidgetCategory.VEHICLE, R.string.apps_kind_obd_dtc_blurb, 3, 2),
    OBD_ALL(R.string.apps_kind_obd_all, WidgetCategory.VEHICLE, R.string.apps_kind_obd_all_blurb),
    RANGE(R.string.apps_kind_range, WidgetCategory.VEHICLE, R.string.apps_kind_range_blurb, 3, 3),
    DOORS(R.string.apps_kind_doors, WidgetCategory.VEHICLE, R.string.apps_kind_doors_blurb, 3, 2),
    CAN_MON(R.string.apps_kind_can_mon, WidgetCategory.VEHICLE, R.string.apps_kind_can_mon_blurb, 4, 3),
    SPEED_HUD(R.string.apps_kind_speed_hud, WidgetCategory.DRIVING, R.string.apps_kind_speed_hud_blurb, 3, 2),
    COMPASS(R.string.apps_kind_compass, WidgetCategory.DRIVING, R.string.apps_kind_compass_blurb, 3, 3),
    TRIP(R.string.apps_kind_trip, WidgetCategory.DRIVING, R.string.apps_kind_trip_blurb, 4, 2),
    GFORCE(R.string.apps_kind_gforce, WidgetCategory.DRIVING, R.string.apps_kind_gforce_blurb, 4, 2),
    PARKING(R.string.apps_kind_parking, WidgetCategory.NAVIGATION, R.string.apps_kind_parking_blurb, 4, 2),
    CLOCK(R.string.apps_kind_clock, WidgetCategory.INFO, R.string.apps_kind_clock_blurb, 3, 2),
    WEATHER(R.string.apps_kind_weather, WidgetCategory.INFO, R.string.apps_kind_weather_blurb, 4, 2),
    CALENDAR(R.string.apps_kind_calendar, WidgetCategory.INFO, R.string.apps_kind_calendar_blurb, 4, 3),
    QUICK_DIAL(R.string.apps_kind_quick_dial, WidgetCategory.INFO, R.string.apps_kind_quick_dial_blurb, 4, 2),
    NOTIFICATIONS(R.string.apps_kind_notifications, WidgetCategory.INFO, R.string.apps_kind_notifications_blurb, 4, 3),
    AUDIO(R.string.apps_kind_audio, WidgetCategory.APPS, R.string.apps_kind_audio_blurb, 3, 2),
    // Car care, tailored by the car profile (CarCareTiles.kt).
    FILTER_CARE(R.string.car_kind_filter, WidgetCategory.VEHICLE, R.string.car_kind_filter_blurb, 4, 3),
    WARMUP(R.string.car_kind_warmup, WidgetCategory.VEHICLE, R.string.car_kind_warmup_blurb, 3, 3),
    BATTERY(R.string.car_kind_battery, WidgetCategory.VEHICLE, R.string.car_kind_battery_blurb, 3, 3),
    MY_CAR(R.string.car_kind_my_car, WidgetCategory.VEHICLE, R.string.car_kind_my_car_blurb, 4, 3),
    ECO_DRIVE(R.string.car_kind_eco, WidgetCategory.DRIVING, R.string.car_kind_eco_blurb, 4, 3),
    BREAK_TIMER(R.string.car_kind_break, WidgetCategory.DRIVING, R.string.car_kind_break_blurb, 3, 3),
    FUEL_TO_DEST(R.string.car_kind_fuel_dest, WidgetCategory.NAVIGATION, R.string.car_kind_fuel_dest_blurb, 3, 3),
    // Servicing planner (Maintenance.kt) and fuel prices around the car (FuelPrices.kt).
    SERVICE(R.string.upkeep_kind, WidgetCategory.VEHICLE, R.string.upkeep_kind_blurb, 4, 3),
    FUEL_PRICES(R.string.fuel_kind, WidgetCategory.NAVIGATION, R.string.fuel_kind_blurb, 4, 3),
    // The car's own data from its CAN box, on the QF firmware (CarBox.kt).
    CAR_STATUS(R.string.car_kind_status, WidgetCategory.VEHICLE, R.string.car_kind_status_blurb, 4, 3),
    // The TPMS sensors, through the head unit's TPMS app (Tyres.kt).
    TYRES(R.string.car_kind_tyres, WidgetCategory.VEHICLE, R.string.car_kind_tyres_blurb, 4, 3),
    // The dashboard bar: dashboards and apps, one tap each, as in the Canvas rail (CanvasTabs.kt).
    DASH_BAR(R.string.apps_kind_dash_bar, WidgetCategory.APPS, R.string.apps_kind_dash_bar_blurb, 1, 5),
    // The head unit's CPU, memory, temperature and busiest apps (HeadUnitMonitor.kt).
    HEAD_UNIT(R.string.monitor_kind, WidgetCategory.INFO, R.string.monitor_kind_blurb, 4, 3),
    // The gear engaged, from reverse and the revs against the speed (GearEstimator.kt).
    GEAR(R.string.gear_kind, WidgetCategory.DRIVING, R.string.gear_kind_blurb, 3, 2)
}

/**
 * One tile on a dashboard page, placed freely on a [GRID_COLS] x [GRID_ROWS]
 * grid. [x],[y] are the top-left cell (0-based) and [w],[h] are the span in
 * cells. Tiles may be moved and resized to any cell rectangle that fits.
 *
 *  - [AppShortcut]   a small icon that launches an installed app,
 *  - [SplitPair]     launches two apps side-by-side in split-screen,
 *  - [LaunchBar]     an editable row of app icons (a dock),
 *  - [BuiltinWidget] one of our own cards (map / media / OBD / directions),
 *  - [SystemWidget]  a real Android app-widget, hosted via [WidgetHostHolder],
 *  - [AppWindow]     any installed app running in a window the size of the tile,
 *                    or inside the tile itself on units that allow it ([AppWindow.inside]).
 *
 * [zoom] scales what the tile draws (text, icons, spacing) inside its cells,
 * like the system's display size but for this tile alone; 1 is as designed.
 */
sealed interface DashboardItem {
    val x: Int
    val y: Int
    val w: Int
    val h: Int
    val zoom: Float

    data class AppShortcut(
        val packageName: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 2, override val h: Int = 2,
        override val zoom: Float = 1f
    ) : DashboardItem

    data class SplitPair(
        val primaryPackage: String,
        val secondaryPackage: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 2, override val h: Int = 2,
        override val zoom: Float = 1f
    ) : DashboardItem

    data class LaunchBar(
        val packages: List<String> = emptyList(),
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 8, override val h: Int = 1,
        override val zoom: Float = 1f
    ) : DashboardItem

    /** [design] is this tile's own look (see WidgetDesigns.kt); two clocks may differ. */
    data class BuiltinWidget(
        val kind: BuiltinKind,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5, override val h: Int = 3,
        val design: WidgetDesign = WidgetDesign.STANDARD,
        override val zoom: Float = 1f
    ) : DashboardItem

    data class SystemWidget(
        val appWidgetId: Int,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5, override val h: Int = 3,
        override val zoom: Float = 1f
    ) : DashboardItem

    /**
     * An app (YouTube Music, Waze, ...) docked as a floating window over this
     * tile, like the Maps window, or with [inside] running inside the tile
     * itself, like Google Maps inside (EmbeddedApp).
     */
    data class AppWindow(
        val packageName: String,
        override val x: Int = 0, override val y: Int = 0,
        override val w: Int = 5, override val h: Int = 3,
        override val zoom: Float = 1f,
        val inside: Boolean = false
    ) : DashboardItem
}

/** The dashboard grid: 12 cells across, 7 down; the other way round on an upright screen ([ScreenShape]). */
val GRID_COLS: Int get() = if (ScreenShape.vertical) 7 else 12
val GRID_ROWS: Int get() = if (ScreenShape.vertical) 12 else 7

/** Compact icon tiles (shortcuts / split pairs) vs. larger widget cards. */
fun DashboardItem.isCompactTile(): Boolean =
    this is DashboardItem.AppShortcut || this is DashboardItem.SplitPair

/**
 * Smallest span a tile may be resized to: a single cell, whatever it is. A
 * small tile's content is made to fit with its [DashboardItem.zoom].
 */
fun DashboardItem.minW(): Int = 1

fun DashboardItem.minH(): Int = 1

/** How far a tile's content can be zoomed out and in, and by how much per tap. */
const val ZOOM_MIN = 0.5f
const val ZOOM_MAX = 2f
const val ZOOM_STEP = 0.1f

/** The zoom one step out ([steps] < 0) or in (> 0) from [zoom], on whole tenths so it never drifts. */
fun zoomStep(zoom: Float, steps: Int): Float {
    val tenths = kotlin.math.round(zoom * 10f).toInt() + steps
    return (tenths / 10f).coerceIn(ZOOM_MIN, ZOOM_MAX)
}

/**
 * Whether zooming changes anything: another app's window or a system widget
 * draws itself. An app inside its tile is drawn at the tile's density, so it does.
 */
fun DashboardItem.canZoom(): Boolean =
    !(this is DashboardItem.AppWindow && !inside) && this !is DashboardItem.SystemWidget

/** Returns a copy whose content is drawn at [zoom] (clamped to [ZOOM_MIN]..[ZOOM_MAX]). */
fun DashboardItem.withZoom(zoom: Float): DashboardItem {
    val z = zoom.coerceIn(ZOOM_MIN, ZOOM_MAX)
    return when (this) {
        is DashboardItem.AppShortcut -> copy(zoom = z)
        is DashboardItem.SplitPair -> copy(zoom = z)
        is DashboardItem.LaunchBar -> copy(zoom = z)
        is DashboardItem.BuiltinWidget -> copy(zoom = z)
        is DashboardItem.SystemWidget -> copy(zoom = z)
        is DashboardItem.AppWindow -> copy(zoom = z)
    }
}

/** Returns a copy placed at cell [x],[y] spanning [w] x [h], clamped to the grid. */
fun DashboardItem.withCell(x: Int, y: Int, w: Int, h: Int): DashboardItem {
    val cw = w.coerceIn(minW(), GRID_COLS)
    val ch = h.coerceIn(minH(), GRID_ROWS)
    val cx = x.coerceIn(0, GRID_COLS - cw)
    val cy = y.coerceIn(0, GRID_ROWS - ch)
    return when (this) {
        is DashboardItem.AppShortcut -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SplitPair -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.LaunchBar -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.BuiltinWidget -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.SystemWidget -> copy(x = cx, y = cy, w = cw, h = ch)
        is DashboardItem.AppWindow -> copy(x = cx, y = cy, w = cw, h = ch)
    }
}

/** True when the two tiles' cell rectangles share at least one cell. */
fun DashboardItem.overlaps(other: DashboardItem): Boolean =
    DashboardStore.rectanglesOverlap(x, y, w, h, other.x, other.y, other.w, other.h)

/**
 * Persists the 3 swipeable dashboards (each an ordered list of [DashboardItem])
 * to SharedPreferences as JSON. The layout is the user's, so it survives restarts.
 *
 * All grid rules live here so add, drag, resize, load-time repair and the
 * edit-mode ghost preview share one definition of "fits".
 */
object DashboardStore {

    /**
     * Dashboards form a cross: three side by side (pages 0-2), and the middle
     * one also has two above and two below (pages 3-6). New pages were added
     * after the first three so older saved layouts keep their page numbers.
     */
    const val PAGE_COUNT = 7

    /** The pages swiped left/right, in order. */
    val ROW = listOf(0, 1, 2)

    /** The middle page of [ROW], the only one with pages above and below. */
    const val CENTER = 1

    /** The pages swiped up/down from [CENTER], top to bottom; [CENTER] sits at [COLUMN_HOME]. */
    val COLUMN = listOf(3, 4, CENTER, 5, 6)
    const val COLUMN_HOME = 2

    /** The page's place in the cross, for labels: Home, left of it, two above it... */
    @StringRes
    fun nameRes(page: Int): Int = when (page) {
        CENTER -> R.string.dash_page_home
        0 -> R.string.dash_page_left
        2 -> R.string.dash_page_right
        4 -> R.string.dash_page_above
        3 -> R.string.dash_page_above2
        5 -> R.string.dash_page_below
        else -> R.string.dash_page_below2
    }

    private const val PREFS = "dashboard_layout_prefs"
    private const val KEY_PAGES = "pages"
    /** Previous good layout, kept so a corrupt write never costs the user everything. */
    private const val KEY_PAGES_BACKUP = "pages_backup"
    private const val KEY_VERSION = "schema"
    private const val TAG = "DashboardStore"

    /**
     * Layout schema version written with every save. Bump it when the JSON
     * shape changes and add the migration to [load]; readers must keep
     * accepting every older version, so a downgrade-then-upgrade never wipes
     * a layout.
     *
     *  1: `{"v":1,"pages":[[tile...], ...]}`. Before v1 the value was the bare
     *     pages array, which is still accepted. A built-in tile may carry an
     *     optional `"d"` (its WidgetDesign name); absent means the standard
     *     design, so older builds simply ignore it.
     */
    private const val SCHEMA_VERSION = 1

    /**
     * Tiles this build does not understand (a type or builtin kind added by a
     * newer version, seen after a downgrade) are carried through untouched, per
     * page, and written back on the next save instead of being silently
     * dropped. Held here because the in-memory model has no slot for them.
     * Kept per layout variant: loading the other arrangement must not swap
     * its unknown tiles into this one's next save.
     */
    private val retainedByVariant = HashMap<String, HashMap<Int, MutableList<JSONObject>>>()
    private fun retained(variant: String) = retainedByVariant.getOrPut(variant) { HashMap() }

    /**
     * The last layout text known to parse, per variant: what [load] read or
     * [save] wrote. [save] keeps it as the backup without parsing it again,
     * which it used to do on the main thread on every drop, undo and zoom.
     */
    private val lastGoodDoc = HashMap<String, String>()

    /**
     * Default layout when nothing is saved yet: the Daily template, laid out
     * for the head unit's 1280x720 screen ([half]: beside a Maps dock). Car
     * tiles are included so a new user sees where to connect the adapter; the
     * CANbox ones only under root ([PrivilegedShell]), where they can show
     * something.
     */
    private fun defaultPages(half: Boolean = false): List<List<DashboardItem>> = TemplatePlacer.pages(
        DashTemplate.DAILY,
        TemplateScreen.of(
            // Upright (720x1280), the Maps dock takes the top or bottom half instead.
            pageWidthDp = if (ScreenShape.vertical) 720f else if (half) 640f else 1280f,
            pageHeightDp = if (ScreenShape.vertical) (if (half) 560f else 1120f) else 576f,
            obdPaired = true,
            driverOnRight = CarProfileStore.current.driverOnRight,
            mapsDocked = half,
            dockApps = emptyList(),
            canbox = PrivilegedShell.access.value.root
        )
    )

    /** Default pages for [variant]: the Canvas theme's own ones put its home in the middle. */
    private fun defaultPagesFor(variant: String): List<List<DashboardItem>> {
        if (variant.contains(TABS_VARIANT)) return tabsPages()
        val pages = defaultPages(variant.endsWith("_half"))
        return if (variant.contains(CANVAS_VARIANT)) withCanvasHome(pages) else pages
    }

    /**
     * The Canvas theme keeps its own arrangement (variant suffix): its home is
     * the map with the trip across the top, so switching to it or away from it
     * never disturbs the other themes' pages.
     */
    const val CANVAS_VARIANT = "_canvas"

    /**
     * The Canvas home: the trip strip across the top, the speed on the left,
     * the car on the right, music at the foot, and the middle left open for
     * the map and the car on it (CanvasSkin.kt).
     */
    internal fun canvasHome(): List<DashboardItem> = if (ScreenShape.vertical) listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 7, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 0, y = 2, w = 3, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY, x = 4, y = 2, w = 3, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 0, y = 10, w = 7, h = 2)
    ) else listOf(
        DashboardItem.BuiltinWidget(BuiltinKind.NAVIGATION, x = 0, y = 0, w = 12, h = 2),
        DashboardItem.BuiltinWidget(BuiltinKind.SPEED_HUD, x = 0, y = 2, w = 3, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.TELEMETRY, x = 9, y = 2, w = 3, h = 3),
        DashboardItem.BuiltinWidget(BuiltinKind.MEDIA, x = 3, y = 5, w = 6, h = 2)
    )

    /**
     * Canvas over an app (Google Maps as the wallpaper) keeps its own
     * arrangement too: one page per tab of the rail (CanvasTabs.kt), none of
     * them swiped.
     */
    const val TABS_VARIANT = "_tabs"

    /**
     * The tabs' first pages, matching [CanvasTabs.defaultTabs]: the map alone,
     * then music, the car and the day. The tiles keep to the side away from
     * the map app's own turn card and arrival time (a column on the right, a
     * band at the foot upright).
     */
    internal fun tabsPages(): List<List<DashboardItem>> {
        fun w(kind: BuiltinKind, x: Int, y: Int, width: Int, height: Int) = DashboardItem.BuiltinWidget(kind, x = x, y = y, w = width, h = height)
        val tabs = if (ScreenShape.vertical) listOf(
            emptyList(),
            listOf(w(BuiltinKind.MEDIA, 0, 9, 7, 3)),
            listOf(w(BuiltinKind.SPEED_HUD, 0, 9, 3, 3), w(BuiltinKind.TELEMETRY, 3, 9, 4, 3)),
            listOf(w(BuiltinKind.WEATHER, 0, 9, 3, 3), w(BuiltinKind.CALENDAR, 3, 9, 4, 3))
        ) else listOf(
            emptyList(),
            listOf(w(BuiltinKind.MEDIA, 8, 0, 4, 3)),
            listOf(w(BuiltinKind.SPEED_HUD, 9, 0, 3, 2), w(BuiltinKind.TELEMETRY, 8, 2, 4, 3), w(BuiltinKind.OBD_DTC, 8, 5, 4, 2)),
            listOf(w(BuiltinKind.WEATHER, 8, 0, 4, 2), w(BuiltinKind.CALENDAR, 8, 2, 4, 3), w(BuiltinKind.SERVICE, 8, 5, 4, 2))
        )
        return List(PAGE_COUNT) { tabs.getOrElse(it) { emptyList() } }
    }

    /** [pages] with the Canvas home in the middle; the other pages come along as they are. */
    internal fun withCanvasHome(pages: List<List<DashboardItem>>): List<List<DashboardItem>> =
        pages.mapIndexed { i, page -> if (i == CENTER) canvasHome() else page }

    /**
     * Layout variants keep separate arrangements: the full-width dashboard and
     * the half-width one beside a Maps dock cannot share tile positions. The
     * default variant is "", the docked layouts share "_half"; an upright
     * screen's are "_v" and "_v_half" ([ScreenShape.layoutPrefix]).
     */
    /** Preference keys for a layout variant; pure so the naming is testable. */
    internal fun pagesKey(variant: String) = KEY_PAGES + variant
    internal fun backupKey(variant: String) = KEY_PAGES_BACKUP + variant

    fun exists(context: Context, variant: String = ""): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(pagesKey(variant))

    fun load(context: Context, variant: String = ""): List<List<DashboardItem>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(pagesKey(variant), null) ?: run { retained(variant).clear(); return defaultPagesFor(variant) }

        // A corrupt primary value falls back to the last good layout rather
        // than to the defaults; only when both are unreadable does the user
        // lose their arrangement, and then it is logged.
        val parsed = parsePages(raw, variant)?.also { lastGoodDoc[variant] = raw }
            ?: prefs.getString(backupKey(variant), null)?.let { backup ->
                Log.w(TAG, "Saved layout unreadable, restoring the previous one")
                parsePages(backup, variant)?.also { lastGoodDoc[variant] = backup }
            }
            ?: run {
                Log.e(TAG, "Saved layout and its backup are both unreadable; using defaults")
                retained(variant).clear()
                lastGoodDoc.remove(variant)
                return defaultPagesFor(variant)
            }

        // Always return exactly PAGE_COUNT pages. Tiles that predate grid
        // coordinates (x = -1) are flowed in; any overlap left by an older
        // grid size, a clamp, or a hand-edited file is repaired so two tiles
        // never share a cell.
        return List(PAGE_COUNT) { p ->
            val page = parsed.getOrElse(p) { emptyList() }
            repairOverlaps(if (page.any { it.x < 0 }) autoPlace(page) else page)
        }
    }

    /**
     * Parses either schema (bare pages array, or the versioned object) into
     * pages of tiles; null if the text is not a layout at all. Unknown tiles
     * are stashed in [retained] ([variant]'s) for the next [save].
     */
    internal fun parsePages(raw: String, variant: String = ""): List<List<DashboardItem>>? = runCatching {
        val trimmed = raw.trim()
        val pages = if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            val v = obj.optInt(KEY_VERSION, 1)
            if (v > SCHEMA_VERSION) Log.w(TAG, "Layout schema v$v is newer than this build (v$SCHEMA_VERSION)")
            obj.optJSONArray(KEY_PAGES) ?: JSONArray()
        } else {
            JSONArray(trimmed)
        }
        val retained = retained(variant)
        retained.clear()
        (0 until pages.length()).map { p ->
            val page = pages.optJSONArray(p) ?: JSONArray()
            (0 until page.length()).mapNotNull { i ->
                val o = page.optJSONObject(i) ?: return@mapNotNull null
                if (o.isDroppedBuiltin()) return@mapNotNull null
                o.toItem() ?: run {
                    retained.getOrPut(p) { mutableListOf() }.add(o)
                    null
                }
            }
        }
    }.onFailure { Log.w(TAG, "Layout parse failed", it) }.getOrNull()

    /** The versioned JSON document [save] writes, including any [retained] tiles. */
    internal fun serializePages(pages: List<List<DashboardItem>>, variant: String = ""): String {
        val retained = retained(variant)
        val json = JSONArray()
        pages.take(PAGE_COUNT).forEachIndexed { p, page ->
            val arr = JSONArray()
            page.forEach { arr.put(it.toJson()) }
            retained[p]?.forEach { arr.put(it) }
            json.put(arr)
        }
        return JSONObject().put(KEY_VERSION, SCHEMA_VERSION).put(KEY_PAGES, json).toString()
    }

    fun save(context: Context, pages: List<List<DashboardItem>>, variant: String = "") {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val doc = serializePages(pages, variant)
        // Keep what was there as the fallback for the next load, unless it is
        // the same text (nothing to gain) or unreadable (nothing to keep). What
        // this process read or wrote is known to parse; only a text nobody has
        // looked at yet (no load before the first save) is checked.
        val previous = lastGoodDoc[variant]
            ?: prefs.getString(pagesKey(variant), null)?.takeIf { parsePagesQuietly(it, variant) }
        lastGoodDoc[variant] = doc
        prefs.edit().apply {
            if (previous != null && previous != doc) putString(backupKey(variant), previous)
            putString(pagesKey(variant), doc)
        }.apply()
    }

    /** True if [raw] parses as a layout, without touching [retained]. */
    private fun parsePagesQuietly(raw: String, variant: String): Boolean {
        val retained = retained(variant)
        val keep = HashMap(retained)
        val ok = parsePages(raw, variant) != null
        retained.clear(); retained.putAll(keep)
        return ok
    }

    /** First cell where a [w] x [h] tile fits without overlapping [items]. */
    fun firstFreeCell(items: List<DashboardItem>, w: Int, h: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        return firstFree(occ, w.coerceIn(1, GRID_COLS), h.coerceIn(1, GRID_ROWS))
    }

    /**
     * True when a proposed tile rectangle is fully in bounds and does not touch
     * another tile. [ignoredIndex] is the tile currently being moved or resized.
     */
    fun canPlace(
        items: List<DashboardItem>,
        ignoredIndex: Int?,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): Boolean {
        if (!inBounds(x, y, w, h)) return false
        return items.withIndex().none { (index, item) ->
            index != ignoredIndex && rectanglesOverlap(x, y, w, h, item.x, item.y, item.w, item.h)
        }
    }

    /**
     * Moves tile [index] so its top-left is at ([x],[y]), resolving collisions
     * instead of refusing them:
     *
     *  1. free target → plain move;
     *  2. exactly one tile in the way that fits in the vacated rectangle → swap;
     *  3. otherwise every tile in the way is nudged to its nearest free cell.
     *
     * Returns the new page, or null when the tiles in the way have nowhere to go
     * (the caller then keeps the layout and tells the user).
     */
    fun moveResolving(items: List<DashboardItem>, index: Int, x: Int, y: Int): List<DashboardItem>? {
        val mover = items.getOrNull(index) ?: return null
        if (!inBounds(x, y, mover.w, mover.h)) return null
        val moved = mover.withCell(x, y, mover.w, mover.h)
        if (canPlace(items, index, x, y, mover.w, mover.h)) {
            return items.mapIndexed { i, it -> if (i == index) moved else it }
        }

        val blocking = items.withIndex().filter { (i, it) -> i != index && it.overlaps(moved) }

        // Swap: one tile in the way, and it fits where the mover came from
        // without touching anything else.
        if (blocking.size == 1) {
            val (bi, b) = blocking.single()
            val swapped = b.withCell(mover.x, mover.y, b.w, b.h)
            val others = items.filterIndexed { i, _ -> i != index && i != bi }
            val clear = swapped.x == mover.x && swapped.y == mover.y &&
                !swapped.overlaps(moved) && others.none { it.overlaps(swapped) }
            if (clear) {
                return items.mapIndexed { i, it ->
                    when (i) {
                        index -> moved
                        bi -> swapped
                        else -> it
                    }
                }
            }
        }

        // Nudge: place the mover, then relocate each blocked tile (largest
        // first, so small ones fill the gaps) to the nearest free cell.
        val result = items.toMutableList()
        result[index] = moved
        val fixed = items.indices.filter { i -> i != index && blocking.none { it.index == i } }
            .map { result[it] }.toMutableList()
        fixed += moved
        for ((bi, b) in blocking.sortedByDescending { it.value.w * it.value.h }) {
            val cell = nearestFreeCell(fixed, b.w, b.h, b.x, b.y) ?: return null
            val placed = b.withCell(cell.first, cell.second, b.w, b.h)
            result[bi] = placed
            fixed += placed
        }
        return result
    }

    /**
     * Free cell for a [w] x [h] tile closest to ([nearX],[nearY]) given the
     * already-placed [items]; null when the page is full.
     */
    fun nearestFreeCell(items: List<DashboardItem>, w: Int, h: Int, nearX: Int, nearY: Int): Pair<Int, Int>? {
        val occ = occupancy(items)
        var best: Pair<Int, Int>? = null
        var bestDist = Int.MAX_VALUE
        for (y in 0..GRID_ROWS - h) for (x in 0..GRID_COLS - w) {
            if (!fits(occ, x, y, w, h)) continue
            val d = abs(x - nearX) + abs(y - nearY)
            if (d < bestDist) { bestDist = d; best = x to y }
        }
        return best
    }

    /**
     * Keeps the first of any overlapping tiles where it is and relocates the
     * later ones to the nearest free cell. A tile with no room left is dropped
     * rather than drawn on top of another one.
     */
    fun repairOverlaps(items: List<DashboardItem>): List<DashboardItem> {
        val placed = mutableListOf<DashboardItem>()
        for (item in items) {
            val fixed = item.withCell(item.x, item.y, item.w, item.h)
            if (placed.none { it.overlaps(fixed) }) {
                placed += fixed
                continue
            }
            val full = nearestFreeCell(placed, fixed.w, fixed.h, fixed.x, fixed.y)
            if (full != null) {
                placed += fixed.withCell(full.first, full.second, fixed.w, fixed.h)
                continue
            }
            // No room at its size: shrink to the minimum span before giving up.
            val small = nearestFreeCell(placed, fixed.minW(), fixed.minH(), fixed.x, fixed.y) ?: continue
            placed += fixed.withCell(small.first, small.second, fixed.minW(), fixed.minH())
        }
        return placed
    }

    private fun inBounds(x: Int, y: Int, w: Int, h: Int): Boolean =
        x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= GRID_COLS && y + h <= GRID_ROWS

    private fun occupancy(items: List<DashboardItem>): Array<BooleanArray> {
        val occ = Array(GRID_ROWS) { BooleanArray(GRID_COLS) }
        items.forEach { mark(occ, it.x, it.y, it.w, it.h, true) }
        return occ
    }

    private fun firstFree(occ: Array<BooleanArray>, w: Int, h: Int): Pair<Int, Int>? {
        for (y in 0..GRID_ROWS - h) {
            for (x in 0..GRID_COLS - w) {
                if (fits(occ, x, y, w, h)) return x to y
            }
        }
        return null
    }

    private fun fits(occ: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int): Boolean {
        for (yy in y until y + h) for (xx in x until x + w) {
            if (yy !in 0 until GRID_ROWS || xx !in 0 until GRID_COLS || occ[yy][xx]) return false
        }
        return true
    }

    private fun mark(occ: Array<BooleanArray>, x: Int, y: Int, w: Int, h: Int, v: Boolean) {
        for (yy in y until y + h) for (xx in x until x + w) {
            if (yy in 0 until GRID_ROWS && xx in 0 until GRID_COLS) occ[yy][xx] = v
        }
    }

    internal fun rectanglesOverlap(
        ax: Int, ay: Int, aw: Int, ah: Int,
        bx: Int, by: Int, bw: Int, bh: Int
    ): Boolean = ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

    /** Flow tiles onto the grid in order, first free cell for each (migration). */
    private fun autoPlace(items: List<DashboardItem>): List<DashboardItem> {
        val occ = Array(GRID_ROWS) { BooleanArray(GRID_COLS) }
        return items.map { item ->
            val w = item.w.coerceIn(item.minW(), GRID_COLS)
            val h = item.h.coerceIn(item.minH(), GRID_ROWS)
            val pos = firstFree(occ, w, h) ?: (0 to 0)
            mark(occ, pos.first, pos.second, w, h, true)
            item.withCell(pos.first, pos.second, w, h)
        }
    }

    private fun DashboardItem.toJson(): JSONObject {
        val o = when (this) {
            is DashboardItem.AppShortcut -> JSONObject().put("t", "app").put("pkg", packageName)
            is DashboardItem.SplitPair ->
                JSONObject().put("t", "split").put("a", primaryPackage).put("b", secondaryPackage)
            is DashboardItem.LaunchBar ->
                JSONObject().put("t", "bar").put("pkgs", JSONArray(packages))
            is DashboardItem.BuiltinWidget -> JSONObject().put("t", "builtin").put("k", kind.name)
                .apply { if (design != WidgetDesign.STANDARD) put("d", design.name) }
            is DashboardItem.SystemWidget -> JSONObject().put("t", "widget").put("id", appWidgetId)
            is DashboardItem.AppWindow -> JSONObject().put("t", "appwin").put("pkg", packageName)
                .apply { if (inside) put("in", true) }
        }
        if (zoom != 1f) o.put("z", zoom.toDouble())
        return o.put("gx", x).put("gy", y).put("gw", w).put("gh", h)
    }

    /** Copy with x = -1, the sentinel load() uses to auto-place migrated tiles. */
    private fun DashboardItem.markUnplaced(): DashboardItem = when (this) {
        is DashboardItem.AppShortcut -> copy(x = -1)
        is DashboardItem.SplitPair -> copy(x = -1)
        is DashboardItem.LaunchBar -> copy(x = -1)
        is DashboardItem.BuiltinWidget -> copy(x = -1)
        is DashboardItem.SystemWidget -> copy(x = -1)
        is DashboardItem.AppWindow -> copy(x = -1)
    }

    /**
     * Built-in kinds this launcher used to have and dropped for good. Unlike a
     * kind from a newer build (kept for it, see [retained]), a tile of one of
     * these has nowhere to go and is left out of the layout on the next save.
     */
    private val DROPPED_KINDS = setOf("CAR3D")

    private fun JSONObject.isDroppedBuiltin(): Boolean =
        optString("t") == "builtin" && optString("k") in DROPPED_KINDS

    private fun JSONObject.toItem(): DashboardItem? {
        val gx = optInt("gx", -1)
        val gy = optInt("gy", -1)
        val gw = optInt("gw", -1)
        val gh = optInt("gh", -1)
        val zoom = optDouble("z", 1.0).toFloat().takeIf { it.isFinite() } ?: 1f
        fun place(item: DashboardItem): DashboardItem =
            (if (gx >= 0 && gy >= 0 && gw > 0 && gh > 0) item.withCell(gx, gy, gw, gh)
            else item.markUnplaced()).let { if (zoom != 1f) it.withZoom(zoom) else it }

        return when (optString("t")) {
            "app" -> optString("pkg").takeIf { it.isNotBlank() }
                ?.let { place(DashboardItem.AppShortcut(it)) }
            "split" -> {
                val a = optString("a"); val b = optString("b")
                if (a.isNotBlank() && b.isNotBlank()) place(DashboardItem.SplitPair(a, b)) else null
            }
            "bar" -> {
                val arr = optJSONArray("pkgs") ?: JSONArray()
                val pkgs = (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
                place(DashboardItem.LaunchBar(pkgs))
            }
            // Default span comes from the kind (a clock is 3x2, a map 5x3), so a
            // legacy tile without coordinates is re-flowed at its proper size.
            "builtin" -> runCatching { BuiltinKind.valueOf(optString("k")) }.getOrNull()
                ?.let { place(DashboardItem.BuiltinWidget(it, w = it.defaultW, h = it.defaultH, design = WidgetDesign.fromName(optString("d")))) }
            "widget" -> optInt("id", -1).takeIf { it != -1 }
                ?.let { place(DashboardItem.SystemWidget(it)) }
            "appwin" -> optString("pkg").takeIf { it.isNotBlank() }
                ?.let { place(DashboardItem.AppWindow(it, inside = optBoolean("in"))) }
            else -> null
        }
    }
}
