package com.openauto.dash

import kotlin.math.abs

/*
 * Room for a new tile, asked when Add is tapped rather than found out after
 * the tile was picked: whether a page has a free cell at all, which other
 * dashboard has one, and how large a new tile can be where its own size does
 * not fit. Pure rules over the grid, beside DashboardStore's.
 */

/** Where a new tile goes on a page and the span it gets there. */
internal data class TileFit(val x: Int, val y: Int, val w: Int, val h: Int)

/** Whether [items] leave a free cell for even the smallest tile. */
internal fun pageHasRoom(items: List<DashboardItem>): Boolean = DashboardStore.firstFreeCell(items, 1, 1) != null

/**
 * The place for a new [w] x [h] tile on a page holding [items]: at its own
 * size where that fits, else at the largest span that does, down to
 * [minW] x [minH]. Of two spans as large, the one closest to the tile's own
 * shape wins. Null when the page has no room for it at all.
 */
internal fun largestFit(items: List<DashboardItem>, w: Int, h: Int, minW: Int = 1, minH: Int = 1): TileFit? {
    val maxW = w.coerceIn(1, GRID_COLS)
    val maxH = h.coerceIn(1, GRID_ROWS)
    var best: TileFit? = null
    for (fw in maxW downTo minW.coerceIn(1, maxW)) for (fh in maxH downTo minH.coerceIn(1, maxH)) {
        val held = best
        if (held != null) {
            val area = fw * fh
            val heldArea = held.w * held.h
            if (area < heldArea) continue
            // As large: only a span nearer the tile's own proportions takes its place.
            if (area == heldArea && abs(fw * maxH - fh * maxW) >= abs(held.w * maxH - held.h * maxW)) continue
        }
        val cell = DashboardStore.firstFreeCell(items, fw, fh) ?: continue
        best = TileFit(cell.first, cell.second, fw, fh)
    }
    return best
}

/** Swipes from [from] to [to] in the cross: along the row or the column, or through Home. */
private fun crossSteps(from: Int, to: Int): Int {
    val row = DashboardStore.ROW
    val column = DashboardStore.COLUMN
    fun toHome(page: Int) =
        if (page in row) abs(row.indexOf(page) - row.indexOf(DashboardStore.CENTER)) else abs(column.indexOf(page) - DashboardStore.COLUMN_HOME)
    return when {
        from in row && to in row -> abs(row.indexOf(from) - row.indexOf(to))
        from in column && to in column -> abs(column.indexOf(from) - column.indexOf(to))
        else -> toHome(from) + toHome(to)
    }
}

/**
 * The dashboard to offer when [from] is full: of the driver's dashboards
 * ([shown], in the bar's order) the nearest other one with a free cell. Near
 * is counted in swipes across the cross, or in places along the rail when
 * the dashboards are tabs ([tabbed]); the bar's order settles a tie. Null
 * when every one of them is full.
 */
internal fun nearestPageWithRoom(pages: List<List<DashboardItem>>, from: Int, shown: List<Int>, tabbed: Boolean = false): Int? =
    shown.filter { it != from && pages.getOrNull(it)?.let(::pageHasRoom) == true }
        .minByOrNull { if (tabbed) abs(shown.indexOf(it) - shown.indexOf(from)) else crossSteps(from, it) }
