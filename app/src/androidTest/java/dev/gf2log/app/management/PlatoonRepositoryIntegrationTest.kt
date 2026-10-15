package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.protocol.model.GuildMember
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatoonRepositoryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = clearState()

    @After
    fun tearDown() = clearState()

    @Test
    fun weeklyScreenStillRendersARevisionThatHistoryCannotEncode() {
        val repository = PlatoonRepository(context, SCOPE)
        val capturedAt = Instant.parse("2026-08-03T12:00:00Z")
        repository.ingest(capturedAt, listOf(member(UID, "Short")), "roster.csv")
        // A pre-bound Updates row can carry a name longer than the history codec accepts.
        val oversizedName = "x".repeat(WeeklyReportHistoryCodec.MAX_STRING_BYTES + 1)
        PlatoonRepository.withExclusiveDatabase(SCOPE) {
            PlatoonDatabase(context, SCOPE.databaseName).use { database ->
                database.writableDatabase.insertOrThrow(
                    "member_events",
                    null,
                    ContentValues().apply {
                        put("uid", UID)
                        put("event_type", MemberEventType.REJOINED.name)
                        put("occurred_at", capturedAt.plusSeconds(60).toEpochMilli())
                        put("observed_at", capturedAt.plusSeconds(60).toEpochMilli())
                        put("precision", EvidencePrecision.EXACT.name)
                        put("source", EvidenceSource.GAME_UPDATES.name)
                        put("note", oversizedName)
                    },
                )
            }
        }

        val revision = repository.buildWeeklyTableRevision(
            referenceDay = LocalDate.of(2026, 8, 3),
            zoneId = ZoneOffset.UTC,
            asOf = capturedAt.plusSeconds(3_600),
        )

        assertEquals(oversizedName, revision.report.members.single().name)
    }

    private fun member(uid: Long, name: String) = GuildMember(
        uid = uid.toUInt(),
        name = name,
        level = 1u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private fun clearState() {
        PlatoonRepository.withExclusiveDatabase(SCOPE) {
            context.deleteDatabase(SCOPE.databaseName)
        }
        File(context.filesDir, "platoons/${SCOPE.storageId}").deleteRecursively()
    }

    private companion object {
        val SCOPE = PlatoonStorageScope("1".repeat(32))
        const val UID = 4242L
    }
}
