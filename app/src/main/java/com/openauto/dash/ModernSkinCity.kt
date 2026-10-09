package com.openauto.dash

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Modern skin: the city under the cards. A dark street plan (light grey by
 * day) seen from above at a slant, as if the screen were tilted back: block
 * tops raised off the ground, a river with its bridges, parks with trees and,
 * at night, street lamps; then a route in the skin's blue running from the
 * car up its avenue, right at a cross street and on into the haze, its white
 * dashes flowing forward. It is decoration, not navigation: one fixed plan,
 * no map data.
 *
 * Everything still is drawn once into a bitmap per size and look ([CityScene],
 * kept by [ModernCity]); each step of the ambient ticker only lays that bitmap
 * down and draws the dashes (a few dozen short lines) and the car's puck.
 */

// The camera, in plan units: x to the right, z ahead of the camera, the ground at height 0.

/** How far ahead of the camera the car sits. */
private const val CAR_Z = 4f

/** Half the width of ground seen across the screen at the car. */
private const val HALF_SPAN = 1.2f

/** Camera height over [CAR_Z]: how steeply the plan is seen (0.55 is the mockup's tilt, depth about half as deep as wide). */
private const val TILT = 0.55f

/** The car's height on the scene, as a share of it from the top. */
private const val CAR_AT = 0.84f

/** Street grid: avenues run ahead every [AV_PITCH], cross streets every [ST_PITCH] through [ST_ORIGIN]. */
private const val AV_PITCH = 0.55f
private const val ST_PITCH = 0.5f
private const val ST_ORIGIN = 4.2f

/** The route turns right at the cross street at [TURN_Z] onto the avenue at [TURN_X], with bends of [TURN_R]. */
private const val TURN_Z = 5.2f
private const val TURN_X = 1.1f
private const val TURN_R = 0.16f

/** Nothing is planned past this depth: an upright screen sees the horizon, and the haze has it long before. */
private const val Z_LIMIT = 22f

/** Widths across the route (core, glow) and of its dashes, their length and spacing along it. */
private const val ROUTE_CORE = 0.05f
private const val ROUTE_GLOW = 0.13f
private const val DASH_W = 0.014f
private const val DASH_LEN = 0.09f
private const val DASH_PITCH = 0.36f

/** The river: its middle's depth, its half width and how far it wanders. */
private const val RIVER_Z = 6.15f
private const val RIVER_HALF = 0.2f
private const val RIVER_SWING = 0.25f

private const val SIDEWALK = 0.018f

/** How much of the page colour the farthest planned ground takes on. */
private const val FOG_MAX = 0.62f

/** One full pass of the dashes along their spacing (the mockup's 1.2 s), and one pulse of the puck. */
internal const val CITY_FLOW_MS = 1_200
internal const val CITY_PULSE_MS = 2_000

/** The car's length on the page background, as a share of the screen's shorter side. */
internal const val PAGE_CAR_SHARE = 0.15f

private class CityColors(
    val page: Color,
    val ground: Color,
    val street: Color,
    val side: Color,
    val sideDark: Color,
    val top: Color,
    val edge: Color,
    val river: Color,
    val bank: Color,
    val park: Color,
    val tree: Color,
    val treeLight: Color,
    val lamp: Color?,
    val route: Color,
    val routeCore: Color,
    val routeGlow: Float,
    val pool: Float,
    val carBody: Color,
    val carGlass: Color,
    val carEdge: Color
)

/** The mockup's night plan: navy ground, slate blocks with lighter tops, a deep blue river. */
private val NightCity = CityColors(
    page = Color(0xFF0B0E14), ground = Color(0xFF10151E), street = Color(0xFF2A3549),
    side = Color(0xFF1B2332), sideDark = Color(0xFF151C29), top = Color(0xFF253049), edge = Color(0xFF3A4966),
    river = Color(0xFF0D2138), bank = Color(0xFF1C3D66), park = Color(0xFF122A20),
    tree = Color(0xFF173827), treeLight = Color(0xFF235A40), lamp = Color(0xFFFFD27A),
    route = Color(0xFF6EA8FE), routeCore = Color(0xFF7DB4FF), routeGlow = 0.36f, pool = 0.16f,
    carBody = Color(0xFFDCE4EF), carGlass = Color(0xD91A2333), carEdge = Color(0xFF9CC3FF)
)

/** By day a light grey plan for dark ink: white streets and block tops, pale water and parks, a deeper blue route. */
private val DayCity = CityColors(
    page = Color(0xFFEEF1F5), ground = Color(0xFFDCE1E8), street = Color(0xFFF7F8FA),
    side = Color(0xFFC3CAD6), sideDark = Color(0xFFB4BCCA), top = Color(0xFFFBFCFD), edge = Color(0xFFFFFFFF),
    river = Color(0xFFB7D0EC), bank = Color(0xFFD7E5F5), park = Color(0xFFCAE4D0),
    tree = Color(0xFFA9D3B5), treeLight = Color(0xFFC6E6CE), lamp = null,
    route = Color(0xFF2F6FE0), routeCore = Color(0xFF2F6FE0), routeGlow = 0.22f, pool = 0.08f,
    carBody = Color(0xFFFFFFFF), carGlass = Color(0xCC2A3950), carEdge = Color(0xFF1F55C0)
)

/** The skin's route blue, as the city draws it: the same in every tile that shows the plan. */
internal fun cityRouteColor(light: Boolean): Color = if (light) DayCity.route else NightCity.route

/** A stable 0..1 for a cell of the plan and a [salt]: the same plan at every size. */
private fun hash(a: Int, b: Int, salt: Int): Float {
    var h = a * 73_856_093 xor b * 19_349_663 xor salt * 83_492_791
    h = h xor (h ushr 13)
    h *= 0x5bd1e995
    h = h xor (h ushr 15)
    return (h and 0xFFFFFF) / 16_777_216f
}

/**
 * The plan seen in a [width]×[height] px box: the projection, the route as
 * points along it, the still drawing as a bitmap made on first use, and the
 * moving parts drawn over it. [glow] scales the route's glow and the light
 * pool (1 full, 0.5 reduced, 0 with effects off); [route] false leaves the
 * streets empty (no route to show).
 */
internal class CityScene(val width: Int, val height: Int, val light: Boolean, val glow: Float, val route: Boolean) {
    private val w = width.toFloat()
    private val h = height.toFloat()
    private val c = if (light) DayCity else NightCity
    private val cx = w / 2f
    private val lateral = (w / 2f) * CAR_Z / HALF_SPAN

    /** The car's puck, where the route starts on screen. */
    val car = Offset(cx, h * CAR_AT)

    // A tall box (an upright screen) would see the horizon a third of the way
    // down, over an empty band of haze: it looks down more steeply instead, so
    // the horizon stays near the top.
    private val tilt = max(TILT, (car.y - h * 0.08f) / lateral)
    private val camH = tilt * CAR_Z
    private val vertical = lateral * camH
    private val horizonY = car.y - vertical / CAR_Z
    private val zNear = depthAt(h)
    private val zFar = (if (horizonY < 0f) depthAt(0f) else Z_LIMIT).coerceIn(CAR_Z + 1f, Z_LIMIT)

    /** The haze over the far plan: solid down to [hazeSolid], gone at [hazeEnd]. */
    private val hazeEnd = (if (horizonY > 0f) horizonY + h * 0.16f else h * 0.36f).coerceAtMost(h * 0.7f)
    private val hazeSolid = max(hazeEnd * 0.25f, horizonY + h * 0.02f).coerceAtMost(hazeEnd * 0.8f)

    // The route's middle line, as points with their distance along it.
    private val rx: FloatArray
    private val rz: FloatArray
    private val rs: FloatArray

    // Scratch for [locate], so the dashes cost no allocation per frame.
    private var qx = 0f
    private var qz = 0f

    private var bitmap: ImageBitmap? = null

    init {
        val xs = ArrayList<Float>()
        val zs = ArrayList<Float>()
        xs += 0f; zs += zNear - 0.4f
        // Ahead, then a right bend onto the cross street...
        for (i in 0..8) {
            val a = PI - PI / 2 * i / 8
            xs += TURN_R + TURN_R * cos(a).toFloat(); zs += TURN_Z - TURN_R + TURN_R * sin(a).toFloat()
        }
        // ...and a left one back onto the avenue, then on into the haze.
        for (i in 0..8) {
            val a = -PI / 2 + PI / 2 * i / 8
            xs += TURN_X - TURN_R + TURN_R * cos(a).toFloat(); zs += TURN_Z + TURN_R + TURN_R * sin(a).toFloat()
        }
        xs += TURN_X; zs += max(zFar + 1f, TURN_Z + 1f)
        rx = xs.toFloatArray()
        rz = zs.toFloatArray()
        rs = FloatArray(rx.size)
        for (i in 1 until rx.size) {
            val dx = rx[i] - rx[i - 1]
            val dz = rz[i] - rz[i - 1]
            rs[i] = rs[i - 1] + sqrt(dx * dx + dz * dz)
        }
    }

    fun matches(width: Int, height: Int, light: Boolean, glow: Float, route: Boolean): Boolean =
        this.width == width && this.height == height && this.light == light && this.glow == glow && this.route == route

    private fun depthAt(y: Float): Float = if (y > horizonY + 1f) vertical / (y - horizonY) else Z_LIMIT
    private fun sx(x: Float, z: Float): Float = cx + lateral * x / z
    private fun sy(z: Float, e: Float = 0f): Float = horizonY + vertical * (1f - e / camH) / z

    /** Half the width of ground the screen holds at depth [z], with a street to spare. */
    private fun span(z: Float): Float = (w / 2f) * z / lateral + AV_PITCH

    private fun fogAt(z: Float): Float = ((z - CAR_Z) / (zFar - CAR_Z)).coerceIn(0f, 1f).pow(1.2f) * FOG_MAX
    private fun fogged(color: Color, z: Float): Color = lerp(color, c.page, fogAt(z))
    private fun riverZ(x: Float): Float = RIVER_Z + RIVER_SWING * sin(1.3f * x + 0.6f)

    /** 0 inside the solid haze, 1 below it, for the dashes that are drawn over the bitmap. */
    private fun hazeFade(y: Float): Float = ((y - hazeSolid) / (hazeEnd - hazeSolid)).coerceIn(0f, 1f)

    /** Avenue number [k]'s width: the car's and the route's wider. */
    private fun avenueWidth(k: Int): Float = when {
        k == 0 -> 0.085f
        k == 2 -> 0.08f
        k % 3 == 0 -> 0.07f
        else -> 0.055f
    }

    private fun streetZ(j: Int): Float = ST_ORIGIN + j * ST_PITCH
    private fun streetWidth(j: Int): Float = if (abs(streetZ(j) - TURN_Z) < 0.01f) 0.075f else 0.05f
    private fun riverStreet(j: Int): Boolean = abs(streetZ(j) - RIVER_Z) < RIVER_HALF + RIVER_SWING + 0.05f

    /** The still part of the scene, drawn on first use. */
    fun raster(): ImageBitmap = bitmap ?: ImageBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1)).also { bmp ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(w, h)) { drawPlan() }
        bitmap = bmp
    }

    private fun DrawScope.drawPlan() {
        val px = max(1f, min(w, h) / 480f)
        val path = Path()
        drawRect(c.page)
        val farY = sy(zFar).coerceAtLeast(0f)
        drawRect(
            Brush.verticalGradient(listOf(fogged(c.ground, zFar), c.ground), startY = farY, endY = h),
            topLeft = Offset(0f, farY), size = Size(w, h - farY)
        )
        drawRiver(path, px)
        drawStreets(path)
        drawBlocks(path, px)
        if (c.lamp != null) drawLamps(c.lamp)
        if (route) drawRoute(path)
        // The far plan melts into the page under the top bar.
        drawRect(
            Brush.verticalGradient(
                0f to c.page, hazeSolid / hazeEnd to c.page, 1f to c.page.copy(alpha = 0f),
                startY = 0f, endY = hazeEnd
            ),
            size = Size(w, hazeEnd)
        )
    }

    private fun DrawScope.drawRiver(path: Path, px: Float) {
        if (RIVER_Z - RIVER_HALF - RIVER_SWING > zFar) return
        val reach = span(RIVER_Z + RIVER_SWING + RIVER_HALF) + 0.5f
        val steps = (reach * 2f / 0.08f).toInt().coerceAtLeast(2)
        fun x(i: Int) = -reach + reach * 2f * i / steps
        path.reset()
        for (i in 0..steps) {
            val xx = x(i)
            val z = riverZ(xx) - RIVER_HALF
            if (i == 0) path.moveTo(sx(xx, z), sy(z)) else path.lineTo(sx(xx, z), sy(z))
        }
        for (i in steps downTo 0) {
            val xx = x(i)
            val z = riverZ(xx) + RIVER_HALF
            path.lineTo(sx(xx, z), sy(z))
        }
        path.close()
        drawPath(path, fogged(c.river, RIVER_Z))
        // A lighter bank along each side.
        val bank = Stroke(1.5f * px)
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            for (i in 0..steps) {
                val xx = x(i)
                val z = riverZ(xx) + side * RIVER_HALF
                if (i == 0) path.moveTo(sx(xx, z), sy(z)) else path.lineTo(sx(xx, z), sy(z))
            }
            drawPath(path, fogged(c.bank, RIVER_Z), style = bank)
        }
    }

    private fun DrawScope.drawStreets(path: Path) {
        val far = sy(zFar).coerceAtLeast(0f)
        val brush = Brush.verticalGradient(listOf(fogged(c.street, zFar), c.street), startY = far, endY = h)
        val zA = (zNear - 0.3f).coerceAtLeast(0.5f)
        val zB = zFar + 0.5f
        // Cross streets (none along the river: it has the banks).
        val j0 = floor((zA - ST_ORIGIN) / ST_PITCH).toInt()
        val j1 = ceil((zB - ST_ORIGIN) / ST_PITCH).toInt()
        for (j in j0..j1) {
            if (riverStreet(j)) continue
            val z = streetZ(j)
            if (z <= zA - ST_PITCH) continue
            val half = streetWidth(j) / 2f
            val reach = span(z + half)
            quad(path, -reach, z - half, reach, z - half, reach, z + half, -reach, z + half)
            drawPath(path, brush)
        }
        // Avenues, running to the far edge (over the river: bridges).
        val k = ceil(span(zB) / AV_PITCH).toInt()
        for (i in -k..k) {
            val x = i * AV_PITCH
            val half = avenueWidth(i) / 2f
            quad(path, x - half, zA, x + half, zA, x + half, zB, x - half, zB)
            drawPath(path, brush)
        }
    }

    /** [path] as the ground quad through four plan points. */
    private fun quad(path: Path, x0: Float, z0: Float, x1: Float, z1: Float, x2: Float, z2: Float, x3: Float, z3: Float) {
        path.reset()
        path.moveTo(sx(x0, z0), sy(z0))
        path.lineTo(sx(x1, z1), sy(z1))
        path.lineTo(sx(x2, z2), sy(z2))
        path.lineTo(sx(x3, z3), sy(z3))
        path.close()
    }

    /** Every block between the streets, far rows first and the outer ones of a row first, so nearer faces cover farther ones. */
    private fun DrawScope.drawBlocks(path: Path, px: Float) {
        val j0 = floor((zNear - 0.6f - ST_ORIGIN) / ST_PITCH).toInt()
        val j1 = ceil((zFar - ST_ORIGIN) / ST_PITCH).toInt()
        for (j in j1 downTo j0) {
            val zA = streetZ(j) + streetWidth(j) / 2f + SIDEWALK
            val zB = streetZ(j + 1) - streetWidth(j + 1) / 2f - SIDEWALK
            // Rows wholly inside the solid haze would be drawn for nothing (an upright screen plans far).
            if (zA > zFar || zB < 0.5f || sy(zA, 0.2f) < hazeSolid) continue
            val k = ceil(span(zB) / AV_PITCH).toInt()
            val order = (-k until k).sortedByDescending { abs(it + 0.5f) }
            for (i in order) {
                val xA = i * AV_PITCH + avenueWidth(i) / 2f + SIDEWALK
                val xB = (i + 1) * AV_PITCH - avenueWidth(i + 1) / 2f - SIDEWALK
                if (wet(xA, xB, zA, zB)) continue
                val park = hash(i, j, 1) < 0.06f || (i == -3 && j == -1) || (i == 2 && j == 3)
                if (park) {
                    drawPark(path, i, j, xA, xB, zA, zB, px)
                    continue
                }
                // One block or two buildings side by side, sometimes two deep.
                val splitX = hash(i, j, 2) < 0.4f
                val splitZ = hash(i, j, 3) < 0.3f
                val mx = xA + (xB - xA) * (0.4f + 0.2f * hash(i, j, 4))
                val mz = zA + (zB - zA) * (0.45f + 0.1f * hash(i, j, 5))
                val gap = 0.025f
                val xs = if (splitX) floatArrayOf(xA, mx - gap / 2f, mx + gap / 2f, xB) else floatArrayOf(xA, xB)
                val zs = if (splitZ) floatArrayOf(zA, mz - gap / 2f, mz + gap / 2f, zB) else floatArrayOf(zA, zB)
                // Far half first, then the near one; outer building first.
                var zi = zs.size - 2
                while (zi >= 0) {
                    val cols = if (xs.size == 4) {
                        if (abs(xs[0] + xs[1]) > abs(xs[2] + xs[3])) intArrayOf(0, 2) else intArrayOf(2, 0)
                    } else intArrayOf(0)
                    for (xi in cols) {
                        val seed = hash(i * 4 + xi, j * 4 + zi, 6)
                        val tower = hash(i * 4 + xi, j * 4 + zi, 7) > 0.93f && zA > TURN_Z + 0.6f
                        val e = 0.02f + 0.07f * seed * seed + if (tower) 0.09f else 0f
                        building(path, xs[xi], xs[xi + 1], zs[zi], zs[zi + 1], e, px)
                    }
                    zi -= 2
                }
            }
        }
    }

    /** Whether the river runs through any part of the block. */
    private fun wet(xA: Float, xB: Float, zA: Float, zB: Float): Boolean {
        var x = xA
        while (x <= xB + 0.001f) {
            val r = riverZ(x)
            if (zB > r - RIVER_HALF - 0.04f && zA < r + RIVER_HALF + 0.04f) return true
            x += 0.1f
        }
        val r = riverZ(xB)
        return zB > r - RIVER_HALF - 0.04f && zA < r + RIVER_HALF + 0.04f
    }

    /** A block raised [e] off the ground: the face toward the camera, the side it sees, then the lit top and its near edge. */
    private fun DrawScope.building(path: Path, x0: Float, x1: Float, z0: Float, z1: Float, e: Float, px: Float) {
        val bn = sy(z0)
        val bf = sy(z1)
        val tn = sy(z0, e)
        val tf = sy(z1, e)
        val nl = sx(x0, z0)
        val nr = sx(x1, z0)
        val fl = sx(x0, z1)
        val fr = sx(x1, z1)
        if (bn < 0f && tn < 0f) return
        if (x0 > 0f || x1 < 0f) {
            // The side facing the middle of the screen.
            val near = if (x0 > 0f) nl else nr
            val far = if (x0 > 0f) fl else fr
            path.reset()
            path.moveTo(near, bn); path.lineTo(near, tn); path.lineTo(far, tf); path.lineTo(far, bf); path.close()
            drawPath(path, fogged(c.sideDark, z0))
        }
        drawRect(fogged(c.side, z0), topLeft = Offset(nl, tn), size = Size(nr - nl, bn - tn))
        path.reset()
        path.moveTo(nl, tn); path.lineTo(nr, tn); path.lineTo(fr, tf); path.lineTo(fl, tf); path.close()
        drawPath(path, fogged(c.top, z0))
        drawLine(fogged(c.edge, z0), Offset(nl, tn), Offset(nr, tn), strokeWidth = px, alpha = 0.6f)
    }

    /** A flat green block with a few round tree tops casting a darker crown. */
    private fun DrawScope.drawPark(path: Path, i: Int, j: Int, xA: Float, xB: Float, zA: Float, zB: Float, px: Float) {
        quad(path, xA, zA, xB, zA, xB, zB, xA, zB)
        drawPath(path, fogged(c.park, zA))
        val trees = 3 + (hash(i, j, 8) * 4).toInt()
        for (t in 0 until trees) {
            val tx = xA + (xB - xA) * (0.15f + 0.7f * hash(i * 7 + t, j, 9))
            val tz = zA + (zB - zA) * (0.2f + 0.6f * hash(i, j * 7 + t, 10))
            val r = max(1.5f * px, lateral * 0.04f / tz)
            val at = Offset(sx(tx, tz), sy(tz, 0.03f))
            drawCircle(fogged(c.tree, tz), r, at)
            drawCircle(fogged(c.treeLight, tz), r * 0.5f, at + Offset(-r * 0.25f, -r * 0.3f))
        }
    }

    /** Night only: warm dots along both kerbs of every avenue, fading with distance. */
    private fun DrawScope.drawLamps(lamp: Color) {
        val zEnd = min(zFar, 12f)
        var z = ceil(zNear / 0.3f) * 0.3f
        while (z < zEnd) {
            val k = ceil(span(z) / AV_PITCH).toInt()
            val fade = 1f - fogAt(z) / FOG_MAX
            val r = max(0.8f, lateral * 0.0055f / z)
            for (i in -k..k) {
                val x = i * AV_PITCH
                val off = avenueWidth(i) / 2f + 0.01f
                for (s in intArrayOf(-1, 1)) {
                    val at = Offset(sx(x + s * off, z), sy(z, 0.01f))
                    drawCircle(lamp, r * 3f, at, alpha = 0.12f * fade)
                    drawCircle(lamp, r, at, alpha = 0.75f * fade)
                }
            }
            z += 0.3f
        }
    }

    /** The route as a ribbon [half] wide each side of its middle line, in perspective. */
    private fun ribbon(path: Path, half: Float) {
        path.reset()
        val n = rx.size
        fun normal(i: Int): Pair<Float, Float> {
            val a = (i - 1).coerceAtLeast(0)
            val b = (i + 1).coerceAtMost(n - 1)
            val dx = rx[b] - rx[a]
            val dz = rz[b] - rz[a]
            val len = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-4f)
            return -dz / len to dx / len
        }
        for (i in 0 until n) {
            val (nx, nz) = normal(i)
            val x = rx[i] + nx * half
            val z = rz[i] + nz * half
            if (i == 0) path.moveTo(sx(x, z), sy(z)) else path.lineTo(sx(x, z), sy(z))
        }
        for (i in n - 1 downTo 0) {
            val (nx, nz) = normal(i)
            val x = rx[i] - nx * half
            val z = rz[i] - nz * half
            path.lineTo(sx(x, z), sy(z))
        }
        path.close()
    }

    private fun DrawScope.drawRoute(path: Path) {
        val far = sy(zFar).coerceAtLeast(0f)
        if (glow > 0f) {
            // A pool of light on the street round the car, flattened by the slant.
            val r = min(w, h) * 0.45f
            scale(1f, 0.55f, car) {
                drawCircle(Brush.radialGradient(listOf(c.route.copy(alpha = c.pool * glow), Color.Transparent), car, r), r, car)
            }
            ribbon(path, ROUTE_GLOW / 2f)
            drawPath(path, Brush.verticalGradient(listOf(c.route.copy(alpha = c.routeGlow * glow * 0.3f), c.route.copy(alpha = c.routeGlow * glow)), startY = far, endY = h))
        }
        ribbon(path, ROUTE_CORE / 2f)
        drawPath(path, Brush.verticalGradient(listOf(c.routeCore.copy(alpha = 0.4f), c.routeCore), startY = far, endY = h))
    }

    /** Sets [qx], [qz] to the point [s] along the route, searching from segment [from]; returns its segment. */
    private fun locate(s: Float, from: Int): Int {
        var seg = from
        while (seg < rs.size - 2 && rs[seg + 1] < s) seg++
        val span = (rs[seg + 1] - rs[seg]).coerceAtLeast(1e-5f)
        val f = ((s - rs[seg]) / span).coerceIn(0f, 1f)
        qx = rx[seg] + (rx[seg + 1] - rx[seg]) * f
        qz = rz[seg] + (rz[seg + 1] - rz[seg]) * f
        return seg
    }

    /**
     * The white dashes along the route, [phase] (0..1) of a spacing ahead of
     * their rest place, narrowing with distance and fading into the haze.
     * Drawn per frame: no allocation.
     */
    fun drawFlow(scope: DrawScope, phase: Float) {
        if (!route) return
        val total = rs[rs.size - 1]
        var seg = 0
        var i = 0
        while (i < 400) {
            val s0 = (i + phase) * DASH_PITCH
            if (s0 >= total) break
            seg = locate(s0, seg)
            val x0 = qx
            val z0 = qz
            locate(min(s0 + DASH_LEN, total), seg)
            val y0 = sy(z0)
            val y1 = sy(qz)
            // Every later dash lies farther up, into the haze.
            if (y1 < hazeSolid) break
            if (y1 <= h + 20f) {
                scope.drawLine(
                    Color.White,
                    Offset(sx(x0, z0), y0), Offset(sx(qx, qz), y1),
                    strokeWidth = lateral * DASH_W * 2f / (z0 + qz),
                    cap = StrokeCap.Round,
                    alpha = hazeFade(y1) * 0.95f
                )
            }
            i++
        }
    }

    /**
     * The car's puck: a ring of light spreading from under it on [pulse]
     * (null: still), a soft halo when [halo], then [car] itself, nose up,
     * flattened a little by the slant.
     */
    fun drawPuck(scope: DrawScope, car: ImageBitmap?, carLength: Float, pulse: Float?, halo: Brush?) = with(scope) {
        val at = this@CityScene.car
        if (pulse != null) {
            val r = carLength * (0.3f + 0.8f * pulse)
            drawOval(c.route, Offset(at.x - r, at.y - r * 0.55f), Size(r * 2f, r * 1.1f), alpha = 0.42f * (1f - pulse))
        }
        if (halo != null) drawCircle(halo, carLength * 0.75f, at)
        if (light) drawOval(Color.Black, Offset(at.x - carLength * 0.3f, at.y - carLength * 0.32f), Size(carLength * 0.6f, carLength * 0.72f), alpha = 0.12f)
        if (car != null) {
            val dh = carLength * CAR_SQUASH
            val dw = carLength * car.width / car.height.coerceAtLeast(1)
            drawImage(
                car,
                dstOffset = IntOffset((at.x - dw / 2f).roundToInt(), (at.y - dh / 2f).roundToInt()),
                dstSize = IntSize(dw.roundToInt().coerceAtLeast(1), dh.roundToInt().coerceAtLeast(1))
            )
        }
    }

    /** The halo behind the puck, built once per scene. */
    fun puckHalo(carLength: Float): Brush =
        Brush.radialGradient(listOf(c.route.copy(alpha = (if (light) 0.35f else 0.6f) * glow), Color.Transparent), car, carLength * 0.75f)

    /**
     * The car from above, nose up, [lengthPx] long: the driver's own (in its
     * chosen style) when a car pack is shown, else the drawn car in the city's
     * silver (white by day) with the route's blue on its edges.
     */
    fun carBitmap(own: CarView?, style: CarLookStyle, lengthPx: Int): ImageBitmap {
        val len = lengthPx.coerceAtLeast(8)
        val aspect = own?.aspect?.coerceIn(0.3f, 0.8f) ?: (DRAWN_CAR_W / DRAWN_CAR_H)
        val wide = (len * aspect).roundToInt().coerceAtLeast(4)
        val bmp = ImageBitmap(wide, len)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bmp), Size(wide.toFloat(), len.toFloat())) {
            if (own != null) {
                drawCarView(own, style, fitCar(own, Rect(Offset.Zero, size)), c.route, AmberNight)
            } else {
                // The drawn car's box (VIEW_W x VIEW_H) holds it with room around; frame just the car and its mirrors.
                val s = min(size.width / DRAWN_CAR_W, size.height / DRAWN_CAR_H)
                translate(size.width / 2f - VIEW_W / 2f * s, size.height / 2f - VIEW_H / 2f * s) {
                    scale(s, s, Offset.Zero) {
                        drawCar(c.carBody, c.carGlass, c.carEdge, AmberNight, 0f, 0f, 0f, 0f, 0f, 0f)
                    }
                }
            }
        }
        return bmp
    }
}

/** The drawn car with its mirrors, in [drawCar]'s own units. */
private const val DRAWN_CAR_W = 100f
private const val DRAWN_CAR_H = 196f

/** The puck's car is drawn a little shorter than wide-open: the plan is seen at a slant. */
private const val CAR_SQUASH = 0.8f

/**
 * The scenes in use (the page's and a tile's or two), so the window frame can
 * reuse the page's bitmap and a size change does not redraw the plan twice.
 */
internal object ModernCity {
    private val scenes = ArrayList<CityScene>()

    /** Where the page background lies on screen, in px, so the window frame can lay the same city in its corners. */
    var page by mutableStateOf<IntRect?>(null)

    fun scene(width: Int, height: Int, light: Boolean, glow: Float, route: Boolean): CityScene {
        val found = scenes.firstOrNull { it.matches(width, height, light, glow, route) }
        if (found != null) {
            scenes.remove(found)
            scenes.add(0, found)
            return found
        }
        val made = CityScene(width, height, light, glow, route)
        scenes.add(0, made)
        while (scenes.size > MAX_SCENES) scenes.removeAt(scenes.lastIndex)
        return made
    }

    private const val MAX_SCENES = 3
}

/**
 * The city drawn behind this element's content: the plan, the flowing dashes
 * and the car's puck [carShare] of the shorter side long. Motion follows the
 * effects setting: full flows at the mockup's pace and pulses the puck,
 * reduced flows at half the pace without the pulse, off is a still picture
 * without glow.
 */
@Composable
internal fun Modifier.modernCity(route: Boolean, carShare: Float): Modifier {
    val light = DashColors.Light
    val effects = DashColors.Effects
    val look by MyCarLook.shown.collectAsState()
    val style by MyCarLook.style.collectAsState()
    val own = look?.top
    val flow = rememberLoop(if (effects == DashEffects.FULL) CITY_FLOW_MS else CITY_FLOW_MS * 2)
    val pulse = rememberLoop(CITY_PULSE_MS)
    val pulsing = effects == DashEffects.FULL
    val glow = effects.scale
    return cachedDraw(light, glow, pulsing, own, style, route, carShare, flow, pulse) {
        val wPx = size.width.roundToInt()
        val hPx = size.height.roundToInt()
        if (wPx < 2 || hPx < 2) return@cachedDraw onDrawBehind { }
        val scene = ModernCity.scene(wPx, hPx, light, glow, route)
        val raster = scene.raster()
        val carLength = min(size.width, size.height) * carShare
        val car = scene.carBitmap(own, style, carLength.roundToInt())
        val halo = if (glow > 0f) scene.puckHalo(carLength) else null
        onDrawBehind {
            drawImage(raster)
            scene.drawFlow(this, flow.value)
            scene.drawPuck(this, car, carLength, if (pulsing) pulse.value else null, halo)
        }
    }
}
