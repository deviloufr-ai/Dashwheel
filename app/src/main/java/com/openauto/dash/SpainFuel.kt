package com.openauto.dash

import org.json.JSONObject

/**
 * Fuel prices in Spain from the Ministry for the Ecological Transition's
 * open feed (geoportalgasolineras.es), which every station must report to.
 * Free, no key. The feed is asked one province at a time (about 1 MB; the
 * whole country is 12 MB), so [FuelPriceRepo] needs the province the car is
 * in, from [CountryLookup.region]. Pure: the address and the reading,
 * unit-tested.
 */
object SpainFuel {
    private const val BASE = "https://sedeaplicaciones.minetur.gob.es/ServiciosRESTCarburantes/PreciosCarburantes/EstacionesTerrestres/FiltroProvincia/"

    /** The stations of the province with the ministry's [id] ("28"). */
    fun url(id: String): String = BASE + id

    /**
     * The ministry's two-digit province id for an ISO 3166-2 code from
     * Nominatim: the province's ("ES-B"), or the region's where the region
     * is one province and Nominatim gives no province ("ES-MD").
     */
    fun provinceId(iso: String?): String? = PROVINCES[iso?.uppercase()]

    /**
     * The stations of the answer within [FuelPrices.RADIUS_KM] of the car,
     * named by their sign ("Rótulo"). Coordinates and prices come with a
     * decimal comma; an empty price means the grade is not sold there.
     */
    fun parse(json: String, lat: Double, lng: Double): List<FuelStation> {
        val list = JSONObject(json).optJSONArray("ListaEESSPrecio") ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val s = list.optJSONObject(i) ?: return@mapNotNull null
            val sLat = number(s.optString("Latitud")) ?: return@mapNotNull null
            val sLng = number(s.optString("Longitud (WGS84)")) ?: return@mapNotNull null
            val prices = GRADES.mapNotNull { (field, grade) -> number(s.optString(field))?.takeIf { it > 0 }?.let { grade to it } }.toMap()
            if (prices.isEmpty()) return@mapNotNull null
            FuelStation(
                id = s.optString("IDEESS").toLongOrNull() ?: return@mapNotNull null,
                address = FuelPrices.tidy(s.optString("Dirección")),
                town = FuelPrices.tidy(s.optString("Localidad")),
                lat = sLat, lng = sLng,
                prices = prices,
                name = FuelPrices.tidy(s.optString("Rótulo"))
            ).takeIf { it.distanceKm(lat, lng) <= FuelPrices.RADIUS_KM }
        }
    }

    private fun number(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

    /** The ministry's grades by their field. */
    private val GRADES = listOf(
        "Precio Gasoleo A" to FuelGrade.GAZOLE,
        "Precio Gasolina 95 E10" to FuelGrade.E10,
        "Precio Gasolina 95 E5" to FuelGrade.SP95,
        "Precio Gasolina 98 E5" to FuelGrade.SP98,
        "Precio Gasolina 95 E85" to FuelGrade.E85,
        "Precio Gases licuados del petróleo" to FuelGrade.GPLC
    )

    /** ISO 3166-2 code → the ministry's province id (the INE number). */
    private val PROVINCES = mapOf(
        "ES-VI" to "01", "ES-AB" to "02", "ES-A" to "03", "ES-AL" to "04", "ES-AV" to "05", "ES-BA" to "06",
        "ES-PM" to "07", "ES-B" to "08", "ES-BU" to "09", "ES-CC" to "10", "ES-CA" to "11", "ES-CS" to "12",
        "ES-CR" to "13", "ES-CO" to "14", "ES-C" to "15", "ES-CU" to "16", "ES-GI" to "17", "ES-GR" to "18",
        "ES-GU" to "19", "ES-SS" to "20", "ES-H" to "21", "ES-HU" to "22", "ES-J" to "23", "ES-LE" to "24",
        "ES-L" to "25", "ES-LO" to "26", "ES-LU" to "27", "ES-M" to "28", "ES-MA" to "29", "ES-MU" to "30",
        "ES-NA" to "31", "ES-OR" to "32", "ES-O" to "33", "ES-P" to "34", "ES-GC" to "35", "ES-PO" to "36",
        "ES-SA" to "37", "ES-TF" to "38", "ES-S" to "39", "ES-SG" to "40", "ES-SE" to "41", "ES-SO" to "42",
        "ES-T" to "43", "ES-TE" to "44", "ES-TO" to "45", "ES-V" to "46", "ES-VA" to "47", "ES-BI" to "48",
        "ES-ZA" to "49", "ES-Z" to "50", "ES-CE" to "51", "ES-ML" to "52",
        // One-province regions, where Nominatim names only the region.
        "ES-MD" to "28", "ES-AS" to "33", "ES-CB" to "39", "ES-RI" to "26", "ES-NC" to "31", "ES-MC" to "30", "ES-IB" to "07"
    )
}
