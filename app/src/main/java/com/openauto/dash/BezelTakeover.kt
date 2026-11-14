package com.openauto.dash

import android.content.Context
import android.util.Log
import android.view.KeyEvent

/**
 * The QF unit's touch keys beside the screen, given to Dashwheel alone: a key
 * whose button on the map has "the unit does nothing with it" is moved, in
 * the firmware's touch key file, from its own function (Vol− = 603) to a spare
 * one (STUDY_KEY1 = 660) the firmware does nothing with, but still hands out
 * through its key service (as key 354). [original] turns that press back into
 * the key's own code, so the button learned before keeps its job. Let go, the
 * key gets its function back. Seen on the K706 on 2026-11-14. Root only.
 */
internal object BezelTakeover {
    private const val TAG = "BezelTakeover"
    private const val FILE = "/great/tp/tp_learn_file"
    private const val STATE = "/sys/class/tp_learn_class/tp_learn_dev/state"
    private const val PREFS = "bezel_takeover"
    private const val KEY_ORIGINALS = "originals"

    /** A key function of the touch key file (QFKeyConstants STUDY_*) → the key the unit hands out for it (QFApi.getKeyCode). */
    private val FUNCTION_KEYS: Map<Int, Int> = mapOf(
        600 to 291, 601 to 292, 602 to 293, 603 to 294,
        604 to KeyEvent.KEYCODE_HOME, 605 to KeyEvent.KEYCODE_MENU, 606 to KeyEvent.KEYCODE_BACK,
        607 to 298, 608 to 299, 609 to 300, 610 to 301, 611 to 302, 612 to 303, 613 to 304, 614 to 305, 615 to 306,
        616 to 307, 617 to 308, 618 to 309, 619 to 310, 620 to 311, 621 to 312, 622 to 313, 623 to 314, 624 to 315,
        625 to 316, 626 to 317, 627 to 318, 628 to 319, 629 to 320, 630 to 321, 631 to 322, 632 to 323, 633 to 324,
        641 to 335, 642 to 336, 648 to 342, 649 to 343, 650 to 344, 651 to 345, 652 to 346, 653 to 347
    )

    /** STUDY_KEY1..8 (660..667, keys 354..361): no firmware action. KEY9/KEY10 open the custom app and the 360 cameras. */
    private val SPARES: Map<Int, Int> = (0 until 8).associate { 660 + it to 354 + it }

    /** The touch key file's keys, by their unit key code, as last read; empty until then, or off a QF unit. */
    @Volatile var present: Set<Int> = emptySet()
        private set

    /** Spare key code → the key's own code, for the keys taken over now. */
    @Volatile private var taken: Map<Int, Int> = emptyMap()

    /** [keyCode] as the button map knows it: a taken-over key's own code, else unchanged. */
    fun original(keyCode: Int): Int = taken[keyCode] ?: keyCode

    /**
     * Makes the unit ignore exactly the touch keys [silenced] (their unit key
     * codes). Blocking, root; does nothing on a unit without the file.
     */
    fun apply(context: Context, silenced: Set<Int>) {
        if (!Edition.full || PrivilegedShell.access.value != PrivilegedShell.Access.ROOT) return
        runCatching {
            val text = RootShell.su("[ -f $FILE ] && cat $FILE", 10).out
            if (text.isBlank()) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val before = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val originals = decode(prefs.getString(KEY_ORIGINALS, "").orEmpty())
            val (after, kept) = rewrite(before, silenced, originals)
            present = after.indices.mapNotNull { ownKey(after, it, kept) }.toSet()
            taken = after.indices.mapNotNull { i -> spareKey(after[i])?.let { spare -> ownKey(after, i, kept)?.let { spare to it } } }.toMap()
            prefs.edit().putString(KEY_ORIGINALS, encode(kept)).apply()
            if (after == before) return
            // Written over in place (owner, mode, label kept), "\n" only as the firmware writes it
            // (with "\r\n" the driver keeps its old keys), then the driver reads it again.
            val out = RootShell.su(null, 10, stdin = "cat > $FILE <<'DW_EOF'\n${after.joinToString("\n", postfix = "\n")}DW_EOF\necho 0 > $STATE\necho 1 > $STATE\n")
            Log.i(TAG, "touch keys only Dashwheel acts on: ${taken.values.sorted().ifEmpty { listOf("none") }} (exit ${out.exit})")
        }.onFailure { Log.w(TAG, "could not write the touch key file", it) }
    }

    /**
     * The file's [lines] with each key in [silenced] on a spare function and
     * each spare that isn't wanted any more back on its own ([originals]: line
     * index → own function). A spare line with no known own function isn't
     * Dashwheel's and stays. Pure, for the tests.
     */
    internal fun rewrite(lines: List<String>, silenced: Set<Int>, originals: Map<Int, Int>): Pair<List<String>, Map<Int, Int>> {
        val kept = originals.filterKeys { it in lines.indices && spareKey(lines[it]) != null }.toMutableMap()
        val free = SPARES.keys.filter { s -> lines.none { function(it) == s } }.toMutableList()
        val out = lines.mapIndexed { i, line ->
            val f = function(line) ?: return@mapIndexed line
            val own = kept[i]
            when {
                own != null && FUNCTION_KEYS[own] !in silenced -> { kept.remove(i); withFunction(line, own) }
                own == null && FUNCTION_KEYS[f] in silenced && free.isNotEmpty() -> { val s = free.removeAt(0); kept[i] = f; withFunction(line, s) }
                else -> line
            }
        }
        return out to kept
    }

    /** "603/-090/0593" → 603; null for an empty line ("000/…") or anything else. */
    private fun function(line: String): Int? = line.substringBefore('/').toIntOrNull()?.takeIf { it != 0 }
    private fun spareKey(line: String): Int? = function(line)?.let { SPARES[it] }
    private fun withFunction(line: String, f: Int) = "%03d".format(f) + line.substring(line.indexOf('/'))
    /** Line [i]'s unit key code: its own one when taken over. */
    private fun ownKey(lines: List<String>, i: Int, kept: Map<Int, Int>): Int? =
        (kept[i] ?: function(lines[i]))?.let { FUNCTION_KEYS[it] }

    internal fun encode(m: Map<Int, Int>) = m.entries.joinToString(",") { "${it.key}:${it.value}" }
    internal fun decode(s: String): Map<Int, Int> = s.split(',').mapNotNull { e ->
        val (a, b) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        (a.toIntOrNull() ?: return@mapNotNull null) to (b.toIntOrNull() ?: return@mapNotNull null)
    }.toMap()
}
