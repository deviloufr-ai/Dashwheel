package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * Settings → Second screen → the page board: each cluster page drawn as the
 * second screen shows it, live. Pick a layout, tap a widget, choose what it
 * shows and in which design. The second screen turns to the page being
 * edited, so the change can be checked on the monitor itself.
 */

/** Height the cluster is laid out at (ClusterPresentation), whatever the monitor. */
private const val CLUSTER_HEIGHT_DP = 600f

/** Narrower than this (a vertical screen) and the pages go back above the preview. */
private val SIDE_PAGES_MIN_WIDTH = 560.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClusterBoard() {
    val context = LocalContext.current
    val config by SecondScreenStore.config.collectAsState()
    val state by DisplayLink.state.collectAsState()
    var editing by remember { mutableStateOf(config.page) }
    var selected by remember { mutableIntStateOf(0) }
    var choosingWidget by remember { mutableStateOf(false) }
    val layout = config.layoutFor(editing)
    val slot = layout.slots.getOrNull(selected) ?: layout.slots.first()
    fun save(change: (ClusterLayout) -> ClusterLayout) = SecondScreenStore.update(context) { c ->
        val next = ClusterLayouts.sanitize(change(c.layoutFor(editing)), editing)
        c.copy(layouts = if (next == ClusterLayouts.default(editing)) c.layouts - editing else c.layouts + (editing to next))
    }

    SettingsSection(stringResource(R.string.second_screen_board))
    Text(
        stringResource(if (config.video) R.string.second_screen_board_detail else R.string.second_screen_board_no_video),
        color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
    fun pickPage(page: ClusterPage) {
        editing = page
        selected = 0
        // The second screen shows the page being edited, when it is one of its pages.
        SecondScreenStore.update(context) { c -> if (page in c.pages) c.copy(page = page) else c }
    }
    val pageOptions = ClusterPage.entries.map { it to stringResource(pageName(it)) }

    // The page as the monitor shows it: its shape, the cluster's own size, scaled down.
    val ratio = (state as? DisplayLinkState.Connected)?.display
        ?.let { d -> (d.width.toFloat() / d.height.coerceAtLeast(1)).takeIf { it in 1f..3f } } ?: (16f / 9f)
    val preview: @Composable (Modifier) -> Unit = { modifier ->
        BoxWithConstraints(
            modifier.aspectRatio(ratio).clip(DashShape.Medium)
                .border(1.dp, DashColors.CardHi, DashShape.Medium)
                .background(DashColors.Background)
        ) {
            val virtualH = CLUSTER_HEIGHT_DP.dp
            val virtualW = virtualH * ratio
            val scale = maxWidth / virtualW
            // In the second screen's own colours, as it will look there.
            val look by SecondScreenStore.config.collectAsState()
            ClusterLook(look) {
                Box(
                    Modifier.requiredSize(virtualW, virtualH)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .background(LocalClusterPalette.current.Background)
                        .padding(12.dp)
                ) {
                    ClusterPageBody(layout, rememberClusterEnv(), preview = true) { index ->
                        val on = index == selected
                        Box(
                            Modifier.fillMaxSize()
                                .border(if (on) 6.dp else 1.dp, if (on) DashColors.Accent else DashColors.Muted.copy(alpha = 0.4f), DashShape.Medium)
                                .clickable { selected = index }
                        )
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= SIDE_PAGES_MIN_WIDTH) {
            // Pages stacked beside the preview: the board fits on a landscape screen at once.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                preview(Modifier.weight(1f))
                PageColumn(pageOptions, selected = { it == editing }, onPick = ::pickPage)
            }
        } else {
            Column {
                ChoiceRow(options = pageOptions, selected = { it == editing }, onPick = ::pickPage)
                preview(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }

    Text(
        stringResource(R.string.second_screen_layout), color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp)
    )
    val tap = rememberTapFeedback()
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ClusterArrangement.entries.forEach { a ->
            val on = a == layout.arrangement
            Column(
                Modifier.clip(DashShape.Medium)
                    .border(if (on) 2.dp else 1.dp, if (on) DashColors.Accent else DashColors.CardHi, DashShape.Medium)
                    .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (on) 0.65f else 0.35f))
                    .clickable {
                        tap()
                        save { ClusterLayouts.arrange(it, a, editing) }
                        selected = selected.coerceAtMost(a.slots - 1)
                    }
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ArrangementSketch(a, if (on) DashColors.Accent else DashColors.TextSecondary)
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    stringResource(arrangementName(a)),
                    color = if (on) DashColors.TextPrimary else DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    SettingsRow(
        Icons.Filled.Widgets, stringResource(R.string.second_screen_widget), slotName(slot)
    ) { choosingWidget = true }
    val designs = ClusterLayouts.designsFor(slot.kind)
    if (designs.isEmpty()) {
        // The live pictures: drawn as they are, and dearer on the Wi-Fi than a still widget.
        Text(
            stringResource(R.string.second_screen_picture_wifi), color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    } else {
        Text(
            stringResource(R.string.second_screen_design), color = DashColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp)
        )
        ChoiceRow(
            options = designs.map { it to stringResource(it.titleRes) },
            selected = { it == slot.design },
            onPick = { d -> save { ClusterLayouts.withSlot(it, selected, slot.kind, d) } }
        )
    }
    if (editing in config.layouts) {
        SettingsRow(Icons.Filled.Restore, stringResource(R.string.second_screen_board_reset), null) {
            SecondScreenStore.update(context) { it.copy(layouts = it.layouts - editing) }
            selected = 0
        }
    }

    if (choosingWidget) {
        WidgetChooserDialog(
            current = slot,
            onPick = { pick -> save { ClusterLayouts.withSlot(it, selected, pick.kind, app = pick.app) }; choosingWidget = false },
            onDismiss = { choosingWidget = false }
        )
    }
}

/** The board's pages, one under the other, all as wide as the longest name. */
@Composable
private fun PageColumn(options: List<Pair<ClusterPage, String>>, selected: (ClusterPage) -> Boolean, onPick: (ClusterPage) -> Unit) {
    val tap = rememberTapFeedback()
    Column(
        Modifier.width(IntrinsicSize.Max).widthIn(max = 200.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (value, label) ->
            val on = selected(value)
            val shape = DashShape.Medium
            Text(
                label,
                color = if (on) DashColors.TextPrimary else DashColors.TextSecondary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .border(if (on) 2.dp else 1.dp, if (on) DashColors.Accent else DashColors.CardHi, shape)
                    .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (on) 0.65f else 0.35f), shape)
                    .clickable { tap(); onPick(value) }
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            )
        }
    }
}

private fun arrangementName(a: ClusterArrangement): Int = when (a) {
    ClusterArrangement.ONE -> R.string.second_screen_layout_one
    ClusterArrangement.BIG_SIDE -> R.string.second_screen_layout_big_side
    ClusterArrangement.BIG_STACK -> R.string.second_screen_layout_big_stack
    ClusterArrangement.HALVES -> R.string.second_screen_layout_halves
    ClusterArrangement.THREE -> R.string.second_screen_layout_three
    ClusterArrangement.GRID -> R.string.second_screen_layout_grid
}

/** A small drawing of [a]: its slots as blocks, the way ClusterPageBody places them. */
@Composable
private fun ArrangementSketch(a: ClusterArrangement, color: androidx.compose.ui.graphics.Color) {
    val gap = 3.dp
    @Composable
    fun Block(m: Modifier) = Box(m.clip(DashShape.Small).background(color.copy(alpha = 0.7f)))
    Box(Modifier.width(64.dp).aspectRatio(16f / 9f)) {
        when (a) {
            ClusterArrangement.ONE -> Block(Modifier.fillMaxSize())
            ClusterArrangement.BIG_SIDE, ClusterArrangement.HALVES -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Block(Modifier.weight(if (a == ClusterArrangement.BIG_SIDE) 1.55f else 1f).fillMaxHeight())
                Block(Modifier.weight(1f).fillMaxHeight())
            }
            ClusterArrangement.BIG_STACK -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Block(Modifier.weight(1.55f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    Block(Modifier.weight(1f).fillMaxWidth())
                    Block(Modifier.weight(1f).fillMaxWidth())
                }
            }
            ClusterArrangement.THREE -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                repeat(3) { Block(Modifier.weight(1f).fillMaxHeight()) }
            }
            ClusterArrangement.GRID -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Block(Modifier.weight(1f).fillMaxHeight())
                        Block(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** What a slot shows, by name: the widget's, or "Google Maps, as on the head unit" for an app's copy. */
@Composable
private fun slotName(slot: ClusterSlot): String =
    if (slot.kind == BuiltinKind.MAPS_INSIDE) stringResource(R.string.second_screen_copy_of, appName(LocalContext.current, slot.app ?: ClusterLayouts.DEFAULT_APP))
    else stringResource(slot.kind.labelRes)

private fun appName(context: android.content.Context, pkg: String): String =
    runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)

private fun installed(context: android.content.Context, pkg: String): Boolean =
    runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.isSuccess

/**
 * The widgets the cluster can show, to pick the one a slot shows: the 3D map,
 * a copy of each app inside a dashboard tile (Google Maps always offered), then
 * the widgets.
 */
@Composable
private fun WidgetChooserDialog(current: ClusterSlot, onPick: (ClusterSlot) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hosted by EmbeddedApp.hosted.collectAsState()
    val apps = remember(hosted) {
        (listOf(ClusterLayouts.DEFAULT_APP).filter { installed(context, it) } + hosted.sorted()).distinct()
    }
    val choices = buildList {
        add(ClusterSlot(BuiltinKind.NAVMAP, WidgetDesign.STANDARD))
        apps.forEach { add(ClusterSlot(BuiltinKind.MAPS_INSIDE, WidgetDesign.STANDARD, it)) }
        ClusterLayouts.KINDS.filter { it !in ClusterLayouts.PICTURES }.forEach { add(ClusterSlot(it, WidgetDesign.STANDARD)) }
    }
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.second_screen_widget_pick), color = DashColors.TextPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(choices, key = { it.kind.name + (it.app ?: "") }) { choice ->
                    val on = choice.kind == current.kind && choice.app == current.app
                    val blurb = when (choice.kind) {
                        BuiltinKind.NAVMAP -> R.string.second_screen_map_blurb
                        BuiltinKind.MAPS_INSIDE -> R.string.second_screen_copy_blurb
                        else -> choice.kind.blurbRes
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(DashShape.Medium)
                            .background(if (on) DashColors.CardHi else DashColors.Card)
                            .clickable { onPick(choice) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            slotName(choice),
                            color = if (on) DashColors.Accent else DashColors.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(stringResource(blurb), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) } }
    )
}
