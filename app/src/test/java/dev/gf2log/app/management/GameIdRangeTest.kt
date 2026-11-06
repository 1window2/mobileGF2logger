package dev.gf2log.app.management

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameIdRangeTest {
    @Test
    fun everyUnsigned32BitIdButZeroIsAGameId() {
        assertTrue(1L in GAME_ID_RANGE)
        assertTrue(4_294_967_295L in GAME_ID_RANGE)
        assertFalse(0L in GAME_ID_RANGE)
        assertFalse(-1L in GAME_ID_RANGE)
        assertFalse(4_294_967_296L in GAME_ID_RANGE)
    }
}
