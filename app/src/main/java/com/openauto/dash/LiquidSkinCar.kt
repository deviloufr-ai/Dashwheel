package com.openauto.dash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.openauto.dash.vehicle.rememberDoors
import kotlin.math.min

/*
 * Liquid skin, the hero: the driver's own car big in the middle of Home on a
 * soft floor shadow, the wallpaper's pink and blue catching its body. In live
 * 3D when the car pack brought a model (Car3D.kt), else the pack's 3/4
 * picture (MyCarLook.kt), else the drawn MPV Panorama also uses. Under it a
 * pair of glass chips: the range and the coolant (and the doors while one is
 * open). Nothing here moves by itself: the 3D car only draws when it turns.
 */

/** The hero's size at least: under it a range tile is a card, not a stage. */
internal val LQ_HERO_MIN_W: Dp = 380.dp
internal val LQ_HERO_MIN_H: Dp = 250.dp

/** The chips' row under the car: their height and the gap above them. */
private val CHIP_ROW = 86.dp


/**
 * The stage filling [modifier]'s box: the car (3D, picture or drawn) on its
 * floor shadow, and the chips under it. While arranging, or on a page beside
 * the one on screen, the 3D car stands in as its picture.
 */
@Composable
internal fun LqHero(env: SkinTileEnv, fuel: FuelInfo?, modifier: Modifier) {
    val mine by MyCarLook.shown.collectAsState()
    val doors = rememberDoors()
    val model = rememberCar3DModel()
    val onScreen = LocalPageOnScreen.current
    val live3d = model != null && !env.editing && onScreen
    val view = mine?.portrait
    val open = doors?.anyOpen == true
    val coolant = rememberCoolantC(env)
    val units = LocalUnits.current
    val said = stringResource(if (open) R.string.liquid_car_open_cd else R.string.liquid_car_cd)
    val chips = ArrayList<Triple<String, String, Boolean>>(3)
    fuel?.let { chips += Triple(stringResource(R.string.liquid_range), units.distanceText(it.rangeKm), it.percent <= SKIN_LOW_FUEL_PCT) }
    if (coolant > 0) chips += Triple(stringResource(R.string.liquid_coolant), "${units.temp(coolant)}°", coolant >= COOLANT_WARNING_C)
    if (open) chips += Triple(stringResource(R.string.liquid_doors), stringResource(R.string.liquid_doors_open), true)
    BoxWithConstraints(modifier.semantics { contentDescription = said }) {
        val stageH = if (chips.isEmpty()) maxHeight else maxHeight - CHIP_ROW
        Box(Modifier.fillMaxWidth().height(stageH)) {
            if (live3d && model != null) {
                FloorShadow3D(Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
                    Car3DProblems(model, emptyList(), null, false, {}, Modifier.fillMaxSize(), chips = false)
                }
            } else {
                LqCarPicture(view, open, Modifier.fillMaxSize())
            }
        }
        if (chips.isNotEmpty()) {
            val size = (maxWidth.value / 22f).coerceIn(22f, 30f)
            val chipMax = maxWidth / chips.size - 14.dp
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                chips.forEach { (label, value, warn) -> HeroChip(label, value, warn, size, chipMax) }
            }
        }
    }
}

/** One chip under the car: a small card of glass with the reading's name over its value. */
@Composable
private fun HeroChip(label: String, value: String, warn: Boolean, size: Float, maxW: Dp) {
    Column(
        Modifier
            .widthIn(max = maxW)
            .liquidGlass(24.dp)
            .padding(start = 20.dp, end = 26.dp, top = 9.dp, bottom = 10.dp)
    ) {
        LqReading(label, value, warn, size)
    }
}

/** Where the car stands in a box of [size]: as wide as fits, its wheels on a floor low in the box. */
private fun carRect(size: Size, aspect: Float): Rect {
    val w = size.width
    val h = size.height
    val floor = h * 0.82f
    val top = h * 0.06f
    val cw = min(w * 0.9f, (floor - top) * aspect)
    val ch = cw / aspect
    return Rect(Offset((w - cw) / 2f, floor - ch), Size(cw, ch))
}

/** The car's soft floor shadow under [car]: a dark core and a wider faint halo, no blur. */
private fun DrawScope.floorShadow(car: Rect, light: Boolean) {
    val c = Offset(car.center.x, car.bottom - car.height * 0.03f)
    val ink = if (light) Color(0xFF2A2A6E) else Color.Black
    val rx = car.width * 0.6f
    val wide = Brush.radialGradient(listOf(ink.copy(alpha = if (light) 0.16f else 0.4f), Color.Transparent), c, rx)
    val core = Brush.radialGradient(listOf(ink.copy(alpha = if (light) 0.3f else 0.6f), Color.Transparent), c, rx * 0.8f)
    scale(1f, 0.16f, c) { drawCircle(wide, rx, c) }
    scale(1f, 0.1f, c) { drawCircle(core, rx * 0.8f, c) }
}

/**
 * The car as a picture with its shadow, the wallpaper's light on its body
 * (pink low, a cool sheen on the roof), in one offscreen layer drawn only
 * when the car or the look changes. A door open traces it in amber.
 */
@Composable
private fun LqCarPicture(view: CarView?, open: Boolean, modifier: Modifier) {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val warn = DashColors.Warning
    val ink = DashColors.TextPrimary
    Spacer(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .cachedDraw(view, light, effects, open, warn, ink) {
                val aspect = view?.aspect ?: PANO_CAR_ASPECT
                val car = carRect(size, aspect)
                val lit = effects != DashEffects.NONE
                val under = if (lit) Brush.verticalGradient(
                    listOf(Color.Transparent, Color(0xFFFF3D7F).copy(alpha = (if (light) 0.1f else 0.22f) * effects.scale)),
                    car.top + car.height * 0.5f, car.bottom
                ) else null
                val roof = if (lit) Brush.radialGradient(
                    listOf(Color(0xFF9FD0FF).copy(alpha = (if (light) 0.12f else 0.2f) * effects.scale), Color.Transparent),
                    Offset(car.center.x + car.width * 0.15f, car.top), car.width * 0.5f
                ) else null
                onDrawBehind {
                    floorShadow(car, light)
                    if (view != null) drawCarView(view, CarLookStyle.PHOTO, car, ink, warn, open)
                    else drawPanoCar(car, light, open, warn)
                    if (under != null) drawRect(under, car.topLeft, car.size, blendMode = BlendMode.SrcAtop)
                    if (roof != null) drawRect(roof, car.topLeft, car.size, blendMode = BlendMode.SrcAtop)
                }
            }
    )
}

/** The floor shadow under the 3D car, where the whole car's view puts its wheels (it fills the width, centred). */
@Composable
private fun FloorShadow3D(modifier: Modifier) {
    val light = DashColors.Light
    Spacer(
        modifier.cachedDraw(light) {
            val w = size.width
            val h = size.height
            val cw = w * 0.86f
            val bottom = (h / 2f + cw * 0.2f).coerceAtMost(h * 0.92f)
            val car = Rect(Offset((w - cw) / 2f, bottom - cw * 0.4f), Size(cw, cw * 0.4f))
            onDrawBehind { floorShadow(car, light) }
        }
    )
}
