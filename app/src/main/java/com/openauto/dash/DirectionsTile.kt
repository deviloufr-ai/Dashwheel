@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.openauto.dash

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.min
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/*
 * Next-turn tile and map banner fed by the Google Maps / Waze navigation notification.
 */

internal const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

/** Opens the navigation app that is driving [nav] (or Google Maps) beside the dashboard. */
internal fun openNavigationApp(context: Context, nav: NavState) {
    // The dashboard's own guidance has no app to open: the tap offers to stop it.
    if (nav.packageName == context.packageName) return InAppNav.askToStop()
    SplitLauncher.launchSplit(context, nav.packageName.ifEmpty { GOOGLE_MAPS_PACKAGE })
}

/**
 * Asked after a tap on the dashboard's own guidance, on any tile or banner
 * ([InAppNav.askToStop]): the one way to stop it from a page without the map tile.
 */
@Composable
internal fun StopGuidancePrompt() {
    val asked by InAppNav.askStop.collectAsState()
    if (!asked) return
    ConfirmDialog(
        title = stringResource(R.string.info_map_stop_title),
        body = stringResource(R.string.info_map_stop_body),
        action = stringResource(R.string.info_map_stop),
        onConfirm = { InAppNav.answerStop(true) },
        onDismiss = { InAppNav.answerStop(false) }
    )
}

/**
 * Dashboard tile showing the next manoeuvre from Google Maps / Waze: the turn
 * glyph on a glowing disc, the distance in hero numerals, the street, and the
 * ETA line as chips. Tapping it brings the navigation app up beside the
 * dashboard. Needs the same Notification access grant as the music player.
 */
@Composable
internal fun DirectionsCard(
    hasAccess: Boolean,
    context: Context,
    modifier: Modifier = Modifier
) {
    val nav by NavDirections.state.collectAsState()
    val glow = DashColors.Glow
    val accent = DashColors.Accent
    // "On my way" texts someone: said first, with three seconds to cancel.
    val pending = rememberPendingAction()

    Card(modifier = modifier) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        if (hasAccess) openNavigationApp(context, nav)
                        else CarMediaController.openNotificationAccessSettings(context)
                    }
                    .padding(DashSpace.Lg)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.info_directions_title),
                        color = DashColors.Accent,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (nav.active) DashColors.Good else DashColors.Muted)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                nav.active && nav.packageName == context.packageName -> stringResource(R.string.app_name)
                                nav.active -> NavDirections.appName(nav.packageName)
                                else -> stringResource(R.string.info_directions_no_route)
                            },
                            color = if (nav.active) DashColors.Good else DashColors.Muted,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                when {
                    !hasAccess -> DirectionsEmpty(
                        icon = Icons.Filled.Directions,
                        title = stringResource(R.string.info_directions_access_title),
                        hint = stringResource(R.string.info_directions_access_hint),
                        action = stringResource(R.string.info_grant_access),
                        onAction = { CarMediaController.openNotificationAccessSettings(context) }
                    )
                    !nav.active -> DirectionsEmpty(
                        icon = Icons.Filled.Navigation,
                        title = stringResource(R.string.info_directions_idle_title),
                        hint = stringResource(R.string.info_directions_idle_hint),
                        action = stringResource(R.string.info_directions_open_maps),
                        onAction = { openNavigationApp(context, nav) }
                    )
                    else -> {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ManeuverIcon(nav = nav, size = 84.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val (value, unit) = nav.distanceParts
                                if (value.isNotEmpty()) {
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = value,
                                            color = if (glow > 0f) Color.Unspecified else DashColors.TextPrimary,
                                            fontSize = 44.sp,
                                            lineHeight = 44.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-0.05).em,
                                            maxLines = 1,
                                            style = TextStyle(
                                                brush = if (glow > 0f) Brush.verticalGradient(
                                                    listOf(DashColors.Bright, lerp(DashColors.Bright, accent, 0.45f))
                                                ) else null,
                                                shadow = if (glow > 0f) Shadow(accent.copy(alpha = 0.8f * glow), blurRadius = 30f) else null
                                            )
                                        )
                                        if (unit.isNotEmpty()) {
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = unit.uppercase(),
                                                color = DashColors.TextSecondary,
                                                fontWeight = FontWeight.SemiBold,
                                                letterSpacing = 0.2.em,
                                                style = MaterialTheme.typography.labelLarge,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = nav.instruction,
                                    color = DashColors.TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        val chips = nav.etaParts
                        val texts by PhoneLink.textsOn.collectAsState()
                        var picking by remember { mutableStateOf(false) }
                        if (chips.isNotEmpty() || texts) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                chips.take(if (texts) 2 else 3).forEach { InfoPill(it) }
                                // "On my way": the arrival time to a favourite, texted by the phone.
                                if (texts) {
                                    Spacer(Modifier.weight(1f))
                                    OnMyWayPill { picking = true }
                                }
                            }
                        }
                        if (picking) OnMyWayDialog(
                            onPick = { fav -> pending.arm(context.getString(R.string.onmyway_sending, fav.name)) { OnMyWay.send(context, fav) } },
                            onDismiss = { picking = false }
                        )
                    }
                }
            }
            PendingActionStrip(pending, Modifier.align(Alignment.BottomCenter).padding(DashSpace.Sm))
        }
    }
}

@Composable
internal fun DirectionsEmpty(
    icon: ImageVector,
    title: String,
    hint: String,
    action: String,
    onAction: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(8.dp))
        Text(title, color = DashColors.TextPrimary, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        Text(
            hint,
            color = DashColors.TextSecondary,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(containerColor = DashColors.Accent, contentColor = DashColors.OnAccent),
            shape = DashShape.Medium,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
        ) { Text(action) }
    }
}

/** The manoeuvre glyph from the notification on a glowing accent disc. */
@Composable
internal fun ManeuverIcon(nav: NavState, size: Dp) {
    val accent = DashColors.Accent
    val glow = DashColors.Glow
    val bitmap = remember(nav.icon) { nav.icon?.asImageBitmap() }
    Box(
        modifier = Modifier
            .size(size)
            .drawBehind {
                if (glow > 0f) {
                    val r = this.size.minDimension * 0.85f
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(accent.copy(alpha = 0.45f * glow), Color.Transparent),
                            center = center, radius = r
                        ),
                        radius = r
                    )
                }
            }
            .clip(CircleShape)
            .background(DashColors.AccentBrush)
            .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = nav.instruction,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size * 0.62f)
            )
        } else {
            Icon(
                Icons.Filled.Directions,
                contentDescription = nav.instruction,
                tint = DashColors.OnAccent,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    }
}

/** The "On my way" button beside the ETA chips, as tall as the other actions used at speed. */
@Composable
private fun OnMyWayPill(onClick: () -> Unit) {
    val shape = DashShape.Pill
    val tap = rememberTapFeedback()
    Row(
        modifier = Modifier
            .clip(shape)
            .background(DashColors.Accent)
            .clickable { tap(); onClick() }
            .heightIn(min = DashSize.TouchPrimary)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Sms, contentDescription = null, tint = DashColors.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.onmyway_button), color = DashColors.OnAccent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

/**
 * Who gets the arrival time: the phone's favourites, the last one texted
 * first, and the text as it will go. One tap picks ([onPick] holds the text
 * back for a moment, see [PendingActionStrip]); the answer is spoken.
 */
@Composable
private fun OnMyWayDialog(onPick: (Favourite) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val contacts = remember { OnMyWay.contacts(context) }
    val preview = remember { OnMyWay.message(context) }
    AlertDialog(
        modifier = Modifier.keepClearOfWindows(),
        onDismissRequest = onDismiss,
        containerColor = DashColors.Card,
        title = { Text(stringResource(R.string.onmyway_title), color = DashColors.TextPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("“$preview”", color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (contacts.isEmpty()) {
                    Text(stringResource(R.string.onmyway_no_contacts), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
                contacts.take(6).forEach { fav ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = DashSize.MenuRow)
                            .clip(DashShape.Medium)
                            .itemFill(if (DashColors.Glass) DashColors.haze(0.06f) else DashColors.CardHi, DashShape.Medium)
                            .clickable { onDismiss(); onPick(fav) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = DashColors.Accent)
                        Spacer(Modifier.width(12.dp))
                        Text(fav.name, color = DashColors.TextPrimary, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.dash_cancel), color = DashColors.Muted) } }
    )
}

/** Small glass pill for an ETA segment ("12 min", "6.4 km", "09:48"). */
@Composable
internal fun InfoPill(text: String) {
    val shape = DashShape.Pill
    Text(
        text = text,
        color = DashColors.TextPrimary,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(shape)
            .itemFill(if (DashColors.Glass) DashColors.haze(0.08f) else DashColors.CardHi, shape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/**
 * Compact next-turn strip floated over the MapLibre map tile: glyph, distance
 * and street on one line, ETA underneath. Tap to bring the navigation app up.
 */
@Composable
internal fun DirectionsBanner(nav: NavState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val shape = DashShape.Medium
    Row(
        modifier = modifier
            .then(if (DashColors.Glass) glassPanel(shape) else Modifier.clip(shape).background(DashColors.Card.copy(alpha = 0.92f)).border(1.dp, DashColors.Line, shape))
            .clickable { openNavigationApp(context, nav) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ManeuverIcon(nav = nav, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                if (nav.distance.isNotEmpty()) {
                    Text(
                        text = nav.distance,
                        color = DashColors.TextPrimary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1
                    )
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    text = nav.instruction,
                    color = DashColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            if (nav.eta.isNotEmpty()) {
                Text(nav.eta, color = DashColors.TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}
