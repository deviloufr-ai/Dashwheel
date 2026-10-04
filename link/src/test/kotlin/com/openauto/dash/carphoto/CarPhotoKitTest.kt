package com.openauto.dash.carphoto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.imageio.ImageIO
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The car from three photos, checked against renders of a 3D model (true view
 * from above known) and a real side photo of the C4 Picasso on flat black.
 * The pictures it makes land in build/carphoto-out for a look.
 */
class CarPhotoKitTest {

    // The model the renders come from (metres): height with tyres, length, width,
    // wheel centres from the middle; the true top view is 0.005 m a pixel, middle at (300, 500).
    private val modelHeight = 1.686
    private val modelLength = 4.468
    private val gtPx = 0.005
    private val xFront = 1.29
    private val xRear = -1.56
    private val yWheel = 0.775

    private val out = File("build/carphoto-out").apply { mkdirs() }

    private fun load(name: String): Argb {
        val img = javaClass.getResourceAsStream("/carphoto/$name")!!.use { ImageIO.read(it) }
        val px = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
        return Argb(img.width, img.height, px)
    }

    private fun save(a: Argb, name: String) {
        val img = BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, a.w, a.h, a.px, 0, a.w)
        ImageIO.write(img, "png", File(out, name))
    }

    private fun png(a: Argb): ByteArray {
        val img = BufferedImage(a.w, a.h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, a.w, a.h, a.px, 0, a.w)
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    private fun note(line: String) {
        println(line)
        File(out, "results.txt").appendText(line + "\n")
    }

    /** [img] on a flat colour, the way the Gemini app hands a cut-out car. */
    private fun onFlat(img: Argb, bg: Int, mirrored: Boolean = false, bg2: Int = bg, square: Int = 16): Argb {
        val pad = 60
        val w = img.w + 2 * pad
        val h = img.h + 2 * pad
        // bg2: the second colour of a drawn "transparent" checkerboard
        fun back(x: Int, y: Int) = if ((x / square + y / square) % 2 == 0) bg else bg2
        val px = IntArray(w * h) { back(it % w, it / w) }
        for (y in 0 until img.h) for (x in 0 until img.w) {
            val p = img.px[y * img.w + (if (mirrored) img.w - 1 - x else x)]
            val a = (p ushr 24) / 255.0
            val under = back(x + pad, y + pad)
            fun mix(sh: Int) = (((p shr sh) and 255) * a + ((under shr sh) and 255) * (1 - a)).roundToInt()
            px[(y + pad) * w + x + pad] = (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        return Argb(w, h, px)
    }

    private class Score(val iou: Double, val wheelErr: Double, val lengthErr: Double, val widthErr: Double)

    /** The view from above placed on the true one, scaled by the car's height alone. */
    private fun score(top: CarTop, name: String): Score {
        val gtImg = javaClass.getResourceAsStream("/carphoto/top_mask.png")!!.use { ImageIO.read(it) }
        val gw = gtImg.width
        val gh = gtImg.height
        val f = modelHeight / top.heightPx / gtPx
        val ox = 300 - top.centrePx.x * f
        val oy = 500 - top.centrePx.y * f
        val img = top.image
        var inter = 0
        var union = 0
        val overlay = BufferedImage(gw, gh, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until gh) for (x in 0 until gw) {
            val gt = (gtImg.getRGB(x, y) and 0xFFFFFF) != 0
            val sx = ((x - ox + 0.5) / f - 0.5).roundToInt()
            val sy = ((y - oy + 0.5) / f - 0.5).roundToInt()
            val rec = sx in 0 until img.w && sy in 0 until img.h && (img.px[sy * img.w + sx] ushr 24) > 128
            if (gt && rec) inter++
            if (gt || rec) union++
            overlay.setRGB(x, y, when {
                gt && rec -> 0xFF808890.toInt()
                gt -> 0xFF30C060.toInt()
                rec -> 0xFFD04040.toInt()
                else -> 0xFF16181E.toInt()
            })
        }
        ImageIO.write(overlay, "png", File(out, "overlay_$name.png"))
        val truth = listOf(xFront to yWheel, xFront to -yWheel, xRear to yWheel, xRear to -yWheel)
        val lengthPx = modelLength / gtPx
        val wheelErr = top.wheels.zip(truth).maxOf { (p, t) ->
            val px = ox + p.x * img.w * f
            val py = oy + p.y * img.h * f
            hypot(px - (300 - t.second / gtPx), py - (500 - t.first / gtPx)) / lengthPx * 100
        }
        val box = top.bodyBox
        val length = (box[3] - box[1]) * img.h * f * gtPx
        val width = (box[2] - box[0]) * img.w * f * gtPx
        return Score(inter.toDouble() / union, wheelErr, (length / modelLength - 1) * 100, (width / 1.786 - 1) * 100)
    }

    private fun timed(block: () -> CarPhotoResult): Pair<CarPhotoResult, Long> {
        block()   // warm the JIT up
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun orthographicRendersGiveTheTrueTopView() {
        val side = load("side.png")
        val (r, ms) = timed { CarPhotoKit.build(side, load("front.png"), load("back.png"), name = "Model") }
        val top = r.top!!
        assertFalse("the render points right", r.side.facedLeft)
        assertEquals(2, r.side.wheelsFound)
        val s = score(top, "ortho")
        note("ortho: IoU %.3f, wheel err %.2f %% of length, length %+.1f %%, width %+.1f %%, build %d ms (side %dx%d, top %dx%d)".format(Locale.ROOT,
            s.iou, s.wheelErr, s.lengthErr, s.widthErr, ms, r.side.image.w, r.side.image.h, top.image.w, top.image.h))
        save(r.side.image, "side_ortho.png")
        save(top.image, "top_ortho.png")
        assertTrue("IoU ${s.iou}", s.iou >= 0.93)
        assertTrue("wheel error ${s.wheelErr} %", s.wheelErr <= 1.0)
        // the side picture's wheels: front then rear, on one row
        assertTrue(r.side.wheels[0].x > r.side.wheels[1].x)
        assertEquals(r.side.wheels[0].y, r.side.wheels[1].y, 0.01f)
    }

    @Test
    fun perspectiveFrontAndBackStayClose() {
        val r = CarPhotoKit.build(load("side.png"), load("front_p.png"), load("back_p.png"))
        val s = score(r.top!!, "persp_fb")
        note("persp front/back: IoU %.3f, wheel err %.2f %% of length, width %+.1f %%".format(Locale.ROOT, s.iou, s.wheelErr, s.widthErr))
        save(r.top!!.image, "top_persp_fb.png")
        assertTrue("IoU ${s.iou}", s.iou >= 0.88)
    }

    @Test
    fun flatBackgroundIsKeyedAndFacingFound() {
        val side = load("side.png")
        val black = 0xFF000000.toInt()
        val right = CarPhotoKit.side(onFlat(side, black))
        val left = CarPhotoKit.side(onFlat(side, black, mirrored = true))
        assertFalse(right.facedLeft)
        assertTrue(left.facedLeft)
        assertEquals(2, left.wheelsFound)
        // keyed on black and mirrored back, the same car as the transparent render
        val clean = CarPhotoKit.side(side)
        assertEquals(clean.image.w.toDouble(), left.image.w.toDouble(), clean.image.w * 0.02)
        assertEquals(clean.wheels[0].x, left.wheels[0].x, 0.01f)
        // keyed front and back on a grey background: the top view barely changes
        val grey = 0xFF2A2C30.toInt()
        val r = CarPhotoKit.build(onFlat(side, black, mirrored = true), onFlat(load("front.png"), grey), onFlat(load("back.png"), grey))
        val s = score(r.top!!, "keyed")
        note("keyed renders: IoU %.3f, wheel err %.2f %%".format(Locale.ROOT, s.iou, s.wheelErr))
        assertTrue("IoU ${s.iou}", s.iou >= 0.93)
    }

    @Test
    fun drawnCheckerboardIsKeyedLikeWhite() {
        val side = load("side.png")
        val white = 0xFFFFFFFF.toInt()
        val flat = CarPhotoKit.cutOut(onFlat(side, white))
        val checker = CarPhotoKit.cutOut(onFlat(side, white, bg2 = 0xFFCCCCCC.toInt()))
        save(checker, "cutout_checker.png")
        // the grey squares go too: the same car, not a car with tiles around it
        assertEquals(flat.w.toDouble(), checker.w.toDouble(), flat.w * 0.02)
        assertEquals(flat.h.toDouble(), checker.h.toDouble(), flat.h * 0.02)
        fun solid(a: Argb) = a.px.count { (it ushr 24) > 128 }
        assertEquals(solid(flat).toDouble(), solid(checker).toDouble(), solid(flat) * 0.03)
        // a plain two-tone border that is no checkerboard keeps one key
        assertEquals(1, CarPhotoKit.backgroundKeys(onFlat(side, 0xFF000000.toInt())).size)
    }

    @Test
    fun paleRimOnWhiteIsPeeled() {
        val side = load("side.png")
        val white = 0xFFFFFFFF.toInt()
        val clean = onFlat(side, white)
        // a soft pale halo a few pixels wide around the car, as Gemini draws on white
        val w = clean.w
        val h = clean.h
        val car = BooleanArray(w * h)
        val pad = 60
        for (y in 0 until side.h) for (x in 0 until side.w) if ((side.px[y * side.w + x] ushr 24) > 128) car[(y + pad) * w + x + pad] = true
        val halo = CarPhotoKit.dilate(car, w, h, 6, 6)
        val rimmed = Argb(w, h, IntArray(w * h) { i -> if (halo[i] && !car[i]) 0xFFE4E4E6.toInt() else clean.px[i] })
        val flat = CarPhotoKit.cutOut(clean)
        val peeled = CarPhotoKit.cutOut(rimmed)
        save(peeled, "cutout_rim.png")
        fun solid(a: Argb) = a.px.count { (it ushr 24) > 128 }
        assertEquals(solid(flat).toDouble(), solid(peeled).toDouble(), solid(flat) * 0.03)
        assertEquals(flat.w.toDouble(), peeled.w.toDouble(), flat.w * 0.02)
    }

    @Test
    fun realSidePhotoOnBlack() {
        val photo = load("real_side.jpg")
        val (r, ms) = timed { CarPhotoKit.build(photo, load("front.png"), load("back.png"), name = "C4 Picasso") }
        assertTrue("the photo points left", r.side.facedLeft)
        assertEquals(2, r.side.wheelsFound)
        // the result points right: read again, it no longer needs mirroring
        assertFalse(CarPhotoKit.side(r.side.image).facedLeft)
        // the screenshot's status bar and buttons are gone: a car-shaped picture
        val aspect = r.side.image.w.toDouble() / r.side.image.h
        assertTrue("aspect $aspect", aspect in 2.0..3.2)
        val top = r.top!!
        note("real: tilt %.2f deg, side %dx%d (aspect %.2f), wheels %s, top %dx%d, top wheels %s, build %d ms".format(Locale.ROOT,
            r.side.tiltDegrees, r.side.image.w, r.side.image.h, aspect, r.side.wheels, top.image.w, top.image.h, top.wheels, ms))
        save(r.side.image, "side_real.png")
        save(top.image, "top_real.png")
        save(CarPhotoKit.cutOut(photo), "cutout_real.png")
        // the pack: car.json in the import's shape, and the zip
        val json = Json.parseToJsonElement(r.toCarJson()).jsonObject
        val anchors = json["anchors"]!!.jsonObject
        assertEquals(2, anchors["side"]!!.jsonObject["wheels"]!!.jsonArray.size)
        assertEquals(4, anchors["top"]!!.jsonObject["wheels"]!!.jsonArray.size)
        File(out, "car_real.json").writeText(r.toCarJson())
        val zip = CarPhotoKit.zip(mapOf("car.json" to r.toCarJson().toByteArray()) + r.packEntries().mapValues { png(it.value) })
        File(out, "real.dwcar").writeBytes(zip)
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { z -> while (true) names.add((z.nextEntry ?: break).name) }
        assertEquals(listOf("car.json", "side.png", "top.png", "back.png"), names)
    }

    @Test
    fun backPhotoIsCleaned() {
        val back = CarPhotoKit.cutOut(onFlat(load("back.png"), 0xFF000000.toInt()))
        save(back, "back_clean.png")
        val corner = back.px[0] ushr 24
        assertEquals(0, corner)
        val middle = back.px[(back.h / 2) * back.w + back.w / 2] ushr 24
        assertEquals(255, middle)
    }

    @Test
    fun photosPickedTogetherAreSortedByNameThenShape() {
        // Named, in any order and language.
        assertEquals(CarPhotoKit.Shots(2, 0, 1, null), CarPhotoKit.sortShots(listOf("avant.jpg", "IMG_arrière.jpg", "côté.jpg"), listOf(1.3f, 1.3f, 2.1f)))
        assertEquals(CarPhotoKit.Shots(1, 2, 0, 3), CarPhotoKit.sortShots(listOf("car_back.png", "car_side.jpg", "car_front.png", "car_top.png"), listOf(1f, 1f, 1f, 1f)))
        // Not named: a tall one is the view from above, the widest the side, then the order picked.
        assertEquals(CarPhotoKit.Shots(1, 0, 3, 2), CarPhotoKit.sortShots(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg", "IMG_4.jpg"), listOf(1.2f, 2.4f, 0.46f, 1.3f)))
        // From above with the doors open: by name (before "top" counts), else the fifth photo.
        assertEquals(CarPhotoKit.Shots(1, 2, 0, 3, 4), CarPhotoKit.sortShots(listOf("back.png", "side.jpg", "front.png", "top.png", "top_open.png"), listOf(1f, 1f, 1f, 0.5f, 0.8f)))
        assertEquals(CarPhotoKit.Shots(1, 0, 3, 2, 4), CarPhotoKit.sortShots(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg", "IMG_4.jpg", "IMG_5.jpg"), listOf(1.2f, 2.4f, 0.46f, 1.3f, 0.9f)))
        // "backup" or "frontier" are not the back or the front; only one photo: the side alone, even tall.
        assertEquals(CarPhotoKit.Shots(0, null, null, null), CarPhotoKit.sortShots(listOf("backup.jpg"), listOf(0.5f)))
    }

    @Test
    fun aPictureFromAboveIsTurnedNoseUpWithItsWheelsFromTheSide() {
        // The true view from above as a silver car with red rear lights on white, lying on its side.
        val gt = javaClass.getResourceAsStream("/carphoto/top_mask.png")!!.use { ImageIO.read(it) }
        val w = gt.width
        val h = gt.height
        var bottom = 0
        for (y in 0 until h) for (x in 0 until w) if ((gt.getRGB(x, y) and 0xFFFFFF) != 0) bottom = y
        val lying = Argb(h, w)
        for (y in 0 until h) for (x in 0 until w) {
            val car = (gt.getRGB(x, y) and 0xFFFFFF) != 0
            val light = car && y > bottom - 40 && (x < w * 0.3 || x > w * 0.7)
            val c = when { light -> 0xFFD01818.toInt(); car -> 0xFFB4B6BA.toInt(); else -> 0xFFFAFAFA.toInt() }
            lying.px[x * h + (h - 1 - y)] = c // a quarter turn clockwise: nose to the right
        }
        val side = CarPhotoKit.side(onFlat(load("side.png"), 0xFF000000.toInt()))
        val top = CarPhotoKit.topFrom(lying, side)
        save(top.image, "above_turned.png")
        assertTrue("upright", top.image.h > top.image.w)
        assertTrue("nose up", top.nose.y < top.tail.y)
        val (fl, fr, rl, rr) = top.wheels
        assertTrue("front wheels ahead", fl.y < rl.y && fr.y < rr.y)
        assertTrue("left of right", fl.x < fr.x && rl.x < rr.x)
        // wheelbase as on the model, within 3 % of the length
        val wheelbase = (rl.y - fl.y) * (top.tail.y - top.nose.y).let { 1 / it }
        assertEquals(2.73 / modelLength, wheelbase.toDouble(), 0.03)
    }

    /**
     * The model rendered from above shut, then with the front left and rear
     * right doors and the tailgate open, framed differently (as the Gemini
     * app's redraw would be): each open part found on its own, in its place.
     */
    @Test
    fun openPartsAreFoundOnTheViewFromAbove() {
        val side = CarPhotoKit.side(onFlat(load("side.png"), 0xFF000000.toInt()))
        for ((name, bg) in listOf("clear" to null, "white" to 0xFFFFFFFF.toInt())) {
            val shut = load("top_render.png").let { if (bg == null) it else onFlat(it, bg) }
            val open = load("top_open.png").let { if (bg == null) it else onFlat(it, bg) }
            val top = CarPhotoKit.topFrom(shut, side)
            val t0 = System.nanoTime()
            val parts = CarPhotoKit.openParts(open, top)
            val ms = (System.nanoTime() - t0) / 1_000_000
            note("open parts ($name, $ms ms): " + parts.joinToString { p -> "${p.part} ${p.box.joinToString(",") { "%.2f".format(Locale.ROOT, it) }}" })
            save(top.image, "open_${name}_shut.png")
            parts.forEach { save(it.image, "open_${name}_${it.part.key}.png") }
            assertEquals(setOf(CarPart.FRONT_LEFT, CarPart.REAR_RIGHT, CarPart.TAILGATE), parts.map { it.part }.toSet())
            val fl = parts.first { it.part == CarPart.FRONT_LEFT }.box
            val rr = parts.first { it.part == CarPart.REAR_RIGHT }.box
            val tail = parts.first { it.part == CarPart.TAILGATE }.box
            assertTrue("front left door out to the left", fl[0] < 0f && fl[2] < 0.5f)
            assertTrue("rear right door out to the right", rr[2] > 1f && rr[0] > 0.5f)
            assertTrue("front door ahead of the rear one", fl[1] < rr[1])
            assertTrue("doors hinged on the body", fl[2] > top.bodyBox[0] && rr[0] < top.bodyBox[2])
            assertTrue("tailgate beyond the tail", tail[3] > top.tail.y + 0.05f)
        }
        // the whole pack, with its parts named in car.json (build/carphoto-out/open.dwcar, to import on a unit)
        val r = CarPhotoKit.build(load("side.png"), load("front.png"), load("back.png"), above = load("top_render.png"), opened = load("top_open.png"))
        val json = Json.parseToJsonElement(r.toCarJson()).jsonObject
        val parts = json["parts"]!!.jsonObject["top"]!!.jsonObject
        assertEquals(setOf("fl", "rr", "tailgate"), parts.keys)
        val entries = r.packEntries()
        assertTrue(parts.values.all { it.jsonObject["box"]!!.jsonArray.size == 4 })
        assertTrue("top_fl.png" in entries && "top_tailgate.png" in entries)
        File(out, "open.dwcar").writeBytes(CarPhotoKit.zip(LinkedHashMap<String, ByteArray>().apply {
            put("car.json", r.toCarJson().toByteArray())
            for ((n, a) in entries) put(n, png(a))
        }))
        // a picture with nothing open gives nothing
        val shut = load("top_render.png")
        assertTrue(CarPhotoKit.openParts(shut, CarPhotoKit.topFrom(shut, side)).isEmpty())
    }

    /**
     * Both doors on one side open, joined by a thin strip along the sill (a
     * redraw a little wider than the car): two doors, each on its own and
     * reaching the body at its hinge, the strip between them left out.
     */
    @Test
    fun doorsOnOneSideAreTwoAndHingedOnTheBody() {
        val side = CarPhotoKit.side(onFlat(load("side.png"), 0xFF000000.toInt()))
        val top = CarPhotoKit.topFrom(load("top_render.png"), side)
        val parts = CarPhotoKit.openParts(load("top_open_left.png"), top)
        note("open parts (left side): " + parts.joinToString { p -> "${p.part} ${p.box.joinToString(",") { "%.2f".format(Locale.ROOT, it) }}" })
        parts.forEach { save(it.image, "open_left_${it.part.key}.png") }
        // (the tailgate is open in this picture too)
        assertEquals(setOf(CarPart.FRONT_LEFT, CarPart.REAR_LEFT, CarPart.TAILGATE), parts.map { it.part }.toSet())
        val fl = parts.first { it.part == CarPart.FRONT_LEFT }.box
        val rl = parts.first { it.part == CarPart.REAR_LEFT }.box
        val bodyLeft = top.bodyBox[0]
        assertTrue("front door hinged on the body", fl[2] > bodyLeft)
        assertTrue("rear door hinged on the body", rl[2] > bodyLeft)
        assertTrue("the two doors apart, the sill between them left out", fl[3] < rl[1])
    }

    /** A picture from above drawn by the Gemini app, when one is put in build/carphoto-in/above.jpg (not kept in the repo). */
    @Test
    fun aGeminiPictureFromAboveWhenGiven() {
        val file = File("build/carphoto-in/above.jpg").takeIf { it.exists() } ?: return
        val img = ImageIO.read(file)
        val a = Argb(img.width, img.height, IntArray(img.width * img.height).also { img.getRGB(0, 0, img.width, img.height, it, 0, img.width) })
        val side = CarPhotoKit.side(load("real_side.jpg"))
        val top = CarPhotoKit.topFrom(a, side)
        save(top.image, "above_gemini.png")
        note("gemini above: ${top.image.w}x${top.image.h}, nose ${top.nose}, tail ${top.tail}, wheels ${top.wheels}")
        assertTrue(top.nose.y < top.tail.y)
    }
}
