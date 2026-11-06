package dev.gf2log.app.management

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A roster captured with the phone clock set ahead is set aside, never deleted, never current. */
@RunWith(AndroidJUnit4::class)
class SnapshotClockIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var profile: PlatoonProfile
    private lateinit var scope: PlatoonStorageScope
    private lateinit var repository: PlatoonRepository

    @Before
    fun setUp() {
        profile = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(Random.nextInt(1_000_000, 9_000_000).toUInt(), "Clock Platoon", 0u, 0u),
        )
        check(PlatoonProfileRegistry(context).setActive(profile.storageId))
        scope = PlatoonStorageScope(profile.storageId)
        GameTimeZonePreferences.set(context, ZoneOffset.UTC, scope.storageId)
        repository = PlatoonRepository(context, scope)
    }

    @After
    fun tearDown() {
        runCatching { PlatoonProfileAdministration(context).deleteProfile(profile.storageId) }
        PlatoonRepository.withExclusiveDatabase(scope) { context.deleteDatabase(scope.databaseName) }
        scope.rootDirectory(context).deleteRecursively()
    }

    @Test
    fun aRosterDatedAYearAheadIsSetAsideByTheNextRealCapture() {
        roster(FUTURE, login = REAL.minusSeconds(2 * HOUR), member(A, "A"), member(B, "B"))

        val real = roster(REAL, login = REAL.minusSeconds(600), member(A, "A"), member(C, "C"))

        assertFalse(real.historical)
        assertTrue(real.clockReviewChanged)
        assertEquals(listOf(REAL), repository.listSnapshots().map { it.capturedAt })
        assertEquals(REAL, repository.latestSnapshot()?.capturedAt)
        assertEquals(1, repository.setAsideSnapshotCount())
        assertEquals(setOf(A, C), activeUids())
        assertTrue(repository.listAllWeeklyReports(ZoneOffset.UTC, REAL).none { it.periodStart.year == 2027 })
        // Nothing is lost: the set-aside roster keeps every row.
        assertEquals(2L, rawCount("snapshots"))
        assertEquals(1L, rawCount("snapshots", "clock_suspect != 0"))

        val next = roster(REAL.plusSeconds(DAY), login = REAL.plusSeconds(DAY - 600), member(A, "A"), member(C, "C"), member(D, "D"))
        assertFalse(next.historical)
        assertFalse(next.clockReviewChanged)
        assertEquals(1, next.joined)
        val pair = SnapshotComparison.latestComparablePair(repository.listSnapshots(SnapshotComparison.COMPARISON_LOOKBACK))
        assertEquals(REAL, pair?.second?.capturedAt)
        assertEquals(setOf(A, C, D), activeUids())
    }

    @Test
    fun aWithdrawalTheFutureRosterInferredReopensOnAPreservedPeriod() {
        roster(REAL, login = REAL.minusSeconds(600), member(A, "A"), member(X, "X"))
        PlatoonRepository.withExclusiveDatabase(scope) {
            writableLive { db -> db.execSQL("UPDATE membership_periods SET note = 'kept' WHERE uid = $X") }
        }
        // The future roster omits X and renames A, and so closes X's annotated period.
        roster(FUTURE, login = REAL.plusSeconds(HOUR), member(A, "A2"))
        check(X !in activeUids())

        roster(REAL.plusSeconds(2 * HOUR), login = REAL.plusSeconds(90 * 60), member(A, "A2"), member(X, "X"))

        val period = repository.listMemberStatuses().single { it.uid == X }.membershipPeriods.single()
        assertEquals("kept", period.note)
        assertNull(period.leftAt)
        assertEquals(setOf(A, X), activeUids())
        assertEquals(0L, rawCount("member_events", "observed_at = ${FUTURE.toEpochMilli()}"))
    }

    @Test
    fun aClockSetBehindKeepsRealRostersWhenServerLoginsVouchForThem() {
        roster(REAL, login = REAL.minusSeconds(600), member(A, "A"), member(B, "B"))

        val behind = roster(
            REAL.minusSeconds(30 * DAY),
            login = REAL.minusSeconds(300),
            member(A, "A"),
        )

        assertTrue(behind.historical)
        assertFalse(behind.clockReviewChanged)
        assertEquals(0, repository.setAsideSnapshotCount())
        assertEquals(REAL, repository.latestSnapshot()?.capturedAt)
    }

    @Test
    fun aRosterSetAsideByAClockSetBehindReturnsOnceTheClockIsRight() {
        // Nobody logged in for hours, so no server time vouches for the real roster.
        val staleLogin = REAL.minusSeconds(3 * HOUR)
        roster(REAL, login = staleLogin, member(A, "A"), member(B, "B"))
        val behind = roster(REAL.minusSeconds(30 * DAY), login = staleLogin, member(A, "A"), member(B, "B"))
        check(behind.clockReviewChanged && repository.setAsideSnapshotCount() == 1)

        val corrected = roster(REAL.plusSeconds(HOUR), login = staleLogin, member(A, "A"), member(B, "B"))

        assertTrue(corrected.clockReviewChanged)
        assertFalse(corrected.historical)
        assertEquals(0, repository.setAsideSnapshotCount())
        assertEquals(
            listOf(REAL.plusSeconds(HOUR), REAL, REAL.minusSeconds(30 * DAY)),
            repository.listSnapshots().map { it.capturedAt },
        )
    }

    @Test
    fun aRosterReallyCapturedAheadStaysAsideAfterItsDatePasses() {
        roster(REAL, login = REAL.minusSeconds(HOUR), member(A, "A"))
        // Captured a day later in real time, with the clock a year ahead.
        roster(FUTURE, login = REAL.plusSeconds(DAY - HOUR), member(A, "A"))
        roster(REAL.plusSeconds(2 * DAY), login = REAL.plusSeconds(2 * DAY - HOUR), member(A, "A"))
        check(repository.setAsideSnapshotCount() == 1)

        val afterItsDate = roster(FUTURE.plusSeconds(DAY), login = FUTURE.plusSeconds(DAY - HOUR), member(A, "A"))

        assertFalse(afterItsDate.clockReviewChanged)
        assertFalse(afterItsDate.historical)
        assertEquals(1, repository.setAsideSnapshotCount())
        assertFalse(FUTURE in repository.listSnapshots().map { it.capturedAt })
    }

    /** Ingests a roster whose phone clock reads its own capture time, as a live capture does. */
    private fun roster(capturedAt: Instant, login: Instant, vararg members: GuildMember): SnapshotIngestResult =
        repository.ingest(
            capturedAt = capturedAt,
            members = members.map { it.copy(lastLogin = login.epochSecond.toUInt()) },
            sourceFile = "roster-${capturedAt.toEpochMilli()}.csv",
            now = capturedAt,
        )

    private fun activeUids(): Set<Long> =
        repository.listMemberStatuses(activeOnly = true).mapTo(mutableSetOf()) { it.uid }

    private fun rawCount(table: String, where: String? = null): Long = PlatoonRepository.withExclusiveDatabase(scope) {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(scope.databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM $table" + (where?.let { " WHERE $it" } ?: ""), null)
                .use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        }
    }

    private fun writableLive(block: (SQLiteDatabase) -> Unit) = SQLiteDatabase.openDatabase(
        File(context.getDatabasePath(scope.databaseName).path).path,
        null,
        SQLiteDatabase.OPEN_READWRITE,
    ).use(block)

    private fun member(uid: Long, name: String) = GuildMember(
        uid = uid.toUInt(),
        name = name,
        level = 60u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private companion object {
        const val A = 1001L
        const val B = 1002L
        const val C = 1003L
        const val D = 1004L
        const val X = 1005L
        const val HOUR = 3_600L
        const val DAY = 24 * HOUR
        val REAL: Instant = Instant.parse("2026-10-05T12:00:00Z")
        val FUTURE: Instant = Instant.parse("2027-10-04T12:00:00Z")
    }
}
