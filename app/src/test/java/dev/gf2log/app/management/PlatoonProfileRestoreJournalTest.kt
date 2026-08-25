package dev.gf2log.app.management

import dev.gf2log.app.settings.GameServerRegion
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class PlatoonProfileRestoreJournalTest {
    @Test
    fun journalRoundTripsUnicodeProfileAndRoutingRollback() {
        val profile = PlatoonProfile(
            storageId = PlatoonProfileIdentity.storageId(
                PlatoonClient.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                101817L,
            ),
            client = PlatoonClient.HAOPLAY,
            serverRegion = GameServerRegion.HAOPLAY_KOREA,
            platoonId = 101817L,
            platoonName = "부엉이",
            emblemPrimary = listOf(1, 2),
            emblemSecondary = listOf(3),
            lastSeenAt = Instant.parse("2026-08-24T12:00:00Z"),
        )
        val value = PlatoonProfileRestoreJournal(
            targetStorageId = profile.storageId,
            previousProfile = profile,
            previousActiveStorageId = profile.storageId,
            ownerPackage = profile.client.packageName,
            previousCaptureRegion = GameServerRegion.HAOPLAY_JAPAN,
        )

        assertEquals(value, PlatoonProfileRestoreJournalCodec.decode(
            PlatoonProfileRestoreJournalCodec.encode(value),
        ))
    }
}
