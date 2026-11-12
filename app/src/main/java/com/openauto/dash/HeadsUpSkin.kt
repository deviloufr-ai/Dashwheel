package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min

/*
 * Heads-up: the map is the page (Canvas's backdrop, CanvasSkin.kt) and every
 * widget lies straight on it with no panel, as a projection in the glass
 * would (Open's frameless faces). The palette carries both: Skin CANVAS for
 * the map, Open for the faces (DashTheme.kt). Its one face of its own is the
 * music, a disc: the cover cut round, the track's progress as a ring around
 * it, the title beside it and bare keys. Without panels, legibility comes
 * from the map's fades at the top and foot and a dark halo under the type.
 */

/** True while the Heads-up look is on: Canvas's page with Open's faces. */
internal fun headsUpLook(): Boolean = DashColors.Skin == DashSkin.CANVAS && DashColors.Open

/** A soft dark halo under type laid on the map; none by day, where the type is dark on a light map. */
internal fun headsUpHalo(): Shadow? =
    if (DashColors.Light) null else Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), blurRadius = 8f)

/** The disc's widest: past it the title and keys get the room. */
private val DISC_MAX = 184.dp
private val DISC_MIN = 72.dp
private const val RING_DP = 4f

/** The music as a disc, with its progress as a ring (Heads-up's face for MEDIA). */
@Composable
internal fun HeadsUpMedia(env: SkinTileEnv) {
    val ms = env.mediaState
    val access = env.hasMediaAccess
    val context = env.context
    val hasTrack = ms.hasMedia && ms.title.isNotBlank()
    val fraction = if (access && ms.durationMs > 0L) rememberMediaFraction(ms, env.mediaController) else 0f
    val halo = headsUpHalo()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (!access && !env.editing) Modifier.clickable { CarMediaController.openNotificationAccessSettings(context) } else Modifier)
            .padding(8.dp)
    ) {
        val disc = min(maxHeight, maxWidth * 0.42f).coerceIn(DISC_MIN, DISC_MAX)
        val wide = maxWidth - disc >= 300.dp
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Disc(ms.artwork, fraction, disc)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                val title = when {
                    !access -> stringResource(R.string.info_media_access_needed)
                    hasTrack -> ms.title
                    else -> stringResource(R.string.info_nothing_playing)
                }
                Text(
                    title, color = DashColors.TextPrimary, fontSize = fixedSp(if (wide) 30f else 24f), fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = halo)
                )
                val sub = if (!access) stringResource(R.string.info_media_grant_access) else ms.artist
                if (sub.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        sub, color = if (!access) DashColors.AccentInk else DashColors.TextSecondary, fontSize = fixedSp(18f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = halo)
                    )
                }
                if (access) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BareKey(Icons.Filled.SkipPrevious, stringResource(R.string.info_media_previous)) { env.mediaController.previous() }
                        BareKey(
                            if (ms.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            stringResource(R.string.info_media_play_pause)
                        ) { env.mediaController.playPause() }
                        BareKey(Icons.Filled.SkipNext, stringResource(R.string.info_media_next)) { env.mediaController.next() }
                    }
                }
            }
        }
    }
}

/** The cover cut round inside its progress ring, with a hole at the centre like a record's. */
@Composable
private fun Disc(cover: android.graphics.Bitmap?, fraction: Float, size: Dp) {
    val bitmap = remember(cover) { cover?.asImageBitmap() }
    val ring = DashColors.Accent
    val track = DashColors.TextPrimary.copy(alpha = 0.22f)
    val hole = DashColors.Background
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = RING_DP.dp.toPx()
            val inset = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            if (fraction > 0f) {
                drawArc(ring, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        val face = size - 28.dp
        Box(
            Modifier.size(face).clip(CircleShape).background(DashColors.CardHi),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Filled.MusicNote, contentDescription = null, tint = DashColors.TextSecondary, modifier = Modifier.size(face * 0.36f))
        }
        Box(Modifier.size(face * 0.12f).clip(CircleShape).background(hole))
    }
}

/** A media key with no fill: the icon alone, on a touch target the thumb finds. */
@Composable
private fun BareKey(icon: ImageVector, description: String, onClick: () -> Unit) {
    val tap = rememberTapFeedback()
    Box(
        modifier = Modifier
            .size(DashSize.TouchPrimary)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = description) { tap(); onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = DashColors.TextPrimary, modifier = Modifier.size(32.dp))
    }
}
