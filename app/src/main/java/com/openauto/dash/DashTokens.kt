package com.openauto.dash

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/*
 * Shape and spacing tokens shared by the standard renderers (skins and the
 * widget faces bring their own). Three radii and five gaps are enough for a
 * dashboard; one value per role keeps a tile, its chips and its buttons in
 * the same family.
 */

object DashShape {
    /** Tiles, dialogs, the app drawer, the update strip. */
    val Large = RoundedCornerShape(24.dp)
    /** Panels inside a tile, menus, buttons, list rows. */
    val Medium = RoundedCornerShape(16.dp)
    /** Chips, small controls, swatches. */
    val Small = RoundedCornerShape(10.dp)
    /** Status pills and badges. */
    val Pill = RoundedCornerShape(999.dp)
}

object DashSpace {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    /** A tile's inset. */
    val Lg = 16.dp
    val Xl = 24.dp
}

/**
 * Touch targets. Everything a finger may be asked to hit while the car moves
 * is at least [Touch] (the Android minimum); the actions used most at speed
 * (media transport, calls, connect) get [TouchPrimary], and the bottom bar
 * [Bar].
 */
object DashSize {
    val Touch = 48.dp
    val TouchPrimary = 56.dp
    /** The bottom bar's own buttons: reached for without looking, so larger still. */
    val Bar = 64.dp
    /** A row of the bar's menus. */
    val MenuRow = 60.dp
}
