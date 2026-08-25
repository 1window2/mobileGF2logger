package dev.gf2log.app.management

import dev.gf2log.app.settings.GameServerRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatoonProfileIdentityTest {
    @Test
    fun identityIsStableAndSeparatesPublisherRegionAndPlatoon() {
        val first = PlatoonProfileIdentity.storageId(
            PlatoonClient.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            101817L,
        )

        assertEquals(
            first,
            PlatoonProfileIdentity.storageId(
                PlatoonClient.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                101817L,
            ),
        )
        assertNotEquals(
            first,
            PlatoonProfileIdentity.storageId(
                PlatoonClient.HAOPLAY,
                GameServerRegion.HAOPLAY_JAPAN,
                101817L,
            ),
        )
        assertNotEquals(
            first,
            PlatoonProfileIdentity.storageId(
                PlatoonClient.DARKWINTER,
                GameServerRegion.DARKWINTER_GLOBAL,
                101817L,
            ),
        )
        assertTrue(PlatoonProfileIdentity.isValidStorageId(first))
    }

    @Test
    fun legacyScopeCannotImpersonateAClientProfile() {
        assertThrows(IllegalArgumentException::class.java) {
            PlatoonProfile(
                storageId = PlatoonProfileIdentity.LEGACY_STORAGE_ID,
                client = PlatoonClient.HAOPLAY,
                serverRegion = GameServerRegion.HAOPLAY_KOREA,
                platoonId = 101817L,
                platoonName = "Invalid legacy identity",
                emblemPrimary = emptyList(),
                emblemSecondary = emptyList(),
                lastSeenAt = java.time.Instant.EPOCH,
                legacy = true,
            )
        }
    }
}
