package com.openauto.dash

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/*
 * Every dashboard of the cross at once, each at its spot as a small picture
 * of its tiles: a tap opens one, − takes it away (Undo brings it back), + on
 * a free spot next to the others adds an empty one there. Nothing to name or
 * place first: that stays in the dashboard's own sheet. While the car moves
 * it only jumps: no + or −.
 */

/** Opens the overview from anywhere (the bar's position chip, the edit bar); the dashboard shows it. */
internal object DashboardsOverviewHost {
    var open by mutableStateOf(false)
}

/** The spot one step nearer Home: a free spot offers + only once this one is shown. */
private val INNER = mapOf(0 to DashboardStore.CENTER, 2 to DashboardStore.CENTER, 4 to DashboardStore.CENTER, 5 to DashboardStore.CENTER, 3 to 4, 6 to 5)

/** Where each page sits, as (column, row) of the 3 x 5 cross. */
private val SPOT = mapOf(3 to (1 to 0), 4 to (1 to 1), 0 to (0 to 2), 1 to (1 to 2), 2 to (2 to 2), 5 to (1 to 3), 6 to (1 to 4))

/** The free spots that get a +: next to Home, or past a dashboard on the same arm. */
internal fun addableSpots(shown: Collection<Int>): List<Int> =
    INNER.filter { (page, inner) -> page !in shown && (inner == DashboardStore.CENTER || inner in shown) }.keys.sorted()

/** A dashboard just taken away, so Undo can put it back where it was. */
private class Removed(val tab: CanvasTab, val index: Int, val hadTiles: Boolean)

/**
 * @param tabs the cross's dashboards ([CanvasTabs.tabsFor]); Home is always among them
 * @param current the page on screen, outlined
 * @param onOpen shows a page and closes the overview
 * @param onAdd adds an empty dashboard on a free page
 * @param onRemove takes a dashboard out (its tiles go too, as one Undo step)
 * @param onUndoRemove puts back [CanvasTab] at its index; true: its tiles went too
 */
@Composable
internal fun DashboardsOverview(
    tabs: List<CanvasTab>,
    pages: List<List<DashboardItem>>,
    current: Int,
    onOpen: (Int) -> Unit,
    onAdd: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onUndoRemove: (CanvasTab, Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Moving, it stays a way to jump: adding and removing wait for a stop.
    val moving = LocalDriveLock.current.moving
    val context = LocalContext.current
    var removed by remember { mutableStateOf<Removed?>(null) }
    val shown = tabs.mapTo(HashSet()) { it.page }
    val config = LocalConfiguration.current
    // A page's shape: the screen less its bars, roughly.
    val pageAspect = (config.screenWidthDp / (config.screenHeightDp * 0.85f)).coerceIn(0.4f, 3f)
    val tap = rememberTapFeedback()
    BackHandler(onBack = onDismiss)
    // Over the dashboard rather than in a dialog window, which stops short of
    // the bars' strips: the whole screen, and nothing under it takes a touch.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .keepClearOfWindows()
                .background(DashColors.Background)
                .padding(horizontal = 28.dp, vertical = 16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.dash_dashboards),
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.dash_dashboards_hint),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { tap(); onDismiss() },
                    modifier = Modifier.heightIn(min = DashSize.TouchPrimary),
                    colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
                    shape = DashShape.Small,
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 6.dp)
                ) { Text(stringResource(R.string.dash_done), style = MaterialTheme.typography.titleMedium) }
            }
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val gapX = 26.dp
                val gapY = 14.dp
                // As big as the five rows and three columns allow, in the page's shape.
                val byHeight = (maxHeight - gapY * 4) / 5
                val cellW = min((maxWidth - gapX * 2) / 3, byHeight * pageAspect)
                val cellH = cellW / pageAspect
                val crossW = cellW * 3 + gapX * 2
                val crossH = cellH * 5 + gapY * 4
                Box(Modifier.size(crossW, crossH)) {
                    fun at(page: Int): Pair<Dp, Dp> = SPOT.getValue(page).let { (c, r) -> (cellW + gapX) * c to (cellH + gapY) * r }
                    if (!moving) addableSpots(shown).forEach { page ->
                        val (x, y) = at(page)
                        AddSpot(Modifier.offset(x, y).size(cellW, cellH)) { onAdd(page) }
                    }
                    tabs.filter { it.page in SPOT }.forEach { tab ->
                        val (x, y) = at(tab.page)
                        DashboardCard(
                            name = tab.label(context),
                            tiles = pages.getOrNull(tab.page).orEmpty(),
                            home = tab.page == DashboardStore.CENTER,
                            current = tab.page == current,
                            modifier = Modifier.offset(x, y).size(cellW, cellH)
                        ) { onOpen(tab.page) }
                    }
                    // The − badges last, over the corners of their cards.
                    if (!moving) tabs.filter { it.page in SPOT && it.page != DashboardStore.CENTER }.forEach { tab ->
                        val (x, y) = at(tab.page)
                        RemoveBadge(
                            label = stringResource(R.string.canvas_tab_remove),
                            modifier = Modifier.offset(x + cellW - BADGE_TOUCH / 2 - 4.dp, y - BADGE_TOUCH / 2 + 4.dp)
                        ) {
                            removed = Removed(tab, tabs.indexOf(tab), pages.getOrNull(tab.page).orEmpty().isNotEmpty())
                            onRemove(tab.page)
                        }
                    }
                }
                removed?.takeIf { !moving }?.let { r ->
                    UndoBar(
                        text = stringResource(R.string.dash_dashboard_removed, r.tab.label(context)),
                        modifier = Modifier.align(Alignment.BottomStart)
                    ) {
                        removed = null
                        onUndoRemove(r.tab, r.index, r.hadTiles)
                    }
                }
            }
        }
    }
}

private val BADGE_TOUCH = 52.dp

/** A dashboard: a picture of its tiles over its name, Home with its house. */
@Composable
private fun DashboardCard(
    name: String,
    tiles: List<DashboardItem>,
    home: Boolean,
    current: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val tap = rememberTapFeedback()
    val shape = RoundedCornerShape(16.dp)
    val colors = categoryColors()
    val pageBg = DashColors.CardHi
    Column(
        modifier = modifier
            .clip(shape)
            .background(DashColors.Card)
            .border(if (current) 3.dp else 1.5.dp, if (current) DashColors.Accent else DashColors.Line, shape)
            .clickable(role = Role.Button, onClickLabel = name) { tap(); onClick() }
            .padding(8.dp)
    ) {
        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            drawRoundRect(pageBg.copy(alpha = pageBg.alpha * 0.5f), cornerRadius = CornerRadius(6.dp.toPx()))
            tiles.forEach { drawTile(it, Offset.Zero, size.width, size.height, colors) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 2.dp)) {
            if (home) {
                Icon(Icons.Filled.Home, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                name,
                color = DashColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** A free spot: dashed, with a + in the middle. */
@Composable
private fun AddSpot(modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    val label = stringResource(R.string.dash_dashboard_add)
    val line = DashColors.TextSecondary.copy(alpha = 0.35f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 2.dp.toPx()
            drawRoundRect(
                line,
                topLeft = Offset(w / 2, w / 2),
                size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx())))
            )
        }
        Box(
            modifier = Modifier.size(48.dp).clip(CircleShape).background(DashColors.Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(32.dp))
        }
    }
}

/** The round red − on a card's corner. */
@Composable
private fun RemoveBadge(label: String, modifier: Modifier, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = modifier
            .size(BADGE_TOUCH)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) { tap(); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(DashColors.Background)
                .padding(3.dp)
                .clip(CircleShape)
                .background(DashColors.Critical),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Remove, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
}

/** "Trip removed", with Undo. */
@Composable
private fun UndoBar(text: String, modifier: Modifier, onUndo: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(30.dp))
            .background(DashColors.CardHi)
            .padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Text(text, color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Box(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(DashColors.Accent.copy(alpha = 0.18f))
                .clickable(role = Role.Button) { tap(); onUndo() }
                .padding(horizontal = 22.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(stringResource(R.string.dash_undo), color = DashColors.Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        }
    }
}
