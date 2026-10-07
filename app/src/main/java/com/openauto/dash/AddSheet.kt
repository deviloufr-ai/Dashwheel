package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Adding to a page: one full-width sheet with three tabs (widgets, apps,
 * windows) in place of the "what kind of thing?" dialog that led to four
 * different pickers. Widgets are shown as they will look on the page: each
 * card holds the live tile at its starting proportions, in the current theme
 * and skin, with its name and blurb underneath, filtered by category chip or
 * search; apps are their icons; windows are an app kept open in its own
 * window, or two side by side.
 */

/** How the windows tab shows the app picked: in a window, inside the tile, or beside another app. */
private enum class WindowMode(@StringRes val titleRes: Int, val icon: ImageVector) {
    SINGLE(R.string.apps_window_mode_single, Icons.Filled.OpenInNew),
    INSIDE(R.string.apps_window_mode_inside, Icons.Filled.CropFree),
    PAIR(R.string.apps_window_mode_pair, Icons.Filled.Splitscreen)
}

private enum class AddTab(@StringRes val titleRes: Int, val icon: ImageVector) {
    WIDGETS(R.string.apps_tab_widgets, Icons.Filled.Widgets),
    APPS(R.string.apps_tab_apps, Icons.Filled.Apps),
    WINDOWS(R.string.apps_tab_windows, Icons.Filled.OpenInNew)
}

@Composable
internal fun AddSheet(
    page: Int,
    apps: List<AppEntry>,
    /** Draws [DashboardItem] as the dashboard would (arranging mode, so live views show their placeholder). */
    previewTile: @Composable (DashboardItem) -> Unit,
    onPickBuiltin: (BuiltinKind) -> Unit,
    onPickLaunchBar: () -> Unit,
    onPickSystemWidget: () -> Unit,
    onPickApp: (AppEntry) -> Unit,
    /** The app, and whether it runs inside the tile rather than in a window over it. */
    onPickWindow: (AppEntry, Boolean) -> Unit,
    onPickPair: (String, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var tab by rememberSaveable { mutableStateOf(AddTab.WIDGETS) }
    var query by rememberSaveable { mutableStateOf("") }
    val tap = rememberTapFeedback()
    SolidCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(DashSize.TouchPrimary)
                        .clip(DashShape.Medium)
                        .background(DashColors.CardHi)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.dash_close)) { tap(); onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.dash_close), tint = DashColors.TextPrimary, modifier = Modifier.size(26.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text(
                    stringResource(R.string.apps_add_title, pageName(page)),
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box(Modifier.width(420.dp)) {
                    SegmentedSwitch(
                        options = AddTab.entries,
                        chosen = tab,
                        icon = { it.icon },
                        title = { stringResource(it.titleRes) },
                        onChoose = { tab = it }
                    )
                }
                Spacer(Modifier.width(12.dp))
                SearchField(query, onChange = { query = it }, modifier = Modifier.width(220.dp))
            }
            Spacer(Modifier.height(10.dp))
            val q = query.trim()
            when (tab) {
                AddTab.WIDGETS -> WidgetsTab(q, apps, previewTile, onPickBuiltin, onPickLaunchBar, onPickSystemWidget)
                AddTab.APPS -> AppGrid(apps.matching(q), onPick = onPickApp)
                AddTab.WINDOWS -> WindowsTab(apps.matching(q), onPickWindow, onPickPair)
            }
        }
    }
}

/**
 * Add on a dashboard with no free cell (AddRoom.kt): said before the sheet
 * opens, not after a tile was picked. [other], the nearest dashboard that
 * still has room, is offered in one button under its own name.
 */
@Composable
internal fun PageFullDialog(other: Int?, onAddTo: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.dash_full_title), color = DashColors.TextPrimary) },
        text = { Text(stringResource(R.string.dash_full_body), color = DashColors.TextSecondary) },
        confirmButton = {
            if (other != null) {
                TextButton(onClick = { onAddTo(other) }) {
                    Text(stringResource(R.string.apps_add_title, pageName(other)), color = DashColors.Accent)
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_got_it), color = DashColors.Accent) }
            }
        },
        dismissButton = {
            if (other != null) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
            }
        }
    )
}

private fun List<AppEntry>.matching(q: String) = if (q.isEmpty()) this else filter { it.label.contains(q, ignoreCase = true) }

@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.apps_search), color = DashColors.Muted) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = DashColors.TextSecondary) },
        shape = DashShape.Small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = DashColors.TextPrimary,
            unfocusedTextColor = DashColors.TextPrimary,
            cursorColor = DashColors.Accent,
            focusedBorderColor = DashColors.Accent,
            unfocusedBorderColor = DashColors.Line,
            focusedContainerColor = DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f),
            unfocusedContainerColor = DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.4f)
        ),
        modifier = modifier.heightIn(min = DashSize.Touch)
    )
}

/**
 * Category chips, then the catalogue as live previews, as many to a row as
 * fit: in sections by category under "All", flat under one chip. The launch
 * bar and system widgets close the Apps section.
 */
@Composable
private fun WidgetsTab(
    query: String,
    apps: List<AppEntry>,
    previewTile: @Composable (DashboardItem) -> Unit,
    onPickBuiltin: (BuiltinKind) -> Unit,
    onPickLaunchBar: () -> Unit,
    onPickSystemWidget: () -> Unit
) {
    var category by rememberSaveable { mutableStateOf<WidgetCategory?>(null) }
    val allLabel = stringResource(R.string.apps_category_all)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CategoryChip(allLabel, category == null) { category = null }
        WidgetCategory.entries.forEach { c ->
            CategoryChip(stringResource(c.titleRes), category == c) { category = c }
        }
    }
    Spacer(Modifier.height(10.dp))
    val context = LocalContext.current
    // The tiles that live on the privileged shell (the Maps window, the CANbox
    // ones) are left out where there is none: they could only sit waiting.
    val access = shellAccess()
    val kinds = BuiltinKind.entries.filter { kind ->
        kind.offered && access.allows(kind) &&
            // Only the QF firmware's car app shares the car's data.
            (kind != BuiltinKind.CAR_STATUS || CarBox.available) &&
            // The doors: from the CAN box stream with root, else from the car app's data.
            (kind != BuiltinKind.DOORS || access.root || CarBox.available) &&
            // Only with the head unit's TPMS app.
            (kind != BuiltinKind.TYRES || Tyres.available) &&
            // Only where Android lets Dashwheel open another app on its own display (PMPatch in Magisk).
            (kind != BuiltinKind.MAPS_INSIDE || EmbeddedApp.allowed(context)) &&
            // The Canvas rail over an app is that bar already.
            (kind != BuiltinKind.DASH_BAR || !CanvasTabs.railShown) &&
            // Only once a second screen wired to its monitor's buttons has linked.
            (kind != BuiltinKind.SCREEN_LIGHT || SecondScreenStore.config.value.brightnessWired) &&
            (category == null || kind.category == category) &&
            (query.isEmpty() || context.getString(kind.labelRes).contains(query, true) || context.getString(kind.blurbRes).contains(query, true))
    }
    val extras = (category == null || category == WidgetCategory.APPS) && query.isEmpty()
    // The launch bar's preview holds a few of the installed apps, so it reads as a bar and not an empty strip.
    val sampleBar = remember(apps) { DashboardItem.LaunchBar(packages = apps.take(5).map { it.packageName }) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = PREVIEW_CARD_MIN_WIDTH),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        val kindCards: (List<BuiltinKind>) -> Unit = { list ->
            items(list, key = { it.name }) { kind ->
                WidgetCard(kind.label, kind.blurb, tileAspect(kind.defaultW, kind.defaultH), onClick = { onPickBuiltin(kind) }) {
                    previewTile(DashboardItem.BuiltinWidget(kind, w = kind.defaultW, h = kind.defaultH))
                }
            }
        }
        val extraCards: () -> Unit = {
            item(key = "bar") {
                WidgetCard(
                    stringResource(R.string.apps_pick_launch_bar), stringResource(R.string.apps_pick_launch_bar_blurb),
                    tileAspect(sampleBar.w, sampleBar.h), onClick = onPickLaunchBar
                ) { previewTile(sampleBar) }
            }
            item(key = "system") {
                // A system widget has no face of its own until one is picked from the list.
                WidgetCard(
                    stringResource(R.string.apps_pick_system_widget), stringResource(R.string.apps_pick_system_widget_blurb),
                    tileAspect(5, 3), onClick = onPickSystemWidget
                ) { IconPreview(Icons.Filled.Widgets) }
            }
        }
        if (category == null) {
            // "All": the catalogue in sections, one per category, each under its title;
            // a search keeps the sections it still has something in.
            WidgetCategory.entries.forEach { c ->
                val inSection = kinds.filter { it.category == c }
                val withExtras = extras && c == WidgetCategory.APPS
                if (inSection.isEmpty() && !withExtras) return@forEach
                item(key = "section:${c.name}", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(stringResource(c.titleRes)) }
                kindCards(inSection)
                if (withExtras) extraCards()
            }
        } else {
            kindCards(kinds)
            if (extras) extraCards()
        }
    }
}

/** A category's name over its widgets in the "All" list. */
@Composable
private fun SectionTitle(title: String) {
    Text(
        title.uppercase(),
        color = DashColors.Muted,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.5.sp,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, top = 6.dp)
    )
}

/** Below this width a row gives up a column: a preview has to stay legible. */
private val PREVIEW_CARD_MIN_WIDTH = 300.dp

/** A tile's proportions on the page: grid cells on the head unit are a little wider than tall. */
private fun tileAspect(w: Int, h: Int): Float = (w * 1.1f / h).coerceIn(0.8f, 3f)

/** A filter chip: the add sheet's categories, the design shelf's families. */
@Composable
internal fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = DashShape.Pill
    Text(
        label,
        color = if (selected) DashColors.OnAccent else DashColors.TextPrimary,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .heightIn(min = DashSize.Touch)
            .clip(shape)
            .then(if (selected) Modifier.background(DashColors.AccentBrush, shape) else Modifier.border(1.dp, DashColors.Line, shape))
            .clickable(role = Role.Tab) { tap(); onClick() }
            .padding(horizontal = 18.dp, vertical = 12.dp)
    )
}

/**
 * One widget as it will look on the page: the live tile over the page
 * background at [aspect], then its name and the whole two-line blurb. The
 * card is a single button; taps never reach the preview's own controls.
 */
@Composable
private fun WidgetCard(label: String, blurb: String, aspect: Float, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val tap = rememberTapFeedback()
    val shape = DashShape.Medium
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * 0.6f))
            .border(1.dp, DashColors.Line, shape)
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(DashShape.Small)
                .background(Brush.linearGradient(DashColors.BackgroundStops))
                .padding(4.dp)
        ) {
            preview()
            // Sits over the preview and takes every touch, without consuming it,
            // so the card's own click gets the tap and the tile's buttons never do.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(blurb, color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The stand-in for a widget with nothing to show yet: its icon on a disc, centred. */
@Composable
private fun IconPreview(icon: ImageVector) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(DashColors.Accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun AppGrid(apps: List<AppEntry>, onPick: (AppEntry) -> Unit, header: (@Composable () -> Unit)? = null) {
    if (apps.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.apps_no_apps_found), color = DashColors.Muted)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(apps, key = { it.packageName }) { app ->
            AppShortcutTile(app = app, packageName = app.packageName, onClick = { onPick(app) })
        }
    }
}

/**
 * An app in its own window on the page, inside the tile itself, or two apps
 * opened side by side. A window of its own is placed through the privileged
 * shell ([DockShell]), so without one ([PrivilegedShell]) only the pair,
 * which the accessibility service opens, is offered. Inside the tile needs
 * the firmware's system permissions too ([EmbeddedApp.allowed]).
 */
@Composable
private fun WindowsTab(apps: List<AppEntry>, onPickWindow: (AppEntry, Boolean) -> Unit, onPickPair: (String, String) -> Unit) {
    val shell = shellAccess().shell
    val canInside = shell && EmbeddedApp.allowed(LocalContext.current)
    val modes = buildList {
        if (shell) add(WindowMode.SINGLE)
        if (canInside) add(WindowMode.INSIDE)
        add(WindowMode.PAIR)
    }
    var chosen by rememberSaveable { mutableStateOf(if (shell) WindowMode.SINGLE else WindowMode.PAIR) }
    val mode = chosen.takeIf { it in modes } ?: WindowMode.PAIR
    val pair = mode == WindowMode.PAIR
    var first by rememberSaveable { mutableStateOf<String?>(null) }
    val firstApp = first?.let { pkg -> apps.firstOrNull { it.packageName == pkg } }
    if (modes.size > 1) {
        Box(Modifier.width(if (modes.size > 2) 560.dp else 420.dp)) {
            SegmentedSwitch(
                options = modes,
                chosen = mode,
                icon = { it.icon },
                title = { stringResource(it.titleRes) },
                onChoose = { chosen = it; first = null }
            )
        }
        Spacer(Modifier.height(8.dp))
    }
    AppGrid(
        apps = apps,
        onPick = { app ->
            when {
                !pair -> onPickWindow(app, mode == WindowMode.INSIDE)
                first == null -> first = app.packageName
                else -> onPickPair(first!!, app.packageName)
            }
        },
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    when {
                        mode == WindowMode.INSIDE -> stringResource(R.string.apps_window_inside_hint)
                        !pair -> stringResource(R.string.apps_window_hint)
                        firstApp == null -> stringResource(R.string.apps_pair_pick_first)
                        else -> stringResource(R.string.apps_pair_pick_second, firstApp.label)
                    },
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                if (pair && first != null) {
                    TextButton(onClick = { first = null }) { Text(stringResource(R.string.apps_pair_change), color = DashColors.Accent) }
                }
            }
        }
    )
}
