package org.walkpadhealth

import org.walkpadhealth.crash.CrashReporter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashReporterTest {
    @Test fun reportHasVersionAndFramesButNeverTheMessage() {
        val text = CrashReporter.render(IllegalStateException("secret /Users/someone/file 02510314"), "0.1.0", 34)
        assertTrue("0.1.0" in text && "SDK 34" in text && "java.lang.IllegalStateException" in text && "CrashReporterTest" in text)
        assertFalse("secret" in text || "/Users/" in text || "02510314" in text)
    }

    @Test fun causesAreIncludedWithoutTheirMessages() {
        val text = CrashReporter.render(RuntimeException("outer", IllegalArgumentException("inner-secret")), "0.1.0", 34)
        assertTrue("java.lang.IllegalArgumentException" in text); assertFalse("inner-secret" in text)
    }
}
