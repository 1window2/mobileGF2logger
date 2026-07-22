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
        assertEquals("FORMATIONS", PayloadCatalog.tag(23201))
    }

    @Test
    fun `only Platoon members is required`() {
        val platoon = PayloadCatalog.find(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS)!!
        assertTrue(platoon.isRequired)
        assertTrue(PayloadCatalog.categories.filterNot { it == platoon }.all { !it.isRequired })
        assertFalse(PayloadCatalog.find(Gfl2PayloadDecoder.TYPE_FORMATIONS)!!.isRequired)
    }

    @Test
    fun `unknown payload type has a useful fallback tag`() {
        assertEquals("TYPE 99999", PayloadCatalog.tag(99999))
        assertEquals("UNKNOWN", PayloadCatalog.tag(null))
    }
}
