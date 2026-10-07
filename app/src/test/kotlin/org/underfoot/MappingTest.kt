package org.underfoot

import org.underfoot.data.toEntity
import org.underfoot.protocol.FinalSession
import org.underfoot.protocol.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MappingTest {
    @Test fun mapsFinalSessionToUnsyncedEntityWithSourceNames() {
        val e = FinalSession(1, 2, 3, 4.0, Source.DEVICE, 5, Source.ESTIMATED, 6.0, Source.ESTIMATED).toEntity()
        assertEquals("DEVICE", e.distanceSource)
        assertEquals("ESTIMATED", e.stepsSource)
        assertEquals(0L, e.id)
        assertFalse(e.synced)
        assertEquals(5, e.steps)
    }
}
