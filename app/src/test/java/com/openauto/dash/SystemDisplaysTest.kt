package com.openauto.dash

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemDisplaysTest {

    @Test
    fun theHelperStartsDetachedFromTheRootShellByItsClassName() {
        val line = SystemDisplays.helperCommand("/data/app/com.openauto.dash-1/base.apk", "com.openauto.dash", "abc123")
        assertEquals(
            "nohup sh -c 'CLASSPATH=/data/app/com.openauto.dash-1/base.apk exec app_process /system/bin " +
                "com.openauto.dash.SystemDisplayHelper abc123 com.openauto.dash' </dev/null >/dev/null 2>&1 &",
            line
        )
    }
}
