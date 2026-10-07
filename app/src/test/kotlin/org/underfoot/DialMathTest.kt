package org.underfoot

import org.underfoot.ui.DialMath
import kotlin.test.Test
import kotlin.test.assertEquals

class DialMathTest {
    // Screen coordinates: x right, y down. The arc starts bottom-left (135 deg), runs clockwise over the top, ends bottom-right.
    @Test fun bottomLeftIsZeroAndBottomRightIsFull() {
        assertEquals(0f, DialMath.fractionAt(-1.0, 1.0), 1e-4f)
        assertEquals(1f, DialMath.fractionAt(1.0, 1.0), 1e-4f)
    }

    @Test fun topIsHalf() = assertEquals(0.5f, DialMath.fractionAt(0.0, -1.0), 1e-4f)

    @Test fun leftIsOneSixth() = assertEquals(0.1667f, DialMath.fractionAt(-1.0, 0.0), 1e-3f)

    @Test fun theGapAtTheBottomSnapsToTheNearerEnd() {
        assertEquals(0f, DialMath.fractionAt(-0.2, 1.0), 1e-4f)
        assertEquals(1f, DialMath.fractionAt(0.2, 1.0), 1e-4f)
    }

    @Test fun touchExactlyAtCentreIsIgnored() = assertEquals(null, DialMath.fractionAtOrNull(0.0, 0.0))
}
