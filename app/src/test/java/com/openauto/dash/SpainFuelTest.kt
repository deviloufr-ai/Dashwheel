package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the Spanish ministry's answer: decimal commas, empty grades, stations too far left out. */
class SpainFuelTest {

    private val json = """{"Fecha":"17/11/2026 18:26:00","ListaEESSPrecio":[
        {"Dirección":"AVENIDA CASTILLA LA MANCHA, 26","Latitud":"40,416800","Localidad":"MADRID","Longitud (WGS84)":"-3,703800",
         "Precio Gasoleo A":"1,459","Precio Gasolina 95 E5":"1,599","Precio Gasolina 95 E10":"","Precio Gasolina 98 E5":"1,729",
         "Precio Gases licuados del petróleo":"","Precio Gasolina 95 E85":"","Rótulo":"REPSOL","IDEESS":"4375","IDProvincia":"28"},
        {"Dirección":"CR CM-332, 46,4","Latitud":"40,900000","Localidad":"LEJOS","Longitud (WGS84)":"-3,703800",
         "Precio Gasoleo A":"1,399","Rótulo":"CEPSA","IDEESS":"4376"},
        {"Dirección":"SIN PRECIOS","Latitud":"40,4168","Localidad":"MADRID","Longitud (WGS84)":"-3,7038",
         "Precio Gasoleo A":"","Rótulo":"","IDEESS":"4377"}
    ]}"""

    @Test fun readsNearbyStationsWithTheirGrades() {
        val stations = SpainFuel.parse(json, 40.4168, -3.7038)
        assertEquals(1, stations.size)
        val s = stations.single()
        assertEquals(4375L, s.id)
        assertEquals("Repsol", s.name)
        assertEquals(mapOf(FuelGrade.GAZOLE to 1.459, FuelGrade.SP95 to 1.599, FuelGrade.SP98 to 1.729), s.prices)
    }

    @Test fun provinceFromNominatimCode() {
        assertEquals("08", SpainFuel.provinceId("ES-B"))
        assertEquals("28", SpainFuel.provinceId("ES-MD"))
        assertNull(SpainFuel.provinceId("FR-75"))
        assertNull(SpainFuel.provinceId(null))
        assertEquals(FuelPlan.SPAIN, FuelPrices.plan("es", hasTankerkoenigKey = true))
    }
}
