package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min
import kotlin.math.pow

/**
 * Whether this head unit runs the firmware the boot logo feature was worked
 * out from: the QF001 / ROCO K706 units on Unisoc UIS7862(S), not FYT based
 * (made by Shenzhen Jitu, sold as Ossuret, Hizpo and others; XDA thread
 * 4525675), whose firmware calls itself ROCO/K706/K706. The menu entry only
 * shows there, so no other unit is ever offered it. Every sign must match:
 * the build's brand and device, the platform property the firmware's own
 * updater checks, and the firmware's logo tools in /system/bin (paths any
 * app may look at; /great and the logo partition are checked again as root
 * before anything is written). Anything unreadable counts as "no".
 */
internal object BootLogoSupport {
    val available: Boolean by lazy {
        runCatching {
            val platform = Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java).invoke(null, "ro.qf.platform") as String
            Build.BRAND.equals("ROCO", ignoreCase = true) &&
                Build.DEVICE.equals("K706", ignoreCase = true) &&
                platform == "7862" &&
                listOf("/system/bin/update_bootlogo.sh", "/system/bin/djpeg").all { File(it).exists() }
        }.getOrDefault(false)
    }
}

/** How the boot logo is drawn: on black or white, with or without the brand's name under it. */
internal data class BootStyle(val light: Boolean, val showName: Boolean)

/**
 * Builds an Android `bootanimation.zip` from a car logo: the logo fades and
 * grows in, a light glint sweeps across it, then it holds until Android has
 * started. Frames are full-screen PNGs at the display's real size, stored
 * (not deflated) in the zip as the boot animation player requires.
 */
internal object BootAnimationMaker {
    private const val FPS = 30
    private const val APPEAR = 20
    private const val GLINT = 16

    /** The display's native (unrotated) size, which is what the boot animation plays at. */
    fun screenSize(context: Context): Pair<Int, Int> {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
        val turned = display.rotation == Surface.ROTATION_90 || display.rotation == Surface.ROTATION_270
        return if (turned) metrics.heightPixels to metrics.widthPixels else metrics.widthPixels to metrics.heightPixels
    }

    /** Dark logos (black lettering) vanish on a black screen: they want the white background. */
    fun isDark(logo: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(logo, 64, 64, true)
        val pixels = IntArray(64 * 64).also { small.getPixels(it, 0, 64, 0, 0, 64, 64) }
        var sum = 0.0
        var weight = 0.0
        for (p in pixels) {
            val a = Color.alpha(p) / 255.0
            if (a < 0.1) continue
            sum += a * (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0
            weight += a
        }
        return weight > 0 && sum / weight < 0.22
    }

    /** The logo without its transparent margins, so every brand fills the screen alike. */
    fun trim(logo: Bitmap): Bitmap {
        val w = logo.width
        val h = logo.height
        val pixels = IntArray(w * h).also { logo.getPixels(it, 0, w, 0, 0, w, h) }
        var left = w; var top = h; var right = -1; var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(pixels[y * w + x]) > 8) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return logo
        return Bitmap.createBitmap(logo, left, top, right - left + 1, bottom - top + 1)
    }

    /**
     * One frame. [appear] runs 0..1 as the logo fades and grows in; [glint]
     * is the glint's position 0..1 across the logo, or null for none.
     */
    fun frame(logo: Bitmap, name: String, w: Int, h: Int, style: BootStyle, appear: Float, glint: Float?): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (style.light) Color.WHITE else Color.BLACK)
        val eased = 1f - (1f - appear).pow(3)

        val textSize = h * 0.055f
        val gap = if (style.showName) textSize * 1.6f else 0f
        val boxH = h * (if (style.showName) 0.42f else 0.5f)
        val boxW = w * 0.6f
        val fit = min(boxW / logo.width, boxH / logo.height) * (0.9f + 0.1f * eased)
        val lw = logo.width * fit
        val lh = logo.height * fit
        val blockH = lh + if (style.showName) gap + textSize else 0f
        val top = (h - blockH) / 2f
        val dst = RectF((w - lw) / 2f, top, (w + lw) / 2f, top + lh)

        // The logo on its own layer so the glint only lights the logo's pixels.
        val layer = canvas.saveLayer(dst, null)
        canvas.drawBitmap(logo, null, dst, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = (255 * eased).toInt() })
        if (glint != null) {
            val band = lw * 0.35f
            val x = dst.left - band + (lw + 2 * band) * glint
            val shine = if (style.light) 0x55FFFFFF else 0x88FFFFFF.toInt()
            canvas.drawRect(dst, Paint().apply {
                shader = LinearGradient(x - band, dst.top, x + band, dst.bottom, intArrayOf(0, shine, 0), null, Shader.TileMode.CLAMP)
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            })
        }
        canvas.restoreToCount(layer)

        if (style.showName) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                letterSpacing = 0.25f
                color = if (style.light) 0xFF3A3A3A.toInt() else 0xFFD8D8D8.toInt()
                alpha = (255 * eased).toInt()
                textAlign = Paint.Align.CENTER
            }
            val text = name.uppercase()
            val bounds = Rect().also { paint.getTextBounds(text, 0, text.length, it) }
            canvas.drawText(text, w / 2f, dst.bottom + gap - bounds.top, paint)
        }
        return out
    }

    /** The resting frame, as shown in the picker's preview. */
    fun still(logo: Bitmap, name: String, w: Int, h: Int, style: BootStyle): Bitmap = frame(logo, name, w, h, style, 1f, null)

    /** Writes the whole animation to [out]. */
    fun build(logo: Bitmap, name: String, w: Int, h: Int, style: BootStyle, out: File) {
        ZipOutputStream(FileOutputStream(out)).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    crc = CRC32().apply { update(bytes) }.value
                })
                zip.write(bytes)
                zip.closeEntry()
            }
            fun png(bitmap: Bitmap): ByteArray =
                ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }

            // "c": the intro always plays to the end; "p 0": part1 loops until Android is up.
            put("desc.txt", "$w $h $FPS\nc 1 0 part0\np 0 0 part1\n".toByteArray())
            var n = 0
            repeat(APPEAR) { i ->
                put("part0/%05d.png".format(n++), png(frame(logo, name, w, h, style, i / (APPEAR - 1f), null)))
            }
            repeat(GLINT) { i ->
                put("part0/%05d.png".format(n++), png(frame(logo, name, w, h, style, 1f, i / (GLINT - 1f))))
            }
            put("part1/00000.png", png(still(logo, name, w, h, style)))
        }
    }

    /**
     * The still logo the bootloader shows before Android, laid out for the
     * raw [panel] (width x height as the LCD scans it). On the QF001 / K706 a
     * 1280 x 720 screen is a 720 x 1280 portrait panel turned sideways, so the
     * picture is turned a quarter clockwise, like the firmware's own
     * `logo_boot_720x1280` pictures; a 1024 x 600 panel takes it as is.
     * Without a known panel, 1280-wide screens are assumed turned.
     */
    fun stillForPanel(logo: Bitmap, name: String, w: Int, h: Int, style: BootStyle, panel: Pair<Int, Int>?): Bitmap {
        val turned = if (panel != null) panel.first < panel.second && w > h else w >= 1280
        // Drawn at exactly the panel's size (turned or not), so it always matches what the bootloader expects.
        val (dw, dh) = when {
            panel == null -> w to h
            turned -> panel.second to panel.first
            else -> panel
        }
        val still = still(logo, name, dw, dh, style)
        if (!turned) return still
        return Bitmap.createBitmap(still, 0, 0, dw, dh, Matrix().apply { postRotate(90f) }, false).also { still.recycle() }
    }

    /** As a 24-bit BMP, the format Factory settings → Logo set reads from a USB stick. */
    fun writeBmp(picture: Bitmap, out: File) = out.writeBytes(bmp24(picture))

    /** As a JPEG, which the firmware's update_bootlogo.sh converts and flashes itself. */
    fun writeJpeg(picture: Bitmap, out: File) = out.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 95, it) }

    private fun bmp24(bitmap: Bitmap): ByteArray {
        val w = bitmap.width
        val h = bitmap.height
        val row = (w * 3 + 3) / 4 * 4
        val data = row * h
        val buf = ByteBuffer.allocate(54 + data).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + data).putInt(0).putInt(54)
        buf.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(data).putInt(2835).putInt(2835).putInt(0).putInt(0)
        val pixels = IntArray(w)
        for (y in h - 1 downTo 0) { // bottom row first
            bitmap.getPixels(pixels, 0, w, 0, y, w, 1)
            for (p in pixels) buf.put(Color.blue(p).toByte()).put(Color.green(p).toByte()).put(Color.red(p).toByte())
            repeat(row - w * 3) { buf.put(0) }
        }
        return buf.array()
    }
}

/**
 * Puts the boot logo in place over the head unit's internal root ADB (see
 * [AdbInstaller]), or Magisk root when that ADB is off, only on the firmware
 * it was worked out from: QF001 / K706 (ROCO, UIS7862S). Every other unit is refused ("Save to USB" still works).
 *
 * - Animation: that firmware's boot animation player reads
 *   `/great/bootanimation.zip` ahead of the stock `/system/media` one, and
 *   `/great` is a writable partition of its own, so /system is never touched.
 *   A broken animation can't stop Android starting; it only isn't shown.
 * - Still logo: the raw `logo` partition the bootloader reads, written the way
 *   the firmware's update_bootlogo.sh (Factory settings → Logo set) does:
 *   its djpeg makes a 256-colour BMP, which is dd'd in. The firmware update
 *   does not rewrite this partition, so it is handled with extra care: a
 *   full, size-checked backup first, the BMP checked (header, panel size,
 *   fits), the write read back, and the backup put straight back on any mismatch.
 *
 * Originals are kept once in /data/local/tmp for [restore], and copied to
 * Dashwheel/boot_backup on the internal storage so they can be kept off the unit too.
 */
internal object BootAnimationInstaller {
    private const val TMP = "/data/local/tmp/oad_bootanimation.zip"
    private const val TMP_BMP = "/data/local/tmp/oad_bootlogo.bmp"
    private const val LOGO_JPG = "/data/local/tmp/oad_bootlogo.jpg"
    private const val LOGO_BMP = "/data/local/tmp/oad_bootlogo_new.bmp"
    private const val BACKUP = "/data/local/tmp/oad_bootanimation_original"
    private const val SAFE = "/sdcard/Dashwheel/boot_backup"
    private const val GREAT_ZIP = "/great/bootanimation.zip"
    private const val LOGO_PART = "/dev/block/by-name/logo"
    private const val LOGO_SCRIPT = "/system/bin/update_bootlogo.sh"

    // Stops the script on anything but the known firmware layout.
    private const val QF_ONLY =
        "[ -d /great ] && [ -f $LOGO_SCRIPT ] && [ -e $LOGO_PART ] && [ -x /system/bin/djpeg ] || { echo NOTQF; exit; }; "

    private class NotQf : Exception()

    /** What a logo file name on the USB stick may be: a brand slug and `.bmp`. */
    private val SAFE_LOGO_NAME = Regex("[a-z0-9_-]+\\.bmp")

    /**
     * Runs [script] as root after putting each local file of [push] at its
     * path: over the internal ADB when it answers and gets root, else through
     * Magisk `su`, else over the non-root ADB (the script then says what it
     * couldn't do). Neither ADB nor su: a message saying how to turn ADB on.
     */
    private fun shell(context: Context, script: String, vararg push: Pair<File, String>): String {
        val port = AdbInstaller.listeningPort()
        val out = when {
            port != null && AdbInstaller.ensureRoot(context, port) -> adbShell(context, port, script, *push)
            SystemInstaller.isRootAvailable() -> suShell(script, *push)
            port != null -> adbShell(context, port, script, *push)
            else -> error(context.getString(R.string.boot_no_adb, AdbInstaller.announcedPort()))
        }
        Log.d("BootAnimation", out)
        if (out.contains("NOTQF")) throw NotQf()
        return out
    }

    // Pushing the animation and writing the logo partition can take a while: the long timeout.
    private fun adbShell(context: Context, port: Int, script: String, vararg push: Pair<File, String>): String =
        AdbInstaller.connect(context, port, readTimeoutMs = AdbInstaller.LONG_TIMEOUT_MS).use { dadb ->
            push.forEach { (file, to) -> dadb.push(file, to) }
            dadb.shell(script).allOutput
        }

    /** The script fed to `su` on stdin, so no quoting gets in the way; bounded so a stuck prompt can't hang the dialog. */
    private fun suShell(script: String, vararg push: Pair<File, String>): String {
        val copy = push.joinToString("") { (file, to) ->
            "cp '${file.absolutePath}' '$to' || { echo 'FAIL:could not copy ${file.name}'; exit; }; "
        }
        return RootShell.su(null, SU_TIMEOUT_S, stdin = copy + script + "\nexit\n").all
    }

    private const val SU_TIMEOUT_S = 120L

    private fun fail(context: Context, e: Throwable): Nothing =
        if (e is NotQf) error(context.getString(R.string.boot_not_qf)) else throw e

    /** Why the script stopped: its FAIL: line, else everything it said. */
    private fun reason(out: String): String =
        out.lineSequence().firstOrNull { it.startsWith("FAIL:") }?.removePrefix("FAIL:")?.trim() ?: out.trim()

    /** The LCD's own size (/sys/qf/panel_hactive x panel_vactive); null when this isn't the QF firmware. */
    fun panel(context: Context): Result<Pair<Int, Int>?> = runCatching {
        val out = try {
            shell(context, QF_ONLY + "echo \"PANEL \$(cat /sys/qf/panel_hactive) \$(cat /sys/qf/panel_vactive)\"")
        } catch (e: NotQf) {
            return@runCatching null
        }
        Regex("PANEL (\\d+) (\\d+)").find(out)?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    }

    /** Refuses a zip the boot animation player couldn't read, before anything is sent. */
    fun verify(zip: File) {
        java.util.zip.ZipFile(zip).use { z ->
            val entries = z.entries().toList()
            check(entries.any { it.name == "desc.txt" }) { "desc.txt missing" }
            check(entries.count { it.name.endsWith(".png") } > 0) { "no frames" }
            check(entries.all { it.method == ZipEntry.STORED }) { "compressed entries" }
        }
    }

    /** Installs the animation [zip] as /great/bootanimation.zip; returns that path. */
    fun install(context: Context, zip: File): Result<String> = runCatching {
        verify(zip)
        val out = try { shell(context, INSTALL_SCRIPT, zip to TMP) } catch (e: Exception) { fail(context, e) }
        if (!out.contains("OKBOOT")) error(reason(out))
        GREAT_ZIP
    }

    /**
     * Writes [jpeg] (exactly the panel's [w] x [h]) as the still boot logo,
     * with the checks described on this object.
     */
    fun installStillLogo(context: Context, jpeg: File, w: Int, h: Int): Result<Unit> = runCatching {
        val out = try { shell(context, stillScript(w, h), jpeg to LOGO_JPG) } catch (e: Exception) { fail(context, e) }
        if (!out.contains("OKLOGO")) error(reason(out))
    }

    /**
     * The firmware's own way: `QF/auto/bootanimation.zip` on a USB stick is
     * imported when the stick is plugged in, and a BMP at the stick's root
     * shows up in Factory settings → Logo set. Writes both to every mounted
     * stick and returns their names. Touches nothing on the unit itself.
     */
    fun saveToUsb(context: Context, zip: File, logo: File, logoName: String): Result<List<String>> = runCatching {
        // Pasted into a root shell command: a plain file name, nothing else.
        require(SAFE_LOGO_NAME.matches(logoName)) { "bad logo file name: $logoName" }
        verify(zip)
        val out = shell(
            context,
            "for D in /mnt/media_rw/*; do [ -d \"\$D\" ] || continue; " +
                "mkdir -p \"\$D/QF/auto\" && cp $TMP \"\$D/QF/auto/bootanimation.zip\" && cp $TMP_BMP \"\$D/$logoName\" && echo OKUSB:\$D; " +
                "done; sync; rm -f $TMP $TMP_BMP",
            zip to TMP, logo to TMP_BMP
        )
        if (out.contains("FAIL:")) error(reason(out))
        val sticks = out.lineSequence().filter { it.startsWith("OKUSB:") }.map { it.trim().substringAfterLast('/') }.toList()
        if (sticks.isEmpty()) error(context.getString(R.string.boot_no_usb))
        sticks
    }

    /** Puts the original animation and still logo back; false if neither was ever changed. */
    fun restore(context: Context): Result<Boolean> = runCatching {
        val out = try { shell(context, RESTORE_SCRIPT) } catch (e: Exception) { fail(context, e) }
        if (out.contains("FAIL:")) error(reason(out))
        out.contains("OKRESTORE")
    }

    /** Plays the installed boot animation over the screen for a few seconds. */
    fun play(context: Context): Result<Unit> = runCatching {
        // Ended from a detached shell too, so a dropped connection can't leave it on screen
        // (and a reboot always clears it).
        shell(
            context,
            "setprop service.bootanim.exit 0; start bootanim; " +
                "nohup sh -c 'sleep 8; setprop service.bootanim.exit 1; stop bootanim' >/dev/null 2>&1 & " +
                "sleep 7; setprop service.bootanim.exit 1; sleep 1; stop bootanim"
        )
        Unit
    }

    // \$ keeps Kotlin from interpolating shell variables.
    private val INSTALL_SCRIPT: String = buildString {
        append(QF_ONLY)
        append("NEW=$TMP; F=$GREAT_ZIP; BK=$BACKUP; SAFE=$SAFE; mkdir -p \$BK \$SAFE; ")
        append("[ -s \$NEW ] || { echo FAIL:nothing received; exit; }; ")
        // /great also keeps touch-screen and camera settings: leave it 2 MB to spare.
        append("NEED=\$(( \$(stat -c %s \$NEW) / 1024 + 2048 )); OLD=0; [ -f \$F ] && OLD=\$(( \$(stat -c %s \$F) / 1024 )); ")
        append("set -- \$(df -k /great | tail -1); FREE=\$4; ")
        append("[ \$(( FREE + OLD )) -ge \$NEED ] || { rm -f \$NEW; echo \"FAIL:not enough room on /great (\$FREE KB free)\"; exit; }; ")
        // The original, once: kept in /data and copied to the internal storage.
        append("if [ ! -f \$BK/great.zip ] && [ ! -f \$BK/great.created ]; then ")
        append("  if [ -f \$F ]; then cp \$F \$BK/great.zip || { rm -f \$BK/great.zip \$NEW; echo FAIL:could not back up the current animation; exit; }; ")
        append("    cp \$BK/great.zip \$SAFE/great_bootanimation.zip; ")
        append("  else touch \$BK/great.created; fi; ")
        append("fi; ")
        append("SUM=\$(md5sum < \$NEW); ")
        append("if cp \$NEW \$F && [ \"\$(md5sum < \$F)\" = \"\$SUM\" ]; then chmod 644 \$F; sync; echo OKBOOT; ")
        append("else if [ -f \$BK/great.zip ]; then cp \$BK/great.zip \$F; else rm -f \$F; fi; sync; echo FAIL:the copy did not arrive whole, original put back; fi; ")
        append("rm -f \$NEW")
    }

    private fun stillScript(w: Int, h: Int): String = buildString {
        append(QF_ONLY)
        append("P=$LOGO_PART; IN=$LOGO_JPG; OUT=$LOGO_BMP; BK=$BACKUP; SAFE=$SAFE; mkdir -p \$BK \$SAFE; ")
        append("bye() { rm -f \$IN \$OUT; echo \"FAIL:\$1\"; exit; }; ")
        append("PS=\$(blockdev --getsize64 \$P); [ -n \"\$PS\" ] && [ \"\$PS\" -gt 0 ] || bye 'logo partition size unknown'; ")
        // A full copy of the partition before the first change, checked for size.
        append("if [ ! -f \$BK/logo_partition.img ]; then ")
        append("  dd if=\$P of=\$BK/logo_partition.img bs=4096 2>/dev/null; ")
        append("  [ \"\$(stat -c %s \$BK/logo_partition.img)\" = \"\$PS\" ] || { rm -f \$BK/logo_partition.img; bye 'could not back up the logo partition'; }; ")
        append("  cp \$BK/logo_partition.img \$SAFE/logo_partition.img; ")
        append("fi; ")
        // The firmware's own conversion, then checks before anything is written.
        append("/system/bin/djpeg -bmp -colors 256 \$IN > \$OUT 2>/dev/null; ")
        append("SZ=\$(stat -c %s \$OUT 2>/dev/null); [ -n \"\$SZ\" ] && [ \"\$SZ\" -gt 1024 ] || bye 'picture conversion failed'; ")
        append("[ \"\$(head -c 2 \$OUT)\" = BM ] || bye 'converted picture is not a BMP'; ")
        append("set -- \$(od -An -tu4 -j18 -N8 \$OUT); [ \"\$1\" = $w ] && [ \"\$2\" = $h ] || bye \"picture is \$1 x \$2, panel is $w x $h\"; ")
        append("[ \"\$SZ\" -le \"\$PS\" ] || bye 'picture larger than the logo partition'; ")
        // Write, read back, and put the backup straight back on any mismatch.
        append("dd if=\$OUT of=\$P bs=4096 conv=notrunc 2>/dev/null; sync; ")
        append("if [ \"\$(head -c \$SZ \$P | md5sum)\" = \"\$(md5sum < \$OUT)\" ]; then rm -f \$IN \$OUT; echo OKLOGO; ")
        append("else dd if=\$BK/logo_partition.img of=\$P bs=4096 conv=notrunc 2>/dev/null; sync; bye 'logo did not read back the same, original put back'; fi")
    }

    private val RESTORE_SCRIPT: String = buildString {
        append(QF_ONLY)
        append("F=$GREAT_ZIP; BK=$BACKUP; P=$LOGO_PART; ")
        append("if [ -f \$BK/great.zip ]; then ")
        append("  if cp \$BK/great.zip \$F && [ \"\$(md5sum < \$F)\" = \"\$(md5sum < \$BK/great.zip)\" ]; then rm -f \$BK/great.zip; echo OKRESTORE; ")
        append("  else echo FAIL:could not put the original animation back; fi; ")
        append("elif [ -f \$BK/great.created ]; then rm -f \$F \$BK/great.created; echo OKRESTORE; fi; ")
        append("if [ -f \$BK/logo_partition.img ]; then ")
        append("  PS=\$(blockdev --getsize64 \$P); ")
        append("  if [ \"\$(stat -c %s \$BK/logo_partition.img)\" = \"\$PS\" ] && dd if=\$BK/logo_partition.img of=\$P bs=4096 conv=notrunc 2>/dev/null && sync ")
        append("    && [ \"\$(md5sum < \$P)\" = \"\$(md5sum < \$BK/logo_partition.img)\" ]; then rm -f \$BK/logo_partition.img; echo OKRESTORE; ")
        append("  else echo FAIL:could not put the original still logo back, the copy is in Dashwheel/boot_backup; fi; ")
        append("fi; sync")
    }
}
