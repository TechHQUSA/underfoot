package org.walkpadhealth

import org.walkpadhealth.ui.fmtDuration
import org.walkpadhealth.ui.fmtKm
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun duration() {
        assertEquals("0:00", fmtDuration(0)); assertEquals("1:05", fmtDuration(65))
        assertEquals("1:00:00", fmtDuration(3600)); assertEquals("0:00", fmtDuration(-5))
    }
    @Test fun km() { assertEquals("0.00", fmtKm(0.0)); assertEquals("1.23", fmtKm(1234.0)); assertEquals("0.00", fmtKm(Double.NaN)) }
}
