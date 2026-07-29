package dev.gf2log.app.management

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonSchemaTest {
    @Test
    fun `base backup requires core membership tables`() {
        val tables = PlatoonSchema.requiredTables(1)

        assertTrue("members" in tables)
        assertTrue("tenures" in tables)
        assertTrue("member_events" in tables)
        assertFalse("weekly_overrides" in tables)
        assertFalse("platoon_activity" in tables)
    }

    @Test
    fun `current backup requires every introduced table`() {
        val tables = PlatoonSchema.requiredTables(PlatoonSchema.CURRENT_VERSION)
        val columns = PlatoonSchema.requiredColumns(PlatoonSchema.CURRENT_VERSION)

        assertTrue("weekly_overrides" in tables)
        assertTrue("platoon_activity" in tables)
        assertTrue("joined_date" in columns.getValue("tenures"))
        assertTrue("time_known" in columns.getValue("member_events"))
    }
}
