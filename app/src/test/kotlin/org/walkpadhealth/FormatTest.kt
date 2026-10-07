package org.walkpadhealth

import org.walkpadhealth.ui.cmToIn
import org.walkpadhealth.ui.fmtDistance
import org.walkpadhealth.ui.fmtDuration
import org.walkpadhealth.ui.fmtKm
import org.walkpadhealth.ui.fmtSpeed
import org.walkpadhealth.ui.inToCm
import org.walkpadhealth.ui.kgToLb
import org.walkpadhealth.ui.lbToKg
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun duration() {
        assertEquals("0:00", fmtDuration(0)); assertEquals("1:05", fmtDuration(65))
        assertEquals("1:00:00", fmtDuration(3600)); assertEquals("0:00", fmtDuration(-5))
    }
    @Test fun imperialDisplayValues() {
        assertEquals("0.62", fmtDistance(1000.0, imperial = true)); assertEquals("1.00", fmtDistance(1000.0, imperial = false))
        assertEquals("3.1", fmtSpeed(5.0, imperial = true)); assertEquals("5.0", fmtSpeed(5.0, imperial = false))
        assertEquals("0.00", fmtDistance(Double.NaN, imperial = true)); assertEquals("0.0", fmtSpeed(-1.0, imperial = true))
    }

    @Test fun bodyUnitConversionsRoundTrip() {
        assertEquals(154.3, kgToLb(70.0), 0.05); assertEquals(70.0, lbToKg(kgToLb(70.0)), 1e-9)
        assertEquals(68.9, cmToIn(175.0), 0.05); assertEquals(175.0, inToCm(cmToIn(175.0)), 1e-9)
    }

    @Test fun km() { assertEquals("0.00", fmtKm(0.0)); assertEquals("1.23", fmtKm(1234.0)); assertEquals("0.00", fmtKm(Double.NaN)) }
}
