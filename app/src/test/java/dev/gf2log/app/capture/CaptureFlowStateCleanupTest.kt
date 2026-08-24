package dev.gf2log.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureFlowStateCleanupTest {
    @Test
    fun metadataIsRemovedWhenFlowNeverCreatedAParser() {
        val parsers = mutableMapOf<Long, String>()
        val metadata = mutableMapOf(7L to metadata())

        assertNull(CaptureFlowStateCleanup.remove(7L, parsers, metadata))
        assertFalse(metadata.containsKey(7L))
    }

    @Test
    fun parserAndMetadataAreRemovedTogether() {
        val parsers = mutableMapOf(7L to "parser")
        val metadata = mutableMapOf(7L to metadata())

        assertEquals("parser", CaptureFlowStateCleanup.remove(7L, parsers, metadata))
        assertFalse(parsers.containsKey(7L))
        assertFalse(metadata.containsKey(7L))
    }

    private fun metadata() = CaptureFlowMetadata(6, "10.0.0.2", 1, "10.0.0.3", 2, null)
}
