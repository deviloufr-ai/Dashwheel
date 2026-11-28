package com.openauto.dash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Traffic
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openauto.dash.roadwatch.RoadLook
import com.openauto.dash.roadwatch.RoadPainter
import com.openauto.dash.roadwatch.RoadShape
import kotlin.math.roundToInt

/**
 * The Drawn road tile (issue 179, experimental, offered while Experimental
 * features are on): the road ahead as the phone's Road watch draws its 3D road, from what its camera sees and the map under it, sent
 * over the link ([RoadWatch.sketch]) and painted here with the same
 * [RoadPainter]. The day look on a light theme, the night look on a dark one.
 * The painting itself is the Companion's DrawnScene.kt over again: the two
 * apps share no Compose code.
 */
@Composable
internal fun DrawnRoadTile(modifier: Modifier = Modifier) {
    val sketch by RoadWatch.sketch.collectAsState()
    // The car's own warning, decided with its own speed and indicators, over the phone's.
    val warning by RoadWatch.warning.collectAsState()
    val look = if (DashColors.Light) RoadLook.DAY else RoadLook.NIGHT
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    Card(modifier = modifier) {
        val picture = sketch
        if (picture == null) {
            Column(
                Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.Traffic, contentDescription = null, tint = DashColors.Muted, modifier = Modifier.size(32.dp))
                Text(stringResource(R.string.widgets_drawn_idle), color = DashColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
            return@Card
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            Canvas(Modifier.fillMaxSize()) {
                val lead = picture.things.firstOrNull { it.lead }
                val shown = picture.copy(warning = warning, leadLabel = lead?.let { "${it.z.roundToInt()} m" })
                for (s in RoadPainter.paint(shown, size.width, size.height, look)) when (s) {
                    is RoadShape.Poly -> {
                        val p = s.points
                        val path = Path().apply {
                            moveTo(p[0], p[1])
                            for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
                            close()
                        }
                        if (s.argb == s.argbBelow) drawPath(path, Color(s.argb)) else {
                            val ys = p.filterIndexed { i, _ -> i % 2 == 1 }
                            drawPath(path, Brush.verticalGradient(listOf(Color(s.argb), Color(s.argbBelow)), ys.min(), ys.max()))
                        }
                    }
                    is RoadShape.Disc -> {
                        if (s.argb != 0) drawCircle(Color(s.argb), s.r, Offset(s.x, s.y))
                        if (s.ringWidth > 0f) drawCircle(Color(s.ringArgb), s.r, Offset(s.x, s.y), style = Stroke(s.ringWidth))
                    }
                    is RoadShape.Label -> {
                        val text = measurer.measure(s.text, style)
                        val padX = 12.dp.toPx()
                        val padY = 5.dp.toPx()
                        val plate = Size(text.size.width + 2 * padX, text.size.height + 2 * padY)
                        val at = Offset(s.x - plate.width / 2, s.y - plate.height)
                        drawRoundRect(Color(s.argb), at, plate, CornerRadius(plate.height / 2))
                        drawText(text, Color(s.textArgb), Offset(at.x + padX, at.y + padY))
                    }
                }
            }
        }
    }
}
