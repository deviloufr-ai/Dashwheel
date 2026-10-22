package com.openauto.dash

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.openauto.dash.unit.QfAdapter
import org.json.JSONObject

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

/**
 * The firmware's own table of CAN box key codes, the one the unit's SWC study
 * writes (QF firmware: com.qf.vehicle's KeyDistributor reads it as it starts).
 * Each entry is "<box code>:<function>"; a box code given function 0, which no
 * key of the firmware has, does nothing on the unit any more, and only
 * Dashwheel acts on it ([WheelButton.unitIgnores]). Function 0 is never
 * written by the study itself, so such entries are Dashwheel's and are taken
 * out again when the button is let go back to the unit, which brings the
 * firmware's default back. Root only.
 */
internal object FirmwareKeyTable {
    private const val TAG = "FirmwareKeyTable"
    private const val KEY = "swc_map_json"
    private const val NOTHING = "0"
    private val FILES = listOf("/data/data", "/data/user_de/0").map { "$it/${QfAdapter.VEHICLE_PACKAGE}/shared_prefs/swc_map_db.xml" }
    private val ELEMENT = Regex("<string name=\"$KEY\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)

    /** The table's entries in order, "study0" on, as the firmware reads them: up to the first missing one. */
    internal fun entries(json: String): List<String> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return generateSequence(0) { it + 1 }.map { obj.optString("study$it", "") }.takeWhile { it.isNotEmpty() && it != "0:0" }.toList()
    }

    /** [entries] with exactly [silenced] mapped to nothing: the study's other entries kept as they are. */
    internal fun silence(entries: List<String>, silenced: Set<Int>): List<String> =
        entries.filterNot { e ->
            val box = e.substringBefore(':').trim().toIntOrNull()?.and(0xFF)
            box in silenced || e.substringAfter(':').trim() == NOTHING
        } + silenced.sorted().map { "${it.toByte()}:$NOTHING" }

    internal fun json(entries: List<String>): String =
        JSONObject().apply { entries.forEachIndexed { i, e -> put("study$i", e) } }.toString()

    /** The table out of the preferences file [xml]. */
    internal fun jsonOf(xml: String): String = ELEMENT.find(xml)?.groupValues?.get(1)?.let(::unescape) ?: "{}"

    /** The preferences file [xml] (null: none yet) holding [json] as its table. */
    internal fun xmlWith(xml: String?, json: String): String {
        val element = "<string name=\"$KEY\">${escape(json)}</string>"
        return when {
            xml != null && ELEMENT.containsMatchIn(xml) -> ELEMENT.replace(xml) { element }
            xml != null && "</map>" in xml -> xml.replace("</map>", "    $element\n</map>")
            else -> "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n    $element\n</map>\n"
        }
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun unescape(s: String) = s.replace("&quot;", "\"").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    /**
     * Makes the unit ignore exactly the box codes [silenced]. Written only when
     * that changes the table; the firmware's app is then restarted to read it,
     * as the study's own Save does. Blocking, root; false when it failed.
     */
    fun apply(silenced: Set<Int>): Boolean = runCatching {
        val found = RootShell.su("for f in ${FILES.joinToString(" ")}; do [ -f \$f ] && { echo \$f; cat \$f; break; }; done", 10)
        val path = found.out.lineSequence().firstOrNull()?.trim()?.takeIf { it.startsWith("/") }
        val xml = path?.let { found.out.substringAfter('\n') }
        val before = entries(xml?.let(::jsonOf) ?: "{}")
        val after = silence(before, silenced)
        if (after == before) return@runCatching true
        val target = path ?: FILES.first()
        val vehicle = QfAdapter.VEHICLE_PACKAGE
        // Written over the file in place: it keeps its owner, mode and SELinux label.
        val script = "t=/data/local/tmp/dw_swc.xml\n" +
            "cat > \$t <<'DW_EOF'\n${xmlWith(xml, json(after))}\nDW_EOF\n" +
            "f=$target\n" +
            "if [ -f \$f ]; then cat \$t > \$f; else d=\${f%/*}; mkdir -p \$d; cp \$t \$f; o=\$(stat -c %u:%g \${d%/*}); chown \$o \$d \$f; chmod 771 \$d; chmod 660 \$f; restorecon -R \$d 2>/dev/null; fi\n" +
            "rm -f \$t\n" +
            "am force-stop $vehicle\n" +
            // Not persistent: started at boot and ACC on, so started again here, or the wheel keys stay dead.
            "am startservice -n $vehicle/.service.VehicleService >/dev/null 2>&1 || am start-foreground-service -n $vehicle/.service.VehicleService\n"
        val out = RootShell.su(null, 20, stdin = script)
        Log.i(TAG, "box codes the unit ignores now: ${silenced.sorted().joinToString { wheelBoxLabel(it) }.ifEmpty { "none" }} (exit ${out.exit})")
        out.exit == 0
    }.onFailure { Log.w(TAG, "could not write the unit's key table", it) }.getOrDefault(false)
}
