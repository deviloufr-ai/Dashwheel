package com.openauto.dash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/*
 * Tile designs: routing a built-in tile to its design, and the picker that
 * changes it (edit mode → palette button on the tile).
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
    val face = rememberWidgetFace(item.kind, env)
    if (face == null) standard() else DesignedFace(face, item.design, Modifier.fillMaxSize())
}

/**
 * Every design for [kind], each previewed with the widget's live reading at
 * the tile's own proportions [aspect].
 * [standardPreview] draws the tile as it is today.
 */
@Composable
internal fun WidgetDesignPickerDialog(
    kind: BuiltinKind,
    current: WidgetDesign,
    aspect: Float,
    env: SkinTileEnv,
    standardPreview: @Composable () -> Unit,
    onPick: (WidgetDesign) -> Unit,
    onDismiss: () -> Unit
) {
    val frozen = rememberFaceSnapshot(kind, env)
    val sample = sampleFace(kind)
    // The families opened to show every member; the current design's family starts open.
    var expanded by remember { mutableStateOf(setOfNotNull(DesignFamily.of(current))) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(
            modifier = Modifier
                .keepClearOfWindows()
                .fillMaxWidth(0.94f)
                .widthIn(max = 1040.dp),
            shape = DashShape.Large,
            color = DashColors.Card.copy(alpha = 1f)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.design_picker_title, kind.label),
                    color = DashColors.TextPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    stringResource(R.string.design_picker_hint),
                    color = DashColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(220.dp),
                    modifier = Modifier.weight(1f, fill = false),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val offered = WidgetDesign.offeredFor(kind, framed = kind in FRAMED_KINDS)
                    val choice: @Composable (WidgetDesign) -> Unit = { design ->
                        DesignChoice(design, design == current, aspect, onClick = { onPick(design) }) {
                            when {
                                design == WidgetDesign.STANDARD -> standardPreview()
                                kind in FRAMED_KINDS -> DesignFrame(design, kindIcon(kind), kind.label, Modifier.fillMaxSize()) { FramedPlaceholder(kind) }
                                else -> DesignedFace(frozen.value ?: sample, design, Modifier.fillMaxSize())
                            }
                        }
                    }
                    // Standard, then the designs made for this widget, then the
                    // generic ones by family: one card each until the family is opened.
                    items(offered.filter { it == WidgetDesign.STANDARD || it.isSignature }, key = { it.name }) { choice(it) }
                    DesignFamily.entries.forEach { family ->
                        val members = offered.filter { it != WidgetDesign.STANDARD && !it.isSignature && DesignFamily.of(it) == family }
                        if (members.isEmpty()) return@forEach
                        val open = family in expanded
                        val shown = if (open) members else listOf(members.firstOrNull { it == current } ?: members.first())
                        item(key = "family:${family.name}", span = { GridItemSpan(maxLineSpan) }) {
                            FamilyHeader(
                                title = stringResource(family.titleRes),
                                hidden = members.size - shown.size,
                                open = open,
                                onToggle = { expanded = if (open) expanded - family else expanded + family }
                            )
                        }
                        items(shown, key = { it.name }) { choice(it) }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.apps_cancel), color = DashColors.Muted) }
                }
            }
        }
    }
}

/**
 * The widget's live reading for the picker's previews, refreshed at most once
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

@Composable
private fun DesignChoice(
    design: WidgetDesign,
    selected: Boolean,
    aspect: Float,
    onClick: () -> Unit,
    preview: @Composable () -> Unit
) {
    val shape = DashShape.Medium
    Column(
        modifier = Modifier
            .clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) DashColors.Accent else DashColors.Line, shape)
            .background(DashColors.CardHi.copy(alpha = DashColors.CardHi.alpha * if (selected) 0.6f else 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(0.8f, 3f))
                .clip(DashShape.Small)
                .background(dashBackgroundBrush())
                .padding(4.dp)
        ) {
            preview()
            // Taps pick the design; they never reach the preview's own buttons.
            Box(Modifier.fillMaxSize().clickable(role = Role.Button, onClickLabel = design.title, onClick = onClick))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Text(design.title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1)
            if (design.isSignature) Text(
                stringResource(R.string.design_made_for_it).uppercase(),
                color = DashColors.Accent2, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                style = MaterialTheme.typography.labelSmall, maxLines = 1,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            if (selected) Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.dash_selected), tint = DashColors.Accent, modifier = Modifier.size(18.dp))
        }
        Text(
            design.description, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp).height(32.dp)
        )
    }
}

/** The page colour behind a preview, so light and bare designs show as they would on the dashboard. */
private fun dashBackgroundBrush(): Brush = Brush.linearGradient(DashColors.BackgroundStops)

/** What a framed live view stands in for inside the picker (the real map or model is too heavy to run sixteen times). */
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
 * The generic designs by family, so the picker shows one of each until the
 * family is opened: forty cards were a wall, four are a choice.
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

/** A family's title and, while it is folded, how many more it holds. */
@Composable
private fun FamilyHeader(title: String, hidden: Int, open: Boolean, onToggle: () -> Unit) {
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(DashShape.Small)
            .clickable(role = Role.Button) { tap(); onToggle() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title.uppercase(),
            color = DashColors.Muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f)
        )
        Text(
            if (open) stringResource(R.string.design_show_fewer) else stringResource(R.string.design_show_more, hidden),
            color = DashColors.Accent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium
        )
        Icon(
            if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null, tint = DashColors.Accent, modifier = Modifier.size(20.dp).padding(start = 2.dp)
        )
    }
}
