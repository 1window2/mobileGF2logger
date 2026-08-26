package dev.gf2log.app.management

import dev.gf2log.app.settings.GameServerRegion
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
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
            bannerFrameId = 2,
            bannerMarkId = 3,
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

    @Test
    fun legacyJournalRemainsReadableWithoutMisclassifyingOldListsAsBannerIds() {
        val storageId = PlatoonProfileIdentity.storageId(
            PlatoonClient.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            101817L,
        )
        val bytes = ByteArrayOutputStream().use { target ->
            DataOutputStream(target).use { output ->
                output.writeInt(1)
                output.writeUTF(storageId)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeBoolean(false)
                output.writeBoolean(true)
                output.writeUTF(storageId)
                output.writeUTF(PlatoonClient.HAOPLAY.name)
                output.writeUTF(GameServerRegion.HAOPLAY_KOREA.storedValue)
                output.writeLong(101817L)
                output.writeUTF("부엉이")
                output.writeInt(3)
                listOf(3L, 2L, 10L).forEach(output::writeLong)
                output.writeInt(4)
                listOf(20L, 11L, 14L, 17L).forEach(output::writeLong)
                output.writeLong(Instant.parse("2026-08-24T12:00:00Z").toEpochMilli())
                output.writeBoolean(false)
            }
            target.toByteArray()
        }

        val decoded = PlatoonProfileRestoreJournalCodec.decode(bytes)

        assertEquals(0L, decoded.previousProfile?.bannerFrameId)
        assertEquals(0L, decoded.previousProfile?.bannerMarkId)
        assertEquals("부엉이", decoded.previousProfile?.platoonName)
    }
}
