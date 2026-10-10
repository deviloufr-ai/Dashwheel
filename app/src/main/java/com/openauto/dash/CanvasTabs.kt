package com.openauto.dash

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

/*
 * Canvas over an app: Google Maps (or Waze) runs full size under the whole
 * dashboard, inside Dashwheel as an app inside a tile does (EmbeddedApp), and
 * the tiles float over it. Nothing is swiped, so every touch that misses a
 * tile goes to the map: the pages are tabs in the rail instead, one tap
 * each, named by the driver (Media, Car, Info...). The steering wheel can
 * step through them, and a tab can come up on its own when the car sets off,
 * stops, or finds a new fault.
 */

/** A tab's suggested name, with its icon. */
enum class TabPreset(@StringRes val labelRes: Int, val icon: ImageVector) {
    MAP(R.string.canvas_tab_map, Icons.Filled.Map),
    MEDIA(R.string.canvas_tab_media, Icons.Filled.MusicNote),
    CAR(R.string.canvas_tab_car, Icons.Filled.DirectionsCar),
    INFO(R.string.canvas_tab_info, Icons.Filled.Info),
    TRIP(R.string.canvas_tab_trip, Icons.Filled.Place),
    PHONE(R.string.canvas_tab_phone, Icons.Filled.Phone),
    WEATHER(R.string.canvas_tab_weather, Icons.Filled.Cloud),
    SERVICE(R.string.canvas_tab_service, Icons.Filled.Build),
    ENGINE(R.string.canvas_tab_engine, Icons.Filled.Speed),
    CHECKS(R.string.canvas_tab_checks, Icons.Filled.Checklist),
    ECO(R.string.canvas_tab_eco, Icons.Filled.Eco)
}

/** When a tab comes up by itself. One tab per moment. */
internal enum class TabTrigger(@StringRes val labelRes: Int) {
    /** Once the car has stood still for [PARKED_HOLD_MS] after a drive; never at power-up, where Home stays. */
    PARKED(R.string.canvas_when_parked),
    /** As the car sets off. */
    DRIVING(R.string.canvas_when_driving),
    /** A fault code the car had not reported before. */
    ENGINE_FAULT(R.string.canvas_when_fault)
}

/**
 * One dashboard of the bar: the [page] it shows, a [preset] for its icon (and
 * its name, unless the driver typed one), and what brings it up by itself.
 * Unnamed, it is called by its place in the cross (Home, Left...).
 */
internal data class CanvasTab(
    val page: Int,
    val preset: TabPreset? = null,
    val name: String? = null,
    val trigger: TabTrigger? = null
) {
    val icon: ImageVector get() = preset?.icon ?: if (page == DashboardStore.CENTER) Icons.Filled.Home else Icons.Filled.Dashboard

    fun label(context: Context): String =
        name?.takeIf { it.isNotBlank() } ?: preset?.let { context.getString(it.labelRes) } ?: context.getString(DashboardStore.nameRes(page))
}

/**
 * The bar's dashboards for the layout on screen. [tabbed]: Canvas over an app,
 * where they are tabs in the rail and nothing is swiped; otherwise they are
 * named pages of the cross, and the bar jumps to them.
 */
internal data class Dashboards(val tabs: List<CanvasTab>, val tabbed: Boolean)

internal val LocalDashboards = compositionLocalOf<Dashboards?> { null }

/** What [page] is called: its name in the bar, else its place in the cross. */
@Composable
internal fun pageName(page: Int): String {
    val tab = LocalDashboards.current?.tabs?.firstOrNull { it.page == page }
    return tab?.label(LocalContext.current) ?: stringResource(DashboardStore.nameRes(page))
}

/** How long the car must stand still before a stop counts as parked (not a red light). */
internal const val PARKED_HOLD_MS = 2 * 60_000L

/**
 * The dashboard bar's content: its dashboards, the named pages of the cross
 * (one list per screen direction, as the pages themselves), and its apps,
 * the same everywhere. Shown by the Canvas rail and the Dashboard bar widget.
 * Over an app the same dashboards are tabs in the rail instead of swipes.
 */
internal object CanvasTabs {

    private const val TAG = "CanvasTabs"
    private const val PREFS = "canvas_tabs"
    private const val KEY_APP = "map_app"
    private const val KEY_APPS = "apps"
    /** The Canvas tabs' own list until 2026-10, when they kept their own pages; read once by [mergeLegacy]. */
    private const val TABS_KEY = "tabs"
    private const val BAR_KEY = "bar"

    /** One dashboard per page at most. */
    const val MAX_TABS = DashboardStore.PAGE_COUNT

    private val _mapApp = MutableStateFlow<String?>(null)

    /** The app under the Canvas dashboard; null for the built-in map. */
    val mapApp: StateFlow<String?> = _mapApp.asStateFlow()

    private val _lists = MutableStateFlow<Map<String, List<CanvasTab>>>(emptyMap())

    /** The saved dashboards, by [keyFor] a layout. A layout missing here has not been set up yet. */
    val lists: StateFlow<Map<String, List<CanvasTab>>> = _lists.asStateFlow()

    private val _apps = MutableStateFlow<List<String>>(emptyList())

    /** The bar's apps, by package, in order. */
    val apps: StateFlow<List<String>> = _apps.asStateFlow()

    /** The Canvas rail is up (an app under Canvas): the Dashboard bar widget would repeat it. */
    var railShown by mutableStateOf(false)

    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _mapApp.value = p.getString(KEY_APP, null)?.takeIf { it.isNotBlank() }
        _lists.value = p.all.keys.filter { it == TABS_KEY || it.startsWith(BAR_KEY) }
            .mapNotNull { key -> p.getString(key, null)?.let(::parse)?.let { key to it } }.toMap()
        _apps.value = p.getString(KEY_APPS, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        prefs = p
    }

    fun setMapApp(context: Context, packageName: String?) {
        load(context)
        _mapApp.value = packageName
        prefs?.edit()?.putString(KEY_APP, packageName.orEmpty())?.apply()
    }

    fun saveTabs(key: String, tabs: List<CanvasTab>) {
        _lists.value = _lists.value + (key to tabs)
        prefs?.edit()?.putString(key, serialize(tabs))?.apply()
    }

    fun saveApps(apps: List<String>) {
        _apps.value = apps
        prefs?.edit()?.putString(KEY_APPS, apps.joinToString(","))?.apply()
    }

    /** Where a screen direction's dashboards are kept ([variant] is DashboardStore's: "" or "_v"). */
    fun keyFor(variant: String): String = BAR_KEY + variant

    /**
     * The dashboards for [key]: the saved ones, else the pages with tiles on
     * them, Home first, each called by its place. The cross shows only its
     * dashboards, so Home and every page holding tiles are always among them:
     * nothing placed is ever out of reach, and an Undo brings a removed one back.
     */
    fun tabsFor(key: String, lists: Map<String, List<CanvasTab>>, pages: List<List<*>>): List<CanvasTab> =
        withEveryPage(lists[key] ?: crossTabs(pages), pages)

    /**
     * The legacy lists folded into the one per direction, with the pages
     * (DashboardStore.mergeLegacyVariants): for each direction whose pages
     * came from the copy the user last used ([suffix]), that copy's names
     * come too: the Canvas tabs' own list (or their first names) over an app,
     * else the list kept beside that copy. The legacy lists then go.
     */
    fun mergeLegacy(suffix: String, directions: Set<String>) {
        val p = prefs ?: return
        val edit = p.edit()
        for (direction in directions) {
            val legacy = if (suffix == DashboardStore.LEGACY_TABS) _lists.value[TABS_KEY] ?: legacyTabs()
                else _lists.value[BAR_KEY + direction + suffix] ?: continue
            edit.putString(keyFor(direction), serialize(legacy))
            _lists.value = _lists.value + (keyFor(direction) to legacy)
        }
        val gone = _lists.value.keys.filter { it == TABS_KEY || it.endsWith(DashboardStore.LEGACY_HALF) || it.endsWith(DashboardStore.LEGACY_CANVAS) || it.endsWith(DashboardStore.LEGACY_TABS) }
        gone.forEach { edit.remove(it) }
        _lists.value = _lists.value - gone.toSet()
        edit.apply()
    }

    /** [tabs] with the dashboard of [page] called by [preset] (a template's name for it); a name the driver typed goes with the old tiles. */
    fun named(tabs: List<CanvasTab>, page: Int, preset: TabPreset?): List<CanvasTab> {
        if (preset == null) return tabs
        val at = tabs.indexOfFirst { it.page == page }
        return if (at < 0) tabs + CanvasTab(page, preset) else tabs.mapIndexed { i, t -> if (i == at) t.copy(preset = preset, name = null) else t }
    }

    /** [tabs] with Home first when it is missing, and the pages with tiles that have no dashboard after them. */
    private fun withEveryPage(tabs: List<CanvasTab>, pages: List<List<*>>): List<CanvasTab> {
        val missing = crossTabs(pages).filter { c -> tabs.none { it.page == c.page } }
        if (missing.isEmpty()) return tabs
        val home = missing.filter { it.page == DashboardStore.CENTER }
        return home + tabs + (missing - home.toSet())
    }

    /** The pages of a cross with tiles on them, Home first then round the cross, unnamed. */
    fun crossTabs(pages: List<List<*>>): List<CanvasTab> =
        CROSS_ORDER.filter { it == DashboardStore.CENTER || pages.getOrNull(it)?.isNotEmpty() == true }.map { CanvasTab(it) }

    /** Home, then left and right of it, then above and below. */
    private val CROSS_ORDER = listOf(DashboardStore.CENTER, 0, 2, 4, 3, 5, 6)

    /** The Canvas tabs' first names until 2026-10 (their pages 0 to 3), for [mergeLegacy] when none were saved. */
    internal fun legacyTabs(): List<CanvasTab> = listOf(
        CanvasTab(0, TabPreset.MAP),
        CanvasTab(1, TabPreset.MEDIA),
        CanvasTab(2, TabPreset.CAR, trigger = TabTrigger.ENGINE_FAULT),
        CanvasTab(3, TabPreset.INFO)
    )

    internal fun serialize(tabs: List<CanvasTab>): String = JSONArray().apply {
        tabs.forEach { tab ->
            put(JSONObject().apply {
                put("g", tab.page)
                tab.preset?.let { put("p", it.name) }
                tab.name?.takeIf { it.isNotBlank() }?.let { put("n", it) }
                tab.trigger?.let { put("w", it.name) }
            })
        }
    }.toString()

    /**
     * Dashboards from [serialize]'s text; null when it is not that. Unknown
     * presets and triggers are dropped, the dashboard kept. Tabs saved before
     * they had a page show the page of their place.
     */
    internal fun parse(text: String): List<CanvasTab>? = runCatching {
        val array = JSONArray(text)
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            CanvasTab(
                page = o.optInt("g", i),
                preset = o.optString("p").let { p -> TabPreset.entries.firstOrNull { it.name == p } },
                name = o.optString("n").takeIf { it.isNotBlank() },
                trigger = o.optString("w").let { w -> TabTrigger.entries.firstOrNull { it.name == w } }
            )
        }.filter { it.page in 0 until DashboardStore.PAGE_COUNT }.distinctBy { it.page }.take(MAX_TABS)
    }.onFailure { Log.w(TAG, "unreadable dashboards, back to the defaults", it) }.getOrNull()

    /** The dashboard [trigger] brings up, if one has it. */
    fun tabFor(tabs: List<CanvasTab>, trigger: TabTrigger): CanvasTab? = tabs.firstOrNull { it.trigger == trigger }

    /** [tab] set, at [place] in the list; its trigger, if any, is taken from the dashboard that had it. */
    fun placed(tabs: List<CanvasTab>, tab: CanvasTab, place: Int): List<CanvasTab> {
        val others = tabs.filter { it.page != tab.page }
            .map { if (tab.trigger != null && it.trigger == tab.trigger) it.copy(trigger = null) else it }
        return others.toMutableList().apply { add(place.coerceIn(0, size), tab) }
    }

    /** A page no dashboard shows, an empty one first; null when every page has its dashboard. */
    fun freePage(tabs: List<CanvasTab>, pages: List<List<*>>): Int? {
        val used = tabs.mapTo(HashSet()) { it.page }
        val free = (0 until DashboardStore.PAGE_COUNT).filter { it !in used }
        return free.firstOrNull { pages.getOrNull(it).isNullOrEmpty() } ?: free.firstOrNull()
    }

    /** The dashboards once pages [a] and [b] trade places in the cross. */
    fun swapped(tabs: List<CanvasTab>, a: Int, b: Int): List<CanvasTab> = tabs.map {
        when (it.page) {
            a -> it.copy(page = b)
            b -> it.copy(page = a)
            else -> it
        }
    }

    /** [items] with the one at [from] moved to [to]. */
    fun <T> moved(items: List<T>, from: Int, to: Int): List<T> {
        if (from !in items.indices) return items
        val list = items.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return list
    }

    /** The tab [step] away from [current] among [count], going round. */
    fun stepped(current: Int, step: Int, count: Int): Int =
        if (count <= 0) 0 else ((current + step) % count + count) % count
}

/**
 * Brings up the tab of each moment (CanvasTabs' [TabTrigger]s) as it comes:
 * the car setting off, standing still long enough to be parked after a
 * drive, a new fault. Only at those moments, so a tab the driver picks stays
 * until the next one, and the dashboard the launcher starts on stays at
 * power-up (the parked one came up at every start, in place of Home).
 */
@Composable
internal fun FollowTabTriggers(tabs: List<CanvasTab>, onTrigger: (TabTrigger) -> Unit) {
    val wanted = remember(tabs) { tabs.mapNotNullTo(HashSet()) { it.trigger } }
    if (wanted.isEmpty()) return
    val fire by rememberUpdatedState(onTrigger)
    if (TabTrigger.PARKED in wanted || TabTrigger.DRIVING in wanted) {
        UseLocationFeed()
        LaunchedEffect(Unit) {
            var movedYet = false
            carSpeedKmh().setOffOrStopped().collectLatest { moving ->
                if (moving) {
                    movedYet = true
                    fire(TabTrigger.DRIVING)
                } else if (movedYet) {
                    // A stop after a drive must last; standing still at the start is no stop at all.
                    delay(PARKED_HOLD_MS)
                    fire(TabTrigger.PARKED)
                }
            }
        }
    }
    if (TabTrigger.ENGINE_FAULT in wanted) {
        LaunchedEffect(Unit) {
            AiMechanic.newFaultAt.drop(1).collectLatest { if (it > 0L) fire(TabTrigger.ENGINE_FAULT) }
        }
    }
}

private val TabShape = RoundedCornerShape(22.dp)
private val TAB_WIDTH = 76.dp
private val TAB_HEIGHT = 72.dp

/** What the dashboard bar shows and does: handed to the Canvas rail and to every Dashboard bar widget. */
internal class DashBarModel(
    val tabs: List<CanvasTab>,
    /** The dashboard on screen, as a page. */
    val currentPage: Int,
    val apps: List<String>,
    val appsByPackage: Map<String, AppEntry>,
    val onSelect: (CanvasTab) -> Unit,
    /** Opens the sheet of the dashboard at this index. */
    val onOpenTab: (Int) -> Unit,
    val onLaunch: (String) -> Unit,
    /** Opens the sheet of the app at this index. */
    val onOpenApp: (Int) -> Unit,
    val onAddDashboard: () -> Unit,
    val onAddApp: () -> Unit,
    /** A page is still without its dashboard. */
    val canAddDashboard: Boolean
)

internal val LocalDashBar = compositionLocalOf<DashBarModel?> { null }

/**
 * The bar's dashboards, then its apps after a line, in a column or a row
 * ([vertical]), scrolling when they overflow. A tap shows a dashboard, in
 * every mode, or opens an app; held (an app also tapped while [editing]) it
 * opens its sheet.
 * [trailing] ends the list (the rail's + while arranging).
 */
@Composable
private fun DashBarItems(
    m: DashBarModel,
    vertical: Boolean,
    editing: Boolean,
    modifier: Modifier = Modifier,
    /** How wide a dashboard button is: smaller in a thin widget, then without its name. */
    tabWidth: Dp = TAB_WIDTH,
    trailing: @Composable () -> Unit = {}
) {
    val context = LocalContext.current
    val tap = rememberTapFeedback()
    val apps = m.apps.mapIndexedNotNull { i, pkg -> m.appsByPackage[pkg]?.let { i to it } }
    val content: @Composable () -> Unit = {
        m.tabs.forEachIndexed { index, tab ->
            RailTab(
                icon = tab.icon,
                label = tab.label(context),
                width = tabWidth,
                active = tab.page == m.currentPage,
                // A tap shows it, arranging or not; held, its sheet.
                onTap = { tap(); m.onSelect(tab) },
                onHold = { tap(); m.onOpenTab(index) }
            )
        }
        if (apps.isNotEmpty()) {
            val rule = tabWidth * 0.7f
            Box(if (vertical) Modifier.width(rule).height(1.dp) else Modifier.width(1.dp).height(rule)) {
                Box(Modifier.fillMaxSize().background(DashColors.Line))
            }
            apps.forEach { (index, app) ->
                BarApp(app, size = tabWidth * 0.8f, onTap = { tap(); if (editing) m.onOpenApp(index) else m.onLaunch(app.packageName) }, onHold = { tap(); m.onOpenApp(index) })
            }
        }
        trailing()
    }
    if (vertical) {
        Column(
            modifier = modifier.verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) { content() }
    } else {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) { content() }
    }
}

/**
 * The rail over an app: the dashboard bar (a + to add while arranging),
 * then the app drawer, the setup pill when something is still to allow, ⋮
 * and the time.
 */
@Composable
internal fun CanvasTabRail(m: TopBarModel, bar: DashBarModel, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val tap = rememberTapFeedback()
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(8.dp)
            .width(88.dp)
            .then(canvasGlass(RoundedCornerShape(30.dp)))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            DashBarItems(bar, vertical = true, editing = m.editing, modifier = Modifier.weight(1f, fill = false))
            // Under the list, not at its end: a long list scrolls, the + stays in sight.
            if (m.editing) {
                Spacer(Modifier.height(6.dp))
                RailTab(
                    icon = Icons.Filled.Add,
                    label = stringResource(R.string.canvas_tab_add),
                    active = false,
                    dashed = true,
                    onTap = { tap(); onAdd() },
                    onHold = { tap(); onAdd() }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.width(52.dp).height(1.dp).background(DashColors.Line))
        Spacer(Modifier.height(6.dp))
        RailIcon(Icons.Filled.Apps, stringResource(R.string.dash_all_apps)) { tap(); m.onApps() }
        if (m.setupPending) {
            RailIcon(Icons.Filled.Info, stringResource(R.string.setup_pill), tint = DashColors.Warning) { tap(); m.onSetup(false) }
        }
        MorePicker(m) { open -> RailIcon(Icons.Filled.MoreVert, stringResource(R.string.dash_more)) { tap(); open() } }
        if (!m.merged) {
            Text(m.clock, color = DashColors.TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = fixedSp(17f), maxLines = 1)
        }
    }
}

/** The Dashboard bar widget: the same bar as the Canvas rail, a column when tall, a row when wide. */
@Composable
internal fun DashBarTile() {
    val m = LocalDashBar.current ?: return
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
        val vertical = maxHeight >= maxWidth
        // The buttons fit the bar's thickness, up to the rail's size.
        val across = if (vertical) maxWidth else maxHeight * (TAB_WIDTH / TAB_HEIGHT)
        DashBarItems(m, vertical = vertical, editing = false, tabWidth = across.coerceIn(36.dp, TAB_WIDTH))
    }
}

@Composable
private fun RailTab(
    icon: ImageVector,
    label: String,
    width: Dp = TAB_WIDTH,
    active: Boolean,
    dashed: Boolean = false,
    onTap: () -> Unit,
    onHold: () -> Unit
) {
    val ink = if (active) DashColors.Accent else DashColors.TextSecondary
    // Read when the finger lifts: keyed on the callbacks, which are new at each
    // redraw of the dashboard, the touch was started over in the middle of a
    // tap and the tap lost.
    val tapped by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHold)
    Column(
        modifier = Modifier
            .size(width = width, height = width * (TAB_HEIGHT / TAB_WIDTH))
            .clip(TabShape)
            .then(if (active) Modifier.background(DashColors.Accent.copy(alpha = 0.18f)) else Modifier)
            .then(if (dashed) Modifier.border(1.5.dp, DashColors.Line, TabShape) else Modifier)
            .pointerInput(Unit) { detectTapGestures(onTap = { tapped() }, onLongPress = { held() }) }
            .semantics { contentDescription = label; role = Role.Tab },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(if (width < 60.dp) 22.dp else 26.dp))
        // Too small for its name: the icon alone (the name stays for talkback).
        if (width >= 60.dp) {
            Spacer(Modifier.height(4.dp))
            Text(
                label, color = ink, fontSize = fixedSp(13f), fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

@Composable
private fun BarApp(app: AppEntry, size: Dp, onTap: () -> Unit, onHold: () -> Unit) {
    // As for a dashboard's button ([RailTab]): a redraw must not lose the tap.
    val tapped by rememberUpdatedState(onTap)
    val held by rememberUpdatedState(onHold)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(18.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { tapped() }, onLongPress = { held() }) }
            .semantics { contentDescription = app.label; role = Role.Button },
        contentAlignment = Alignment.Center
    ) {
        AppIcon(app.icon, size * 0.8f)
    }
}

@Composable
private fun RailIcon(icon: ImageVector, description: String, tint: Color = DashColors.TextPrimary, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
    }
}

/** The Dashboard bar tile's panel while arranging: add a dashboard or an app to it. */
@Composable
internal fun DashBarPanelActions(canAddDashboard: Boolean, onAddDashboard: () -> Unit, onAddApp: () -> Unit) {
    if (canAddDashboard) {
        ChooserRow(Icons.Filled.Dashboard, stringResource(R.string.canvas_bar_add_dashboard), stringResource(R.string.canvas_bar_add_dashboard_short), onAddDashboard)
    }
    ChooserRow(Icons.Filled.Apps, stringResource(R.string.canvas_bar_add_app), stringResource(R.string.canvas_bar_add_app_short), onAddApp)
}

/** The rail's +: a dashboard or an app. */
@Composable
internal fun DashBarAddChooser(canAddDashboard: Boolean, onDashboard: () -> Unit, onApp: () -> Unit, onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    SettingsSheet(
        title = stringResource(R.string.canvas_bar_add_title),
        onDismiss = onDismiss,
        actions = { SheetButton(stringResource(R.string.apps_cancel), primary = false, onClick = onDismiss) }
    ) {
        if (canAddDashboard) {
            ChooserRow(Icons.Filled.Dashboard, stringResource(R.string.canvas_bar_add_dashboard), stringResource(R.string.canvas_bar_add_dashboard_detail), onDashboard)
        }
        ChooserRow(Icons.Filled.Apps, stringResource(R.string.canvas_bar_add_app), stringResource(R.string.canvas_bar_add_app_detail), onApp)
    }
}

/** One choice in a sheet: an icon on a disc, its name and a line about it. */
@Composable
internal fun ChooserRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(DashColors.CardHi)
            .clickable { tap(); onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(DashColors.Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(28.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * A dashboard's sheet, new ([tab] null, on [newPage]) or one of the bar's: a
 * suggested name (its icon comes with it) or the driver's own, the moment
 * that brings it up by itself, and its place: in the bar when [tabbed], a
 * spot of the cross otherwise (a taken one trades places with it; a new one
 * takes a free one). [pagesWithTiles]: the cross's pages that already hold tiles.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DashboardSheet(
    tab: CanvasTab?,
    newPage: Int,
    tabs: List<CanvasTab>,
    tabbed: Boolean,
    pagesWithTiles: Set<Int>,
    canRemove: Boolean,
    onSave: (CanvasTab, Int) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    ParkedOnly(onDismiss)
    val context = LocalContext.current
    val page = tab?.page ?: newPage
    // A new tab over an app starts with a name the rail has not got yet: a place
    // in the cross (its fallback name) means nothing there.
    val firstPreset = if (tab == null && tabbed) TabPreset.entries.firstOrNull { p -> tabs.none { it.preset == p } } else tab?.preset
    var preset by remember { mutableStateOf(firstPreset) }
    var name by remember { mutableStateOf(tab?.label(context) ?: firstPreset?.let { context.getString(it.labelRes) }.orEmpty()) }
    var trigger by remember { mutableStateOf(tab?.trigger) }
    val others = tabs.filter { it.page != page }
    // Tabbed: the place in the bar; a cross: the page.
    var place by remember { mutableStateOf(if (tabbed) tabs.indexOfFirst { it.page == page }.takeIf { it >= 0 } ?: tabs.size else page) }
    SettingsSheet(
        title = if (tab == null) stringResource(R.string.canvas_tab_new) else tab.label(context),
        onDismiss = onDismiss,
        actions = {
            if (canRemove) SheetButton(stringResource(R.string.canvas_tab_remove), primary = false, onClick = onRemove)
            SheetButton(stringResource(if (tab == null) R.string.canvas_tab_create else R.string.canvas_tab_done)) {
                val typed = name.trim()
                val own = when {
                    typed.isNotEmpty() -> typed.takeIf { it != preset?.let { p -> context.getString(p.labelRes) } && it != context.getString(DashboardStore.nameRes(page)) }
                    else -> null
                }
                onSave(CanvasTab(page, preset, own, trigger), place)
            }
        }
    ) {
        Text(stringResource(R.string.canvas_tab_name), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TabPreset.entries.forEach { p ->
                SheetChip(stringResource(p.labelRes), p.icon, selected = p == preset) {
                    preset = p
                    name = context.getString(p.labelRes)
                }
            }
        }
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(16) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors()
        )
        val label = name.trim().ifEmpty { preset?.let { context.getString(it.labelRes) } ?: context.getString(DashboardStore.nameRes(page)) }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(if (tabbed) R.string.canvas_tab_place else R.string.canvas_tab_where),
            color = DashColors.TextSecondary, style = MaterialTheme.typography.labelLarge
        )
        if (tabbed) {
            PlaceSlots(others.map { it.label(context) }, place, label) { place = it }
        } else {
            CrossPicker(others, place, label, pagesWithTiles, swapTaken = tab != null) { place = it }
        }
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.canvas_tab_when), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
        // A moment another dashboard already has says so on its chip, and is asked for before it changes hands.
        var takeFrom by remember { mutableStateOf<Pair<TabTrigger, CanvasTab>?>(null) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TabTrigger.entries.forEach { t ->
                val holder = others.firstOrNull { it.trigger == t }
                val text = if (holder != null && t != trigger) stringResource(R.string.canvas_when_taken, stringResource(t.labelRes), holder.label(context))
                    else stringResource(t.labelRes)
                SheetChip(text, null, selected = t == trigger) {
                    when {
                        trigger == t -> trigger = null
                        holder != null -> takeFrom = t to holder
                        else -> trigger = t
                    }
                }
            }
        }
        Text(stringResource(R.string.canvas_tab_when_hint), color = DashColors.Muted, style = MaterialTheme.typography.bodySmall)
        takeFrom?.let { (t, holder) ->
            ConfirmDialog(
                title = stringResource(R.string.canvas_when_take_title, stringResource(t.labelRes), holder.label(context)),
                body = stringResource(R.string.canvas_when_take_body),
                action = stringResource(R.string.canvas_when_take),
                onConfirm = { trigger = t; takeFrom = null },
                onDismiss = { takeFrom = null }
            )
        }
    }
}

/** An app of the bar: its place among the others, or out of the bar. */
@Composable
internal fun DashBarAppSheet(app: AppEntry, otherApps: List<String>, place: Int, onSave: (Int) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    ParkedOnly(onDismiss)
    var at by remember { mutableIntStateOf(place) }
    SettingsSheet(
        title = app.label,
        onDismiss = onDismiss,
        actions = {
            SheetButton(stringResource(R.string.canvas_bar_app_remove), primary = false, onClick = onRemove)
            SheetButton(stringResource(R.string.canvas_tab_done)) { onSave(at) }
        }
    ) {
        Text(stringResource(R.string.canvas_tab_place), color = DashColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
        PlaceSlots(otherApps, at, app.label) { at = it }
    }
}

/**
 * [names] in order with a gap before, between and after them; the gap at
 * [place] holds [label] (what is being placed), a tap on another moves it there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceSlots(names: List<String>, place: Int, label: String, onPlace: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0..names.size) {
            if (i == place) {
                SheetChip(label, null, selected = true) {}
            } else {
                val tap = rememberTapFeedback()
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button) { tap(); onPlace(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(20.dp).border(2.dp, DashColors.Muted, CircleShape))
                }
            }
            if (i < names.size) {
                Box(
                    modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(DashColors.CardHi).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) { Text(names[i], color = DashColors.TextSecondary, maxLines = 1) }
            }
        }
    }
}

/**
 * The seven pages as their cross; [place] holds [label]. The other
 * dashboards show their names; a spot without one is dotted (a dot inside
 * when it already holds tiles). A free spot takes it; a taken one only when
 * [swapTaken] (the two trade places).
 */
@Composable
private fun CrossPicker(others: List<CanvasTab>, place: Int, label: String, pagesWithTiles: Set<Int>, swapTaken: Boolean, onPlace: (Int) -> Unit) {
    val context = LocalContext.current
    val cellW = 118.dp
    val cellH = 52.dp
    val gap = 8.dp
    val centreCol = DashboardStore.ROW.indexOf(DashboardStore.CENTER)
    val cells = DashboardStore.COLUMN.mapIndexed { row, p -> Triple(p, centreCol, row) } +
        DashboardStore.ROW.mapIndexedNotNull { col, p -> if (p == DashboardStore.CENTER) null else Triple(p, col, DashboardStore.COLUMN_HOME) }
    Box(modifier = Modifier.size(width = cellW * 3 + gap * 2, height = cellH * 5 + gap * 4)) {
        cells.forEach { (p, col, row) ->
            val taken = others.firstOrNull { it.page == p }
            val here = p == place
            val tap = rememberTapFeedback()
            val shape = RoundedCornerShape(14.dp)
            val selectable = !here && (taken == null || swapTaken)
            Box(
                modifier = Modifier
                    .offset(x = (cellW + gap) * col, y = (cellH + gap) * row)
                    .size(cellW, cellH)
                    .clip(shape)
                    .then(
                        when {
                            here -> Modifier.background(DashColors.Accent.copy(alpha = 0.18f)).border(2.dp, DashColors.Accent, shape)
                            taken != null -> Modifier.background(DashColors.CardHi)
                            else -> Modifier.border(1.5.dp, DashColors.Muted, shape)
                        }
                    )
                    .then(if (selectable) Modifier.clickable(role = Role.Button) { tap(); onPlace(p) } else Modifier),
                contentAlignment = Alignment.Center
            ) {
                when {
                    here -> Text(label, color = DashColors.Accent, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                    taken != null -> Text(taken.label(context), color = DashColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
                    p in pagesWithTiles -> Box(Modifier.size(8.dp).clip(CircleShape).background(DashColors.Muted))
                }
            }
        }
    }
}

@Composable
private fun SheetChip(text: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val ink = if (selected) DashColors.Accent else DashColors.TextPrimary
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) DashColors.Accent.copy(alpha = 0.18f) else DashColors.CardHi)
            .then(if (selected) Modifier.border(2.dp, DashColors.Accent, RoundedCornerShape(24.dp)) else Modifier)
            .clickable { tap(); onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/**
 * The app under the Canvas dashboard, full size beside the rail (on the
 * driver's side, [railOnRight]); the page's colour behind the rail. It gets
 * every touch no tile takes: the tabs' pages have nothing of their own
 * between the tiles, so Compose hands those touches on to it.
 */
@Composable
internal fun CanvasAppBackdrop(packageName: String, railOnRight: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val label = remember(packageName) {
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }
    Box(modifier = modifier.background(DashColors.Background)) {
        EmbeddedAppCard(
            packageName, label,
            Modifier.fillMaxSize().padding(start = if (railOnRight) 0.dp else CANVAS_RAIL_SPACE, end = if (railOnRight) CANVAS_RAIL_SPACE else 0.dp)
        )
    }
}

/**
 * Whether [packageName] is the app under the Canvas dashboard right now. An
 * app runs once, on one display: a tile of it on a tab would wait for a
 * picture the wallpaper keeps, so the tile says where the app is instead.
 */
@Composable
internal fun underDashboard(packageName: String): Boolean {
    val under by CanvasTabs.mapApp.collectAsState()
    return CanvasTabs.railShown && under == packageName
}

/**
 * Settings → Look, with Canvas: what lies under the dashboard, the built-in
 * map or a navigation app running inside Dashwheel. Only where Android lets
 * Dashwheel open an app on its own display (EmbeddedApp.allowed), and only
 * the navigation apps installed.
 */
@Composable
internal fun CanvasMapSetting() {
    val context = LocalContext.current
    val allowed = remember { EmbeddedApp.allowed(context) }
    if (!allowed) return
    CanvasTabs.load(context) // returns at once after the first call
    val chosen by CanvasTabs.mapApp.collectAsState()
    val apps = remember {
        NavDirections.PACKAGES.mapNotNull { pkg ->
            runCatching { pkg to context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        }.toMap()
    }
    if (apps.isEmpty()) return
    SettingsSection(stringResource(R.string.canvas_map_title))
    SegmentedSwitch(
        options = listOf<String?>(null) + apps.keys,
        chosen = chosen,
        icon = { if (it == null) Icons.Filled.Layers else Icons.Filled.Navigation },
        title = { pkg -> pkg?.let { apps[it] } ?: stringResource(R.string.canvas_map_builtin) },
        onChoose = { CanvasTabs.setMapApp(context, it) }
    )
    SwitchHint(stringResource(if (chosen == null) R.string.canvas_map_builtin_hint else R.string.canvas_map_app_hint))
    Spacer(Modifier.height(20.dp))
}
