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
import androidx.compose.ui.unit.dp

/*
 * Settings → Second screen → the page board: each cluster page drawn as the
 * second screen shows it, live. Pick a layout, tap a widget, choose what it
 * shows and in which design. The second screen turns to the page being
 * edited, so the change can be checked on the monitor itself.
 */

/** Height the cluster is laid out at (ClusterPresentation), whatever the monitor. */
private const val CLUSTER_HEIGHT_DP = 600f

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
        color = if (config.video) DashColors.TextSecondary else DashColors.Warning,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 12.dp)
    )
    ChoiceRow(
        options = ClusterPage.entries.map { it to stringResource(pageName(it)) },
        selected = { it == editing },
        onPick = { page ->
            editing = page
            selected = 0
            // The second screen shows the page being edited, when it is one of its pages.
            SecondScreenStore.update(context) { c -> if (page in c.pages) c.copy(page = page) else c }
        }
    )

    // The page as the monitor shows it: its shape, the cluster's own size, scaled down.
    val ratio = (state as? DisplayLinkState.Connected)?.display
        ?.let { d -> (d.width.toFloat() / d.height.coerceAtLeast(1)).takeIf { it in 1f..3f } } ?: (16f / 9f)
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .aspectRatio(ratio).clip(DashShape.Medium)
            .border(1.dp, DashColors.CardHi, DashShape.Medium)
            .background(DashColors.Background)
    ) {
        val virtualH = CLUSTER_HEIGHT_DP.dp
        val virtualW = virtualH * ratio
        val scale = maxWidth / virtualW
        Box(
            Modifier.requiredSize(virtualW, virtualH)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .padding(12.dp)
        ) {
            ClusterPageBody(layout, rememberClusterEnv()) { index ->
                val on = index == selected
                Box(
                    Modifier.fillMaxSize()
                        .border(if (on) 6.dp else 1.dp, if (on) DashColors.Accent else DashColors.Muted.copy(alpha = 0.4f), DashShape.Medium)
                        .clickable { selected = index }
                )
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
        Icons.Filled.Widgets, stringResource(R.string.second_screen_widget), stringResource(slot.kind.labelRes)
    ) { choosingWidget = true }
    Text(
        stringResource(R.string.second_screen_design), color = DashColors.TextSecondary,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp, top = 8.dp)
    )
    ChoiceRow(
        options = ClusterLayouts.designsFor(slot.kind).map { it to stringResource(it.titleRes) },
        selected = { it == slot.design },
        onPick = { d -> save { ClusterLayouts.withSlot(it, selected, slot.kind, d) } }
    )
    if (editing in config.layouts) {
        SettingsRow(Icons.Filled.Restore, stringResource(R.string.second_screen_board_reset), null) {
            SecondScreenStore.update(context) { it.copy(layouts = it.layouts - editing) }
            selected = 0
        }
    }

    if (choosingWidget) {
        WidgetChooserDialog(
            current = slot.kind,
            onPick = { kind -> save { ClusterLayouts.withSlot(it, selected, kind) }; choosingWidget = false },
            onDismiss = { choosingWidget = false }
        )
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

/** The widgets the cluster can show, to pick the one a slot shows. */
@Composable
private fun WidgetChooserDialog(current: BuiltinKind, onPick: (BuiltinKind) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.second_screen_widget_pick), color = DashColors.TextPrimary) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(ClusterLayouts.KINDS, key = { it.name }) { kind ->
                    val on = kind == current
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(DashShape.Medium)
                            .background(if (on) DashColors.CardHi else DashColors.Card)
                            .clickable { onPick(kind) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            stringResource(kind.labelRes),
                            color = if (on) DashColors.Accent else DashColors.TextPrimary,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(stringResource(kind.blurbRes), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_close), color = DashColors.Muted) } }
    )
}
