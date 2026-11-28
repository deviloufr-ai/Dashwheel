package com.openauto.dash.roadwatch

import kotlin.math.abs
import kotlin.math.hypot

/** The drawn road's two looks: the issue's first picture by day, its second by night. */
enum class RoadLook { DAY, NIGHT }

/** What a screen paints, in its own pixels, in the order given. Colours are ARGB. */
sealed interface RoadShape {
    /** A filled outline, [points] as x, y pairs; shaded from [argb] at its top to [argbBelow] at its bottom. */
    class Poly(val points: FloatArray, val argb: Int, val argbBelow: Int = argb) : RoadShape
    class Disc(val x: Float, val y: Float, val r: Float, val argb: Int, val ringArgb: Int = 0, val ringWidth: Float = 0f) : RoadShape
    /** Words on a rounded plate, its bottom middle at ([x], [y]). */
    class Label(val x: Float, val y: Float, val text: String, val argb: Int, val textArgb: Int) : RoadShape
}

/**
 * A [RoadPicture] as shapes for a screen [w] x [h] pixels: the road seen from
 * behind and above the car, everything on it a plain box, far things fading
 * into the haze. No drawing library here, so the phone and the head unit can
 * paint the same list and the geometry is tested on the JVM.
 */
object RoadPainter {
    fun paint(picture: RoadPicture, w: Float, h: Float, look: RoadLook): List<RoadShape> =
        Scene(picture, w, h, if (look == RoadLook.DAY) DAY else NIGHT).shapes()

    private const val LANE = RoadPicture.LANE_M
    /** The car's tail this far from the eye, and the car this long: things [z] metres ahead stand at ZC + CAR + z. */
    private const val ZC = 20f
    private const val CAR = 4.4f
    private const val NEAR = 5f
    private const val FAR = 330f
    /** Things further than this are not drawn. */
    private const val MAX_AHEAD = 160f
    private const val DASH_EVERY = 9f
    private const val DASH = 3.2f

    private class Colours(
        val sky0: Int, val sky1: Int, val ground: Int, val road: Int, val fog: Int, val line: Int, val vehicle: Int, val glass: Int,
        val own: Int, val path: Int, val pathAlpha: Float, val crit: Int, val warn: Int, val chip: Int, val chipText: Int,
        val shadow: Int, val tail: Int, val person: Int, val personIn: Int, val pole: Int, val block: Int
    )

    private val DAY = Colours(
        0xFFE9EDF2.toInt(), 0xFFCFD5DE.toInt(), 0xFFC3C9D2.toInt(), 0xFFAEB5C0.toInt(), 0xFFCFD5DE.toInt(), 0xFFFFFFFF.toInt(),
        0xFFE4E7EC.toInt(), 0xFF8A94A4.toInt(), 0xFFD63F38.toInt(), 0xFF2F6BFF.toInt(), 0.13f, 0xFFE03A3A.toInt(), 0xFFE8A200.toInt(),
        0xFFFFFFFF.toInt(), 0xFF18202E.toInt(), 0xFF5A6373.toInt(), 0xFFD8433C.toInt(), 0xFF7B8494.toInt(), 0xFFE03A3A.toInt(), 0xFF8A93A2.toInt(), 0xFFD3D8E0.toInt()
    )
    private val NIGHT = Colours(
        0xFF0A2A94.toInt(), 0xFF3F8CFF.toInt(), 0xFF1A52D6.toInt(), 0xFF1746BD.toInt(), 0xFF3A82F5.toInt(), 0xFFFFFFFF.toInt(),
        0xFFEDF2FF.toInt(), 0xFF27408F.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0.55f, 0xFFFF5A5A.toInt(), 0xFFFFD23F.toInt(),
        0xFF0C2D9C.toInt(), 0xFFFFFFFF.toInt(), 0xFF0A2377.toInt(), 0xFFFF6A5A.toInt(), 0xFF35E0A0.toInt(), 0xFFFF5A5A.toInt(), 0xFFCFE0FF.toInt(), 0xFF2A66E6.toInt()
    )
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val HOUSING = 0xFF181C24.toInt()
    private const val LAMP_OFF = 0xFF3C424E.toInt()
    private val LAMPS = intArrayOf(0xFFFF463C.toInt(), 0xFFFFBE28.toInt(), 0xFF3CE182.toInt())

    /** A vehicle as two boxes: the body, and a cabin (or a rider) from [cabFrom] to [cabTo] metres along it, up to [cabTop]. */
    private class Body(val w: Float, val l: Float, val h: Float, val cabFrom: Float = 0f, val cabTo: Float = 0f, val cabTop: Float = 0f)

    private fun body(kind: ThingKind): Body = when (kind) {
        ThingKind.TRUCK -> Body(2.5f, 9f, 3.2f)
        ThingKind.BUS -> Body(2.5f, 11f, 3f)
        ThingKind.MOTORCYCLE -> Body(0.7f, 2f, 1f, 0.5f, 1.3f, 1.7f)
        ThingKind.BICYCLE -> Body(0.5f, 1.8f, 0.9f, 0.5f, 1.1f, 1.7f)
        else -> Body(1.8f, CAR, 0.85f, 0.7f, 3f, 1.45f)
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int): Int {
            val x = a shr shift and 255
            return (x + ((b shr shift and 255) - x) * t).toInt().coerceIn(0, 255) shl shift
        }
        return BLACK or ch(16) or ch(8) or ch(0)
    }

    private fun alpha(argb: Int, a: Float): Int = (argb and 0xFFFFFF) or ((a.coerceIn(0f, 1f) * 255).toInt() shl 24)

    private fun shade(argb: Int, k: Float): Int = mix(argb, if (k > 0) WHITE else BLACK, abs(k))

    private class Scene(val p: RoadPicture, val w: Float, val h: Float, val c: Colours) {
        private val out = ArrayList<RoadShape>()
        private val wide = w > h
        private val horizon = h * if (wide) 0.3f else 0.38f
        /** The focal length, pixels: the car's lane is a fifth of a wide screen at the car, half of a tall one. */
        private val f = (if (wide) 0.2f else 0.5f) * w * ZC / LANE
        /** The eye's height, set so the car's tail sits low on the screen. */
        private val eye = (h * (if (wide) 0.8f else 0.82f) - horizon) * ZC / f

        /** The middle of the car's road ahead from the map, as x, z pairs from the car; without it the road is straight. */
        private val path = p.around?.path?.takeIf { it.size >= 4 }
        /** Off while the map's own roads and buildings are drawn: they are where they stand, not along the car's lane. */
        private var bent = true

        /** How far right the car's lane has bent, a scene z from the eye. */
        private fun bend(z: Float): Float {
            val path = path ?: return 0f
            val ahead = z - ZC - CAR
            if (!bent || ahead <= 0f) return 0f
            var i = 0
            while (true) {
                val x0 = path[i]; val z0 = path[i + 1]; val x1 = path[i + 2]; val z1 = path[i + 3]
                // Turning back on itself (a hairpin): drawn no further than where it turns.
                if (z1 <= z0) return x0
                val last = i + 4 >= path.size
                if (ahead <= z1 || last) return x0 + (x1 - x0) * ((ahead - z0) / (z1 - z0)).coerceAtMost(if (last) 4f else 1f)
                i += 2
            }
        }

        /** What stands on the car's road is moved aside whole by the bend where it stands, not bent along its length. */
        private var shift = 0f

        private fun sx(x: Float, z: Float) = w / 2 + f * (x + bend(z) + shift) / z
        private fun sy(y: Float, z: Float) = horizon + f * (eye - y) / z

        /** [xyz]: corners as x (right), y (up), z (from the eye), metres. */
        private fun poly(argb: Int, vararg xyz: Float) {
            val pts = FloatArray(xyz.size / 3 * 2)
            for (i in 0 until xyz.size / 3) {
                pts[i * 2] = sx(xyz[i * 3], xyz[i * 3 + 2])
                pts[i * 2 + 1] = sy(xyz[i * 3 + 1], xyz[i * 3 + 2])
            }
            out += RoadShape.Poly(pts, argb)
        }

        private fun rect(x0: Float, y0: Float, x1: Float, y1: Float, argb: Int, below: Int = argb) {
            out += RoadShape.Poly(floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1), argb, below)
        }

        /** A strip of the car's road between two distances, in pieces where the road bends. */
        private fun strip(x: Float, half: Float, z0: Float, z1: Float, argb: Int) {
            var a = z0
            while (a < z1) {
                val e = if (path == null) z1 else minOf(z1, a + if (a < 110f) 5f else 30f)
                poly(argb, x - half, 0f, a, x + half, 0f, a, x + half, 0f, e, x - half, 0f, e)
                a = e
            }
        }

        /** A road of the map, flat on the ground along its middle line. */
        private fun sideRoad(road: SideRoad) {
            val l = road.line
            for (i in 0 until l.size / 2 - 1) {
                val ax = l[i * 2]; val az = l[i * 2 + 1] + ZC + CAR
                val bx = l[i * 2 + 2]; val bz = l[i * 2 + 3] + ZC + CAR
                val len = hypot(bx - ax, bz - az)
                if (len < 0.1f) continue
                val px = (bz - az) / len * road.halfWidth
                val pz = -(bx - ax) / len * road.halfWidth
                // Lengthened by its half width, so two stretches meet without a gap at a bend.
                val ex = (bx - ax) / len * road.halfWidth
                val ez = (bz - az) / len * road.halfWidth
                poly(c.road, ax + px - ex, 0f, az + pz - ez, ax - px - ex, 0f, az - pz - ez, bx - px + ex, 0f, bz - pz + ez, bx + px + ex, 0f, bz + pz + ez)
            }
        }

        /** A building: the walls that face the eye, the farthest first, and its roof when the eye is above it. */
        private fun block(b: Block, fog: Float) {
            val r = b.ring
            val n = r.size / 2
            if (n < 3) return
            var area = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                area += r[i * 2] * r[j * 2 + 1] - r[j * 2] * r[i * 2 + 1]
            }
            val turn = if (area > 0) 1f else -1f
            val col = mix(c.block, c.fog, fog)
            val walls = ArrayList<Pair<Float, FloatArray>>()
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = r[i * 2]; val az = r[i * 2 + 1] + ZC + CAR
                val bx = r[j * 2]; val bz = r[j * 2 + 1] + ZC + CAR
                val len = hypot(bx - ax, bz - az)
                if (len < 0.05f) continue
                // The wall's outward side, and whether the eye (at 0, 0) is on it.
                val nx = (bz - az) / len * turn
                val nz = -(bx - ax) / len * turn
                val mx = (ax + bx) / 2
                val mz = (az + bz) / 2
                if (nx * -mx + nz * -mz <= 0f) continue
                walls += hypot(mx, mz) to floatArrayOf(ax, az, bx, bz, nx)
            }
            for ((_, wl) in walls.sortedByDescending { it.first }) {
                // Walls that face the road a little lighter than those that face the car.
                poly(shade(col, -0.2f + 0.14f * abs(wl[4])), wl[0], 0f, wl[1], wl[2], 0f, wl[3], wl[2], b.height, wl[3], wl[0], b.height, wl[1])
            }
            if (b.height < eye) {
                val roof = FloatArray(n * 3)
                for (i in 0 until n) { roof[i * 3] = r[i * 2]; roof[i * 3 + 1] = b.height; roof[i * 3 + 2] = r[i * 2 + 1] + ZC + CAR }
                poly(shade(col, 0.12f), *roof)
            }
        }

        private fun disc(x: Float, y: Float, z: Float, r: Float, argb: Int, ring: Int = 0, ringWidth: Float = 0f) {
            out += RoadShape.Disc(sx(x, z), sy(y, z), r * f / z, argb, ring, ringWidth * f / z)
        }

        /** The three faces the eye sees of a box: the side towards the middle, the top, the back. */
        private fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float, col: Int, top: Int = col) {
            val xs = if ((x0 + x1) / 2 + bend(z0) + shift > 0) x0 else x1
            poly(shade(col, -0.28f), xs, y0, z0, xs, y0, z1, xs, y1, z1, xs, y1, z0)
            poly(shade(top, 0.12f), x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1)
            poly(shade(col, -0.1f), x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0)
        }

        private fun vehicle(kind: ThingKind, x: Float, z: Float, col: Int, fog: Float) {
            val b = body(kind)
            val x0 = x - b.w / 2
            val x1 = x + b.w / 2
            val body = mix(col, c.fog, fog)
            poly(alpha(c.shadow, 0.3f * (1 - fog)), x0 - 0.25f, 0f, z - 0.4f, x1 + 0.25f, 0f, z - 0.4f, x1 + 0.25f, 0f, z + b.l + 0.2f, x0 - 0.25f, 0f, z + b.l + 0.2f)
            box(x0, x1, 0.25f, b.h, z, z + b.l, body)
            if (b.cabTop > 0f) box(x0 + 0.15f, x1 - 0.15f, b.h, b.cabTop, z + b.cabFrom, z + b.cabTo, mix(body, c.glass, 0.75f), body)
            val tail = mix(c.tail, c.fog, fog)
            val y = if (kind == ThingKind.CAR) 0.55f else 0.7f
            if (b.w < 1f) poly(tail, x - 0.15f, y, z, x + 0.15f, y, z, x + 0.15f, y + 0.2f, z, x - 0.15f, y + 0.2f, z)
            else for (a in floatArrayOf(x0 + 0.1f, x1 - 0.55f)) poly(tail, a, y, z, a + 0.45f, y, z, a + 0.45f, y + 0.2f, z, a, y + 0.2f, z)
        }

        private fun person(x: Float, z: Float, fog: Float) {
            // In the car's lane: in the way.
            val inWay = abs(x) < LANE / 2
            val col = mix(if (inWay) c.personIn else c.person, c.fog, fog)
            disc(x, 0f, z, 0.45f, alpha(c.shadow, 0.25f * (1 - fog)))
            box(x - 0.24f, x + 0.24f, 0f, 1.35f, z, z + 0.28f, col)
            disc(x, 1.58f, z, 0.17f, shade(col, 0.1f))
            if (inWay) disc(x, 0f, z, 0.7f, 0, alpha(c.personIn, 0.8f * (1 - fog)), 0.08f)
        }

        private fun light(x: Float, z: Float, lit: LightColour?, fog: Float) {
            box(x - 0.07f, x + 0.07f, 0f, 3.3f, z, z + 0.14f, mix(c.pole, c.fog, fog))
            box(x - 0.24f, x + 0.24f, 3.3f, 4.5f, z, z + 0.3f, mix(HOUSING, c.fog, fog))
            for (i in 0..2) {
                val y = 4.25f - i * 0.36f
                val on = lit?.ordinal == i
                if (on) disc(x, y, z, 0.4f, alpha(LAMPS[i], 0.28f * (1 - fog)))
                disc(x, y, z, 0.13f, if (on) mix(LAMPS[i], c.fog, fog / 2) else LAMP_OFF)
            }
        }

        private fun stopSign(x: Float, z: Float, fog: Float) {
            box(x - 0.05f, x + 0.05f, 0f, 2.2f, z, z + 0.1f, mix(c.pole, c.fog, fog))
            disc(x, 2.65f, z, 0.42f, mix(0xFFD42A20.toInt(), c.fog, fog), mix(WHITE, c.fog, fog), 0.08f)
        }

        fun shapes(): List<RoadShape> {
            val danger = p.warning == RoadWarning.COLLISION || p.warning == RoadWarning.TOO_CLOSE
            rect(0f, 0f, w, horizon, c.sky0, c.sky1)
            rect(0f, horizon, w, h, c.ground)

            // The map's roads under the car's own, so a junction reads as one.
            bent = false
            p.around?.roads?.forEach(::sideRoad)
            bent = true
            val left = p.lanesBeside(-1)
            val right = p.lanesBeside(1)
            val xl = -LANE * (left + 0.5f)
            val xr = LANE * (right + 0.5f)
            strip((xl + xr) / 2, (xr - xl) / 2, NEAR, FAR, c.road)
            strip(xl, 0.1f, NEAR, FAR, alpha(c.line, 0.6f))
            strip(xr, 0.1f, NEAR, FAR, alpha(c.line, 0.6f))
            val phase = p.travelledM % DASH_EVERY
            for (i in (1 - left)..right) {
                val x = (i - 0.5f) * LANE
                val mine = i == 0 || i == 1
                val warned = (i == 0 && p.warning == RoadWarning.LANE_LEFT) || (i == 1 && p.warning == RoadWarning.LANE_RIGHT)
                if (warned) {
                    strip(x, 0.3f, NEAR, 140f, alpha(c.warn, 0.3f))
                    strip(x, 0.11f, NEAR, FAR, c.warn)
                    continue
                }
                // The car's own lines faint while the camera doesn't see them; the others are guessed from the vehicles there.
                val a = if (!mine) 0.6f else if (if (i == 0) p.leftSeen else p.rightSeen) 0.95f else 0.25f
                var z = NEAR - phase
                while (z < 190f) {
                    if (z + DASH > NEAR) strip(x, 0.09f, maxOf(z, NEAR), z + DASH, alpha(c.line, a))
                    z += DASH_EVERY
                }
            }
            val things = p.things.filter { it.z <= MAX_AHEAD }.sortedByDescending { it.z }
            val lead = things.firstOrNull { it.lead }
            // The car's way, lit up to the vehicle ahead.
            val from = ZC + CAR + 0.8f
            val to = ZC + CAR + (lead?.z?.minus(0.6f) ?: 95f)
            if (to > from) strip(0f, 1.3f, from, to, alpha(if (danger) c.crit else c.path, c.pathAlpha))
            rect(0f, horizon, w, horizon + h * 0.14f, c.fog, alpha(c.fog, 0f))

            // Everything that stands, the farthest first: the map's buildings among what the camera sees.
            val standing = ArrayList<Pair<Float, () -> Unit>>()
            for (b in p.around?.buildings.orEmpty()) {
                val depth = b.ring.filterIndexed { i, _ -> i % 2 == 1 }.average().toFloat()
                standing += depth to { bent = false; block(b, (0.25f + depth / 160f).coerceIn(0.25f, 0.8f)); bent = true }
            }
            for (t in things) standing += t.z to {
                // Far things fade into the haze; a thing whose distance is a guess is fainter still.
                val fog = maxOf(((t.z - 40f) / 70f).coerceIn(0f, 0.88f), if (t.sure) 0f else 0.5f)
                val z = ZC + CAR + maxOf(t.z, 0.5f)
                shift = bend(z)
                bent = false
                when (t.kind) {
                    ThingKind.PERSON -> person(t.x, z, fog)
                    ThingKind.TRAFFIC_LIGHT -> light(t.x, z, t.light, fog * 0.6f)
                    ThingKind.STOP_SIGN -> stopSign(t.x, z, fog * 0.6f)
                    else -> vehicle(t.kind, t.x, z, if (t.lead && danger) c.crit else c.vehicle, fog)
                }
                shift = 0f
                bent = true
            }
            standing.sortedByDescending { it.first }.forEach { it.second() }
            vehicle(ThingKind.CAR, (p.offset ?: 0f) * LANE, ZC, c.own, 0f)
            val text = p.leadLabel
            if (lead != null && text != null) {
                val b = body(lead.kind)
                val z = ZC + CAR + maxOf(lead.z, 0.5f)
                out += RoadShape.Label(sx(lead.x, z), sy(maxOf(b.h, b.cabTop) + 0.6f, z), text, alpha(if (danger) c.crit else c.chip, 0.94f), if (danger) WHITE else c.chipText)
            }
            return out
        }
    }
}
