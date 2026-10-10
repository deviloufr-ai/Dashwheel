package com.openauto.dash.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * The car's registration certificate (carte grise, Zulassungsbescheinigung
 * Teil I, libretto...): the EU numbers its fields the same way everywhere
 * (directive 1999/37/EC), so one reader takes them all. The phone reads a
 * photo of it with on-device text recognition; only the fields found go to
 * the head unit, never the photo.
 */

/**
 * Phone → head unit: what was read off the registration certificate, checked
 * by the driver on the phone. Every field may be blank or null.
 */
@Serializable
@SerialName("car_registration")
data class CarRegistration(
    /** A: the plate, "AB-123-CD" for a French one. */
    val plate: String = "",
    /** B: the first registration, ISO yyyy-MM-dd. */
    val firstRegistration: String = "",
    /** E: the vehicle identification number. */
    val vin: String = "",
    /** D.1: the make, as printed. */
    val make: String = "",
    /** D.3: the commercial name. */
    val model: String = "",
    /** P.3: the fuel as printed ("GO", "ES", "Diesel"...). */
    val energy: String = "",
    /** P.2: the engine's power. */
    val powerKw: Int? = null,
    /** P.1: the cylinder capacity. */
    val displacementCc: Int? = null,
    /** From V.9: the Euro emission standard, 1..6. */
    val euro: Int? = null,
    /**
     * X.1 on a French certificate, "VISITE AVANT LE ...": the deadline of the
     * next roadworthiness test when the certificate was made, ISO yyyy-MM-dd.
     */
    val nextInspection: String = ""
) : LinkMessage {
    val empty: Boolean
        get() = plate.isBlank() && firstRegistration.isBlank() && vin.isBlank() && make.isBlank() && model.isBlank() &&
            energy.isBlank() && powerKw == null && displacementCc == null && euro == null && nextInspection.isBlank()
}

/** What a car runs on, as far as the registration and the Crit'Air rules tell them apart. */
enum class Energy { PETROL, DIESEL, HYBRID_PETROL, HYBRID_DIESEL, PLUG_IN_HYBRID, LPG, NATURAL_GAS, ELECTRIC, HYDROGEN }

object Energies {
    /** The French certificate's P.3 codes. */
    private val CODES = mapOf(
        "ES" to Energy.PETROL, "FE" to Energy.PETROL, "GO" to Energy.DIESEL,
        "EH" to Energy.HYBRID_PETROL, "GH" to Energy.HYBRID_DIESEL,
        "EE" to Energy.PLUG_IN_HYBRID, "GL" to Energy.PLUG_IN_HYBRID,
        "GP" to Energy.LPG, "GPL" to Energy.LPG, "EG" to Energy.LPG,
        "GN" to Energy.NATURAL_GAS, "GNV" to Energy.NATURAL_GAS, "EN" to Energy.NATURAL_GAS,
        "EL" to Energy.ELECTRIC, "H2" to Energy.HYDROGEN
    )

    /** [printed] as a French code or a word in one of the EU's languages; null when neither. */
    fun of(printed: String): Energy? {
        val text = printed.trim().uppercase()
        if (text.isEmpty()) return null
        CODES[text.split(' ', '/', '-').first()]?.let { return it }
        // "ON" is Polish for diesel (olej napędowy): a word of its own only.
        val diesel = listOf("DIESEL", "GAZOLE", "GASOLIO", "GASOLEO", "GASÓLEO").any { it in text } || "ON" in text.split(Regex("[^A-Z]+"))
        val petrol = listOf("ESSENCE", "BENZIN", "BENZYN", "PETROL", "GASOLINA", "GASOLINE", "SUPER").any { it in text }
        val electric = listOf("ELEKTR", "ELECTR", "ÉLECTR", "ELETTR").any { it in text }
        return when {
            listOf("LPG", "GPL", "AUTOGAS", "FLÜSSIGGAS", "FLUSSIGGAS").any { it in text } -> Energy.LPG
            listOf("CNG", "GNV", "ERDGAS", "METANO").any { it in text } -> Energy.NATURAL_GAS
            listOf("HYDROG", "WASSERSTOFF", "IDROGENO").any { it in text } -> Energy.HYDROGEN
            electric && "PLUG" in text -> Energy.PLUG_IN_HYBRID
            electric && diesel -> Energy.HYBRID_DIESEL
            electric && petrol -> Energy.HYBRID_PETROL
            electric -> Energy.ELECTRIC
            diesel -> Energy.DIESEL
            petrol -> Energy.PETROL
            else -> null
        }
    }
}

/**
 * The French air-quality sticker a private car gets (arrêté du 21 juin 2016),
 * from its fuel and its Euro standard, or else its first registration date.
 */
object CritAir {
    /** Too old to be classed: no sticker, so kept out of every low-emission zone. */
    const val UNCLASSED = 6

    /** 0..5, [UNCLASSED], or null when not enough is known. */
    fun of(energy: Energy?, firstRegistration: LocalDate?, euro: Int?): Int? {
        energy ?: return null
        return when (energy) {
            Energy.ELECTRIC, Energy.HYDROGEN -> 0
            Energy.LPG, Energy.NATURAL_GAS, Energy.PLUG_IN_HYBRID -> 1
            Energy.PETROL, Energy.HYBRID_PETROL -> when {
                euro != null -> when {
                    euro >= 5 -> 1
                    euro == 4 -> 2
                    euro >= 2 -> 3
                    else -> UNCLASSED
                }
                firstRegistration == null -> null
                firstRegistration >= LocalDate.of(2011, 1, 1) -> 1
                firstRegistration >= LocalDate.of(2006, 1, 1) -> 2
                firstRegistration >= LocalDate.of(1997, 1, 1) -> 3
                else -> UNCLASSED
            }
            Energy.DIESEL, Energy.HYBRID_DIESEL -> when {
                euro != null -> when {
                    euro >= 5 -> 2
                    euro == 4 -> 3
                    euro == 3 -> 4
                    euro == 2 -> 5
                    else -> UNCLASSED
                }
                firstRegistration == null -> null
                firstRegistration >= LocalDate.of(2011, 1, 1) -> 2
                firstRegistration >= LocalDate.of(2006, 1, 1) -> 3
                firstRegistration >= LocalDate.of(2001, 1, 1) -> 4
                firstRegistration >= LocalDate.of(1997, 1, 1) -> 5
                else -> UNCLASSED
            }
        }
    }
}

/**
 * Reads the certificate's fields out of the lines a text recognizer found on
 * its photo. Each field is looked for after its printed label (A, B, D.1,
 * D.3, E, P.1, P.2, P.3, X.1), on the same line or, when the label stands alone,
 * the nearest line to its right or under it; the plate, the VIN and the date
 * are also known by their shape, for a photo where the labels didn't come out.
 */
object RegistrationReader {
    /** One line the recognizer found, with its box in the photo. */
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * Every label printed on the certificate: the wanted ones, and the others
     * that end a value. A one-letter label may carry a dot ("A.", "E.").
     */
    private val LABEL = Regex("(?:^|(?<=\\s))([CDFJPSUVXYZ][.,]\\s?\\d(?:[.,]\\s?\\d)?|[ABEGHIJKQ]\\.?)(?=[\\s:]|$)")
    private val WANTED = setOf("A", "B", "E", "D.1", "D.3", "P.1", "P.2", "P.3", "X.1")
    private val SIV = Regex("(?<![A-Z0-9])([A-Z]{2})\\s?[-\\s]\\s?([0-9OI]{3})\\s?[-\\s]\\s?([A-Z]{2})(?![A-Z0-9])")
    private val SIV_TIGHT = Regex("(?<![A-Z0-9])([A-Z]{2})([0-9]{3})([A-Z]{2})(?![A-Z0-9])")
    private val FNI = Regex("^(\\d{1,4})\\s?([A-Z]{1,3})\\s?(\\d{2}|2A|2B)$")
    private val DATE = Regex("(?<!\\d)(\\d{1,2})\\s?[/.\\-]\\s?(\\d{1,2})\\s?[/.\\-]\\s?(\\d{4})(?!\\d)")
    private val VIN_SHAPE = Regex("(?<![A-Z0-9])[A-Z0-9]{17}(?![A-Z0-9])")
    /** The recognizer often reads EURO5 as EUR05. */
    private val EURO = Regex("EUR[O0]\\s?([1-6])")
    private val NUMBER = Regex("^(\\d{1,5})(?:[.,]\\d+)?")
    /** X.1's words, "VISITE AVANT LE 18/05/2027", up to the date even across a line break. */
    private val INSPECTION_BY = Regex("AVANT\\s+L[EF]\\s*\\d{1,2}\\s?[/.\\-]\\s?\\d{1,2}\\s?[/.\\-]\\s?\\d{4}")

    /** A date as printed, past or future. */
    private fun anyDateOf(m: MatchResult): LocalDate? {
        val (d, mo, y) = m.destructured
        return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()?.takeIf { it.year in 1950..2100 }
    }

    /** Plain text, one line per line (tests, or a recognizer without boxes). */
    fun read(text: String, today: LocalDate = LocalDate.now()): CarRegistration =
        read(text.lines().filter { it.isNotBlank() }.mapIndexed { i, t -> Line(t, 0, i * 20, 1000, i * 20 + 16) }, today)

    fun read(lines: List<Line>, today: LocalDate = LocalDate.now()): CarRegistration {
        val clean = lines.map { it.copy(text = it.text.uppercase().replace(Regex("\\s+"), " ").trim()) }.filter { it.text.isNotEmpty() }
        val labelled = labelled(clean)
        val all = clean.joinToString("\n") { it.text }

        fun <T : Any> field(key: String, read: (String) -> T?): T? = labelled[key]?.firstNotNullOfOrNull(read)

        val plate = field("A", ::plateOf) ?: sivIn(all).orEmpty()
        // The VIN is printed twice on a French certificate (E and the coupon): one from a known maker wins.
        val vins = (labelled["E"].orEmpty() + VIN_SHAPE.findAll(all).map { it.value }).mapNotNull(::vinOf)
        val vin = (vins.firstOrNull { it.take(3) in MAKERS } ?: vins.firstOrNull()).orEmpty()
        val dates = DATE.findAll(all).mapNotNull { dateOf(it, today) }.toList()
        // B is the first registration; with its label lost, the earliest date (I, this certificate's, comes later).
        val first = field("B") { v -> DATE.find(v)?.let { dateOf(it, today) } } ?: dates.minOrNull()
        return CarRegistration(
            plate = plate,
            firstRegistration = first?.toString().orEmpty(),
            vin = vin,
            make = field("D.1", ::nameOf).orEmpty(),
            model = field("D.3", ::nameOf).orEmpty(),
            energy = field("P.3", ::energyOf).orEmpty(),
            powerKw = field("P.2") { v -> numberOf(v)?.takeIf { it in 5..1500 } },
            displacementCc = field("P.1") { v -> numberOf(v)?.takeIf { it in 50..10_000 } },
            euro = EURO.find(all)?.groupValues?.get(1)?.toInt(),
            // A deadline, so it may well be ahead of today; never before the car was registered.
            // With its label misread ("X1", "X.I") or on a line of its own, the words before the date still tell it.
            nextInspection = (field("X.1") { v -> DATE.find(v)?.let(::anyDateOf)?.takeIf { first == null || it.isAfter(first) } }
                ?: INSPECTION_BY.find(all)?.let { DATE.find(it.value) }?.let(::anyDateOf)?.takeIf { first == null || it.isAfter(first) })
                ?.toString().orEmpty()
        )
    }

    /**
     * Each wanted label's values, in reading order: the text after it on its
     * line up to the next label, or the nearest line beside or under it. A
     * make or a model runs up to the next dotted label only: "CLASSE A" is a
     * model, not a model and a plate.
     */
    private fun labelled(lines: List<Line>): Map<String, List<String>> {
        val found = LinkedHashMap<String, MutableList<String>>()
        for (line in lines) {
            val labels = LABEL.findAll(line.text).toList()
            for ((i, m) in labels.withIndex()) {
                val key = m.value.replace(" ", "").replace(',', '.').trimEnd('.')
                if (key !in WANTED) continue
                val named = key == "D.1" || key == "D.3"
                val next = labels.drop(i + 1).firstOrNull { !named || it.value.length > 1 }
                val value = line.text.substring(m.range.last + 1, next?.range?.first ?: line.text.length).trim().trimStart(':').trim()
                val text = value.ifEmpty { if (next == null) neighbour(line, lines)?.text.orEmpty() else "" }
                if (text.isNotEmpty()) found.getOrPut(key) { mutableListOf() } += text
            }
        }
        return found
    }

    /** The line just right of [line] on its row, else the one just under it. */
    private fun neighbour(line: Line, lines: List<Line>): Line? {
        val height = (line.bottom - line.top).coerceAtLeast(1)
        val middle = (line.top + line.bottom) / 2
        val right = lines.filter { it !== line && it.left >= line.right - height && middle in it.top..it.bottom && !startsWithLabel(it) }
            .minByOrNull { it.left - line.right }
        if (right != null && right.left - line.right < height * 8) return right
        return lines.filter { it !== line && it.top >= line.bottom - height / 2 && it.top - line.bottom < height * 2 && it.right > line.left && it.left < line.right + height * 4 }
            .filter { !startsWithLabel(it) }
            .minByOrNull { it.top }
    }

    private fun startsWithLabel(line: Line): Boolean = LABEL.find(line.text)?.range?.first == 0

    /** A French plate (either system), or any short plate-like text when labelled. */
    internal fun plateOf(value: String): String? {
        sivIn(value)?.let { return it }
        val v = value.trim()
        FNI.find(v)?.let { return "${it.groupValues[1]} ${it.groupValues[2]} ${it.groupValues[3]}" }
        val first = v.split(' ').take(3).joinToString(" ")
        return first.takeIf { it.length in 4..12 && it.any(Char::isDigit) && it.any(Char::isLetter) && it.all { c -> c.isLetterOrDigit() || c == ' ' || c == '-' } }
    }

    /** The first French SIV plate in [text], as "AB-123-CD". */
    private fun sivIn(text: String): String? {
        val m = SIV.find(text) ?: SIV_TIGHT.find(text) ?: return null
        val digits = m.groupValues[2].replace('O', '0').replace('I', '1')
        return "${m.groupValues[1]}-$digits-${m.groupValues[3]}"
    }

    /** A VIN, with the recognizer's usual slips put right: a VIN never holds I, O or Q. */
    internal fun vinOf(value: String): String? {
        val v = value.replace(" ", "").take(17).replace('O', '0').replace('Q', '0').replace('I', '1')
        if (v.length != 17 || !v.all { it in 'A'..'Z' || it in '0'..'9' }) return null
        if (v.count(Char::isDigit) < 4 || v.take(3).none(Char::isLetter)) return null
        return withMaker(v)
    }

    /**
     * The first three characters name the maker (VF7 is Citroën): a code no
     * maker uses that one look-alike swap makes a known one ("VE7" read off
     * "VF7") is put right.
     */
    private fun withMaker(vin: String): String {
        val wmi = vin.take(3)
        if (wmi in MAKERS) return vin
        for (i in 0 until 3) {
            for (other in LOOK_ALIKE[wmi[i]].orEmpty()) {
                val fixed = wmi.substring(0, i) + other + wmi.substring(i + 1)
                if (fixed in MAKERS) return fixed + vin.substring(3)
            }
        }
        return vin
    }

    private val LOOK_ALIKE = mapOf(
        'E' to "F", 'F' to "E", 'B' to "8", '8' to "B", 'S' to "5", '5' to "S",
        'Z' to "2", '2' to "Z", 'G' to "6", '6' to "G", 'U' to "V", 'V' to "U", 'W' to "V"
    )

    /** Makers' codes (WMI) of the cars most seen in Europe. */
    private val MAKERS = setOf(
        "VF1", "VF3", "VF7", "VR1", "VR3", "VR7", "VF6", "VNE", "UU1", "VSS", "VSK", "TMB", "WVW", "WV1", "WV2",
        "WAU", "WUA", "WBA", "WBS", "WBY", "WMW", "WDB", "WDD", "WDC", "W1K", "W1N", "WF0", "WME", "W0L", "W0V",
        "ZFA", "ZFF", "ZAR", "ZCF", "ZLA", "YV1", "YS3", "SAL", "SAJ", "SCC", "KNA", "KNE", "KMH", "TMA", "JTD",
        "JTE", "JTN", "SB1", "NMT", "JMZ", "JN1", "SJN", "VNK", "JHM", "SHH", "JF1", "JS2", "TSM", "LRW", "5YJ",
        "XTA", "LSJ", "LVS", "VX1"
    )

    private fun dateOf(m: MatchResult, today: LocalDate): LocalDate? {
        val (d, mo, y) = m.destructured
        val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
        return date.takeIf { it.year >= 1950 && !it.isAfter(today) }
    }

    /** A make or a model: the words, without trailing marks. */
    private fun nameOf(value: String): String? = value.trim(' ', ':', '.', '-').takeIf { it.length in 1..40 && it.any(Char::isLetterOrDigit) }

    private fun energyOf(value: String): String? = value.trim().take(30).takeIf { Energies.of(it) != null }

    private fun numberOf(value: String): Int? = NUMBER.find(value.trim())?.groupValues?.get(1)?.toIntOrNull()

    /** [iso] (yyyy-MM-dd) as a date, or null. */
    fun date(iso: String): LocalDate? = runCatching { LocalDate.parse(iso.trim()) }.getOrNull()
}
