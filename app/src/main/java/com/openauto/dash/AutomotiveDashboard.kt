@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import kotlin.math.abs
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.width
import kotlin.math.min
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.compose.ReportDrawn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource

/** How many layout steps Undo can walk back while arranging. */
internal const val MAX_UNDO = 30

/**
 * Gap between the docked Maps window and the divider. The system claims the
 * 30 dp band around a freeform window as its resize handle (WindowManager's
 * RESIZE_HANDLE_WIDTH_IN_DP) and swallows drags that start in it; the divider
 * has to start outside that band to receive its drag at all.
 */
private val DOCK_RESIZE_CLEARANCE = 32.dp

/**
 * Simple car launcher: three swipeable "virtual desktop" dashboards. Each page
 * is a grid the user fills with app shortcuts and widgets (our built-in Maps /
 * media / OBD cards, or any real Android app-widget) via the "+" tile. A single
 * Apps button opens the full app drawer. Nothing launches automatically.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AutomotiveDashboard(inSplitMode: Boolean = false) {
    val context = LocalContext.current
    // Tells the system the launcher is up once the dashboard has composed: the
    // start-up time it records (and the profile it compiles for) ends here.
    ReportDrawn()
    var themeMode by remember { mutableStateOf(DashThemeStore.load(context)) }
    var appearance by remember { mutableStateOf(DashThemeStore.loadAppearance(context)) }
    var effects by remember { mutableStateOf(DashThemeStore.loadEffects(context)) }
    var barAutoHide by remember { mutableStateOf(DashThemeStore.loadBarAutoHide(context)) }
    var barHideSeconds by remember { mutableIntStateOf(DashThemeStore.loadBarHideSeconds(context)) }
    val barState = rememberBarAutoHideState()
    val barRevealTap = rememberTapFeedback()
    val themeState = ThemeState(
        mode = themeMode, appearance = appearance, effects = effects,
        onMode = { themeMode = it; DashThemeStore.save(context, it) },
        onAppearance = { appearance = it; DashThemeStore.saveAppearance(context, it) },
        onEffects = { effects = it; DashThemeStore.saveEffects(context, it) },
        barAutoHide = barAutoHide, barHideSeconds = barHideSeconds,
        onBarAutoHide = { barAutoHide = it; DashThemeStore.saveBarAutoHide(context, it) },
        onBarHideSeconds = { barHideSeconds = it; DashThemeStore.saveBarHideSeconds(context, it) }
    )
    var layout by remember { mutableStateOf(DashLayoutStore.load(context)) }
    // Canvas over an app (Google Maps as the wallpaper): the pages become the rail's tabs (CanvasTabs.kt).
    CanvasTabs.load(context) // returns at once after the first call
    val mapApp by CanvasTabs.mapApp.collectAsState()
    val barLists by CanvasTabs.lists.collectAsState()
    val barApps by CanvasTabs.apps.collectAsState()
    val embedAllowed = remember { EmbeddedApp.allowed(context) }
    val tabsMode = themeMode == DashThemeMode.CANVAS && mapApp != null && embedAllowed
    val tabsModeState = rememberUpdatedState(tabsMode)
    // Kept as a State and read only by the two panes it sizes: dragging the
    // divider writes it every frame, and a read here would recompose everything.
    val dockFraction = remember { mutableFloatStateOf(DashLayoutStore.loadDockFraction(context)) }
    // The half-width dashboard beside a Maps dock keeps its own arrangement,
    // and so does each screen shape (ScreenShape.layoutPrefix).
    // The Canvas theme keeps its own too (DashboardStore.CANVAS_VARIANT).
    fun variantOf(l: DashLayout) = if (tabsModeState.value) ScreenShape.layoutPrefix + DashboardStore.TABS_VARIANT
        else ScreenShape.layoutPrefix +
        (if (themeMode == DashThemeMode.CANVAS) DashboardStore.CANVAS_VARIANT else "") +
        if (l == DashLayout.GRID) "" else "_half"
    // Read when called, never captured: gesture handlers outlive a composition,
    // and a stale arrangement would save one layout's tiles over the other's.
    fun variant() = variantOf(layout)
    /** The arrangement of the other layout (full width / beside the Maps dock) on this screen shape. */
    fun otherVariant() = variantOf(if (layout == DashLayout.GRID) DashLayout.MAPS_LEFT else DashLayout.GRID)
    var showTemplates by remember { mutableStateOf(false) }
    // The Settings screen, on the tab it was opened to; null while closed. A
    // dashboard rebuilt by a turn of the screen made in Settings opens there again.
    var settingsTab by remember { mutableStateOf(if (ScreenShape.settingsWanted()) SettingsTab.DISPLAY else null) }
    /** Closes Settings: its open sheet leaves first, and a screen direction on trial stays. */
    fun closeSettings() {
        if (settingsTab == null) return
        OpenSheet.dismiss()
        ScreenShape.keep()
        settingsTab = null
    }
    DashColors.Sync(themeMode, appearance, effects)
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val mediaController = remember { CarMediaController(context) }
    val updateManager = remember { UpdateManager(context) }
    // The live readings stay States: reading them here would recompose the whole
    // dashboard on every OBD sample. Tiles read them where they draw them.
    val obd = ObdBluetoothManager.data.collectAsState()
    // The link's state too: it flips at every dial while the adapter is out of
    // reach, and read here that would recompose the whole dashboard each time.
    val obdConnection = ObdBluetoothManager.connectionState.collectAsState()
    // Demo mode (Settings → Advanced) plays its own made-up tracks in place of the real session.
    val demoState = DemoMode.active.collectAsState()
    val demoOn by demoState
    val realMedia = mediaController.mediaState.collectAsState()
    val demoMedia = DemoMode.media.collectAsState()
    val media = remember { derivedStateOf { if (demoState.value) demoMedia.value else realMedia.value } }
    val updateStatus by updateManager.status.collectAsState()

    // Enumerating every launchable app (labels + icons) is the slowest part of
    // a cold start, so it runs on IO; tiles render their placeholder until then.
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    // Bumped when an app is installed, removed or updated; the list is read again.
    var appsChanged by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val stop = AppLauncher.watchPackages(context) { appsChanged++ }
        onDispose { stop() }
    }
    LaunchedEffect(appsChanged) {
        // An update fires several callbacks in a row: read the list once they settle.
        if (appsChanged > 0) delay(1_000)
        apps = withContext(Dispatchers.IO) { AppLauncher.loadApps(context) }
    }
    val appsByPackage = remember(apps) { apps.associateBy { it.packageName } }

    /**
     * The pages for the theme and layout in use. The first time Canvas is
     * used, its pages start as the driver's own with the Canvas home in the
     * middle, rather than as the defaults.
     */
    fun loadPages(): List<List<DashboardItem>> {
        val v = variant()
        if (themeMode == DashThemeMode.CANVAS && !tabsModeState.value && !DashboardStore.exists(context, v)) {
            val own = v.replace(DashboardStore.CANVAS_VARIANT, "")
            if (DashboardStore.exists(context, own)) {
                val seeded = DashboardStore.withCanvasHome(DashboardStore.load(context, own))
                DashboardStore.save(context, seeded, v)
                return seeded
            }
        }
        return DashboardStore.load(context, v)
    }
    var pages by remember { mutableStateOf(loadPages()) }
    // The dashboard bar's dashboards for this layout (CanvasTabs.kt): the rail's
    // tabs over an app, the named pages of the cross otherwise.
    val barTabs = CanvasTabs.tabsFor(CanvasTabs.keyFor(variant()), barLists, pages)
    val barTabsState = rememberUpdatedState(barTabs)
    // Over an app, the tab on screen, as a page.
    var tabPage by remember { mutableIntStateOf(barTabs.firstOrNull()?.page ?: 0) }
    // The other arrangement's window apps, cached (see windowAppsEverywhere); null = read again.
    var otherLayoutWindows by remember { mutableStateOf<Set<String>?>(null) }
    // Layout snapshots for Undo while arranging (newest last, capped).
    var history by remember { mutableStateOf<List<List<List<DashboardItem>>>>(emptyList()) }

    // Switching to Canvas or away from it swaps in that theme's own pages,
    // and so does putting an app under it or taking it away (the tabs' pages).
    var pagesForCanvas by remember { mutableStateOf((themeMode == DashThemeMode.CANVAS) to tabsMode) }
    LaunchedEffect(themeMode, tabsMode) {
        val canvas = (themeMode == DashThemeMode.CANVAS) to tabsMode
        if (canvas == pagesForCanvas) return@LaunchedEffect
        pagesForCanvas = canvas
        pages = loadPages()
        tabPage = CanvasTabs.tabsFor(CanvasTabs.keyFor(variant()), CanvasTabs.lists.value, pages).firstOrNull()?.page ?: 0
        history = emptyList()
        otherLayoutWindows = null
    }

    val shellAccess = shellAccess()
    // The default layout is built before the shell is known. Once it is, and
    // nothing was saved yet, build it again: with the CANbox tiles under root,
    // without them elsewhere (see DashboardStore.defaultPages).
    LaunchedEffect(shellAccess) {
        if (shellAccess != PrivilegedShell.Access.UNKNOWN && !DashboardStore.exists(context, variant())) {
            pages = DashboardStore.load(context, variant())
        }
    }

    /** Switches layout, loading that layout's own arrangement (seeded from the current one the first time). */
    val switchLayout: (DashLayout) -> Unit = { next ->
        if (next != layout) {
            val nextVariant = variantOf(next)
            if (!DashboardStore.exists(context, nextVariant)) DashboardStore.save(context, pages, nextVariant)
            layout = next
            DashLayoutStore.save(context, next)
            pages = DashboardStore.load(context, nextVariant)
            history = emptyList()
            otherLayoutWindows = null
        }
    }
    // (page, index) of the launch bar whose apps are being edited.
    var launchBarEditor by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // Pages 0-2 swipe sideways; the middle one also swipes up/down (see DashboardStore.COLUMN).
    // Home is the centre of the cross, so that is where the launcher starts.
    val pagerState = rememberPagerState(initialPage = DashboardStore.CENTER, pageCount = { DashboardStore.ROW.size })
    val columnState = rememberPagerState(
        initialPage = DashboardStore.COLUMN_HOME,
        pageCount = { DashboardStore.COLUMN.size }
    )
    /** The dashboard on screen, as an index into pages. */
    val currentPage by remember {
        derivedStateOf {
            when {
                tabsModeState.value -> tabPage
                pagerState.currentPage == DashboardStore.CENTER -> DashboardStore.COLUMN[columnState.currentPage]
                else -> DashboardStore.ROW[pagerState.currentPage]
            }
        }
    }
    /** Brings [page] on screen: back to the middle row first when it is above or below, and the reverse. Over an app, its tab. */
    fun showPage(page: Int) {
        if (tabsModeState.value) {
            tabPage = page
            return
        }
        scope.launch {
            val row = DashboardStore.COLUMN.indexOf(page)
            if (row >= 0 && page != DashboardStore.CENTER) {
                pagerState.animateScrollToPage(DashboardStore.CENTER)
                columnState.animateScrollToPage(row)
            } else {
                columnState.animateScrollToPage(DashboardStore.COLUMN_HOME)
                pagerState.animateScrollToPage(DashboardStore.ROW.indexOf(page))
            }
        }
    }

    /** Home: the middle of the cross, or the first tab over an app. */
    fun showHome() = showPage(if (tabsModeState.value) barTabsState.value.firstOrNull()?.page ?: 0 else DashboardStore.CENTER)

    // After a page change the floating cross shows for a moment, then fades.
    var pageIndicatorShown by remember { mutableStateOf(false) }
    var pageIndicatorFor by remember { mutableIntStateOf(currentPage) }
    LaunchedEffect(currentPage) {
        if (currentPage != pageIndicatorFor) {
            pageIndicatorFor = currentPage
            // The tabs say which one shows themselves.
            if (tabsModeState.value) return@LaunchedEffect
            pageIndicatorShown = true
            delay(PAGE_INDICATOR_MS)
            pageIndicatorShown = false
        }
    }
    var showAllApps by remember { mutableStateOf(false) }
    var showSplitPicker by remember { mutableStateOf(false) }
    var showSplitEnable by remember { mutableStateOf(false) }
    var blockPagerSwipe by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // The dashboard bar's sheets: a dashboard's (by index, -1 for a new one), an
    // app's (by index), the rail's "dashboard or app", the app picker.
    var dashSheet by remember { mutableStateOf<Int?>(null) }
    var appSheet by remember { mutableStateOf<Int?>(null) }
    var showBarChooser by remember { mutableStateOf(false) }
    var pickBarApp by remember { mutableStateOf(false) }
    // A dashboard can come up by itself: as the car sets off, parks, or finds a new fault.
    FollowTabTriggers(barTabs) { trigger ->
        if (!editing && dashSheet == null) CanvasTabs.tabFor(barTabsState.value, trigger)?.let { showPage(it.page) }
    }
    // The Home key: close whatever is open and come back to the middle of the cross.
    val homePressed by MainActivity.homePressed.collectAsState()
    LaunchedEffect(homePressed) {
        if (homePressed > 0L) {
            showAllApps = false
            closeSettings()
            editing = false
            showHome()
        }
    }
    // A learned steering wheel button stepping through the dashboards: the
    // tabs over an app, else the pages of the middle row.
    val dashboardStep by MainActivity.dashboardStep.collectAsState()
    LaunchedEffect(dashboardStep) {
        val step = dashboardStep.second
        if (dashboardStep.first == 0L || step == 0) return@LaunchedEffect
        if (tabsModeState.value) {
            val tabs = barTabsState.value
            val at = tabs.indexOfFirst { it.page == tabPage }.coerceAtLeast(0)
            tabs.getOrNull(CanvasTabs.stepped(at, step, tabs.size))?.let { tabPage = it.page }
        } else if (columnState.currentPage != DashboardStore.COLUMN_HOME) {
            showPage(DashboardStore.CENTER)
        } else {
            pagerState.animateScrollToPage(CanvasTabs.stepped(pagerState.currentPage, step, DashboardStore.ROW.size))
        }
    }
    // The same up and down the middle column, going round; over an app, through its tabs.
    val dashboardClimb by MainActivity.dashboardClimb.collectAsState()
    LaunchedEffect(dashboardClimb) {
        val step = dashboardClimb.second
        if (dashboardClimb.first == 0L || step == 0) return@LaunchedEffect
        if (tabsModeState.value) {
            val tabs = barTabsState.value
            val at = tabs.indexOfFirst { it.page == tabPage }.coerceAtLeast(0)
            tabs.getOrNull(CanvasTabs.stepped(at, step, tabs.size))?.let { tabPage = it.page }
        } else {
            val at = if (pagerState.currentPage == DashboardStore.CENTER) columnState.currentPage else DashboardStore.COLUMN_HOME
            showPage(DashboardStore.COLUMN[CanvasTabs.stepped(at, step, DashboardStore.COLUMN.size)])
        }
    }
    // A learned steering wheel button asking for the app drawer (SteeringWheelActions.kt).
    val openAppsRequested by MainActivity.openAppsRequested.collectAsState()
    LaunchedEffect(openAppsRequested) {
        if (openAppsRequested > 0L) {
            closeSettings()
            showAllApps = true
        }
    }
    var hasMediaAccess by remember { mutableStateOf(CarMediaController.hasNotificationAccess(context)) }
    // The demo's music and directions need no access grant.
    val mediaAccess = hasMediaAccess || demoOn
    var showDevicePicker by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // "+" add flow: the page a tile is being added to, and the sheet that offers them (AddSheet.kt).
    var addTargetPage by remember { mutableIntStateOf(-1) }
    var showAddSheet by remember { mutableStateOf(false) }
    var layoutNotice by remember { mutableStateOf<String?>(null) }
    // (page, tile index) whose panel is open while arranging (TileOptions.kt),
    // where that tile is and where the pages are, in root coordinates.
    var tileOptions by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var selectedTileBounds by remember { mutableStateOf<Pair<Pair<Int, Int>, Rect>?>(null) }
    var pagesArea by remember { mutableStateOf(Rect.Zero) }
    // Asked before a page is cleared, and before a template replaces every page.
    var confirmReset by remember { mutableStateOf(false) }
    var confirmTemplate by remember { mutableStateOf<DashTemplate?>(null) }
    // The setup (SetupScreen.kt): its step while open, null while closed. A
    // fresh install starts on it; Settings and the bar's pill reopen it.
    var setupDone by remember { mutableStateOf(SetupStore.isDone(context)) }
    var setupPillOff by remember { mutableStateOf(SetupStore.pillOff(context)) }
    var setupStep by remember { mutableStateOf(if (setupDone) null else SetupStep.CAR) }
    var showCarSettings by remember { mutableStateOf(false) }
    // Bumped on every return to the launcher: an access granted in the system settings shows at once.
    var accessGeneration by remember { mutableIntStateOf(0) }

    // The Back key (the head unit's button, or a wheel button taught to it):
    // closes what is open, top-most first, then heads back to Home. Dialogs
    // are windows of their own and take Back themselves before this runs.
    val offHome = currentPage != (if (tabsMode) barTabs.firstOrNull()?.page ?: 0 else DashboardStore.CENTER)
    BackHandler(enabled = setupStep != null || showAllApps || settingsTab != null || showAddSheet || tileOptions != null || editing || offHome) {
        when {
            setupStep != null -> {
                SetupStore.markDone(context); setupDone = true
                setupStep = null
            }
            showAddSheet -> showAddSheet = false
            showAllApps -> showAllApps = false
            settingsTab != null -> closeSettings()
            tileOptions != null -> tileOptions = null
            editing -> editing = false
            else -> showHome()
        }
    }

    // System-app install (root) — unlocks embedding the real Google Maps app.
    var showSystemDialog by remember { mutableStateOf(false) }

    // The drive lock (DriveLock.kt): while the car moves, arranging, settings
    // and pickers wait. Anything open when it engages closes; a locked tap
    // shows the notice chip for a moment instead of doing nothing.
    var lockWhileMoving by remember { mutableStateOf(DriveLockStore.load(context)) }
    val moving by rememberMoving(lockWhileMoving, demoOn)
    var lockNoticeAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(lockNoticeAt) {
        if (lockNoticeAt > 0L) {
            delay(LOCK_NOTICE_MS)
            lockNoticeAt = 0L
        }
    }
    /** Runs [action] now, or shows the parked-only notice while moving. */
    fun whenParked(action: () -> Unit) {
        if (moving) lockNoticeAt = System.currentTimeMillis() else action()
    }
    // The same lock for the tiles' own dialogs (LocalDriveLock, DriveLock.kt).
    val driveLock = remember(moving) { DriveLockState(moving) { lockNoticeAt = System.currentTimeMillis() } }
    LaunchedEffect(moving) {
        if (moving) {
            editing = false
            closeSettings()
            showTemplates = false
            showSystemDialog = false
            showAddSheet = false
            tileOptions = null
            confirmReset = false
            confirmTemplate = null
            setupStep = null
            showCarSettings = false
            showSplitPicker = false
            showSplitEnable = false
            showDevicePicker = false
            launchBarEditor = null
        }
    }
    // The panel belongs to a tile of the page on screen, while arranging.
    LaunchedEffect(editing, currentPage) {
        if (tileOptions?.first != currentPage || !editing) tileOptions = null
    }

    // Docked app windows sit above dialogs and menus on this head unit. Those
    // report where they are (keepClearOfWindows), so only a window they overlap
    // steps aside. The app drawer covers everything, so it sends every window
    // aside. A page swipe, sideways or up/down, must take the pages' windows
    // along at once, but the Maps dock beside the pages does not move with them
    // and stays put.
    val fullScreenSheet = showAllApps || settingsTab != null || showAddSheet || setupStep != null
    LaunchedEffect(fullScreenSheet) { PipAnchor.steppedAside.value = fullScreenSheet }
    // The floating bar would cover the bottom of the edit bar, Settings and the
    // app drawer: it steps down while they are open (a swipe up still brings
    // it), and comes back as they close, since it is what opened them.
    val barCovers = editing || fullScreenSheet
    LaunchedEffect(barCovers) {
        if (barCovers) barState.visible.targetState = false else barState.reveal()
    }
    val pageSwiping = pagerState.isScrollInProgress || columnState.isScrollInProgress
    LaunchedEffect(pageSwiping) { PipAnchor.pageSwiping.value = pageSwiping }

    /** Apps shown in a window by [items]' tiles. */
    fun tileWindowApps(items: List<DashboardItem>): Set<String> = items.mapNotNullTo(HashSet()) {
        when {
            it is DashboardItem.BuiltinWidget && it.kind == BuiltinKind.PIP_ANCHOR -> PipAnchor.MAPS_PACKAGE
            it is DashboardItem.AppWindow && !it.inside -> it.packageName
            else -> null
        }
    }

    /** Apps shown in a window by [items]' tiles, plus Maps when a layout docks it beside the pages. */
    fun windowApps(items: List<DashboardItem>): Set<String> =
        tileWindowApps(items) + if (layout != DashLayout.GRID) setOf(PipAnchor.MAPS_PACKAGE) else emptySet()

    /**
     * Window apps of every page, in both arrangements (a tile in the other layout
     * still owns its window). The other arrangement is read from storage once and
     * kept until it can change (a layout switch or a template), not on every swipe.
     */
    fun windowAppsEverywhere(): Set<String> {
        val other = otherLayoutWindows ?: run {
            val variant = otherVariant()
            (if (DashboardStore.exists(context, variant)) tileWindowApps(DashboardStore.load(context, variant).flatten()) else emptySet())
                .also { otherLayoutWindows = it }
        }
        return windowApps(pages.flatten()) + other
    }

    // As soon as the current page changes (mid-swipe), clear the windows whose
    // tile is not on the new page; waiting for the old page to be disposed left
    // a strip of the window visible for a few seconds after the swipe. They are
    // parked aside, still running, so a navigation or a song goes on.
    LaunchedEffect(currentPage, pages, layout) {
        PipAnchor.placedPackages.value = windowAppsEverywhere()
        PipAnchor.stashAllExcept(context, windowApps(pages.getOrNull(currentPage).orEmpty()))
    }
    // An app inside a tile runs until the last tile showing it goes, in either
    // arrangement; then its display is closed and the app with it (EmbeddedApp).
    LaunchedEffect(pages, layout, tabsMode, mapApp) {
        val other = withContext(Dispatchers.IO) {
            val variant = otherVariant()
            if (DashboardStore.exists(context, variant)) DashboardStore.load(context, variant).flatten() else emptyList()
        }
        val inside = (pages.flatten() + other).mapNotNullTo(HashSet()) {
            when {
                it is DashboardItem.BuiltinWidget && it.kind == BuiltinKind.MAPS_INSIDE -> EmbeddedApp.MAPS_PACKAGE
                it is DashboardItem.AppWindow && it.inside -> it.packageName
                else -> null
            }
        }
        // The app under Canvas runs as long as it is there.
        if (tabsMode) mapApp?.let { inside += it }
        EmbeddedApp.releaseUnless(inside)
    }
    var rootChecked by remember { mutableStateOf(false) }
    var rootAvailable by remember { mutableStateOf(false) }
    var systemBusy by remember { mutableStateOf(false) }
    var systemInstalled by remember { mutableStateOf(false) }
    var systemMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSystemDialog) {
        if (showSystemDialog && !rootChecked) {
            rootAvailable = withContext(Dispatchers.IO) { SystemInstaller.isRootAvailable() }
            rootChecked = true
        }
    }

    // The privileged /system install used to run automatically on first launch
    // so the Maps tile could embed Google Maps. Embedding turned out to need
    // platform signing, so priv-app status buys nothing: the install is now
    // only offered from the system dialog (Build icon), never run unasked.

    /** Replaces one page, remembering the previous layout for Undo, and persists. */
    fun mutatePage(page: Int, transform: (List<DashboardItem>) -> List<DashboardItem>) {
        val before = pages
        val after = before.mapIndexed { i, list -> if (i == page) transform(list) else list }
        if (after == before) return
        history = (history + listOf(before)).takeLast(MAX_UNDO)
        pages = after
        DashboardStore.save(context, pages, variant())
    }

    /** Adds at the first free cell; returns the new tile's index, or -1 when the page is full. */
    fun addItemAt(page: Int, item: DashboardItem): Int {
        val list = pages.getOrNull(page) ?: return -1
        val cell = DashboardStore.firstFreeCell(list, item.w, item.h)
            ?: DashboardStore.firstFreeCell(list, item.minW(), item.minH())
        if (cell == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_space)
            return -1
        }
        val fits = DashboardStore.canPlace(list, null, cell.first, cell.second, item.w, item.h)
        val placed = if (fits) item.withCell(cell.first, cell.second, item.w, item.h)
            else item.withCell(cell.first, cell.second, item.minW(), item.minH())
        mutatePage(page) { it + placed }
        return list.size
    }

    /** Add only when the tile fits; a full page must never create a hidden overlap. */
    fun addItem(page: Int, item: DashboardItem): Boolean = addItemAt(page, item) >= 0

    /** Removing an app's last window tile ends the tile's keep-it-open duty. */
    fun releaseMapsAnchorIfGone() {
        // Both arrangements count: a window tile that exists only in the other
        // layout must keep its app's keep-open intent.
        val keep = windowAppsEverywhere()
        PipAnchor.placedPackages.value = keep
        PipAnchor.releaseAutoOpenExcept(context, keep)
    }
    // A layout switch adds or removes the Maps dock; redo the keep-open bookkeeping.
    LaunchedEffect(layout) { releaseMapsAnchorIfGone() }

    fun removeAt(page: Int, index: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        if (item is DashboardItem.SystemWidget) WidgetHostHolder.delete(context, item.appWidgetId)
        mutatePage(page) { list -> list.filterIndexed { i, _ -> i != index } }
        releaseMapsAnchorIfGone()
    }

    /** Moves the tile at [index] on [page] to the first free cell of [target]; a full target page says so. */
    fun moveToPage(page: Int, index: Int, target: Int) {
        val item = pages.getOrNull(page)?.getOrNull(index) ?: return
        val list = pages.getOrNull(target) ?: return
        val cell = DashboardStore.firstFreeCell(list, item.w, item.h)
            ?: DashboardStore.firstFreeCell(list, item.minW(), item.minH())
        if (cell == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_space)
            return
        }
        val fits = DashboardStore.canPlace(list, null, cell.first, cell.second, item.w, item.h)
        val placed = if (fits) item.withCell(cell.first, cell.second, item.w, item.h)
            else item.withCell(cell.first, cell.second, item.minW(), item.minH())
        history = (history + listOf(pages)).takeLast(MAX_UNDO)
        pages = pages.mapIndexed { i, l ->
            when (i) {
                page -> l.filterIndexed { j, _ -> j != index }
                target -> l + placed
                else -> l
            }
        }
        DashboardStore.save(context, pages, variant())
        showPage(target)
    }

    /** Replaces the tile at [index] (same cell) with [item], e.g. an edited launch bar. */
    fun updateItem(page: Int, index: Int, item: DashboardItem) {
        mutatePage(page) { list ->
            list.mapIndexed { i, old -> if (i == index) item.withCell(old.x, old.y, old.w, old.h) else old }
        }
    }

    // Move a tile's top-left to grid cell (x, y), keeping its span. A tile in
    // the way is swapped or nudged aside; only a page with no room refuses.
    fun moveCell(page: Int, index: Int, x: Int, y: Int) {
        val list = pages.getOrNull(page) ?: return
        val resolved = DashboardStore.moveResolving(list, index, x, y)
        if (resolved == null) {
            layoutNotice = context.getString(R.string.dash_notice_no_room_to_move)
            return
        }
        mutatePage(page) { resolved }
    }

    // Resize a tile to span w x h cells, keeping its top-left. Width/height are
    // capped at the grid edge from the tile's position so it grows in place
    // instead of being shoved left/up to make a too-big span fit.
    fun resizeCell(page: Int, index: Int, w: Int, h: Int) {
        val list = pages.getOrNull(page) ?: return
        val it = list.getOrNull(index) ?: return
        val cw = w.coerceIn(it.minW(), GRID_COLS - it.x)
        val ch = h.coerceIn(it.minH(), GRID_ROWS - it.y)
        if (!DashboardStore.canPlace(list, index, it.x, it.y, cw, ch)) {
            layoutNotice = context.getString(R.string.dash_notice_resize_overlap)
            return
        }
        mutatePage(page) { l -> l.mapIndexed { i, t -> if (i == index) t.withCell(t.x, t.y, cw, ch) else t } }
    }

    /** Draws the tile at [index] with its text and icons [zoom] times their size. */
    fun zoomTile(page: Int, index: Int, zoom: Float) {
        mutatePage(page) { list -> list.mapIndexed { i, t -> if (i == index) t.withZoom(zoom) else t } }
    }

    /** Restores the layout from before the last add / move / resize / remove. */
    fun undo() {
        val previous = history.lastOrNull() ?: return
        history = history.dropLast(1)
        pages = previous
        DashboardStore.save(context, pages, variant())
    }

    /** Clears one page (releasing any hosted app-widgets); Undo brings it back. */
    fun resetPage(page: Int) {
        pages.getOrNull(page)?.filterIsInstance<DashboardItem.SystemWidget>()
            ?.forEach { WidgetHostHolder.delete(context, it.appWidgetId) }
        mutatePage(page) { emptyList() }
        releaseMapsAnchorIfGone()
    }

    /** Replaces every page at once (a template); one Undo step brings them all back. */
    fun mutateAll(after: List<List<DashboardItem>>) {
        if (after == pages) return
        history = (history + listOf(pages)).takeLast(MAX_UNDO)
        pages = after
        DashboardStore.save(context, pages, variant())
    }

    val screenConfig = LocalConfiguration.current
    /** The car and screen a template is placed for, in the full-width ([half] false) or docked arrangement. */
    fun templateScreen(half: Boolean): TemplateScreen = TemplateScreen.of(
        // Upright, the Maps dock sits above or below the pages and takes height, not width.
        pageWidthDp = screenConfig.screenWidthDp * if (half && !ScreenShape.vertical) 1f - dockFraction.floatValue else 1f,
        // Roughly what the bars leave the grid.
        pageHeightDp = screenConfig.screenHeightDp * (if (ScreenShape.vertical) 0.9f else 0.8f) *
            if (half && ScreenShape.vertical) 1f - dockFraction.floatValue else 1f,
        obdPaired = ObdBluetoothManager.savedDeviceAddress() != null || obdConnection.value == ObdConnectionState.CONNECTED,
        driverOnRight = CarProfileStore.current.driverOnRight,
        mapsDocked = half,
        dockApps = TemplatePlacer.dockApps(pages, appsByPackage.keys),
        canbox = shellAccess.root,
        tyres = Tyres.available
    )

    /**
     * Lays [template] out on every page ([replaceAll]) or only on the empty
     * ones. The other arrangement (full width / beside the Maps dock) gets it
     * too if the user has never set that one up.
     */
    fun applyTemplate(template: DashTemplate, replaceAll: Boolean) {
        val built = TemplatePlacer.pages(template, templateScreen(layout != DashLayout.GRID))
        if (replaceAll) {
            pages.flatten().filterIsInstance<DashboardItem.SystemWidget>()
                .forEach { WidgetHostHolder.delete(context, it.appWidgetId) }
        }
        mutateAll(pages.mapIndexed { p, old -> if (replaceAll || old.isEmpty()) built[p] else old })
        val other = otherVariant()
        if (!DashboardStore.exists(context, other)) {
            DashboardStore.save(context, TemplatePlacer.pages(template, templateScreen(layout == DashLayout.GRID)), other)
            otherLayoutWindows = null
        }
        releaseMapsAnchorIfGone()
    }

    // System app-widget picker; adds the bound widget to the page that requested it.
    val addSystemWidget = rememberSystemWidgetAdder { id ->
        if (addTargetPage in 0 until DashboardStore.PAGE_COUNT) {
            if (!addItem(addTargetPage, DashboardItem.SystemWidget(id))) {
                // The picker already allocated a host ID. Release it if the
                // layout cannot accept the widget, otherwise it leaks unused IDs.
                WidgetHostHolder.delete(context, id)
            }
        }
    }

    // Restarted when the clock choice changes, so the bar switches at once.
    val units = LocalUnits.current
    var clock by remember { mutableStateOf(currentClock(units)) }
    LaunchedEffect(units.clock24) {
        while (true) {
            clock = currentClock(units)
            // The bar shows hours and minutes, so wake at the next minute boundary (+ a beat).
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50L)
        }
    }

    // Observe media; re-check notification access on resume so granting it in
    // system settings takes effect without an app restart. The OBD link, its
    // poll and the reconnect run in VehicleMonitor, for the whole process; the
    // screen only says whether the launcher is in front.
    DisposableEffect(lifecycleOwner) {
        ObdBluetoothManager.setContext(context)
        McuReader.setContext(context)
        PrivilegedShell.probe()
        PmPatch.finishSetup(context)
        CarProfileStore.setContext(context)
        SpeedCorrection.setContext(context)
        MediaVolume.setContext(context)
        SpeedVolume.start(context)
        CarCare.setContext(context)
        Maintenance.setContext(context)
        DriveLog.start(context)
        PidExplorer.setContext(context)
        AiMechanic.setContext(context)
        SteeringWheelStore.setContext(context)
        // Read now: a wheel button may ask for home, or the drawer for the apps used most, at any time.
        PlacesStore.load(context)
        AppUsage.load(context)
        StartupBriefing.start(context)
        VehicleMonitor.start(context)
        mediaController.start()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    hasMediaAccess = CarMediaController.hasNotificationAccess(context)
                    // Root granted or ADB turned on meanwhile: the features come back with it.
                    PrivilegedShell.refresh()
                    accessGeneration++
                    if (hasMediaAccess) mediaController.start()
                    VehicleMonitor.connectSaved()
                    VehicleMonitor.setForeground(true)
                }
                Lifecycle.Event.ON_PAUSE -> VehicleMonitor.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            VehicleMonitor.setForeground(false)
            mediaController.stop()
        }
    }

    LaunchedEffect(Unit) { updateManager.checkForUpdate() }
    // The head unit running hot is said once, tile or not (HeadUnitMonitor).
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { HeadUnitMonitor.watchHeat(context) } }
    val checkForUpdates: () -> Unit = { scope.launch { updateManager.checkForUpdate() } }

    // Installing restarts the launcher, and the permission screen is another
    // app's: neither while the car moves.
    val installUpdate: () -> Unit = {
        val status = updateStatus
        val info = status.updateInfo
        if (info != null && status !is UpdateStatus.Downloading && status !is UpdateStatus.Installing) whenParked {
            when {
                !updateManager.canInstallPackages() -> updateManager.openInstallPermissionSettings()
                status is UpdateStatus.Ready -> updateManager.install(status.file)
                else -> scope.launch { updateManager.downloadAndInstall(info) }
            }
        }
    }
    // "Update to vX" (the ⋮ menu, Settings) first shows what the build brings:
    // the release's notes, with the Update button that installs it.
    var releaseNotes by remember { mutableStateOf<UpdateInfo?>(null) }
    val onUpdate: () -> Unit = {
        val info = updateStatus.updateInfo
        if (info != null) whenParked { releaseNotes = info }
    }
    // A newer build downloads by itself on a connection that costs nothing
    // (never over a phone's hotspot), then asks once, parked: now or later.
    // No strip on the dashboard: a dot on ⋮ and a row in Settings say the rest.
    var autoDownloadedBuild by remember { mutableLongStateOf(-1L) }
    var promptedBuild by remember { mutableLongStateOf(-1L) }
    var updatePrompt by remember { mutableStateOf<UpdateStatus.Ready?>(null) }
    LaunchedEffect(updateStatus, moving) {
        val status = updateStatus
        if (status is UpdateStatus.Available && autoDownloadedBuild != status.info.buildNumber &&
            updateManager.canInstallPackages() && updateManager.onUnmeteredNetwork()
        ) {
            autoDownloadedBuild = status.info.buildNumber
            updateManager.download(status.info)
        }
        if (status is UpdateStatus.Ready && !moving && promptedBuild != status.info.buildNumber) {
            promptedBuild = status.info.buildNumber
            // One dialog at a time: the notes give way to "Update ready".
            releaseNotes = null
            updatePrompt = status
        }
    }

    /** The adapter picker is a list to read: parked only. Reconnecting a saved one needs no picker. */
    fun openDevicePicker() = whenParked {
        pairedDevices = ObdBluetoothManager.bondedDevices()
        showDevicePicker = true
    }

    fun connectSavedOrPick() {
        val saved = ObdBluetoothManager.savedDeviceAddress()
        // Tapped by the driver: Bluetooth is switched on and a lost pairing redone (Android asks for the PIN).
        if (saved != null) scope.launch { ObdBluetoothManager.connect(saved, byDriver = true) } else openDevicePicker()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) connectSavedOrPick()
    }

    val ensureBluetooth: (() -> Unit) -> Unit = { action ->
        val missing = requiredBluetoothPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray()) else action()
    }

    val onConnectObd: () -> Unit = { ensureBluetooth { connectSavedOrPick() } }
    val onPickDevice: () -> Unit = { ensureBluetooth { openDevicePicker() } }

    val onLaunchApp: (String) -> Unit = { pkg ->
        if (AppLauncher.launch(context, pkg)) showAllApps = false
    }

    val onLaunchSplitPair: (String, String) -> Unit = { primary, secondary ->
        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.launchSplitPair(context, primary, secondary)
        else showSplitEnable = true
    }

    val onAdd: (Int) -> Unit = { page ->
        whenParked {
            addTargetPage = page
            showAddSheet = true
        }
    }
    // The bar stays in reach under the sheets that fill the pages (Settings,
    // the setup, the add sheet, the app grid): what it starts closes them
    // first, so nothing opens underneath one.
    val closeSheets: () -> Unit = {
        closeSettings()
        showAddSheet = false
        showAllApps = false
        if (setupStep != null) {
            SetupStore.markDone(context); setupDone = true
            setupStep = null
        }
    }
    // The bar's "Finish setting up" pill: a tile on some page still lacks
    // what it needs, the setup has been seen, and the driver has not skipped it.
    // Looked at again when the OBD link changes, but only a change of the answer reaches the bar.
    val setupPending by remember(pages, accessGeneration, setupDone, setupPillOff) {
        derivedStateOf {
            obdConnection.value
            setupDone && !setupPillOff && AccessNeed.pending(context, pages).isNotEmpty()
        }
    }

    // Android forces the status bar on whenever a floating (freeform) window is
    // on screen, i.e. while a Maps window is docked, unless the head unit can
    // keep it off (FreeformBar). The head unit's bar paints
    // over its strip and takes every tap there, so the dashboard lays out below
    // it; the bar already shows the time, so the launcher bar swaps its clock
    // for the page dots. Not via WindowInsets.statusBars: on Android 10 that
    // stays at the bar's height even while the bar is hidden, which pushed the
    // whole dashboard down permanently.
    val barForced by FreeformBar.forced.collectAsState()
    val density = LocalDensity.current
    val rootView = LocalView.current
    val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density)
    // Only the part of the bar that really covers the dashboard is reserved.
    // When the bar comes up, the head unit already shifts the window's content
    // down below it while still reporting the bar's full height as an inset;
    // padding by that inset again left an empty strip as tall as the bar
    // between the bar and the tiles. So the padding is the bar's bottom edge
    // minus where the content actually starts on the screen, never less than 0.
    var contentTopPx by remember { mutableIntStateOf(0) }
    // Everything the bar shows and can do, built once: the bar, its menu and
    // the Settings screen all read the same model.
    val settingsModel = TopBarModel(
        clock = clock,
        versionName = updateManager.currentVersionName,
        obdConnectionState = obdConnection,
        obd = obd,
        editing = editing,
        layout = layout,
        onLayout = { next ->
            closeSheets()
            switchLayout(next)
        },
        onApps = {
            closeSheets()
            showAllApps = true
        },
        onConnectObd = onConnectObd,
        onSplit = {
            whenParked {
                closeSheets()
                if (SplitLauncher.isSystemSplitAvailable()) showSplitPicker = true
                else showSplitEnable = true
            }
        },
        onToggleEdit = {
            if (editing) editing = false
            else whenParked {
                closeSheets()
                editing = true
            }
        },
        onTemplates = {
            whenParked {
                closeSheets()
                showTemplates = true
            }
        },
        onSystem = { whenParked { showSystemDialog = true } },
        onCheckUpdates = checkForUpdates,
        update = updateStatus,
        onUpdate = onUpdate,
        onDismissUpdate = { updateManager.dismiss() },
        setupPending = setupPending,
        onSetup = { fromStart ->
            whenParked {
                closeSheets()
                setupStep = if (fromStart) SetupStep.CAR else SetupStep.ACCESS
            }
        },
        demo = demoOn,
        onDemo = { DemoMode.toggle(context) },
        merged = barForced,
        page = currentPage,
        moving = moving,
        lockWhileMoving = lockWhileMoving,
        onLockWhileMoving = {
            lockWhileMoving = it
            DriveLockStore.save(context, it)
        },
        onSettings = {
            whenParked {
                closeSheets()
                editing = false
                settingsTab = SettingsTab.CAR
            }
        }
    )

    // The bar lives at the bottom: the OS status bar owns the top edge on
    // this head unit whenever a floating window is on screen, and it used to
    // cover the launcher bar there. A horizontal swipe across the bar (or
    // the page dots) changes page, for when a docked window covers the pages.
    // A parked window's cover sits in the bottom-right corner; the bar stops
    // short of it so its ⋮ button stays reachable.
    val coverShowing by ParkedCover.showing.collectAsState()
    val launcherBar: @Composable (@Composable () -> Unit) -> Unit = { bar ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = if (coverShowing) (ParkedCover.WIDTH_DP + 4).dp else 0.dp)
                .pointerInput(Unit) {
                    var dragged = 0f
                    val threshold = 48.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = {
                            val step = when {
                                dragged <= -threshold -> 1
                                dragged >= threshold -> -1
                                else -> 0
                            }
                            // Sideways along the middle row; from a page above or
                            // below the centre that means back to the row first.
                            if (step != 0) {
                                if (columnState.currentPage != DashboardStore.COLUMN_HOME) {
                                    showPage(DashboardStore.CENTER)
                                } else {
                                    val next = (pagerState.currentPage + step).coerceIn(0, DashboardStore.ROW.size - 1)
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                }
                            }
                        }
                    ) { change, dx ->
                        change.consume()
                        dragged += dx
                    }
                }
        ) {
            bar()
        }
    }

    // Arranging with the edit bar up (a split screen's half has no room for it).
    val arranging = editing && !inSplitMode
    val barOverlapPx = if (barForced) (statusBarPx - contentTopPx).coerceAtLeast(0) else 0
    // Canvas: the live map is the page itself, and a rail at the driver's side stands in for the bar.
    val canvas = DashColors.Skin == DashSkin.CANVAS
    val railOnRight = CarProfileStore.current.driverOnRight
    // Over an app, the tabs name the pages (pageName) and the app is the wallpaper beside the rail.
    val tabsShown = canvas && tabsMode
    SideEffect { CanvasTabs.railShown = tabsShown }
    val barModel = DashBarModel(
        tabs = barTabs,
        currentPage = currentPage,
        apps = barApps,
        appsByPackage = appsByPackage,
        onSelect = { showPage(it.page) },
        onOpenTab = { whenParked { dashSheet = it } },
        onLaunch = onLaunchApp,
        onOpenApp = { whenParked { appSheet = it } },
        onAddDashboard = { whenParked { dashSheet = -1 } },
        onAddApp = { whenParked { pickBarApp = true } },
        canAddDashboard = CanvasTabs.freePage(barTabs, pages) != null
    )
    CompositionLocalProvider(
        LocalDriveLock provides driveLock,
        LocalDashboards provides Dashboards(barTabs, tabbed = tabsShown),
        LocalDashBar provides barModel
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
    val underApp = mapApp
    if (tabsShown && underApp != null) {
        CanvasAppBackdrop(underApp, railOnRight, Modifier.fillMaxSize())
    } else if (canvas) CanvasBackdrop(Modifier.fillMaxSize())
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(dashBackground())
            .onGloballyPositioned { coords ->
                val origin = IntArray(2).also { rootView.getLocationOnScreen(it) }
                contentTopPx = origin[1] + coords.positionInRoot().y.roundToInt()
            }
            .padding(top = with(density) { barOverlapPx.toDp() })
    ) {

        Box(
            modifier = Modifier
                .weight(1f)
                // With auto-hide the bar floats over the pages; once it has gone,
                // a swipe up from their bottom edge brings it back.
                .then(if (barAutoHide && !canvas) Modifier.swipeUpRevealsBar(barState, barRevealTap) else Modifier)
                // Docked app windows must stay inside this area (above the bar,
                // unless it floats over the pages and they step aside for it).
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInRoot()
                    pagesArea = b
                    val origin = IntArray(2).also { rootView.getLocationOnScreen(it) }
                    PipAnchor.allowedArea.value = ScreenRect(
                        (b.left + origin[0]).roundToInt(), (b.top + origin[1]).roundToInt(),
                        (b.right + origin[0]).roundToInt(), (b.bottom + origin[1]).roundToInt()
                    )
                }
        ) {
            // "Maps left" layout: a permanent Google Maps dock takes the left half
            // and never leaves composition, so the window is placed once and
            // swiping pages never touches it. Not while the OS itself has us in
            // split-screen: half of a half is too small for either.
            val dockSide = if (inSplitMode || tabsShown) null else when (layout) {
                DashLayout.MAPS_LEFT -> Alignment.Start
                DashLayout.MAPS_RIGHT -> Alignment.End
                DashLayout.GRID -> null
            }
            // Upright, the dock sits above the pages (MAPS_LEFT) or below them (MAPS_RIGHT).
            val vertical = ScreenShape.vertical
            // Drag the divider to trade room between the dock and the pages; the
            // dock re-measures itself, so the Maps window follows once released.
            var splitLengthPx by remember { mutableIntStateOf(1) }
            /** One dashboard, by its index into pages. */
            val dashboardPage: @Composable (Int) -> Unit = { page ->
                // The pagers keep the pages beside this one composed; their
                // window tiles must leave the windows alone (LocalPageOnScreen),
                // and their clocks and motion stand still until the page can be
                // seen: as soon as the pages start sliding (LocalPageActive).
                CompositionLocalProvider(
                    LocalPageOnScreen provides (page == currentPage),
                    LocalPageActive provides (page == currentPage || pagerState.isScrollInProgress || columnState.isScrollInProgress)
                ) {
                DashboardPage(
                    pageItems = pages[page],
                    editing = editing,
                    inSplitMode = inSplitMode,
                    onModelTouch = { blockPagerSwipe = it },
                    appsByPackage = appsByPackage,
                    media = media,
                    mediaController = mediaController,
                    hasMediaAccess = mediaAccess,
                    obd = obd,
                    obdConnection = obdConnection,
                    onConnectObd = onConnectObd,
                    onPickDevice = onPickDevice,
                    onLaunchApp = onLaunchApp,
                    onLaunchSplitPair = onLaunchSplitPair,
                    onEditLaunchBar = { index -> whenParked { launchBarEditor = page to index } },
                    onMoveCell = { index, x, y -> moveCell(page, index, x, y) },
                    onResizeCell = { index, w, h -> resizeCell(page, index, w, h) },
                    canPlace = { index, x, y, w, h ->
                        DashboardStore.canPlace(pages[page], index, x, y, w, h)
                    },
                    canMove = { index, x, y ->
                        DashboardStore.moveResolving(pages[page], index, x, y) != null
                    },
                    onAdd = { onAdd(page) },
                    onTemplates = { whenParked { showTemplates = true } },
                    onTileOptions = { index -> tileOptions = page to index },
                    selectedIndex = tileOptions?.takeIf { it.first == page }?.second ?: -1,
                    onSelectedBounds = { bounds -> tileOptions?.let { selectedTileBounds = it to bounds } },
                    onTapEmpty = { tileOptions = null },
                    // An empty tab is the map alone; the edit bar still adds to it.
                    emptyPrompt = !tabsShown
                )
                }
            }
            // The pages' pane reads the dock fraction and the pagers' scroll
            // The pages' pane reads the pagers' scroll state itself, so a swipe
            // starting and ending recomposes this pane, not the dashboard.
            val pagesPane: @Composable () -> Unit = pane@{
            // Over an app nothing is swiped: one tab's page at a time, and every
            // touch between its tiles goes on to the app under it.
            if (tabsShown) {
                Crossfade(targetState = tabPage.coerceIn(0, DashboardStore.PAGE_COUNT - 1), label = "tab") { tab -> dashboardPage(tab) }
                return@pane
            }
            // Sideways swipes only from the middle row: the pages above and
            // below the centre one have nothing beside them.
            val onHomeRow = columnState.currentPage == DashboardStore.COLUMN_HOME && !columnState.isScrollInProgress
            // The neighbouring pages stay composed: a map tile survives a swipe
            // away and back instead of rebuilding its GL surface. Not for the
            // first frames: at a cold start the page on screen is drawn alone,
            // the four beside it right after, long before a swipe can reach them.
            var beyondViewport by remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                withFrameNanos { }
                beyondViewport = 1
            }
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !blockPagerSwipe && onHomeRow,
                beyondViewportPageCount = beyondViewport,
                modifier = Modifier.fillMaxSize()
            ) { index ->
                val page = DashboardStore.ROW[index]
                if (page == DashboardStore.CENTER) {
                    VerticalPager(
                        state = columnState,
                        userScrollEnabled = !blockPagerSwipe && !pagerState.isScrollInProgress,
                        beyondViewportPageCount = beyondViewport,
                        // Off the home row a sideways swipe heads back to it: the
                        // row is the only way sideways, and a dead swipe feels broken.
                        modifier = Modifier.fillMaxSize().pointerInput(onHomeRow, blockPagerSwipe) {
                            if (onHomeRow || blockPagerSwipe) return@pointerInput
                            var dragged = 0f
                            val threshold = 48.dp.toPx()
                            detectHorizontalDragGestures(
                                onDragStart = { dragged = 0f },
                                onDragEnd = { if (abs(dragged) >= threshold) showPage(DashboardStore.CENTER) }
                            ) { change, dx ->
                                dragged += dx
                                change.consume()
                            }
                        }
                    ) { row -> dashboardPage(DashboardStore.COLUMN[row]) }
                } else {
                    dashboardPage(page)
                }
            }
            }
            DockSplit(
                vertical = vertical,
                docked = dockSide != null,
                dockFirst = dockSide == Alignment.Start,
                dockFraction = { dockFraction.floatValue },
                modifier = Modifier.fillMaxSize()
                    .then(if (canvas) Modifier.padding(start = if (railOnRight) 0.dp else CANVAS_RAIL_SPACE, end = if (railOnRight) CANVAS_RAIL_SPACE else 0.dp) else Modifier)
                    .onSizeChanged { splitLengthPx = if (vertical) it.height else it.width },
                dock = {
                    // Android treats the 30 dp around a freeform window as its
                    // resize handle and takes any drag that starts there for the
                    // system, so the divider must sit further away than that from
                    // the Maps window's edge or dragging it does nothing.
                    val first = dockSide == Alignment.Start
                    val near = DOCK_RESIZE_CLEARANCE
                    Box(
                        modifier = Modifier.fillMaxSize().padding(
                            start = if (vertical || first) 8.dp else near,
                            end = if (vertical || !first) 8.dp else near,
                            top = if (!vertical || first) 8.dp else near,
                            bottom = if (!vertical || !first) 8.dp else near
                        )
                    ) {
                        PipAnchorCard(modifier = Modifier.fillMaxSize(), isDock = true)
                    }
                },
                divider = {
                    DockDivider(
                        vertical = vertical,
                        onDrag = { d ->
                            val delta = d / splitLengthPx.coerceAtLeast(1)
                            val signed = if (dockSide == Alignment.Start) delta else -delta
                            dockFraction.floatValue = (dockFraction.floatValue + signed)
                                .coerceIn(DashLayoutStore.MIN_DOCK_FRACTION, DashLayoutStore.MAX_DOCK_FRACTION)
                        },
                        onDragEnd = { DashLayoutStore.saveDockFraction(context, dockFraction.floatValue) }
                    )
                },
                pane = pagesPane
            )

            if (tabsShown) {
                CanvasTabRail(
                    m = settingsModel,
                    bar = barModel,
                    onAdd = { whenParked { showBarChooser = true } },
                    modifier = Modifier.align(if (railOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                )
            } else if (canvas) {
                CanvasRail(
                    m = settingsModel,
                    onHome = { showHome() },
                    modifier = Modifier.align(if (railOnRight) Alignment.CenterEnd else Alignment.CenterStart)
                )
            }

            // Floating swap button (bottom-centre), shown whenever the launcher
            // shares the screen — regardless of how the split was started (our
            // accessibility path or the OS's manual recents gesture). Swapping
            // Always in sight while the dashboard shows made-up data, with the way
            // out. The standard bar carries it itself; the skins' bars don't, so
            // there it floats over the pages.
            if (demoOn && DashColors.Skin != DashSkin.STANDARD) {
                DemoBadge(
                    onStop = DemoMode::stop,
                    // Canvas has its trip strip up there; the corner under the car is free.
                    modifier = if (canvas) Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp)
                        else Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                )
            }
            if (lockNoticeAt > 0L) {
                DriveLockChip(modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp))
            }
            FadingPageIndicator(
                shown = pageIndicatorShown && !editing,
                page = pageIndicatorFor,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
            )
            if (barAutoHide && !arranging && !canvas) BarHandle(barState, Modifier.align(Alignment.BottomCenter))

            // The tapped tile's panel, over the side of the pages that leaves
            // the tile in sight (TileOptions.kt).
            val selected = tileOptions
            if (arranging && selected != null) {
                val (page, index) = selected
                val tile = pages.getOrNull(page)?.getOrNull(index)
                if (tile == null) {
                    // The tile went away (undo, template): close, but not mid-composition.
                    LaunchedEffect(Unit) { tileOptions = null }
                } else key(page, index) {
                    val panelPx = if (vertical) min(pagesArea.height * 0.45f, with(density) { PANEL_SHEET_MAX.toPx() })
                        else with(density) { PANEL_WIDTH.toPx() }
                    val driverOnRight = CarProfileStore.current.driverOnRight
                    // Where the tile is, once it has said so (the frame after it was tapped).
                    val bounds = selectedTileBounds?.takeIf { it.first == selected }?.second
                    val side = bounds?.let { b -> panelSide(b, pagesArea, panelPx, vertical, driverOnRight) }
                        ?: if (vertical) PanelSide.BOTTOM else if (driverOnRight) PanelSide.RIGHT else PanelSide.LEFT
                    val widget = tile as? DashboardItem.BuiltinWidget
                    val placing = when (side) {
                        PanelSide.LEFT -> Modifier.align(AbsoluteAlignment.TopLeft).width(PANEL_WIDTH)
                        PanelSide.RIGHT -> Modifier.align(AbsoluteAlignment.TopRight).width(PANEL_WIDTH)
                        PanelSide.TOP -> Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        PanelSide.BOTTOM -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    }.then(
                        when {
                            widget == null -> Modifier
                            vertical -> Modifier.height(with(density) { panelPx.toDp() })
                            else -> Modifier.fillMaxHeight()
                        }
                    ).padding(8.dp)
                    TilePanel(
                        item = tile,
                        title = when (tile) {
                            is DashboardItem.AppShortcut -> appsByPackage[tile.packageName]?.label
                            is DashboardItem.AppWindow -> appsByPackage[tile.packageName]?.label?.let {
                                stringResource(if (tile.inside) R.string.dash_app_inside else R.string.dash_app_window, it)
                            }
                            else -> null
                        } ?: tile.describe(),
                        page = page,
                        side = side,
                        designs = widget?.takeIf { it.kind != BuiltinKind.DASH_BAR }?.let {
                            { shelfModifier ->
                                DesignShelf(
                                    kind = widget.kind,
                                    current = widget.design,
                                    // Grid cells on the head unit are a little wider than tall.
                                    aspect = (widget.w * 1.1f) / widget.h,
                                    env = SkinTileEnv(
                                        editing = false, appsByPackage = appsByPackage, media = media, mediaController = mediaController,
                                        hasMediaAccess = mediaAccess, context = context, obd = obd, obdConnectionState = obdConnection,
                                        onConnectObd = onConnectObd, onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onEditLaunchBar = {}
                                    ),
                                    standardPreview = {
                                        // Arranging mode: view-hosting tiles show their placeholder, not a second live map.
                                        TileContent(
                                            item = widget.copy(design = WidgetDesign.STANDARD), editing = true, appsByPackage = appsByPackage,
                                            media = media, mediaController = mediaController, hasMediaAccess = mediaAccess,
                                            context = context, obd = obd, obdConnection = obdConnection, onConnectObd = onConnectObd,
                                            onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onLaunchSplitPair = onLaunchSplitPair,
                                            onEditLaunchBar = {}, onModelTouch = {}
                                        )
                                    },
                                    onPick = { design -> if (design != widget.design) updateItem(page, index, widget.copy(design = design)) },
                                    modifier = shelfModifier
                                )
                            }
                        },
                        onZoom = { zoomTile(page, index, it) },
                        onMoveTo = { target -> tileOptions = null; moveToPage(page, index, target) },
                        barActions = if (widget?.kind == BuiltinKind.DASH_BAR) {
                            {
                                DashBarPanelActions(
                                    canAddDashboard = barModel.canAddDashboard,
                                    onAddDashboard = { tileOptions = null; dashSheet = -1 },
                                    onAddApp = { tileOptions = null; pickBarApp = true }
                                )
                            }
                        } else null,
                        onRemove = { tileOptions = null; removeAt(page, index) },
                        onClose = { tileOptions = null },
                        modifier = placing
                    )
                }
            }

            // needs the accessibility service; if it isn't on, tapping prompts to
            // enable it instead of silently doing nothing.
            if (inSplitMode) {
                FloatingActionButton(
                    onClick = {
                        if (SplitLauncher.isSystemSplitAvailable()) SplitLauncher.swapSplit()
                        else showSplitEnable = true
                    },
                    containerColor = DashColors.Accent,
                    contentColor = DashColors.OnAccent,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .size(56.dp)
                ) {
                    Icon(
                        Icons.Filled.SwapHoriz,
                        contentDescription = stringResource(R.string.dash_swap_split)
                    )
                }
            }

            settingsTab?.let { tab ->
                SettingsScreen(
                    m = settingsModel,
                    theme = themeState,
                    initialTab = tab,
                    onPickObd = onPickDevice,
                    onClose = { closeSettings() },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            if (showAddSheet) {
                AddSheet(
                    page = addTargetPage,
                    apps = apps,
                    previewTile = { item ->
                        // Arranging mode, as in the design picker: view-hosting tiles show their placeholder, not a second live map.
                        TileContent(
                            item = item, editing = true, appsByPackage = appsByPackage,
                            media = media, mediaController = mediaController, hasMediaAccess = mediaAccess,
                            context = context, obd = obd, obdConnection = obdConnection, onConnectObd = onConnectObd,
                            onPickDevice = onPickDevice, onLaunchApp = onLaunchApp, onLaunchSplitPair = onLaunchSplitPair,
                            onEditLaunchBar = {}, onModelTouch = {}
                        )
                    },
                    onPickBuiltin = { kind ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.BuiltinWidget(kind, w = kind.defaultW, h = kind.defaultH))
                    },
                    onPickLaunchBar = {
                        showAddSheet = false
                        if (addTargetPage >= 0) {
                            // Open the editor right away so the new bar isn't left empty.
                            val index = addItemAt(addTargetPage, DashboardItem.LaunchBar())
                            if (index >= 0) launchBarEditor = addTargetPage to index
                        }
                    },
                    onPickSystemWidget = {
                        showAddSheet = false
                        addSystemWidget.pickFromList()
                    },
                    onPickApp = { app ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.AppShortcut(app.packageName))
                    },
                    onPickWindow = { app, inside ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.AppWindow(app.packageName, inside = inside))
                    },
                    onPickPair = { first, second ->
                        showAddSheet = false
                        if (addTargetPage >= 0) addItem(addTargetPage, DashboardItem.SplitPair(first, second))
                    },
                    onClose = { showAddSheet = false },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            setupStep?.let { step ->
                SetupScreen(
                    initialStep = step,
                    theme = themeState,
                    onCarSettings = { showCarSettings = true },
                    onPickObd = onPickDevice,
                    onClose = { finished ->
                        // Seen either way; skipping also rests the pill until the setup is run again.
                        SetupStore.markDone(context); setupDone = true
                        SetupStore.setPillOff(context, !finished); setupPillOff = !finished
                        setupStep = null
                    },
                    modifier = Modifier.fillMaxSize().padding(10.dp)
                )
            }

            if (showAllApps) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(DashColors.Background.copy(alpha = 0.72f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showAllApps = false }
                ) {
                    AppDrawer(
                        apps = apps,
                        moving = moving,
                        onLaunch = { onLaunchApp(it.packageName) },
                        onClose = { showAllApps = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    )
                }
            }
        }

        // One bar at a time: while arranging, the edit bar stands where the
        // launcher bar does (a swipe along it still turns the page), and
        // Done brings the launcher bar back.
        if (arranging) {
            val pageTiles = pages.getOrNull(currentPage).orEmpty()
            launcherBar {
                EditBar(
                    page = currentPage,
                    canUndo = history.isNotEmpty(),
                    // The page's text size: what its tiles share, or what most of them have.
                    pageZoom = pageTiles.map { it.zoom }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 1f,
                    onAdd = { onAdd(currentPage) },
                    onUndo = { undo() },
                    onReset = { confirmReset = true },
                    onTemplates = { showTemplates = true },
                    onPageZoom = { zoom -> mutatePage(currentPage) { list -> list.map { it.withZoom(zoom) } } },
                    onDone = { editing = false }
                )
            }
        } else if (!barAutoHide && !canvas) {
            launcherBar { TopBar(settingsModel) }
        }
    }
    // Auto-hide (Settings › Display): the bar floats over the pages, which keep
    // the whole height, so showing or hiding it never resizes the dashboard.
    if (barAutoHide && !arranging && !canvas) {
        Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            launcherBar {
                AutoHidingBar(state = barState, hideSeconds = barHideSeconds) {
                    TopBar(settingsModel)
                }
            }
        }
    }
    }
    }

    launchBarEditor?.let { (page, index) ->
        val bar = pages.getOrNull(page)?.getOrNull(index) as? DashboardItem.LaunchBar
        if (bar == null) {
            LaunchedEffect(Unit) { launchBarEditor = null }
        } else {
            LaunchBarEditorDialog(
                apps = apps,
                appsByPackage = appsByPackage,
                packages = bar.packages,
                onSave = { pkgs ->
                    updateItem(page, index, bar.copy(packages = pkgs))
                    launchBarEditor = null
                },
                onDismiss = { launchBarEditor = null }
            )
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.dash_reset_confirm_title),
            body = stringResource(R.string.dash_reset_confirm_body),
            action = stringResource(R.string.dash_clear),
            onConfirm = { confirmReset = false; resetPage(currentPage) },
            onDismiss = { confirmReset = false }
        )
    }

    // --- The dashboard bar's sheets (CanvasTabs.kt) ---
    val barKey = CanvasTabs.keyFor(variant())
    if (showBarChooser) {
        DashBarAddChooser(
            canAddDashboard = barModel.canAddDashboard,
            onDashboard = { showBarChooser = false; dashSheet = -1 },
            onApp = { showBarChooser = false; pickBarApp = true },
            onDismiss = { showBarChooser = false }
        )
    }
    dashSheet?.let { index ->
        val tab = barTabs.getOrNull(index)
        val newPage = CanvasTabs.freePage(barTabs, pages)
        if (tab == null && newPage == null) {
            LaunchedEffect(Unit) { dashSheet = null }
            return@let
        }
        DashboardSheet(
            tab = tab,
            newPage = tab?.page ?: newPage ?: 0,
            tabs = barTabs,
            tabbed = tabsShown,
            pagesWithTiles = pages.indices.filterTo(HashSet()) { pages[it].isNotEmpty() },
            canRemove = tab != null && barTabs.size > 1,
            onSave = { edited, place ->
                if (tabsShown) {
                    // Over an app: its place in the rail.
                    CanvasTabs.saveTabs(barKey, CanvasTabs.placed(barTabs, edited, place))
                    if (tab == null) tabPage = edited.page
                } else {
                    // A cross: its spot. Moving to a taken one trades the two pages.
                    var list = barTabs
                    if (tab != null && place != tab.page) {
                        tileOptions = null
                        // Undo would bring the tiles back without their names.
                        history = emptyList()
                        val a = tab.page
                        pages = pages.mapIndexed { i, l -> when (i) { a -> pages[place]; place -> pages[a]; else -> l } }
                        DashboardStore.save(context, pages, variant())
                        list = CanvasTabs.swapped(list, a, place)
                    }
                    CanvasTabs.saveTabs(barKey, CanvasTabs.placed(list, edited.copy(page = place), if (tab != null) index else list.size))
                    showPage(place)
                }
                dashSheet = null
            },
            onRemove = {
                val gone = tab ?: return@DashboardSheet
                val left = barTabs.filterIndexed { i, _ -> i != index }
                if (tabsShown) {
                    // Over an app its widgets go with it; in a cross the page stays as it is.
                    tileOptions = null
                    history = emptyList()
                    pages.getOrNull(gone.page)?.filterIsInstance<DashboardItem.SystemWidget>()
                        ?.forEach { WidgetHostHolder.delete(context, it.appWidgetId) }
                    pages = pages.mapIndexed { i, l -> if (i == gone.page) emptyList() else l }
                    DashboardStore.save(context, pages, variant())
                    if (tabPage == gone.page) tabPage = left.firstOrNull()?.page ?: 0
                }
                CanvasTabs.saveTabs(barKey, left)
                dashSheet = null
            },
            onDismiss = { dashSheet = null }
        )
    }
    appSheet?.let { index ->
        val app = barApps.getOrNull(index)?.let { appsByPackage[it] }
        if (app == null) {
            LaunchedEffect(Unit) { appSheet = null }
            return@let
        }
        DashBarAppSheet(
            app = app,
            otherApps = barApps.filterIndexed { i, _ -> i != index }.map { appsByPackage[it]?.label ?: it },
            place = index,
            onSave = { at -> CanvasTabs.saveApps(CanvasTabs.moved(barApps, index, at)); appSheet = null },
            onRemove = { CanvasTabs.saveApps(barApps.filterIndexed { i, _ -> i != index }); appSheet = null },
            onDismiss = { appSheet = null }
        )
    }
    if (pickBarApp) {
        AppPickerDialog(
            apps = apps.filter { it.packageName !in barApps },
            onPick = { app -> CanvasTabs.saveApps(barApps + app.packageName); pickBarApp = false },
            onDismiss = { pickBarApp = false }
        )
    }

    confirmTemplate?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.templates_replace_confirm_title),
            body = stringResource(R.string.templates_replace_confirm_body),
            action = stringResource(R.string.templates_replace),
            onConfirm = {
                confirmTemplate = null
                applyTemplate(template, true)
                showHome()
            },
            onDismiss = { confirmTemplate = null }
        )
    }

    releaseNotes?.let { info ->
        ParkedOnly { releaseNotes = null }
        ReleaseNotesDialog(
            info = info,
            onUpdate = { releaseNotes = null; installUpdate() },
            onDismiss = { releaseNotes = null }
        )
    }

    updatePrompt?.let { ready ->
        ParkedOnly { updatePrompt = null }
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { updatePrompt = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_update_ready_title), color = DashColors.TextPrimary) },
            text = { Text(stringResource(R.string.dash_update_ready_body, ready.info.versionName), color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { updatePrompt = null; updateManager.install(ready.file) }) {
                    Text(stringResource(R.string.dash_update_now), color = DashColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { updatePrompt = null; updateManager.dismiss() }) {
                    Text(stringResource(R.string.dash_update_later), color = DashColors.Muted)
                }
            }
        )
    }

    if (showCarSettings) CarSettingsDialog(onDismiss = { showCarSettings = false })

    // Tiles that need root or the unit's ADB, where Dashwheel has neither: said, then removed.
    RootlessTiles(pages, shellAccess) { kept ->
        mutateAll(kept)
        releaseMapsAnchorIfGone()
    }

    layoutNotice?.let { notice ->
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { layoutNotice = null },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_notice_title), color = DashColors.TextPrimary) },
            text = { Text(notice, color = DashColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { layoutNotice = null }) {
                    Text(stringResource(R.string.dash_got_it), color = DashColors.Accent)
                }
            }
        )
    }

    if (showTemplates) {
        DashTemplateDialog(
            screen = templateScreen(layout != DashLayout.GRID),
            onApply = { template, replaceAll ->
                showTemplates = false
                if (replaceAll) {
                    confirmTemplate = template
                } else {
                    applyTemplate(template, false)
                    showHome()
                }
            },
            onDismiss = { showTemplates = false }
        )
    }

    if (showDevicePicker) {
        DevicePickerDialog(
            devices = pairedDevices,
            onPick = { mac ->
                ObdBluetoothManager.saveDeviceAddress(mac)
                showDevicePicker = false
                scope.launch { ObdBluetoothManager.connect(mac, byDriver = true) }
            },
            onDismiss = { showDevicePicker = false },
            onOpenSettings = {
                showDevicePicker = false
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        )
    }

    if (showSplitPicker) {
        AppPickerDialog(
            apps = apps,
            title = stringResource(R.string.dash_split_with),
            onPick = { app ->
                showSplitPicker = false
                SplitLauncher.launchSplit(context, app.packageName)
            },
            onDismiss = { showSplitPicker = false }
        )
    }

    if (showSplitEnable) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { showSplitEnable = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_enable_split_title), color = DashColors.TextPrimary) },
            text = {
                Text(
                    stringResource(R.string.dash_enable_split_body),
                    color = DashColors.Muted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSplitEnable = false
                    SplitLauncher.openAccessibilitySettings(context)
                }) {
                    Text(stringResource(R.string.dash_open_settings), color = DashColors.TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSplitEnable = false }) {
                    Text(stringResource(R.string.dash_cancel), color = DashColors.Muted)
                }
            }
        )
    }

    if (showSystemDialog) {
        AlertDialog(
            modifier = Modifier.keepClearOfWindows(),
            onDismissRequest = { if (!systemBusy) showSystemDialog = false },
            containerColor = DashColors.Card,
            title = { Text(stringResource(R.string.dash_system_app_title), color = DashColors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(R.string.dash_system_app_body, AdbInstaller.DEFAULT_PORT),
                        color = DashColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = when {
                            !rootChecked -> stringResource(R.string.dash_root_checking)
                            rootAvailable -> stringResource(R.string.dash_root_available)
                            else -> stringResource(R.string.dash_root_missing, AdbInstaller.DEFAULT_PORT)
                        },
                        color = if (rootAvailable) DashColors.Good else DashColors.Muted,
                        style = MaterialTheme.typography.labelLarge
                    )
                    systemMessage?.let {
                        Text(
                            it,
                            color = if (systemInstalled) DashColors.Good else DashColors.Warning,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (systemBusy) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = DashColors.Accent
                        )
                    }
                }
            },
            confirmButton = {
                if (systemInstalled) {
                    TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { SystemInstaller.rebootDevice(context) } } }) {
                        Text(stringResource(R.string.dash_reboot_now), color = DashColors.Accent)
                    }
                } else {
                    TextButton(
                        enabled = !systemBusy,
                        onClick = {
                            systemBusy = true
                            systemMessage = null
                            scope.launch {
                                val res = withContext(Dispatchers.IO) {
                                    SystemInstaller.install(context)
                                }
                                systemBusy = false
                                res.onSuccess {
                                    systemInstalled = true
                                    systemMessage = context.getString(R.string.dash_system_installed)
                                }.onFailure {
                                    systemMessage = context.getString(R.string.dash_system_failed, it.message.orEmpty())
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.dash_install_system_app), color = DashColors.Accent)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!systemBusy) showSystemDialog = false }) {
                    Text(stringResource(R.string.dash_close), color = DashColors.Muted)
                }
            }
        )
    }

    // The phone's call, when the overlay window can't show it over other apps.
    PhoneCallHost()
    DoorAlertHost()
    RadarHost()
    ClimateHost()
    TyreAlertHost()
    BeltAlertHost()
}

/**
 * The page cross after a page change, fading in fast and out slowly. The fade
 * is read here, in a scope of its own: read where the dashboard is laid out,
 * every frame of it recomposed the whole dashboard, its bar and its background
 * for half a second after each swipe.
 */
@Composable
private fun FadingPageIndicator(shown: Boolean, page: Int, modifier: Modifier = Modifier) {
    val fade by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(if (shown) 150 else 400),
        label = "pageIndicator"
    )
    if (fade > 0f) {
        PageIndicator(page, modifier = modifier.graphicsLayer { alpha = fade })
    }
}

/**
 * The Maps dock, its divider and the pages side by side, or above one another
 * on an upright screen ([vertical]); without [docked], the pages alone. The
 * dock's share is read while measuring, so dragging the divider re-measures
 * the panes without recomposing the dashboard. The three keep their places in
 * composition whichever side the dock is on ([dockFirst]): moving it never
 * rebuilds the dock, which would close the Maps window.
 */
@Composable
private fun DockSplit(
    vertical: Boolean,
    docked: Boolean,
    dockFirst: Boolean,
    dockFraction: () -> Float,
    modifier: Modifier,
    dock: @Composable () -> Unit,
    divider: @Composable () -> Unit,
    pane: @Composable () -> Unit
) {
    Layout(
        content = {
            if (docked) {
                Box { dock() }
                Box { divider() }
            }
            Box { pane() }
        },
        modifier = modifier
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val length = if (vertical) h else w
        fun exact(main: Int) = if (vertical) Constraints.fixed(w, main) else Constraints.fixed(main, h)
        if (measurables.size < 3) {
            val alone = measurables.last().measure(exact(length))
            return@Layout layout(w, h) { alone.place(0, 0) }
        }
        val (dockM, dividerM, paneM) = measurables
        val bar = dividerM.measure(
            if (vertical) Constraints(minWidth = w, maxWidth = w, maxHeight = length)
            else Constraints(maxWidth = length, minHeight = h, maxHeight = h)
        )
        val rest = (length - if (vertical) bar.height else bar.width).coerceAtLeast(0)
        val dockLength = (rest * dockFraction()).roundToInt().coerceIn(0, rest)
        val dockP = dockM.measure(exact(dockLength))
        val paneP = paneM.measure(exact(rest - dockLength))
        layout(w, h) {
            var at = 0
            for (part in if (dockFirst) listOf(dockP, bar, paneP) else listOf(paneP, bar, dockP)) {
                if (vertical) part.place(0, at) else part.place(at, 0)
                at += if (vertical) part.height else part.width
            }
        }
    }
}

/** A two-button question before something a tap could regret; where Undo still exists, the body says so. */
@Composable
internal fun ConfirmDialog(title: String, body: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(title, color = DashColors.TextPrimary) },
        text = { Text(body, color = DashColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(action, color = DashColors.Critical) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
        }
    )
}
