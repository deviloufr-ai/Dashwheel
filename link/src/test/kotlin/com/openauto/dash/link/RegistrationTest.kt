package com.openauto.dash.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RegistrationTest {
    private val today = LocalDate.of(2026, 10, 10)

    @Test
    fun readsAFrenchCertificateLineByLine() {
        val r = RegistrationReader.read(
            """
            A AB-123-CD B 15/03/2011
            C.1 DUPONT JEAN
            D.1 CITROEN D.2 UDRHJD/ZP
            D.2.1 M10CTRVP000H180
            D.3 C4 PICASSO
            E VF7UDRHJ8BJ512345
            F.1 2050 F.2 2050
            I 02/06/2019
            J VP J.1 VP
            P.1 1560 P.2 82 P.3 GO P.6 6
            V.7 140 V.9 715/2007*692/2008EURO5
            """.trimIndent(),
            today
        )
        assertEquals("AB-123-CD", r.plate)
        assertEquals("2011-03-15", r.firstRegistration)
        assertEquals("VF7UDRHJ8BJ512345", r.vin)
        assertEquals("CITROEN", r.make)
        assertEquals("C4 PICASSO", r.model)
        assertEquals("GO", r.energy)
        assertEquals(82, r.powerKw)
        assertEquals(1560, r.displacementCc)
        assertEquals(5, r.euro)
    }

    @Test
    fun readsTheRealLayoutWithDottedLabelsAndTheNextInspection() {
        // As a French certificate prints it (made-up values): "A." and "E." carry a dot,
        // E sits beside D.3, and X.1 holds the next roadworthiness test's deadline.
        val r = RegistrationReader.read(
            """
            N° IMMATRICULATION DATE DE 1ÈRE IMMATRICULATION
            A. AB-123-CD B. 15/03/2011
            C.1 DUPONT
            C.4A EST LE PROPRIÉTAIRE DU VÉHICULE
            D.1 CITROEN
            D.2 UDRHJD/ZP
            D.2.1 M10CTRVP000H180
            D.3 C4 PICASSO E. VF7UDRHJ8BJ512345
            F.1 2060 F.2 2060 F.3 3130
            J M1 J.1 VP J.2 AC J.3 BREAK
            K E2*2001/116*0345*15
            P.1 1560 P.2 82 P.3 GO P.6 6
            U.2 2700 V.7 130 V.9 715/2007*692/2008EUR05
            X.1 VISITE AVANT LE 18/05/2027
            I 07/10/2025
            """.trimIndent(),
            today
        )
        assertEquals("AB-123-CD", r.plate)
        assertEquals("2011-03-15", r.firstRegistration)
        assertEquals("VF7UDRHJ8BJ512345", r.vin)
        assertEquals("C4 PICASSO", r.model)
        assertEquals("GO", r.energy)
        assertEquals(82, r.powerKw)
        assertEquals(5, r.euro)
        assertEquals("2027-05-18", r.nextInspection)
    }

    @Test
    fun aLabelStandingAloneTakesTheLineBesideIt() {
        val r = RegistrationReader.read(
            listOf(
                RegistrationReader.Line("A", 10, 100, 30, 130),
                RegistrationReader.Line("AB-123-CD", 60, 98, 260, 132),
                RegistrationReader.Line("D.3", 10, 300, 50, 330),
                RegistrationReader.Line("C4 PICASSO", 10, 340, 200, 370)
            ),
            today
        )
        assertEquals("AB-123-CD", r.plate)
        assertEquals("C4 PICASSO", r.model)
    }

    @Test
    fun knowsThePlateTheVinAndTheDateWithoutTheirLabels() {
        val r = RegistrationReader.read("CERTIFICAT D'IMMATRICULATION\nAB 123 CD\n02/06/2019\n15/03/2011\nVF7UDRHJ8BJ5I2345", today)
        assertEquals("AB-123-CD", r.plate)
        // The earliest date is the first registration; I (this certificate's) comes later.
        assertEquals("2011-03-15", r.firstRegistration)
        // A VIN never holds I, O or Q: the recognizer's slip is put right.
        assertEquals("VF7UDRHJ8BJ512345", r.vin)
    }

    @Test
    fun aMakersCodeMisreadByOneLookAlikeIsPutRight() {
        // Seen on a real certificate: the recognizer read Citroën's VF7 as VE7.
        assertEquals("VF7UDRHJ8BJ512345", RegistrationReader.vinOf("VE7UDRHJ8BJ512345"))
        // The coupon's copy, read right, wins over the misread one.
        assertEquals("VF7UDRHJ8BJ512345", RegistrationReader.read("E. VE7UDRHJ8BJ512345\nVF7UDRHJ8BJ512345", today).vin)
        // An unknown code with no known look-alike stays as read.
        assertEquals("XYZUDRHJ8BJ512345", RegistrationReader.vinOf("XYZUDRHJ8BJ512345"))
    }

    @Test
    fun aMercedesClassIsAModelNotAPlate() {
        val r = RegistrationReader.read("D.1 MERCEDES-BENZ\nD.3 CLASSE A\nA GH-456-JK", today)
        assertEquals("CLASSE A", r.model)
        assertEquals("GH-456-JK", r.plate)
    }

    @Test
    fun readsAnOldFrenchPlateWhenLabelled() {
        assertEquals("1234 AB 75", RegistrationReader.read("A 1234 AB 75", today).plate)
    }

    @Test
    fun aDateInTheFutureIsNotTheFirstRegistration() {
        assertEquals("", RegistrationReader.read("B 15/03/2031", today).firstRegistration)
    }

    @Test
    fun energiesFromCodesAndWords() {
        assertEquals(Energy.DIESEL, Energies.of("GO"))
        assertEquals(Energy.PETROL, Energies.of("ES"))
        assertEquals(Energy.PLUG_IN_HYBRID, Energies.of("EE"))
        assertEquals(Energy.HYBRID_PETROL, Energies.of("EH"))
        assertEquals(Energy.LPG, Energies.of("EG"))
        assertEquals(Energy.DIESEL, Energies.of("Diesel"))
        assertEquals(Energy.PETROL, Energies.of("Benzin"))
        assertEquals(Energy.HYBRID_PETROL, Energies.of("Benzin/Elektro"))
        assertEquals(Energy.DIESEL, Energies.of("ON"))
        assertEquals(Energy.ELECTRIC, Energies.of("EL"))
        assertNull(Energies.of("VP"))
    }

    @Test
    fun critAirFollowsTheFuelAndTheEuroStandardOrTheDate() {
        val d2011 = LocalDate.of(2011, 3, 15)
        assertEquals(2, CritAir.of(Energy.DIESEL, d2011, null))
        assertEquals(2, CritAir.of(Energy.DIESEL, LocalDate.of(2010, 6, 1), 5))
        assertEquals(3, CritAir.of(Energy.DIESEL, LocalDate.of(2010, 6, 1), null))
        assertEquals(4, CritAir.of(Energy.DIESEL, LocalDate.of(2003, 6, 1), null))
        assertEquals(5, CritAir.of(Energy.DIESEL, LocalDate.of(1998, 6, 1), null))
        assertEquals(CritAir.UNCLASSED, CritAir.of(Energy.DIESEL, LocalDate.of(1995, 6, 1), null))
        assertEquals(1, CritAir.of(Energy.PETROL, d2011, null))
        assertEquals(2, CritAir.of(Energy.PETROL, LocalDate.of(2008, 1, 1), null))
        assertEquals(3, CritAir.of(Energy.PETROL, LocalDate.of(2000, 1, 1), null))
        assertEquals(1, CritAir.of(Energy.LPG, LocalDate.of(2000, 1, 1), null))
        assertEquals(0, CritAir.of(Energy.ELECTRIC, null, null))
        assertNull(CritAir.of(Energy.DIESEL, null, null))
        assertNull(CritAir.of(null, d2011, 5))
    }

    @Test
    fun roundTripsThroughTheCodec() {
        val sent = CarRegistration("AB-123-CD", "2011-03-15", "VF7UDRHJ8BJ512345", "CITROEN", "C4 PICASSO", "GO", 82, 1560, 5, "2027-05-18")
        assertEquals(sent, LinkCodec.decode(LinkCodec.encode(sent)))
        assertTrue(CarRegistration().empty)
    }
}
