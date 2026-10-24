package com.openauto.dash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openauto.dash.vehicle.rememberDoors
import java.util.Date
import kotlin.math.min

/*
 * Expressive skin, the car in its badge: the driver's car standing in a big
 * scalloped cookie of periwinkle that turns slowly while parked, the car
 * itself wider than the badge so its nose and tail break out of the frame.
 * The car is the live 3D model when there is one (Car3D.kt), else the car
 * pack's picture (MyCarLook.kt), else the drawn MPV Panorama uses. Under it,
 * white chips with what is known: the arrival, the rain to come, the range.
 */

/** The badge's one turn while parked, in ms: a slow drift, not a spin. */
private const val BADGE_TURN_MS = 60_000


/**
 * Fuel and range. With the room, the hero of the design: the car in its
 * badge with the chips under it. On a small tile, a periwinkle card with the
 * range and the fuel's gauge; fuel not learned yet there falls back to the
 * standard tile (on a white card), which explains how to learn it.
 */
@Composable
internal fun ExRange(item: DashboardItem, env: SkinTileEnv) {
    val fuel = rememberFuel()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val hero = maxWidth >= 300.dp && maxHeight >= 240.dp
        when {
            hero -> ExCarBadge(env, fuel, maxWidth, maxHeight)
            fuel == null -> Box(Modifier.fillMaxSize().exCard(ExTone.chip, editing = env.editing)) { StandardSkinnedTile(item, env) }
            else -> ExRangeCard(fuel, env.editing)
        }
    }
}

/** Where the badge and the car stand in a stage [w] x [h]: the badge's box, and the car's footprint (its width, its wheels' line). */
private class BadgeGeometry(w: Float, h: Float) {
    val d = min(w * 0.94f, h * 0.98f)
    val badge = Rect(Offset((w - d) / 2f, (h - d) / 2f), Size(d, d))
    val carW = min(d * 1.12f, w * 0.99f)
    val floor = badge.center.y + d * 0.3f
}

@Composable
private fun ExCarBadge(env: SkinTileEnv, fuel: FuelInfo?, w: Dp, h: Dp) {
    val chips = rememberBadgeChips(fuel)
    val chipH = 48.dp
    val stageH = if (chips.isEmpty()) h else h - chipH - 12.dp
    val doors = rememberDoors()
    val open = doors?.anyOpen == true
    val said = stringResource(R.string.expressive_car_cd)
    // While arranging, the dashed outline of the skin's cards says the stage can be moved.
    val arranging = if (env.editing) Modifier.exCard(Color.Transparent, editing = true, shadow = false) else Modifier
    Box(Modifier.fillMaxSize().then(arranging).clipToBounds().semantics { contentDescription = said }) {
        Box(Modifier.size(w, stageH)) {
            ExBadge(Modifier.fillMaxSize())
            val model = rememberCar3DModel()
            if (model != null) {
                Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    ExGroundShadow(Modifier.requiredSize(w, stageH))
                    Car3DProblems(model, emptyList(), null, false, {}, Modifier.requiredSize(w, stageH), chips = false)
                }
            } else {
                ExCarPicture(open, Modifier.fillMaxSize())
            }
        }
        if (chips.isNotEmpty()) {
            ExFitRow(10.dp, Modifier.align(Alignment.BottomStart).padding(start = 4.dp)) {
                chips.forEach { (text, dot) -> ExChip(text, dot, 19f) }
            }
        }
    }
}

/** What the chips under the car say, as far as it is known: the arrival, the rain, the range. */
@Composable
private fun rememberBadgeChips(fuel: FuelInfo?): List<Pair<String, Color>> {
    val nav by NavDirections.state.collectAsState()
    val weather = rememberWeather()
    val timeFmt = rememberTimeFormat()
    val nowMs = rememberWallClock(60_000L).longValue
    val units = LocalUnits.current
    val arrival = if (nav.active) arrivalClock(nav) else null
    val list = ArrayList<Pair<String, Color>>(3)
    if (arrival != null) list += stringResource(R.string.expressive_arrive, arrival) to ExTone.coral
    val w = weather
    if (w?.rainFromMs != null) {
        val text = if (w.rainUntilMs != null && w.rainFromMs <= nowMs) stringResource(R.string.expressive_rain_until, timeFmt.format(Date(w.rainUntilMs)))
        else stringResource(R.string.expressive_rain_from, timeFmt.format(Date(w.rainFromMs)))
        list += text to ExTone.dotRain
    }
    if (fuel != null) list += units.distanceText(fuel.rangeKm) to (if (fuel.percent <= SKIN_LOW_FUEL_PCT) DashColors.Warning else ExTone.dotRange)
    return list
}

/**
 * The scalloped badge: a 12-bump cookie of periwinkle with a lighter cookie
 * inside it for depth, turning once a minute while parked (its own layer, so
 * a step only turns the picture; still while driving, on a hidden page and
 * with the effects off).
 */
@Composable
private fun ExBadge(modifier: Modifier) {
    val fill = ExTone.badge
    val light = DashColors.Light
    val turn = rememberSpin(BADGE_TURN_MS)
    BoxWithConstraints(modifier) {
        val g = BadgeGeometry(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val origin = androidx.compose.ui.graphics.TransformOrigin(
            g.badge.center.x / constraints.maxWidth.coerceAtLeast(1), g.badge.center.y / constraints.maxHeight.coerceAtLeast(1)
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationZ = turn.value
                    transformOrigin = origin
                }
                .cachedDraw(fill, light) {
                    val geo = BadgeGeometry(size.width, size.height)
                    val outer = polarPath(Path(), geo.badge.size, 12, 0.075f)
                    val innerD = geo.d * 0.86f
                    val inner = polarPath(Path(), Size(innerD, innerD), 12, 0.075f)
                    val innerInk = lerp(fill, if (light) Color.White else Color(0xFF3D5486), if (light) 0.35f else 0.4f)
                    val inset = (geo.d - innerD) / 2f
                    onDrawBehind {
                        translate(geo.badge.left, geo.badge.top) {
                            drawPath(outer, fill)
                            translate(inset, inset) { drawPath(inner, innerInk) }
                        }
                    }
                }
        )
    }
}

/** The soft contact shadow under the car's wheels, flattened on the floor. */
@Composable
private fun ExGroundShadow(modifier: Modifier) {
    val light = DashColors.Light
    Spacer(
        modifier.cachedDraw(light) {
            val geo = BadgeGeometry(size.width, size.height)
            val c = Offset(geo.badge.center.x, geo.floor)
            val rx = geo.carW * 0.48f
            val shadow = Brush.radialGradient(
                0f to Color.Black.copy(alpha = if (light) 0.22f else 0.55f), 0.7f to Color.Transparent,
                center = c, radius = rx
            )
            onDrawBehind { scale(1f, 0.12f, c) { drawCircle(shadow, rx, c) } }
        }
    )
}

/**
 * The car as a picture standing on the badge's floor line, wider than the
 * badge: the car pack's 3/4 view as a photo, else the drawn MPV; a door open
 * traces it in amber. Drawn once into its own layer.
 */
@Composable
private fun ExCarPicture(open: Boolean, modifier: Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val view = mine?.portrait
    val light = DashColors.Light
    val warn = DashColors.Warning
    val ink = DashColors.TextPrimary
    Spacer(
        modifier
            .graphicsLayer()
            .cachedDraw(view, open, light, warn, ink) {
                val geo = BadgeGeometry(size.width, size.height)
                val aspect = view?.aspect ?: PANO_CAR_ASPECT
                val cw = min(geo.carW, (geo.floor - geo.badge.top) * aspect)
                val ch = cw / aspect
                val car = Rect(Offset(geo.badge.center.x - cw / 2f, geo.floor - ch), Size(cw, ch))
                val c = Offset(car.center.x, geo.floor - ch * 0.02f)
                val rx = cw * 0.5f
                val shadow = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = if (light) 0.3f else 0.6f), 0.7f to Color.Transparent,
                    center = c, radius = rx
                )
                onDrawBehind {
                    scale(1f, 0.11f, c) { drawCircle(shadow, rx, c) }
                    if (view != null) drawCarView(view, CarLookStyle.PHOTO, car, ink, warn, open)
                    else drawPanoCar(car, light, open, warn)
                }
            }
    )
}
