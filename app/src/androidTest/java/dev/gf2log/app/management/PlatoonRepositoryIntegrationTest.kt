package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.protocol.model.GuildMember
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun rosterCapturedExactlyAtTheSundayResetAlsoRefreshesTheWeekItCloses() {
        val repository = utcRepository()
        repository.ingest(Instant.parse("2026-07-29T12:00:00Z"), listOf(member(UID, "A", 100u)), "a.csv")
        repository.ingest(Instant.parse("2026-08-01T04:56:00Z"), listOf(member(UID, "A", 200u)), "b.csv")
        val before = latestStoredRevision(repository, CLOSING_WEEK)

        // 05:00 on Sunday opens the next game week but is the closing capture of Saturday.
        repository.ingest(Instant.parse("2026-08-02T05:00:00Z"), listOf(member(UID, "A", 300u)), "c.csv")

        val after = latestStoredRevision(repository, CLOSING_WEEK)
        assertNotEquals(before, after)
        assertEquals(liveReport(repository, CLOSING_WEEK), after)
    }

    @Test
    fun rosterCapturedJustBeforeTheResetRefreshesTheFollowingWeekThatCarriesItIn() {
        val repository = utcRepository()
        repository.ingest(
            Instant.parse("2026-07-29T12:00:00Z"),
            listOf(member(UID, "A", 100u), member(OTHER_UID, "B", 100u)),
            "a.csv",
        )
        // The following week already has evidence of its own, so its revision is listed.
        repository.addWeeklyNote(OPENING_WEEK.toEpochDay(), OPENING_WEEK.plusDays(1).toEpochDay(), "Note")
        val before = latestStoredRevision(repository, OPENING_WEEK)
        assertEquals(setOf(UID, OTHER_UID), before?.members?.map { it.uid }?.toSet())

        // 04:55 belongs to Saturday's game day, yet this roster is the opening baseline and the
        // period-end roster of the following week, which no longer contains the withdrawn B.
        repository.ingest(Instant.parse("2026-08-02T04:55:00Z"), listOf(member(UID, "A", 300u)), "b.csv")

        val after = latestStoredRevision(repository, OPENING_WEEK)
        assertEquals(listOf(UID), after?.members?.map { it.uid })
        assertEquals(liveReport(repository, OPENING_WEEK), after)
    }

    private fun utcRepository(): PlatoonRepository {
        GameTimeZonePreferences.set(context, ZoneOffset.UTC, SCOPE.storageId)
        return PlatoonRepository(context, SCOPE)
    }

    private fun latestStoredRevision(
        repository: PlatoonRepository,
        periodStart: LocalDate,
    ): WeeklyReportBuilder.Report? = repository.listWeeklyReportHistory(periodStart)
        .firstOrNull()
        ?.let { repository.weeklyReportHistory(it.id, periodStart) }

    /** The live revision as history stores it, so codec normalization does not differ. */
    private fun liveReport(repository: PlatoonRepository, periodStart: LocalDate) =
        WeeklyReportHistoryCodec.decode(
            WeeklyReportHistoryCodec.encode(
                repository.readWeeklyTableRevision(periodStart, ZoneOffset.UTC),
            ).payload,
        )

    private fun member(uid: Long, name: String, totalMerit: UInt = 0u) = GuildMember(
        uid = uid.toUInt(),
        name = name,
        level = 1u,
        weeklyMerit = 0u,
        totalMerit = totalMerit,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private fun clearState() {
        PlatoonRepository.withExclusiveDatabase(SCOPE) {
            context.deleteDatabase(SCOPE.databaseName)
        }
        File(context.filesDir, "platoons/${SCOPE.storageId}").deleteRecursively()
        GameTimeZonePreferences.clearScope(context, SCOPE.storageId)
    }

    private companion object {
        val SCOPE = PlatoonStorageScope("1".repeat(32))
        const val UID = 4242L
        const val OTHER_UID = 4343L
        val CLOSING_WEEK: LocalDate = LocalDate.of(2026, 7, 26)
        val OPENING_WEEK: LocalDate = LocalDate.of(2026, 8, 2)
    }
}
