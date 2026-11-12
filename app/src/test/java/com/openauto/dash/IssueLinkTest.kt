package com.openauto.dash

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** The GitHub issues a crash or a compatibility report opens, filled in. */
class IssueLinkTest {

    private val facts = listOf("Device" to "ROCO K706", "Firmware" to "QF")

    @Test
    fun compatibilityReportSaysWhatWorks() {
        val body = IssueLink.compatibilityBody(facts, mapOf(ReportItem.WHEEL to ReportAnswer.WORKS, ReportItem.MAPS to ReportAnswer.FAILS), null)
        assertTrue(body.contains("- Device: ROCO K706"))
        assertTrue(body.contains("- wheel: works"))
        assertTrue(body.contains("- maps: does NOT work"))
        assertTrue(body.contains("- obd: not tried"))
    }

    @Test
    fun addressStaysScannable() {
        val trace = (1..400).joinToString("\n") { "    at com.openauto.dash.Something.method$it(Something.kt:$it)" }
        val url = IssueLink.url("o", "r", "Crash: java.lang.IllegalStateException", IssueLink.crashBody(facts, "java.lang.IllegalStateException: boom\n$trace"), "crash")
        assertTrue(url.length <= IssueLink.MAX_URL)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body.contains("IllegalStateException: boom"))
        assertTrue(url.startsWith("https://github.com/o/r/issues/new?labels=crash&title="))
    }

    @Test
    fun qrAddressIsShortAndKeepsTheCrash() {
        val crash = "time: 2026-11-12 08:00:00\nthread: main\n\njava.lang.IllegalStateException: boom\n" +
            "    at android.view.View.draw(View.java:1)\n" +
            (1..200).joinToString("\n") { "    at com.openauto.dash.Something.method$it(Something.kt:$it)" } +
            "\nCaused by: java.lang.NullPointerException: deep\n    at com.openauto.dash.Deep.run(Deep.kt:9)"
        val facts = listOf("Dashwheel" to "1.0 (GitHub)", "Device" to "ROCO K706", "Android" to "10 (API 29)", "Build" to "a very long build id")
        val url = IssueLink.url("o", "r", "Crash: java.lang.IllegalStateException: boom", IssueLink.shortCrashBody(facts, crash), "crash", IssueLink.MAX_QR)
        assertTrue(url.length <= IssueLink.MAX_QR)
        val body = URLDecoder.decode(url.substringAfter("&body="), "UTF-8")
        assertTrue(body, body.contains("IllegalStateException: boom"))
        assertTrue(body, body.contains("Caused by: java.lang.NullPointerException: deep"))
        assertTrue(body, body.contains("Something.method1("))
    }

    @Test
    fun shortCompatibilityReportFitsAQrCode() {
        val facts = listOf("Dashwheel" to "1.0 (GitHub)", "Device" to "ROCO K706", "Android" to "10 (API 29)")
        val answers = ReportItem.entries.associateWith { ReportAnswer.FAILS }
        val url = IssueLink.url("o", "r", "Compatibility: ROCO K706", IssueLink.shortCompatibilityBody(facts, answers), "compatibility", IssueLink.MAX_QR)
        assertTrue(url.length <= IssueLink.MAX_QR)
        assertTrue(URLDecoder.decode(url, "UTF-8").contains("does NOT work: home, wheel"))
    }
}
