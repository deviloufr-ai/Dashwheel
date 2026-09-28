package com.openauto.dash

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Tile designs: routing a built-in tile to its design, and the shelf that
 * changes it (in the tile's panel while arranging, TileOptions.kt).
 */

/**
 * A built-in tile drawn in its non-standard design. Framed kinds (map, Maps
 * window, 3D car, My car) keep their [standard] content inside the design's
 * frame; every other widget draws its live face, which covers the states
 * without a reading too (see WidgetFaceData.kt).
 */
@Composable
internal fun DesignedTile(item: DashboardItem.BuiltinWidget, env: SkinTileEnv, standard: @Composable () -> Unit) {
    if (item.kind in FRAMED_KINDS) {
        DesignFrame(item.design, kindIcon(item.kind), item.kind.label, Modifier.fillMaxSize()) { standard() }
        return
    }
    // A design no longer offered for this widget (it could not show what the widget needs): its own look.
    if (!item.design.appliesTo(item.kind)) return standard()
    val face = rememberWidgetFace(item.kind, env)
    if (face == null) standard() else DesignedFace(face, item.design, Modifier.fillMaxSize())
}

/**
 * Every design for [kind], in the tile's panel: a chip per family (the first,
 * named after the widget, holds Standard and the designs made for it), that
 * family's designs as live previews at the tile's own proportions [aspect],
 * and the name and description of the one in use. A tap puts a design on the
 * tile at once; the panel stays, so several can be tried in a row.
 * [standardPreview] draws the tile as it is without a design.
 */
@Composable
internal fun DesignShelf(
    kind: BuiltinKind,
    current: WidgetDesign,
    aspect: Float,
    env: SkinTileEnv,
    standardPreview: @Composable () -> Unit,
    onPick: (WidgetDesign) -> Unit,
    modifier: Modifier = Modifier
) {
    val frozen = rememberFaceSnapshot(kind, env)
    val sample = sampleFace(kind)
    val framed = kind in FRAMED_KINDS
    val offered = remember(kind) { WidgetDesign.offeredFor(kind, framed) }
    // The widget's own shelf (null), then each family that has something for it.
    val shelves = remember(offered) {
        listOf<DesignFamily?>(null) + DesignFamily.entries.filter { f -> offered.any { DesignFamily.of(it) == f } }
    }
    var shelf by remember(kind) { mutableStateOf(DesignFamily.of(current)) }
    val shown = offered.filter { DesignFamily.of(it) == shelf }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            shelves.forEach { f ->
                CategoryChip(if (f == null) kind.label else stringResource(f.titleRes), shelf == f) { shelf = f }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(DESIGN_THUMB_MIN_WIDTH),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(shown, key = { it.name }) { design ->
                DesignThumb(design, design == current, aspect, onClick = { onPick(design) }) {
                    when {
                        design == WidgetDesign.STANDARD -> standardPreview()
                        framed -> DesignFrame(design, kindIcon(kind), kind.label, Modifier.fillMaxSize()) { FramedPlaceholder(kind) }
                        else -> DesignedFace(frozen.value ?: sample, design, Modifier.fillMaxSize())
                    }
                }
            }
        }
        // The previews carry names only; the one in use says what it is.
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold)) { append(current.title) }
                append("  ")
                append(current.description)
            },
            color = DashColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Below this width the shelf gives up a column: two to a row in the side panel. */
private val DESIGN_THUMB_MIN_WIDTH = 140.dp

/**
 * The widget's live reading for the shelf's previews, refreshed at most once
 * a second: some twenty previews at feed rate (15 Hz g-force, OBD) would bog
 * the unit down while the driver just picks a look. Null for framed kinds.
 */
@Composable
private fun rememberFaceSnapshot(kind: BuiltinKind, env: SkinTileEnv): State<WidgetFace?> {
    val live = if (kind in FRAMED_KINDS) null else rememberWidgetFace(kind, env)
    val latest = rememberUpdatedState(live)
    val frozen = remember { mutableStateOf(live) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            frozen.value = latest.value
        }
    }
    return frozen
}

/**
 * One design: its preview over the page colour, framed in the accent when it
 * is the tile's, and its name. The whole card is one button; taps never reach
 * the preview's own controls.
 */
@Composable
private fun DesignThumb(
    design: WidgetDesign,
    selected: Boolean,
    aspect: Float,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    val tap = rememberTapFeedback()
    Column(
        modifier = Modifier
            .clip(DashShape.Small)
            .clickable(role = Role.Button, onClickLabel = design.title) { tap(); onClick() },
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.8f, 3f))
                .clip(DashShape.Small)
                .background(dashBackgroundBrush())
                .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, DashShape.Small)
                .padding(3.dp)
        ) {
            preview()
            // Sees every touch without consuming it, so the card's click gets the tap.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(
                design.title,
                color = if (selected) DashColors.Accent else DashColors.TextPrimary,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** The page colour behind a preview, so light and bare designs show as they would on the dashboard. */
private fun dashBackgroundBrush(): Brush = Brush.linearGradient(DashColors.BackgroundStops)

/** What a framed live view stands in for on the shelf (the real map or model is too heavy to run sixteen times). */
@Composable
private fun FramedPlaceholder(kind: BuiltinKind) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(DashColors.CardHi, DashColors.Card))),
        contentAlignment = Alignment.Center
    ) {
        Icon(kindIcon(kind), contentDescription = null, tint = DashColors.Accent.copy(alpha = 0.7f), modifier = Modifier.size(36.dp))
    }
}

/**
 * The generic designs by family, one chip each on the shelf: forty cards
 * were a wall, a family at a time is a choice.
 */
internal enum class DesignFamily(@StringRes val titleRes: Int) {
    THEME(R.string.design_family_theme),
    MATERIALS(R.string.design_family_materials),
    COPPER(R.string.design_family_copper),
    CABINS(R.string.design_family_cabins);

    companion object {
        private val CABIN_LOOKS = setOf(FaceLookKind.NAVY_GOLD, FaceLookKind.SAGE, FaceLookKind.CRIMSON, FaceLookKind.GRAPHITE)

        /** The family of a generic design; null for Standard and the widget-specific ones. */
        fun of(design: WidgetDesign): DesignFamily? = when {
            design == WidgetDesign.STANDARD || design.isSignature -> null
            design.look == FaceLookKind.COPPER || design.look == FaceLookKind.PETROL -> COPPER
            design.look in CABIN_LOOKS -> CABINS
            design.look == FaceLookKind.THEME -> THEME
            else -> MATERIALS
        }
    }
}
