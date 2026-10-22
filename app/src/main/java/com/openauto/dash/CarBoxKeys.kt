package com.openauto.dash

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * The steering wheel keys a Peugeot, Citroën or DS CAN box (Raise, RZC
 * protocol) sends in its own key frame, type 0x02: the button's code while it
 * is held, 0 once it is let go. The firmware turns several of them into one
 * key (Voice and Phone both its phone key 301, the two thumb wheels both
 * previous/next track), so the button map tells such buttons apart by this
 * code ([SteeringWheelStore.onBoxKey]). Read off the CAN box stream: the MCU's
 * CAN channel ([McuFeed], or the root log through [McuReader]). Recorded on a
 * C4 Picasso 2011 with the box set to Citroen Quatre 08-16.
 */
internal object CarBoxKeys {
    private const val TAG = "CarBoxKeys"
    const val VENDOR = "box"
    private const val CAR_MODE = "persist.sys.car_mode"

    private val reader = BoxKeyReader()
    private val main = Handler(Looper.getMainLooper())
    /** Key frames were seen on the stream, and the car mode read then: what the unit report says. */
    @Volatile private var framesSeen = false
    @Volatile private var carMode: String? = null
    @Volatile private var ignoredLogged = false

    /** Bytes of the CAN box stream from [from] on: an MCU message, cmd 65 first. */
    fun feed(message: ByteArray, from: Int) {
        if (!SteeringWheelStore.AVAILABLE) return
        val codes = synchronized(reader) { reader.push(message, from, SystemClock.uptimeMillis()) }
        if (codes.isEmpty()) return
        framesSeen = true
        // Type 0x02 means a key only in the PSA protocol: the box's car model says which one it speaks.
        val mode = SysProps.get(CAR_MODE)
        carMode = mode
        if (!psa(mode)) {
            if (!ignoredLogged) {
                ignoredLogged = true
                Log.i(TAG, "CAN box key frames ignored: car mode '${mode.orEmpty()}' is not a Raise box on a Peugeot, Citroen or DS")
            }
            return
        }
        for (code in codes) main.post { SteeringWheelStore.onBoxKey(code) }
    }

    /** One line for the unit report: whether the box's key frames are read, and why not. */
    fun reportLine(): String {
        val mode = carMode ?: SysProps.getOrNull(CAR_MODE)
        val modeText = if (mode.isNullOrBlank()) "car mode unset" else "car mode $mode"
        return when {
            !framesSeen -> "no key frames seen, $modeText"
            psa(mode) -> "yes, $modeText"
            else -> "ignored, $modeText is not a Raise box on a PSA car"
        }
    }

    /** "[Raise-2]-[Citroen]-[Quatre]-[08-16]": a Raise box set to a Peugeot, Citroën or DS. */
    internal fun psa(carMode: String?): Boolean {
        if (carMode == null || !carMode.contains("Raise", ignoreCase = true)) return false
        return PSA_BRANDS.any { carMode.contains("[$it", ignoreCase = true) }
    }

    private val PSA_BRANDS = listOf("Citroen", "Peugeot", "DS")
}

/**
 * The key frames out of the CAN box stream: each new code (0 = let go), a
 * repeat of the held one left out. The box drops frames now and then: a code
 * still "held" after [holdMaxMs] whose release was lost that way counts as
 * pressed again. Pure, for the tests.
 */
internal class BoxKeyReader(private val holdMaxMs: Long = 3_000L) {
    private val frames = RzcFrames()
    private var held = 0
    private var heldAt = 0L

    /** [now] on any clock in ms, for the lost-release rule. */
    fun push(bytes: ByteArray, from: Int, now: Long = 0L): List<Int> {
        var codes: MutableList<Int>? = null
        frames.push(bytes, from) { type, data ->
            if (type != TYPE_KEY || data.isEmpty() || data.size > 2) return@push
            val code = data[0].toInt() and 0xFF
            if (code == held && (code == 0 || now - heldAt < holdMaxMs)) return@push
            held = code
            heldAt = now
            (codes ?: mutableListOf<Int>().also { codes = it }) += code
        }
        return codes ?: emptyList()
    }

    private companion object {
        const val TYPE_KEY = 0x02
    }
}
