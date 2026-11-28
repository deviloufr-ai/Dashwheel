package com.openauto.dash.companion

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import com.openauto.dash.roadwatch.RoadLook
import com.openauto.dash.roadwatch.RoadPainter
import com.openauto.dash.roadwatch.RoadShape
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

/** Road watch's screen: the camera's picture, the road drawn ([RoadDrawn]), or the 3D road ([DrawnScene]). */
enum class RoadViewKind { CAMERA, DRAWN, SCENE }

/**
 * Which of the drawn views the driver chose, kept from one drive to the next.
 * Both are experimental and offered only while the car's Experimental
 * features are on; the drawn road is the one shown until the driver picks
 * the other.
 */
object RoadViewChoice {
    private const val PREFS = "road_watch"
    private const val KEY = "view"

    private val _kind = MutableStateFlow(RoadViewKind.DRAWN)
    val kind: StateFlow<RoadViewKind> = _kind

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        _kind.value = RoadViewKind.entries.firstOrNull { it.name == saved } ?: RoadViewKind.DRAWN
    }

    fun set(context: Context, kind: RoadViewKind) {
        _kind.value = kind
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, kind.name).apply()
    }
}

/**
 * The 3D road (experimental, issue 179): the road in the look of the issue's
 * two pictures, pale by day and blue by night, with what the camera sees on
 * it (vehicles, people, traffic lights, stop signs) and the map under it (the
 * road's bends, the roads that meet it, the buildings). [RoadPainter]'s
 * shapes, painted as they come; the head unit's Drawn road tile paints the
 * same list (DrawnRoadTile.kt there: the two apps share no Compose code).
 */
@Composable
internal fun DrawnScene(view: RoadView, modifier: Modifier = Modifier) {
    val look = if (view.dark) RoadLook.NIGHT else RoadLook.DAY
    val label = view.lead?.let { stringResource(R.string.road_lead_m, it.distanceM.roundToInt()) }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    // Between two of the camera's frames the lane's dashes go on passing under the car, thirty times a second at most.
    var rolled by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(view.picture) {
        rolled = 0f
        val mps = (view.speedKmh ?: 0f) / 3.6f
        if (mps < 1f) return@LaunchedEffect
        val start = withFrameNanos { it }
        var shown = start
        while (true) {
            val t = withFrameNanos { it }
            if (t - shown < 33_000_000L) continue
            shown = t
            rolled = mps * ((t - start) / 1e9f).coerceAtMost(0.5f)
        }
    }
    Canvas(modifier) {
        val picture = view.picture.copy(leadLabel = label, travelledM = view.picture.travelledM + rolled)
        for (s in RoadPainter.paint(picture, size.width, size.height, look)) when (s) {
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
