@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * App shortcut, split-pair and launch-bar tiles, the app picker and the all-apps drawer.
 */

/**
 * A dock: an editable row of app icons. Tap an icon to launch; the pencil at
 * the end opens [LaunchBarEditorDialog] (adding, ordering and removing apps).
 * Icons scale with the tile height and show labels when there is room.
 */
@Composable
internal fun LaunchBarTile(
    item: DashboardItem.LaunchBar,
    appsByPackage: Map<String, AppEntry>,
    editing: Boolean,
    onLaunch: (String) -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tap = rememberTapFeedback()
    Card(modifier = modifier) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)) {
            val showLabels = maxHeight >= 110.dp
            val iconSize = (maxHeight.value * if (showLabels) 0.42f else 0.62f).coerceIn(32f, 64f).dp
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.packages.isEmpty()) {
                    Text(
                        stringResource(R.string.apps_launch_bar_empty),
                        color = DashColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                } else {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item.packages.forEach { pkg ->
                            val app = appsByPackage[pkg]
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(DashShape.Medium)
                                    .clickable(enabled = !editing) { tap(); onLaunch(pkg) }
                                    .padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(iconSize + 14.dp)
                                        .clip(CircleShape)
                                        .itemFill(DashColors.CardHi, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    // No disc on bare themes, so the icon takes its room.
                                    if (app != null) AppIcon(icon = app.icon, size = if (DashColors.Bare) iconSize + 10.dp else iconSize)
                                    else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(iconSize * 0.7f))
                                }
                                if (showLabels) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = app?.label ?: pkg.substringAfterLast('.'),
                                        color = DashColors.TextSecondary,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.apps_launch_bar_edit),
                        tint = DashColors.Muted,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

/** Editor for a launch bar's apps: add, reorder with the arrows, remove, save. */
@Composable
internal fun LaunchBarEditorDialog(
    apps: List<AppEntry>,
    appsByPackage: Map<String, AppEntry>,
    packages: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(packages) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.apps_launch_bar_editor_title), color = DashColors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (current.isEmpty()) {
                    Text(stringResource(R.string.apps_launch_bar_editor_empty), color = DashColors.Muted)
                }
                current.forEachIndexed { i, pkg ->
                    val app = appsByPackage[pkg]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(DashShape.Small)
                            .background(DashColors.CardHi)
                            .padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (app != null) AppIcon(icon = app.icon, size = 30.dp)
                        else Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            app?.label ?: pkg,
                            color = DashColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { current = current.swap(i, i - 1) }, enabled = i > 0, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.apps_move_left), tint = if (i > 0) DashColors.TextSecondary else DashColors.Muted)
                        }
                        IconButton(onClick = { current = current.swap(i, i + 1) }, enabled = i < current.lastIndex, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.apps_move_right), tint = if (i < current.lastIndex) DashColors.TextSecondary else DashColors.Muted)
                        }
                        IconButton(onClick = { current = current.filterIndexed { j, _ -> j != i } }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.apps_remove), tint = DashColors.Warning)
                        }
                    }
                }
                TextButton(onClick = { showPicker = true }, enabled = current.size < MAX_LAUNCH_BAR_APPS) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (current.size < MAX_LAUNCH_BAR_APPS) stringResource(R.string.apps_add_app)
                        else pluralStringResource(R.plurals.apps_launch_bar_full, MAX_LAUNCH_BAR_APPS, MAX_LAUNCH_BAR_APPS),
                        color = DashColors.Accent
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(current) }) { Text(stringResource(R.string.apps_save), color = DashColors.Accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel), color = DashColors.Muted) }
        }
    )

    if (showPicker) {
        AppPickerDialog(
            apps = apps.filter { it.packageName !in current },
            title = stringResource(R.string.apps_launch_bar_add_title),
            onPick = { app ->
                showPicker = false
                if (app.packageName !in current) current = current + app.packageName
            },
            onDismiss = { showPicker = false }
        )
    }
}

/** Most icons a launch bar holds; beyond that they get too small to hit while driving. */
internal const val MAX_LAUNCH_BAR_APPS = 10

internal fun <T> List<T>.swap(a: Int, b: Int): List<T> {
    if (a !in indices || b !in indices) return this
    return toMutableList().also { val t = it[a]; it[a] = it[b]; it[b] = t }
}

@Composable
internal fun AppShortcutTile(
    app: AppEntry?,
    packageName: String,
    editing: Boolean = false,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .clip(DashShape.Medium)
            // Disable launching while editing so the tile's long-press starts a
            // drag (to reorder / stack) instead of opening the app.
            .clickable(enabled = !editing) { tap(); onClick() }
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .itemFill(DashColors.CardHi, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (app != null) {
                AppIcon(icon = app.icon, size = if (DashColors.Bare) 56.dp else 44.dp)
            } else {
                Icon(Icons.Filled.Apps, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = app?.label ?: packageName.substringAfterLast('.'),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun SplitPairTile(
    primaryApp: AppEntry?,
    secondaryApp: AppEntry?,
    primaryPackage: String,
    secondaryPackage: String,
    editing: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(DashShape.Medium)
            // Disabled while editing so a long-press starts a drag, not a launch.
            .clickable(enabled = !editing, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .height(64.dp)
                .clip(DashShape.Medium)
                .itemFill(DashColors.CardHi, DashShape.Medium, rim = null)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PairIcon(primaryApp)
                Icon(
                    Icons.Filled.Splitscreen,
                    contentDescription = null,
                    tint = DashColors.Accent,
                    modifier = Modifier.size(16.dp)
                )
                PairIcon(secondaryApp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${primaryApp?.label ?: primaryPackage.substringAfterLast('.')} | " +
                (secondaryApp?.label ?: secondaryPackage.substringAfterLast('.')),
            color = DashColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun PairIcon(app: AppEntry?) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .itemFill(DashColors.Card, CircleShape, rim = null),
        contentAlignment = Alignment.Center
    ) {
        if (app != null) {
            AppIcon(icon = app.icon, size = 26.dp)
        } else {
            Icon(
                Icons.Filled.Apps,
                contentDescription = null,
                tint = DashColors.Muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun AddChoiceRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DashShape.Small)
            .clickable(onClick = onClick)
            .background(DashColors.CardHi)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = DashColors.TextPrimary, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun AppPickerDialog(
    apps: List<AppEntry>,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.apps_choose_app),
    /** An extra choice beside Cancel ("the unit's own choice"...). */
    neutral: (@Composable () -> Unit)? = null
) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(title, color = DashColors.TextPrimary) },
        text = {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 84.dp),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
            ) {
                items(apps, key = { it.packageName }) { app ->
                    AppShortcutTile(app = app, packageName = app.packageName, onClick = { onPick(app) })
                }
            }
        },
        confirmButton = { neutral?.invoke() },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.apps_cancel), color = DashColors.Muted)
            }
        }
    )
}

@Composable
internal fun AppDrawer(
    apps: List<AppEntry>,
    onLaunch: (AppEntry) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** The car is moving: one app per 64 dp row, big icon and name, instead of the grid. */
    moving: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { AppUsage.load(context) }
    val counts by AppUsage.counts.collectAsState()
    // The apps opened most come first, as they were when the drawer opened:
    // an app just launched does not reshuffle the row under the finger.
    val mostUsed = remember(apps, counts.isEmpty()) {
        val byPackage = apps.associateBy { it.packageName }
        AppUsage.mostUsed(apps.map { it.packageName }, counts).mapNotNull { byPackage[it] }
    }
    // Typing waits for a stop (the drive lock): no search field on the move.
    var query by remember { mutableStateOf("") }
    val found = remember(apps, query, moving) {
        val q = query.trim()
        if (q.isEmpty() || moving) null else apps.filter { it.label.contains(q, ignoreCase = true) }
    }

    SolidCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 6.dp, top = 14.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.apps_all_apps),
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.width(20.dp))
                if (!moving && apps.isNotEmpty()) SearchField(query, { query = it }, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onClose, modifier = Modifier.size(DashSize.TouchPrimary)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.apps_close_drawer),
                        tint = DashColors.TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            val shown = found ?: apps
            // Sections only when there is a row of favourites to tell apart from the rest.
            val sections = found == null && mostUsed.isNotEmpty()
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.apps_no_apps_found), color = DashColors.Muted)
                }
            } else if (moving) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (sections) {
                        item(key = "h:top") { DrawerSection(stringResource(R.string.apps_most_used)) }
                        items(mostUsed, key = { "top:" + it.packageName }) { app ->
                            AppRow(app = app, onClick = { onLaunch(app) })
                        }
                        item(key = "h:all") { DrawerSection(stringResource(R.string.apps_all_apps)) }
                    }
                    items(shown, key = { it.packageName }) { app ->
                        AppRow(app = app, onClick = { onLaunch(app) })
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 92.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (sections) {
                        item(key = "h:top", span = { GridItemSpan(maxLineSpan) }) { DrawerSection(stringResource(R.string.apps_most_used)) }
                        items(mostUsed, key = { "top:" + it.packageName }) { app ->
                            AppShortcutTile(app = app, packageName = app.packageName, onClick = { onLaunch(app) })
                        }
                        item(key = "h:all", span = { GridItemSpan(maxLineSpan) }) { DrawerSection(stringResource(R.string.apps_all_apps)) }
                    }
                    items(shown, key = { it.packageName }) { app ->
                        AppShortcutTile(app = app, packageName = app.packageName, onClick = { onLaunch(app) })
                    }
                }
            }
        }
    }
}

/** "Most used", "All apps": what the drawer's two parts are. */
@Composable
private fun DrawerSection(title: String) {
    Text(
        title.uppercase(),
        color = DashColors.Accent,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp)
    )
}

/** One app on a 64 dp row: the drawer's layout while the car moves, hit without aiming. */
@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(DashShape.Medium)
            .clickable { tap(); onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .itemFill(DashColors.CardHi, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            AppIcon(icon = app.icon, size = 40.dp)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = app.label,
            color = DashColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Launcher icons already turned into bitmaps, keyed by the app's drawable and
 * the size drawn. Process-wide, so scrolling the drawer or paging the
 * dashboard back to an app doesn't rasterise its icon again; bounded to ~4 MB,
 * least recently used first out.
 */
private object IconBitmaps {
    private val cache = object : LruCache<Pair<Drawable, Int>, ImageBitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: Pair<Drawable, Int>, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(icon: Drawable, px: Int): ImageBitmap {
        val key = icon to px
        return cache.get(key) ?: icon.toBitmap(width = px, height = px).asImageBitmap().also { cache.put(key, it) }
    }
}

/** Renders an installed app's launcher [Drawable] as a Compose image. */
@Composable
internal fun AppIcon(icon: Drawable, size: androidx.compose.ui.unit.Dp) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val bitmap = remember(icon, px) { IconBitmaps.get(icon, px.coerceAtLeast(1)) }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = Modifier.size(size)
    )
}
