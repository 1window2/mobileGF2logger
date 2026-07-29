package dev.gf2log.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCatalogTest {
    @Test
    fun `catalog names every supported payload type`() {
        assertEquals(Gfl2PayloadDecoder.supportedTypes, PayloadCatalog.categories.map { it.payloadType }.toSet())
        assertEquals("WEAPONS", PayloadCatalog.tag(11021))
        assertEquals("ATTACHMENTS", PayloadCatalog.tag(11061))
        assertEquals("KEYS", PayloadCatalog.tag(11138))
        assertEquals("PLATOON", PayloadCatalog.tag(21917))
        assertEquals("ACTIVITY", PayloadCatalog.tag(21935))
        assertEquals("UPDATES", PayloadCatalog.tag(21960))
        assertEquals("FORMATIONS", PayloadCatalog.tag(23201))
    }

    @Test
    fun `Platoon members activity and updates are required`() {
        val required = PayloadCatalog.categories.filter(PayloadCategory::isRequired)
        assertEquals(
            setOf(
                Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY,
                Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES,
            ),
            required.map(PayloadCategory::payloadType).toSet(),
        )
        assertTrue(required.all(PayloadCategory::isRequired))
        assertFalse(PayloadCatalog.find(Gfl2PayloadDecoder.TYPE_FORMATIONS)!!.isRequired)
    }

    @Test
    fun `unknown payload type has a useful fallback tag`() {
        assertEquals("TYPE 99999", PayloadCatalog.tag(99999))
        assertEquals("UNKNOWN", PayloadCatalog.tag(null))
    }
}
