package com.openauto.dash.display

import java.awt.image.BufferedImage
import java.io.File
import java.io.RandomAccessFile

/**
 * The kernel's own framebuffer (fb0): what the monitor falls back to whenever no
 * program holds the screen, such as between the boot logo and the first picture
 * or between drawn pictures and video. Filled with the picture about to be
 * replaced, those hand-overs show it instead of a black console.
 */
object ConsoleFrameBuffer {
    private const val DEVICE = "/dev/fb0"
    private const val SYS = "/sys/class/graphics/fb0"

    fun show(image: BufferedImage) {
        runCatching {
            val bpp = File("$SYS/bits_per_pixel").readText().trim().toInt()
            val (w, h) = File("$SYS/virtual_size").readText().trim().split(',').map { it.toInt() }
            val stride = File("$SYS/stride").readText().trim().toInt()
            val bytes = when (bpp) {
                16 -> rgb565(image, w, h, stride)
                32 -> xrgb8888(image, w, h, stride)
                else -> return
            }
            RandomAccessFile(DEVICE, "rw").use { it.write(bytes) }
        }
    }

    private fun rgb565(image: BufferedImage, w: Int, h: Int, stride: Int): ByteArray {
        val out = ByteArray(stride * h)
        val row = IntArray(w)
        for (y in 0 until minOf(h, image.height)) {
            val n = minOf(w, image.width)
            image.getRGB(0, y, n, 1, row, 0, w)
            var o = y * stride
            for (x in 0 until n) {
                val p = row[x]
                val v = ((p shr 8) and 0xF800) or ((p shr 5) and 0x07E0) or ((p shr 3) and 0x001F)
                out[o++] = v.toByte()
                out[o++] = (v shr 8).toByte()
            }
        }
        return out
    }

    private fun xrgb8888(image: BufferedImage, w: Int, h: Int, stride: Int): ByteArray {
        val out = ByteArray(stride * h)
        val row = IntArray(w)
        for (y in 0 until minOf(h, image.height)) {
            val n = minOf(w, image.width)
            image.getRGB(0, y, n, 1, row, 0, w)
            var o = y * stride
            for (x in 0 until n) {
                val p = row[x]
                out[o++] = p.toByte()
                out[o++] = (p shr 8).toByte()
                out[o++] = (p shr 16).toByte()
                out[o++] = 0
            }
        }
        return out
    }
}
