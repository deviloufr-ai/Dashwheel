package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * Where the driver is among the dashboards, always in sight: the bar's clock
 * slot carries a chip with the dashboard's name and the cross with the
 * current spot lit (PagePositionChip). A tap on it opens the cross as a
 * picker and jumps to the dashboard picked. The dashboard writes the
 * position here as it changes (PagePosition.update); every bar reads it.
 */

/** The dashboard on screen and the cross it sits in, for the bars' chip and anything else that shows the position. */
internal object PagePosition {

    /** [page] is on screen; [cross] holds the dashboards in the swipes; [tabbed] when they are tabs in the rail instead. */
    data class Shown(val page: Int, val cross: ShownCross, val tabbed: Boolean)

    private val _shown = MutableStateFlow(Shown(DashboardStore.CENTER, DashboardStore.shownCross(emptyList()), tabbed = false))
    val shown: StateFlow<Shown> = _shown.asStateFlow()

    /** Brings a dashboard on screen; set by the dashboard that owns the pagers. Null until then. */
    var onPick: ((Int) -> Unit)? = null

    fun update(page: Int, cross: ShownCross, tabbed: Boolean) {
        val next = Shown(page, cross, tabbed)
        if (_shown.value != next) _shown.value = next
    }
}

/**
 * The current dashboard's name beside a small cross with its spot lit, for
 * the bars' clock slot. A tap opens the cross as a picker and shows the
 * dashboard picked. Takes no room beyond its text, and no touches but the tap.
 */
@Composable
fun PagePositionChip(modifier: Modifier = Modifier) {
    val shown by PagePosition.shown.collectAsState()
    var picking by remember { mutableStateOf(false) }
    val tap = rememberTapFeedback()
    val name = pageName(shown.page)
    val label = stringResource(R.string.dash_position_chip, name)
    Row(
        modifier = modifier
            .heightIn(min = DashSize.Touch)
            .clip(DashShape.Small)
            .clickable(role = Role.Button, onClickLabel = label) {
                tap()
                // The overview draws the whole cross with + and -; over an app the rail holds tabs instead, so
                // the plain picker stays for that case.
                if (shown.tabbed) picking = true else DashboardsOverviewHost.open = true
            }
            .semantics { contentDescription = label }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CrossGlyph(shown.page, shown.cross)
        Text(
            name,
            color = DashColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (picking) {
        PageJumpDialog(
            current = shown.page,
            cross = shown.cross,
            onPick = { page -> picking = false; PagePosition.onPick?.invoke(page) },
            onDismiss = { picking = false }
        )
    }
}

/** The cross at glyph size: the dashboards in the swipes, the one on screen filled in, the rest hollow. */
@Composable
private fun CrossGlyph(current: Int, cross: ShownCross) {
    val cell = 6.dp
    val gap = 1.5.dp
    val accent = DashColors.Accent
    val ink = DashColors.TextSecondary
    Canvas(modifier = Modifier.size(width = cell * 3 + gap * 2, height = cell * 5 + gap * 4)) {
        val c = cell.toPx()
        val step = (cell + gap).toPx()
        val r = CornerRadius(1.5.dp.toPx())
        fun draw(page: Int, col: Int, row: Int) {
            val topLeft = Offset(col * step, row * step)
            if (page == current) drawRoundRect(accent, topLeft, Size(c, c), r)
            else drawRoundRect(ink, topLeft, Size(c, c), r, style = Stroke(1.dp.toPx()))
        }
        val centreCol = DashboardStore.ROW.indexOf(DashboardStore.CENTER)
        DashboardStore.COLUMN.forEachIndexed { row, page -> if (page in cross) draw(page, centreCol, row) }
        DashboardStore.ROW.forEachIndexed { col, page -> if (page != DashboardStore.CENTER && page in cross) draw(page, col, DashboardStore.COLUMN_HOME) }
    }
}

/**
 * The cross as a picker, each dashboard in the swipes as a cell with its
 * name, the one on screen lit; a tap on another shows it. The spots of the
 * dashboards taken out of the swipes stay empty.
 */
@Composable
internal fun PageJumpDialog(current: Int, cross: ShownCross, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.dash_position_title), color = DashColors.TextPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                NamedCross(current, cross, onPick)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) }
        }
    )
}

/** The dashboards of [cross] at their spots, named; [current] is lit and takes no tap. */
@Composable
private fun NamedCross(current: Int, cross: ShownCross, onPick: (Int) -> Unit) {
    val cellW = 118.dp
    val cellH = 52.dp
    val gap = 8.dp
    val centreCol = DashboardStore.ROW.indexOf(DashboardStore.CENTER)
    val cells = DashboardStore.COLUMN.mapIndexed { row, p -> Triple(p, centreCol, row) } +
        DashboardStore.ROW.mapIndexedNotNull { col, p -> if (p == DashboardStore.CENTER) null else Triple(p, col, DashboardStore.COLUMN_HOME) }
    Box(modifier = Modifier.size(width = cellW * 3 + gap * 2, height = cellH * 5 + gap * 4)) {
        cells.forEach { (p, col, row) ->
            if (p !in cross) return@forEach
            val here = p == current
            val tap = rememberTapFeedback()
            val shape = RoundedCornerShape(14.dp)
            val name = pageName(p)
            Box(
                modifier = Modifier
                    .offset(x = (cellW + gap) * col, y = (cellH + gap) * row)
                    .size(cellW, cellH)
                    .clip(shape)
                    .then(
                        if (here) Modifier.background(DashColors.Accent.copy(alpha = 0.18f)).border(2.dp, DashColors.Accent, shape)
                        else Modifier.background(DashColors.CardHi).clickable(role = Role.Button, onClickLabel = name) { tap(); onPick(p) }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    name,
                    color = if (here) DashColors.Accent else DashColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
        }
    }
    Spacer(Modifier.width(0.dp))
}
