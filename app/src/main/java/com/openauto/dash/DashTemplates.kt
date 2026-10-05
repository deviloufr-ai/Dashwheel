package com.openauto.dash

import androidx.annotation.StringRes
import com.openauto.dash.BuiltinKind.AUDIO
import com.openauto.dash.BuiltinKind.BATTERY
import com.openauto.dash.BuiltinKind.BREAK_TIMER
import com.openauto.dash.BuiltinKind.CALENDAR
import com.openauto.dash.BuiltinKind.CAN_MON
import com.openauto.dash.BuiltinKind.CAR_STATUS
import com.openauto.dash.BuiltinKind.CLOCK
import com.openauto.dash.BuiltinKind.COMPASS
import com.openauto.dash.BuiltinKind.DOORS
import com.openauto.dash.BuiltinKind.ECO_DRIVE
import com.openauto.dash.BuiltinKind.FILTER_CARE
import com.openauto.dash.BuiltinKind.FUEL_PRICES
import com.openauto.dash.BuiltinKind.FUEL_TO_DEST
import com.openauto.dash.BuiltinKind.GFORCE
import com.openauto.dash.BuiltinKind.MEDIA
import com.openauto.dash.BuiltinKind.NAVIGATION
import com.openauto.dash.BuiltinKind.NAVMAP
import com.openauto.dash.BuiltinKind.NOTIFICATIONS
import com.openauto.dash.BuiltinKind.OBD_ALL
import com.openauto.dash.BuiltinKind.OBD_DTC
import com.openauto.dash.BuiltinKind.PARKING
import com.openauto.dash.BuiltinKind.QUICK_DIAL
import com.openauto.dash.BuiltinKind.RANGE
import com.openauto.dash.BuiltinKind.SERVICE
import com.openauto.dash.BuiltinKind.SPEED_HUD
import com.openauto.dash.BuiltinKind.TELEMETRY
import com.openauto.dash.BuiltinKind.TRIP
import com.openauto.dash.BuiltinKind.TYRES
import com.openauto.dash.BuiltinKind.WARMUP
import com.openauto.dash.BuiltinKind.WEATHER
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** One page of a [DashTemplate]: its widgets, most important first, and whether it ends in an app dock. */
data class TemplatePage(val kinds: List<BuiltinKind>, val dock: Boolean = false)

/**
 * Ready-made sets of widgets for all seven dashboards. A template only says
 * which widgets go on each page, most important first; [TemplatePlacer] works
 * out where they go for the screen at hand, so one template fits the
 * full-width dashboard and the narrow one beside a Maps dock alike.
 *
 * Page numbers follow [DashboardStore]: 1 is Home, 0 / 2 beside it, 4 / 3
 * above it and 5 / 6 below it (nearest first).
 */
enum class DashTemplate(@StringRes val titleRes: Int, @StringRes val blurbRes: Int, val pages: Map<Int, TemplatePage>) {
    // Built around what a driver uses: the map, music, and the car-care tiles
    // that carry the spoken warnings. Tools (G-force, raw OBD values, the CAN
    // monitor) and tiles with nothing to show on a head unit (quick dial,
    // parking spot) are left to the add sheet.
    DAILY(
        R.string.templates_daily, R.string.templates_daily_blurb, mapOf(
            1 to TemplatePage(listOf(NAVMAP, NAVIGATION, SPEED_HUD, MEDIA), dock = true),
            0 to TemplatePage(listOf(MEDIA, AUDIO, NOTIFICATIONS)),
            2 to TemplatePage(listOf(RANGE, OBD_DTC, SERVICE, BATTERY)),
            4 to TemplatePage(listOf(WEATHER, CALENDAR, CLOCK)),
            5 to TemplatePage(listOf(TRIP, ECO_DRIVE, BREAK_TIMER)),
            3 to TemplatePage(listOf(TELEMETRY, WARMUP, FILTER_CARE)),
            6 to TemplatePage(listOf(TYRES, CAR_STATUS, FUEL_PRICES))
        )
    ),
    ROAD_TRIP(
        R.string.templates_road_trip, R.string.templates_road_trip_blurb, mapOf(
            1 to TemplatePage(listOf(NAVMAP, NAVIGATION, FUEL_TO_DEST, SPEED_HUD), dock = true),
            0 to TemplatePage(listOf(MEDIA, AUDIO, NOTIFICATIONS)),
            2 to TemplatePage(listOf(FUEL_PRICES, RANGE, BREAK_TIMER, WEATHER)),
            4 to TemplatePage(listOf(CALENDAR, CLOCK)),
            5 to TemplatePage(listOf(TRIP, ECO_DRIVE)),
            3 to TemplatePage(listOf(OBD_DTC, TYRES, BATTERY, WARMUP)),
            6 to TemplatePage(listOf(SERVICE, CAR_STATUS))
        )
    ),
    CAR_HEALTH(
        R.string.templates_car_health, R.string.templates_car_health_blurb, mapOf(
            1 to TemplatePage(listOf(TELEMETRY, OBD_DTC, BATTERY, WARMUP), dock = true),
            0 to TemplatePage(listOf(NAVMAP, NAVIGATION, MEDIA)),
            2 to TemplatePage(listOf(FILTER_CARE, SERVICE, TYRES, RANGE)),
            4 to TemplatePage(listOf(ECO_DRIVE, BREAK_TIMER, TRIP)),
            3 to TemplatePage(listOf(CAR_STATUS, DOORS)),
            5 to TemplatePage(listOf(WEATHER, CALENDAR, CLOCK)),
            6 to TemplatePage(listOf(NOTIFICATIONS, AUDIO))
        )
    )
}

/**
 * What the placer needs to know about the car and the screen.
 *
 * @param cellAspect a grid cell's width / height as drawn, so a tile's real shape is known
 * @param minCols narrowest a widget may be, in cells, to stay readable
 * @param obdPaired an OBD adapter has been chosen; without one the car-data tiles are held back
 * @param driverOnRight right-hand drive (the car profile's setting): the most important tiles go on the right
 * @param mapsDocked Google Maps is docked beside the pages, so the in-app map is left out
 * @param dockApps apps for the Home page's dock; none means no dock
 */
data class TemplateScreen(
    val cellAspect: Double,
    val minCols: Int = MIN_COLS,
    val obdPaired: Boolean = true,
    val driverOnRight: Boolean = false,
    val mapsDocked: Boolean = false,
    val dockApps: List<String> = emptyList(),
    /** The CANbox stream can be read (root, see [PrivilegedShell]): the door and CAN tiles have something to show. */
    val canbox: Boolean = true,
    /** The head unit has the TPMS app that reports the tyres ([Tyres.available]). */
    val tyres: Boolean = true
) {
    companion object {
        /** Widgets never get narrower than their resize minimum. */
        const val MIN_COLS = 3

        /** Narrowest a widget stays readable at, in dp. */
        private const val MIN_TILE_WIDTH_DP = 180f

        /**
         * The screen for a page [pageWidthDp] x [pageHeightDp]: the cell shape,
         * and how many columns a readable widget needs there (more when the
         * page is squeezed beside a Maps dock).
         */
        fun of(
            pageWidthDp: Float,
            pageHeightDp: Float,
            obdPaired: Boolean,
            driverOnRight: Boolean,
            mapsDocked: Boolean,
            dockApps: List<String>,
            canbox: Boolean = true,
            tyres: Boolean = true
        ): TemplateScreen {
            val cellW = (pageWidthDp / GRID_COLS).coerceAtLeast(1f)
            val cellH = (pageHeightDp / GRID_ROWS).coerceAtLeast(1f)
            return TemplateScreen(
                cellAspect = (cellW / cellH).toDouble(),
                minCols = maxOf(MIN_COLS, ceil(MIN_TILE_WIDTH_DP / cellW).toInt()).coerceAtMost(GRID_COLS),
                obdPaired = obdPaired,
                driverOnRight = driverOnRight,
                mapsDocked = mapsDocked,
                dockApps = dockApps,
                canbox = canbox,
                tyres = tyres
            )
        }
    }
}

/**
 * Turns a [DashTemplate] into tile positions. Each page is cut in two again
 * and again (a treemap): the more important half of the widgets goes on the
 * driver's side or on top, each widget gets room in proportion to how much
 * it has to show, and of all the ways to cut, the one where every tile comes
 * out closest to its natural shape wins. The page is always covered with no
 * gaps; a widget that cannot get a readable size is left off, least
 * important first.
 */
object TemplatePlacer {

    /** How much room a widget wants ([weight]) and its natural width / height ([aspect]). */
    private class Shape(val weight: Int, val aspect: Double)

    private val SHAPES = mapOf(
        NAVMAP to Shape(14, 1.3), NAVIGATION to Shape(4, 1.3), SPEED_HUD to Shape(3, 1.5),
        MEDIA to Shape(5, 2.0), AUDIO to Shape(2, 1.6), QUICK_DIAL to Shape(3, 1.8),
        NOTIFICATIONS to Shape(4, 1.4), TELEMETRY to Shape(6, 1.6), RANGE to Shape(3, 1.3),
        OBD_DTC to Shape(3, 1.6), DOORS to Shape(3, 1.5), WEATHER to Shape(3, 1.8),
        CALENDAR to Shape(4, 1.4), CLOCK to Shape(2, 1.6), TRIP to Shape(3, 1.8),
        PARKING to Shape(3, 1.8), COMPASS to Shape(2, 1.0),
        GFORCE to Shape(3, 1.8), OBD_ALL to Shape(6, 1.4), CAN_MON to Shape(6, 1.4),
        FILTER_CARE to Shape(3, 1.3), WARMUP to Shape(3, 1.2), BATTERY to Shape(3, 1.2),
        ECO_DRIVE to Shape(4, 1.4), BREAK_TIMER to Shape(3, 1.2), FUEL_TO_DEST to Shape(3, 1.3),
        SERVICE to Shape(4, 1.4), FUEL_PRICES to Shape(4, 1.3), CAR_STATUS to Shape(4, 1.4),
        TYRES to Shape(4, 1.3)
    )
    private val DEFAULT_SHAPE = Shape(3, 1.6)
    private fun shape(kind: BuiltinKind) = SHAPES[kind] ?: DEFAULT_SHAPE

    /**
     * Tiles fed by the OBD adapter's readings alone, and what (if anything)
     * stands in for them without one. The fuel, the doors and the car's own
     * status come from the CAN box instead ([NEEDS_CANBOX]).
     */
    private val NEEDS_OBD = setOf(TELEMETRY, OBD_DTC, OBD_ALL, BATTERY, WARMUP, FILTER_CARE, ECO_DRIVE, BREAK_TIMER)
    private val WITHOUT_OBD = mapOf(TELEMETRY to TRIP)

    /** Tiles fed by the CANbox stream alone, which only root can read: nothing stands in for them. */
    private val NEEDS_CANBOX = setOf(DOORS, CAN_MON, CAR_STATUS)

    /** Fuel and range: from the CAN box, else the adapter's fuel reading on cars that give one. */
    private val NEEDS_FUEL = setOf(RANGE, FUEL_TO_DEST)

    /** Every page of [template] laid out for [screen]; pages the template leaves out stay empty. */
    fun pages(template: DashTemplate, screen: TemplateScreen): List<List<DashboardItem>> =
        List(DashboardStore.PAGE_COUNT) { p -> template.pages[p]?.let { page(it, screen) } ?: emptyList() }

    /** The widgets of [page] that make sense on [screen], in order of importance. */
    fun kindsFor(page: TemplatePage, screen: TemplateScreen): List<BuiltinKind> {
        val out = mutableListOf<BuiltinKind>()
        for (kind in page.kinds) {
            val use = when {
                screen.mapsDocked && kind == NAVMAP -> null
                !screen.canbox && kind in NEEDS_CANBOX -> null
                !screen.canbox && !screen.obdPaired && kind in NEEDS_FUEL -> null
                !screen.tyres && kind == TYRES -> null
                !screen.obdPaired && kind in NEEDS_OBD -> WITHOUT_OBD[kind]?.takeIf { it !in page.kinds }
                else -> kind
            }
            if (use != null && use !in out) out += use
        }
        return out
    }

    /** One page's tiles: its widgets packed into the grid, plus the app dock along the bottom. */
    fun page(page: TemplatePage, screen: TemplateScreen): List<DashboardItem> {
        val dock = if (page.dock && screen.dockApps.isNotEmpty()) {
            DashboardItem.LaunchBar(screen.dockApps, x = 0, y = GRID_ROWS - 1, w = GRID_COLS, h = 1)
        } else null
        val area = Cells(0, 0, GRID_COLS, if (dock != null) GRID_ROWS - 1 else GRID_ROWS)
        var kinds = kindsFor(page, screen)
        while (kinds.isNotEmpty()) {
            val fit = Search(screen, kinds, area).best(kinds, area)
            if (fit != null) return fit.tiles + listOfNotNull(dock)
            kinds = kinds.dropLast(1)
        }
        return listOfNotNull(dock)
    }

    private data class Cells(val x: Int, val y: Int, val w: Int, val h: Int)

    private class Fit(val cost: Double, val tiles: List<DashboardItem>)

    private class Search(val screen: TemplateScreen, all: List<BuiltinKind>, area: Cells) {
        private val totalWeight = all.sumOf { shape(it).weight }.toDouble()
        private val totalArea = (area.w * area.h).toDouble()
        private val minCols = screen.minCols.coerceAtLeast(TemplateScreen.MIN_COLS)
        private val minRows = 2

        /** How far a tile at [r] is from its natural shape and fair share of the page, weighted by importance. */
        private fun cost(kind: BuiltinKind, r: Cells): Double {
            val s = shape(kind)
            val aspect = r.w.toDouble() / r.h * screen.cellAspect
            val share = r.w * r.h / totalArea
            val fair = s.weight / totalWeight
            return (abs(ln(aspect / s.aspect)) + 0.7 * abs(ln(share / fair))) * s.weight
        }

        /** Cheapest way to lay [kinds] (in order of importance) out over exactly [r]; null if they cannot fit. */
        fun best(kinds: List<BuiltinKind>, r: Cells): Fit? {
            if (kinds.size == 1) {
                if (r.w < minCols || r.h < minRows) return null
                val kind = kinds.single()
                return Fit(cost(kind, r), listOf(DashboardItem.BuiltinWidget(kind, r.x, r.y, r.w, r.h)))
            }
            var winner: Fit? = null
            for (acrossColumns in listOf(true, false)) {
                val length = if (acrossColumns) r.w else r.h
                val min = if (acrossColumns) minCols else minRows
                if (length < 2 * min) continue
                for (i in 1 until kinds.size) {
                    val first = kinds.subList(0, i)
                    val rest = kinds.subList(i, kinds.size)
                    val wFirst = first.sumOf { shape(it).weight }.toDouble()
                    val wRest = rest.sumOf { shape(it).weight }.toDouble()
                    val even = length * wFirst / (wFirst + wRest)
                    val sizes = setOf(floor(even).toInt(), ceil(even).toInt(), even.roundToInt().coerceIn(min, length - min))
                    for (size in sizes) {
                        if (size < min || length - size < min) continue
                        val (a, b) = split(r, acrossColumns, size)
                        val fa = best(first, a) ?: continue
                        val fb = best(rest, b) ?: continue
                        val cost = fa.cost + fb.cost
                        if (winner == null || cost < winner.cost) winner = Fit(cost, fa.tiles + fb.tiles)
                    }
                }
            }
            return winner
        }

        /** Cuts [r] into the important part (driver's side, or top) of [size] cells and the rest. */
        private fun split(r: Cells, acrossColumns: Boolean, size: Int): Pair<Cells, Cells> = when {
            !acrossColumns -> Cells(r.x, r.y, r.w, size) to Cells(r.x, r.y + size, r.w, r.h - size)
            screen.driverOnRight -> Cells(r.x + r.w - size, r.y, size, r.h) to Cells(r.x, r.y, r.w - size, r.h)
            else -> Cells(r.x, r.y, size, r.h) to Cells(r.x + size, r.y, r.w - size, r.h)
        }
    }

    /** Apps worth putting in a dock when the user has none on their dashboards yet, in order. */
    val SUGGESTED_DOCK_APPS = listOf(
        "com.google.android.apps.maps",
        "com.waze",
        NavHandoff.TOMTOM,
        NavHandoff.TOMTOM_AMIGO,
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "deezer.android.app",
        "com.google.android.dialer",
        "com.android.dialer"
    )

    /** Most apps a template's dock gets. */
    const val DOCK_SIZE = 8

    /**
     * The apps for a template's dock: the shortcuts and dock apps already on
     * [pages] (so applying a template keeps them), or, with none, the
     * installed ones of [SUGGESTED_DOCK_APPS].
     */
    fun dockApps(pages: List<List<DashboardItem>>, installed: Set<String>): List<String> {
        val own = pages.flatten().flatMap {
            when (it) {
                is DashboardItem.AppShortcut -> listOf(it.packageName)
                is DashboardItem.LaunchBar -> it.packages
                is DashboardItem.SplitPair -> listOf(it.primaryPackage, it.secondaryPackage)
                else -> emptyList()
            }
        }.distinct()
        return own.ifEmpty { SUGGESTED_DOCK_APPS.filter { it in installed } }.take(DOCK_SIZE)
    }
}
