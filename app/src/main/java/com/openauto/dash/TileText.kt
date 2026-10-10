package com.openauto.dash

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * The words on a navigation app's tile, read off its picture. An app inside
 * a tile runs on a display of its own, which the accessibility service never
 * sees (it tracks the dashboard's window only), so [WazeScreen] and
 * [MapsScreen] got nothing from a Waze or a Maps in a tile. The tile's
 * picture is Dashwheel's own, though: every second and a half it is handed
 * to on-device text recognition, and the lines found, with where they sit,
 * go through the same readers as the accessibility tree would: Waze's next
 * turn, Maps' destination off its route preview.
 *
 * Read only while there is something to read: Waze guiding in a tile, or
 * Maps in a tile before its guidance starts (its turns come from its
 * notification once it does). Latin script only: the recognizer Play
 * services ship by default.
 *
 * Kept cheap: each tile's picture is drawn into the same bitmap read after
 * read, no read is taken while the pages slide, and a picture equal to the
 * last one ([frameSignature]) gives the last words again without asking the
 * recognizer; the sums and the recognizer run off the main thread.
 */
internal object TileText {
    private const val TAG = "TileText"
    private const val READ_MS = 1_500L
    private const val LOG_EVERY_MS = 10_000L
    /** The picture is read at most this wide: plenty for the banner's words, cheap for the recognizer. */
    private const val MAX_WIDTH = 800

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var recognizer: TextRecognizer? = null
    private var logged: String? = null
    private var loggedAt = 0L

    /** Per tile: its kept picture, that picture's [frameSignature] and the words read off it. */
    private class Seen {
        var bitmap: Bitmap? = null
        var signature = 0L
        var pieces: List<WazeScreen.Piece>? = null
    }

    /** Main thread, and one read at a time: a kept picture is never drawn into while the recognizer reads it. */
    private val seen = HashMap<String, Seen>()
    private var pixels = IntArray(0)

    fun start(context: Context) {
        if (started || !Edition.full) return
        started = true
        val app = context.applicationContext
        scope.launch {
            combine(EmbeddedApp.hosted, NavDirections.running) { hosted, running -> wanted(hosted, running) }
                .distinctUntilChanged()
                .collectLatest { packages ->
                    if (packages.isEmpty()) return@collectLatest
                    Log.i(TAG, "reading the tile of ${packages.joinToString()}")
                    try {
                        while (true) {
                            for (pkg in packages) read(app, pkg)
                            delay(READ_MS)
                        }
                    } finally {
                        // Let go, not recycled: a read cut short may still be in the recognizer's hands.
                        seen.clear()
                        pixels = IntArray(0)
                    }
                }
        }
    }

    /**
     * Whose tile is worth reading: Waze's whenever it is up (its route preview
     * names the destination, and while it guides its notification says
     * nothing, so the turn comes from the picture); Maps' while it is up and
     * not guiding yet (its preview names the destination; once it guides,
     * its notification tells the turns and the preview is gone). Pure, for the tests.
     */
    internal fun wanted(hosted: Set<String>, running: String?): Set<String> = buildSet {
        if (WazeScreen.PACKAGE in hosted) add(WazeScreen.PACKAGE)
        if (MapsScreen.PACKAGE in hosted && running != MapsScreen.PACKAGE) add(MapsScreen.PACKAGE)
    }

    private suspend fun read(context: Context, pkg: String) {
        // Mid-swipe the picture is not worth the main thread: the next read, a second and a half on, takes it.
        if (PipAnchor.pageSwiping.value) return
        val tile = seen.getOrPut(pkg) { Seen() }
        val kept = tile.bitmap
        val bitmap = EmbeddedApp.tileBitmap(pkg, MAX_WIDTH, kept) ?: return
        if (bitmap !== kept) {
            // A new size: the old picture's last read is over, it can go.
            kept?.recycle()
            tile.bitmap = bitmap
            tile.pieces = null
        }
        val height = bitmap.height
        val pieces = try {
            withContext(Dispatchers.Default) {
                val count = bitmap.width * height
                if (pixels.size < count) pixels = IntArray(count)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, height)
                val signature = frameSignature(pixels, bitmap.width, height)
                tile.pieces?.takeIf { signature == tile.signature }
                    ?: recognize(bitmap).also { tile.signature = signature; tile.pieces = it }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The tile stopped being worth reading mid-read: not a failure.
            throw e
        } catch (e: Exception) {
            // The recognizer is missing (Play services still fetching it) or refused the picture: tried again in a minute.
            Log.w(TAG, "no text recognition: ${e.message}")
            delay(60_000)
            return
        }
        when (pkg) {
            WazeScreen.PACKAGE -> {
                if (NavDirections.running.value != WazeScreen.PACKAGE) {
                    // Not guiding yet: its route preview names the destination, like Maps' does.
                    val destination = MapsScreen.destinationOf(pieces, height)
                    log(pkg, pieces, destination?.let { "destination \"$it\"" } ?: "no destination")
                    if (destination != null) MapsScreen.noticed(destination)
                    return
                }
                val state = WazeScreen.fromScreen(pieces, height, context.getString(R.string.info_nav_continue))
                log(pkg, pieces, state?.let { "${it.distance} ${it.instruction} (${it.eta})" } ?: "no turn")
                NavDirections.publishScreen(state)
            }
            MapsScreen.PACKAGE -> {
                val destination = MapsScreen.destinationOf(pieces, height)
                log(pkg, pieces, destination?.let { "destination \"$it\"" } ?: "no destination")
                if (destination != null) MapsScreen.noticed(destination)
            }
        }
    }

    /** The lines the recognizer finds, as pieces with their place in the picture. */
    private suspend fun recognize(bitmap: Bitmap): List<WazeScreen.Piece> {
        val client = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        return suspendCancellableCoroutine { cont ->
            client.process(InputImage.fromBitmap(bitmap, 0))
                // Answered on the recognizer's own thread, not posted to the main one.
                .addOnSuccessListener(DIRECT) { text ->
                    cont.resume(
                        text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            val words = line.text.trim()
                            if (words.isEmpty()) null else WazeScreen.Piece(words, box.top, box.bottom, box.left)
                        }
                    )
                }
                .addOnFailureListener(DIRECT) { cont.cancel(it) }
        }
    }

    private val DIRECT = java.util.concurrent.Executor { it.run() }

    /**
     * A sum of every pixel of a picture and its size (FNV-1a): equal for the
     * same frame, different as soon as one pixel differs, so an unchanged
     * tile is not read again. Every pixel, not a sample: a turn's distance
     * ticking down changes only a few. Pure, for the tests.
     */
    internal fun frameSignature(pixels: IntArray, width: Int, height: Int): Long {
        var h = -0x340d631b7bdddcdbL // FNV offset basis
        h = (h xor width.toLong()) * 0x100000001b3L
        h = (h xor height.toLong()) * 0x100000001b3L
        for (i in 0 until width * height) h = (h xor (pixels[i].toLong() and 0xffffffffL)) * 0x100000001b3L
        return h
    }

    /** What was read, when it changes and at most every [LOG_EVERY_MS]: to teach the readers a layout they get wrong. */
    private fun log(pkg: String, pieces: List<WazeScreen.Piece>, result: String) {
        val what = pieces.joinToString(" | ") { it.text + "@" + it.top }
        val now = android.os.SystemClock.elapsedRealtime()
        if (what == logged || now - loggedAt < LOG_EVERY_MS) return
        logged = what
        loggedAt = now
        Log.i(TAG, "${pkg.substringAfterLast('.')}: $result from [$what]")
    }
}
