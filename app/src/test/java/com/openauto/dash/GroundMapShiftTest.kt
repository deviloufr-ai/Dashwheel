package com.openauto.dash

import org.junit.Assert.assertArrayEquals
import org.junit.Test

/** The ground map follows the car by moving what it holds, not by wiping it. */
class GroundMapShiftTest {
    private val n = 4
    private val grid = IntArray(n * n) { it + 1 }

    private fun shift(du: Int, dv: Int) = IntArray(n * n).also { shifted(grid, it, du, dv, n) }

    @Test fun nothingMovedKeepsAll() = assertArrayEquals(grid, shift(0, 0))

    @Test fun movedRightKeepsTheRest() = assertArrayEquals(
        intArrayOf(2, 3, 4, 0, 6, 7, 8, 0, 10, 11, 12, 0, 14, 15, 16, 0),
        shift(1, 0)
    )

    @Test fun movedUpAndLeft() = assertArrayEquals(
        intArrayOf(0, 0, 0, 0, 0, 1, 2, 3, 0, 5, 6, 7, 0, 9, 10, 11),
        shift(-1, -1)
    )

    @Test fun movedFurtherThanTheMapEmptiesIt() = assertArrayEquals(IntArray(n * n), shift(5, 0))
}
