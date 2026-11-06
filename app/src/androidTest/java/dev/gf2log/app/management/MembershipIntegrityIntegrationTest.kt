package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Membership-identity and boundary invariants that need a real SQLite database. */
@RunWith(AndroidJUnit4::class)
class MembershipIntegrityIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: PlatoonDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        database = PlatoonDatabase(context, TEST_DATABASE, TEST_SCOPE)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun aNoteOnlySaveKeepsFollowingInGameRenames() {
        roster(T0, member(TARGET_UID, "Alice"))
        assertTrue(database.updateMember(TARGET_UID, "Alice", "officer"))
        assertNull(customName(TARGET_UID))

        roster(T0.plusSeconds(HOUR), member(TARGET_UID, "Bob"))
        val renamed = status(TARGET_UID)
        assertEquals("Bob", renamed.name)
        assertEquals("Bob", renamed.currentName)
        assertEquals("officer", renamed.note)

        assertTrue(database.updateMember(TARGET_UID, " Carol ", ""))
        assertEquals("Carol", customName(TARGET_UID))
        assertEquals("Carol", status(TARGET_UID).name)
        assertTrue(database.updateMember(TARGET_UID, "Bob", ""))
        assertNull(customName(TARGET_UID))
        assertTrue(database.updateMember(TARGET_UID, "Carol", ""))
        assertTrue(database.updateMember(TARGET_UID, "  ", ""))
        assertNull(customName(TARGET_UID))
        assertEquals("Bob", status(TARGET_UID).name)
    }

    @Test
    fun rosterTimeCorrelationIgnoresActivityBeyondTheLeadBound() {
        roster(T0, member(OTHER_UID, "Other"), member(TARGET_UID, "Leaver"))
        database.ingestPlatoonActivity(listOf(activity("Leaver", T0.plusSeconds(30 * 60))), T0.plusSeconds(HOUR))
        val revealingAt = T0.plusSeconds(72 * HOUR)
        roster(revealingAt, member(OTHER_UID, "Other"))

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(revealingAt, period.leftAt)
        assertEquals(EvidencePrecision.INFERRED, period.leftPrecision)
        assertEquals(EvidenceSource.SNAPSHOT, period.leftSource)
        assertEquals(0L, count("platoon_activity", "member_event_id IS NOT NULL"))
    }

    @Test
    fun rosterTimeCorrelationDatesAWithdrawalFromActivityWithinTheLeadBound() {
        roster(T0, member(OTHER_UID, "Other"), member(TARGET_UID, "Leaver"))
        val revealingAt = T0.plusSeconds(6 * HOUR)
        val actedAt = revealingAt.minusSeconds(HOUR)
        database.ingestPlatoonActivity(listOf(activity("Leaver", actedAt)), actedAt.plusSeconds(60))
        roster(revealingAt, member(OTHER_UID, "Other"))

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(actedAt, period.leftAt)
        assertEquals(EvidenceSource.ACTIVITY, period.leftSource)
        assertTrue(period.isLeftBoundaryEditable)
        assertEquals(1L, count("member_events", "source = 'ACTIVITY' AND event_type = 'LEFT'"))
        assertEquals(0L, count("member_events", "source = 'GAME_UPDATES'"))
    }

    @Test
    fun anActivityResolvedToAnotherUidNeverDatesANewJoin() {
        roster(T0, member(OTHER_UID, "Other"), member(TARGET_UID, "Bob"))
        val actedAt = T0.plusSeconds(HOUR)
        database.ingestPlatoonActivity(listOf(activity("Bob", actedAt)), actedAt.plusSeconds(60))
        assertEquals(1L, count("platoon_activity", "resolved_uid = $TARGET_UID"))
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, T0.plusSeconds(2 * HOUR), TARGET_UID, "Rob")),
            T0.plusSeconds(2 * HOUR),
        )
        roster(T0.plusSeconds(3 * HOUR), member(OTHER_UID, "Other"), member(THIRD_UID, "Bob"))

        val joined = status(THIRD_UID).membershipPeriods.single()
        assertEquals(EvidencePrecision.INFERRED, joined.joinedPrecision)
        assertEquals(T0.plusSeconds(3 * HOUR), joined.joinedAt)
        assertEquals(1L, count("platoon_activity", "resolved_uid = $TARGET_UID AND member_event_id IS NULL"))
    }

    @Test
    fun aLaterUpdatesJoinReplacesAnActivityDatedJoin() {
        val previousAt = Instant.parse("2026-08-03T09:00:00Z")
        val actedAt = Instant.parse("2026-08-03T10:20:00Z")
        val revealingAt = Instant.parse("2026-08-03T12:00:00Z")
        roster(previousAt, member(OTHER_UID, "Other"))
        database.ingestPlatoonActivity(listOf(activity("Target", actedAt)), actedAt.plusSeconds(600))
        roster(revealingAt, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        val dated = status(TARGET_UID).membershipPeriods.single()
        assertEquals(actedAt, dated.joinedAt)
        assertEquals(EvidenceSource.ACTIVITY, dated.joinedSource)

        val joinedAt = Instant.parse("2026-08-03T10:00:00Z")
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, joinedAt, TARGET_UID, "Target")),
            revealingAt.plusSeconds(60),
        )

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(joinedAt, period.joinedAt)
        assertEquals(EvidenceSource.GAME_UPDATES, period.joinedSource)
        assertNull(period.leftAt)
        assertEquals(1L, count("member_events", "uid = $TARGET_UID AND event_type IN ('JOINED', 'REJOINED')"))
        assertEquals(0L, count("platoon_activity", "member_event_id IS NOT NULL"))
        assertEquals(0L, count("platoon_activity", "resolution = 'MEMBERSHIP_CORRELATION'"))
        assertTrue(status(TARGET_UID).isActive)
        assertForeignKeysValid()
    }

    @Test
    fun aLaterUpdatesWithdrawalReplacesAnActivityDatedWithdrawal() {
        val previousAt = Instant.parse("2026-08-03T09:00:00Z")
        val actedAt = Instant.parse("2026-08-03T10:20:00Z")
        val revealingAt = Instant.parse("2026-08-03T12:00:00Z")
        roster(previousAt, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        database.ingestPlatoonActivity(listOf(activity("Target", actedAt)), actedAt.plusSeconds(600))
        roster(revealingAt, member(OTHER_UID, "Other"))
        assertEquals(EvidenceSource.ACTIVITY, status(TARGET_UID).membershipPeriods.single().leftSource)

        val withdrewAt = Instant.parse("2026-08-03T11:00:00Z")
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, withdrewAt, TARGET_UID, "Target")),
            revealingAt.plusSeconds(60),
        )

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(withdrewAt, period.leftAt)
        assertEquals(EvidenceSource.GAME_UPDATES, period.leftSource)
        assertEquals(1L, count("member_events", "uid = $TARGET_UID AND event_type IN ('LEFT', 'REMOVED')"))
        assertFalse(status(TARGET_UID).isActive)
        assertForeignKeysValid()
    }

    @Test
    fun aLateUpdatesJoinFoldsIntoTheRosterInferredJoinOfTheSameGap() {
        val monday = Instant.parse("2026-08-03T00:00:00Z")
        val sunday = Instant.parse("2026-08-09T15:00:00Z")
        roster(monday, member(OTHER_UID, "Other"))
        roster(sunday, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))

        val joinedAt = Instant.parse("2026-08-04T10:00:00Z")
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, joinedAt, TARGET_UID, "Target")),
            sunday.plusSeconds(HOUR),
        )

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(joinedAt, period.joinedAt)
        assertEquals(EvidencePrecision.EXACT, period.joinedPrecision)
        assertEquals(EvidenceSource.GAME_UPDATES, period.joinedSource)
        assertNull(period.leftAt)
        assertEquals(1L, count("member_events", "uid = $TARGET_UID AND event_type IN ('JOINED', 'REJOINED')"))
        assertForeignKeysValid()
    }

    @Test
    fun aLateUpdatesWithdrawalFoldsIntoTheRosterInferredWithdrawalOfTheSameGap() {
        val monday = Instant.parse("2026-08-03T00:00:00Z")
        val sunday = Instant.parse("2026-08-09T15:00:00Z")
        roster(monday, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        roster(sunday, member(OTHER_UID, "Other"))

        val withdrewAt = Instant.parse("2026-08-04T10:00:00Z")
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, withdrewAt, TARGET_UID, "Target")),
            sunday.plusSeconds(HOUR),
        )

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(withdrewAt, period.leftAt)
        assertEquals(EvidenceSource.GAME_UPDATES, period.leftSource)
        assertEquals(1L, count("member_events", "uid = $TARGET_UID AND event_type IN ('LEFT', 'REMOVED')"))
        assertForeignKeysValid()
    }

    @Test
    fun anUpdatesRejoinNeverFoldsAnEarlierMembershipAcrossItsWithdrawal() {
        val joined = Instant.parse("2026-08-04T00:00:00Z")
        val omitted = Instant.parse("2026-08-07T00:00:00Z")
        val back = Instant.parse("2026-08-11T00:00:00Z")
        roster(Instant.parse("2026-08-03T00:00:00Z"), member(OTHER_UID, "Other"))
        roster(joined, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        roster(omitted, member(OTHER_UID, "Other"))
        roster(back, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        assertEquals(2, status(TARGET_UID).membershipPeriods.size)

        // The rejoin lies in the last roster gap; the first join is a week older and the first
        // membership ended in between, so only the rejoin's roster boundary records this change.
        val rejoinedAt = Instant.parse("2026-08-10T10:00:00Z")
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, rejoinedAt, TARGET_UID, "Target")),
            back.plusSeconds(HOUR),
        )

        val periods = status(TARGET_UID).membershipPeriods.sortedBy { it.leftAt == null }
        assertEquals(2, periods.size)
        assertEquals(joined, periods.first().joinedAt)
        assertEquals(omitted, periods.first().leftAt)
        assertEquals(rejoinedAt, periods.last().joinedAt)
        assertEquals(EvidenceSource.GAME_UPDATES, periods.last().joinedSource)
        assertNull(periods.last().leftAt)
        assertEquals(2L, count("member_events", "uid = $TARGET_UID AND event_type IN ('JOINED', 'REJOINED')"))
        assertForeignKeysValid()
    }

    @Test
    fun aRosterOmissionClosesEveryOpenPeriod() {
        roster(T0, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        insertOpenPeriod(TARGET_UID, T0.minusSeconds(10 * 24 * HOUR), EvidencePrecision.MANUAL, EvidenceSource.MANUAL)
        insertOpenPeriod(TARGET_UID, T0.minusSeconds(24 * HOUR), EvidencePrecision.EXACT, EvidenceSource.GAME_UPDATES)
        assertEquals(3L, count("membership_periods", "uid = $TARGET_UID AND left_at IS NULL"))

        val omittedAt = T0.plusSeconds(HOUR)
        roster(omittedAt, member(OTHER_UID, "Other"))

        assertEquals(0L, count("membership_periods", "uid = $TARGET_UID AND left_at IS NULL"))
        // The roster-only duplicate was folded; both real periods close at the omission.
        assertEquals(2L, count("membership_periods", "uid = $TARGET_UID AND left_at = ${omittedAt.toEpochMilli()}"))
        assertEquals(2L, count("member_events", "uid = $TARGET_UID AND event_type = 'LEFT'"))
        assertFalse(status(TARGET_UID).isActive)
        assertForeignKeysValid()
    }

    @Test
    fun aWithdrawalIsDatedByTheLatestAdmittedActivity() {
        val previousAt = Instant.parse("2026-08-03T06:00:00Z")
        val revealingAt = Instant.parse("2026-08-03T21:00:00Z")
        roster(previousAt, member(OTHER_UID, "Other"), member(TARGET_UID, "Target"))
        roster(revealingAt, member(OTHER_UID, "Other"))

        val lastAction = Instant.parse("2026-08-03T18:00:00Z")
        database.ingestPlatoonActivity(
            listOf(activity("Target", Instant.parse("2026-08-03T10:00:00Z")), activity("Target", lastAction)),
            revealingAt.plusSeconds(HOUR),
        )

        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(lastAction, period.leftAt)
        assertEquals(EvidenceSource.ACTIVITY, period.leftSource)
    }

    @Test
    fun activityRetentionNeverEvictsExactDailyPatrolFacts() {
        val patrolAt = Instant.parse("2026-07-01T00:00:00Z")
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_DAILY_PATROL, patrolAt, TARGET_UID, "Target")),
            patrolAt,
        )
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            repeat(PlatoonDatabase.MAX_STORED_ACTIVITY_OBSERVATIONS + 5) { index ->
                writable.insertOrThrow(
                    "platoon_activity",
                    null,
                    ContentValues().apply {
                        put("occurred_at", patrolAt.toEpochMilli() + index + 1)
                        put("action_id", 701_001L)
                        put("kind", 1L)
                        put("member_name", "Unresolved $index")
                        put("captured_at", patrolAt.toEpochMilli() + index + 1)
                        put("resolution", ActivityResolution.UNRESOLVED.name)
                    },
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        reopen()

        assertEquals(
            PlatoonDatabase.MAX_STORED_ACTIVITY_OBSERVATIONS.toLong(),
            count("platoon_activity", "resolution = 'UNRESOLVED'"),
        )
        assertEquals(
            listOf(DailyPatrolFact(TARGET_UID, patrolAt)),
            database.listDailyPatrolFacts(patrolAt, patrolAt.plusSeconds(HOUR)),
        )
    }

    @Test
    fun membershipPeriodsAreListedChronologically() {
        roster(T0, member(TARGET_UID, "Target"), member(OTHER_UID, "Other"))
        roster(T0.plusSeconds(10 * 24 * HOUR), member(OTHER_UID, "Other"))
        roster(T0.plusSeconds(20 * 24 * HOUR), member(TARGET_UID, "Target"), member(OTHER_UID, "Other"))

        val periods = status(TARGET_UID).membershipPeriods
        assertEquals(2, periods.size)
        assertNull(periods[0].joinedAt)
        assertEquals(T0.plusSeconds(10 * 24 * HOUR), periods[0].leftAt)
        assertEquals(T0.plusSeconds(20 * 24 * HOUR), periods[1].joinedAt)
        assertNull(periods[1].leftAt)
    }

    @Test
    fun deletingAMemberRemovesUnresolvedActivityOnlyItCouldOwn() {
        roster(T0, member(TARGET_UID, "Ghost"), member(OTHER_UID, "Shared"), member(THIRD_UID, "Shared"))
        insertUnresolvedActivity("Ghost", T0.plusSeconds(60))
        insertUnresolvedActivity("Shared", T0.plusSeconds(120))

        assertTrue(database.deleteMember(TARGET_UID))
        assertTrue(database.deleteMember(THIRD_UID))

        assertEquals(0L, count("platoon_activity", "member_name = 'Ghost'"))
        // OTHER_UID still holds the name, so the fact's owner stays unknown and it is kept.
        assertEquals(1L, count("platoon_activity", "member_name = 'Shared'"))
        assertForeignKeysValid()
    }

    @Test
    fun manualMembershipRejectsFutureBoundariesAndOutOfRangeUids() {
        val joined = boundary(Instant.parse("2020-01-01T00:00:00Z"))
        val withdrew = boundary(Instant.parse("2020-02-01T00:00:00Z"))
        assertThrows(IllegalArgumentException::class.java) {
            database.addWithdrawnMember(4_294_967_296L, "Phantom", joined, withdrew, "")
        }
        assertTrue(database.addWithdrawnMember(4_294_967_295L, "Largest", joined, withdrew, ""))

        val future = MembershipBoundaryValue(
            LocalDate.of(2062, 10, 5),
            LocalDate.of(2062, 10, 5).atStartOfDay(ZoneOffset.UTC).toInstant(),
            timeKnown = false,
        )
        assertThrows(IllegalArgumentException::class.java) {
            database.addWithdrawnMember(TARGET_UID, "Future", joined, future, "")
        }
        assertEquals(0L, count("members", "uid = $TARGET_UID"))
        assertFalse(database.listWeeklyEvidenceDays(ZoneOffset.UTC).any { it.year == 2062 })

        val inAMonth = boundary(Instant.now().plus(Duration.ofDays(30)))
        val largest = status(4_294_967_295L).membershipPeriods.single()
        assertThrows(IllegalArgumentException::class.java) {
            database.updateMembershipPeriod(largest.id, joined, inAMonth, "")
        }
    }

    @Test
    fun openingRederivesActiveStateWrittenByAnOlderRule() {
        val writable = database.writableDatabase
        writable.execSQL(
            "INSERT INTO members(uid, current_name, current_level, is_active, first_seen_at, last_seen_at) " +
                "VALUES(?, ?, 60, 1, ?, ?)",
            arrayOf<Any>(TARGET_UID, "Target", T0.toEpochMilli(), T0.toEpochMilli()),
        )
        insertOpenPeriod(TARGET_UID, T0, EvidencePrecision.EXACT, EvidenceSource.GAME_UPDATES)
        SnapshotIngestion.insertSnapshotRows(
            writable,
            PlatoonSnapshot(id = 0, capturedAt = T0.plusSeconds(HOUR), members = listOf(member(OTHER_UID, "Other"))),
        )
        writable.execSQL(
            "UPDATE members SET is_active = 1 WHERE uid = ?",
            arrayOf<Any>(TARGET_UID),
        )
        writable.delete("platoon_maintenance_state", "key = ?", arrayOf(MembershipRows.ACTIVE_STATE_RULE_KEY))
        reopen()

        assertFalse(status(TARGET_UID).isActive)
        assertEquals(
            MembershipRows.ACTIVE_STATE_RULE_VERSION,
            queryLong(
                "SELECT value FROM platoon_maintenance_state WHERE key = '${MembershipRows.ACTIVE_STATE_RULE_KEY}'",
            ),
        )
    }

    @Test
    fun schemaFourteenUpgradeMarksActivityDatedBoundariesAndFoldsTheirDuplicates() {
        val writable = database.writableDatabase
        writable.execSQL(
            "INSERT INTO members(uid, current_name, current_level, is_active, first_seen_at, last_seen_at) " +
                "VALUES(?, ?, 60, 1, ?, ?)",
            arrayOf<Any>(TARGET_UID, "Target", T0.toEpochMilli(), T0.toEpochMilli()),
        )
        val actedAt = T0.plusSeconds(20 * 60)
        val activityDated = insertOpenPeriod(TARGET_UID, actedAt, EvidencePrecision.EXACT, EvidenceSource.GAME_UPDATES)
        val activityEvent = insertJoinEvent(activityDated, actedAt)
        writable.insertOrThrow(
            "platoon_activity",
            null,
            ContentValues().apply {
                put("occurred_at", actedAt.toEpochMilli())
                put("action_id", 701_001L)
                put("kind", 1L)
                put("member_name", "Target")
                put("captured_at", actedAt.toEpochMilli())
                put("resolved_uid", TARGET_UID)
                put("resolution", ActivityResolution.MEMBERSHIP_CORRELATION.name)
                put("member_event_id", activityEvent)
            },
        )
        val exact = insertOpenPeriod(TARGET_UID, T0, EvidencePrecision.EXACT, EvidenceSource.GAME_UPDATES)
        insertJoinEvent(exact, T0)
        database.close()
        context.openOrCreateDatabase(TEST_DATABASE, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.version = 13
        }
        database = PlatoonDatabase(context, TEST_DATABASE, TEST_SCOPE)

        assertEquals(PlatoonSchema.CURRENT_VERSION, database.writableDatabase.version)
        val period = status(TARGET_UID).membershipPeriods.single()
        assertEquals(exact, period.id)
        assertEquals(EvidenceSource.GAME_UPDATES, period.joinedSource)
        assertEquals(1L, count("member_events", "uid = $TARGET_UID"))
        assertEquals(
            1L,
            count("platoon_activity", "resolution = 'UNIQUE_ROSTER_NAME' AND member_event_id IS NULL"),
        )
        assertForeignKeysValid()
    }

    @Test
    fun schemaFourteenUpgradeKeepsADepartedMemberWhoseActivityDatedJoinAnUpdatesJoinSuperseded() {
        insertMember(TARGET_UID, active = false)
        // 2.7.1: an activity-dated join stored as exact Updates evidence, left open, ...
        val actedAt = T0.plusSeconds(20 * 60)
        val activityDated = insertOpenPeriod(TARGET_UID, actedAt, EvidencePrecision.EXACT, EvidenceSource.GAME_UPDATES)
        insertCitingActivity(insertJoinEvent(activityDated, actedAt), actedAt)
        // ... then the 21960 join of the same change opened a second period, which a leave closed.
        val updatesJoin = T0.plusSeconds(2 * HOUR)
        val leftAt = T0.plusSeconds(10 * HOUR)
        val updates = insertOpenPeriod(TARGET_UID, updatesJoin, EvidencePrecision.EXACT, EvidenceSource.GAME_UPDATES)
        insertJoinEvent(updates, updatesJoin)
        closeExactly(updates, leftAt)
        insertExactWithdrawalEvent(updates, leftAt)
        upgradeFromThirteen()

        val target = status(TARGET_UID)
        assertFalse(target.isActive)
        assertTrue(target.membershipPeriods.none { it.leftAt == null })
        assertEquals(listOf(leftAt), target.membershipPeriods.map { it.leftAt })
        assertEquals(1L, count("member_events", "uid = $TARGET_UID AND event_type = 'LEFT'"))

        roster(leftAt.plusSeconds(2 * HOUR), member(OTHER_UID, "Other"))

        assertFalse(status(TARGET_UID).isActive)
        assertEquals(1L, count("member_events", "uid = $TARGET_UID AND event_type = 'LEFT'"))
        assertForeignKeysValid()
    }

    private fun insertMember(uid: Long, active: Boolean) {
        database.writableDatabase.execSQL(
            "INSERT INTO members(uid, current_name, current_level, is_active, first_seen_at, last_seen_at) " +
                "VALUES(?, ?, 60, ?, ?, ?)",
            arrayOf<Any>(uid, "Target", if (active) 1 else 0, T0.toEpochMilli(), T0.toEpochMilli()),
        )
    }

    private fun insertCitingActivity(eventId: Long, actedAt: Instant) {
        database.writableDatabase.insertOrThrow(
            "platoon_activity",
            null,
            ContentValues().apply {
                put("occurred_at", actedAt.toEpochMilli())
                put("action_id", 701_001L)
                put("kind", 1L)
                put("member_name", "Target")
                put("captured_at", actedAt.toEpochMilli())
                put("resolved_uid", TARGET_UID)
                put("resolution", ActivityResolution.MEMBERSHIP_CORRELATION.name)
                put("member_event_id", eventId)
            },
        )
    }

    private fun closeExactly(membershipPeriodId: Long, leftAt: Instant) {
        database.writableDatabase.update(
            "membership_periods",
            ContentValues().apply {
                put("left_at", leftAt.toEpochMilli())
                put("left_time_known", 1)
                put("left_precision", EvidencePrecision.EXACT.name)
                put("left_source", EvidenceSource.GAME_UPDATES.name)
            },
            "id = ?",
            arrayOf(membershipPeriodId.toString()),
        )
    }

    private fun insertExactWithdrawalEvent(membershipPeriodId: Long, occurredAt: Instant): Long =
        MembershipRows.insertEvent(
            db = database.writableDatabase,
            uid = TARGET_UID,
            type = MemberEventType.LEFT,
            occurredAt = occurredAt,
            observedAt = occurredAt,
            precision = EvidencePrecision.EXACT,
            source = EvidenceSource.GAME_UPDATES,
            note = "Target",
            membershipPeriodId = membershipPeriodId,
        )

    private fun upgradeFromThirteen() {
        database.close()
        context.openOrCreateDatabase(TEST_DATABASE, Context.MODE_PRIVATE, null).use { legacy ->
            legacy.version = 13
        }
        database = PlatoonDatabase(context, TEST_DATABASE, TEST_SCOPE)
        assertEquals(PlatoonSchema.CURRENT_VERSION, database.writableDatabase.version)
    }

    @Test
    fun schemaFiveUpgradeKeepsOneEventForAManuallyEditedTenure() {
        val databaseName = "platoon-v5-manual-tenure-test.db"
        context.deleteDatabase(databaseName)
        val joinedAt = Instant.parse("2026-07-20T09:00:00Z").toEpochMilli()
        val observedAt = Instant.parse("2026-07-23T00:00:00Z").toEpochMilli()
        try {
            context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { legacy ->
                SCHEMA_FIVE.forEach { statement -> legacy.execSQL(statement.trimIndent()) }
                legacy.execSQL(
                    "INSERT INTO members(uid, current_name, current_level, is_active, first_seen_at, last_seen_at) " +
                        "VALUES(?, 'Target', 60, 1, ?, ?)",
                    arrayOf<Any>(TARGET_UID, observedAt, observedAt),
                )
                legacy.execSQL(
                    "INSERT INTO tenures(uid, joined_at, joined_precision, joined_source) " +
                        "VALUES(?, ?, 'MANUAL', 'MANUAL')",
                    arrayOf<Any>(TARGET_UID, joinedAt),
                )
                legacy.execSQL(
                    "INSERT INTO member_events(uid, event_type, occurred_at, observed_at, precision, source, note) " +
                        "VALUES(?, 'JOINED', NULL, ?, 'INFERRED', 'SNAPSHOT', 'Target')",
                    arrayOf<Any>(TARGET_UID, observedAt),
                )
                legacy.version = 5
            }

            PlatoonDatabase(context, databaseName, TEST_SCOPE).use { upgraded ->
                upgraded.readableDatabase.rawQuery(
                    "SELECT source, membership_period_id IS NOT NULL FROM member_events " +
                        "WHERE uid = ? AND event_type IN ('JOINED', 'REJOINED')",
                    arrayOf(TARGET_UID.toString()),
                ).use { cursor ->
                    assertEquals(1, cursor.count)
                    assertTrue(cursor.moveToFirst())
                    assertEquals(EvidenceSource.MANUAL.name, cursor.getString(0))
                    assertEquals(1, cursor.getInt(1))
                }
            }
        } finally {
            context.deleteDatabase(databaseName)
        }
    }

    private fun reopen() {
        database.close()
        database = PlatoonDatabase(context, TEST_DATABASE, TEST_SCOPE)
        database.writableDatabase
    }

    private fun roster(capturedAt: Instant, vararg members: SnapshotMember) {
        database.ingestSnapshot(
            PlatoonSnapshot(id = 0, capturedAt = capturedAt, members = members.toList()),
            EvidenceSource.SNAPSHOT,
        )
    }

    private fun status(uid: Long): MemberStatus = database.listMemberStatuses().single { it.uid == uid }

    private fun customName(uid: Long): String? =
        database.readableDatabase.rawQuery(
            "SELECT custom_name FROM members WHERE uid = ?",
            arrayOf(uid.toString()),
        ).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getString(0)
        }

    private fun insertOpenPeriod(
        uid: Long,
        joinedAt: Instant,
        precision: EvidencePrecision,
        source: EvidenceSource,
    ): Long = MembershipRows.insertMembershipPeriod(database.writableDatabase, uid, joinedAt, precision, source)

    private fun insertJoinEvent(membershipPeriodId: Long, occurredAt: Instant): Long =
        MembershipRows.insertEvent(
            db = database.writableDatabase,
            uid = TARGET_UID,
            type = MemberEventType.JOINED,
            occurredAt = occurredAt,
            observedAt = occurredAt,
            precision = EvidencePrecision.EXACT,
            source = EvidenceSource.GAME_UPDATES,
            note = "Target",
            membershipPeriodId = membershipPeriodId,
        )

    private fun insertUnresolvedActivity(name: String, occurredAt: Instant) {
        database.writableDatabase.insertOrThrow(
            "platoon_activity",
            null,
            ContentValues().apply {
                put("occurred_at", occurredAt.toEpochMilli())
                put("action_id", 701_001L)
                put("kind", 1L)
                put("member_name", name)
                put("captured_at", occurredAt.toEpochMilli())
                put("resolution", ActivityResolution.UNRESOLVED.name)
            },
        )
    }

    private fun boundary(instant: Instant) =
        MembershipBoundaryValue(instant.atZone(ZoneOffset.UTC).toLocalDate(), instant, timeKnown = true)

    private fun activity(name: String, occurredAt: Instant) = PlatoonActivityObservation(
        occurredAt = occurredAt,
        actionId = 701_001L,
        kind = 1,
        memberName = name,
    )

    private fun update(kind: Long, at: Instant, uid: Long, name: String) =
        PlatoonUpdateObservation(
            kind = kind,
            occurredAt = at,
            members = listOf(PlatoonUpdateMemberObservation(role = 0, uid = uid, name = name)),
        )

    private fun member(uid: Long, name: String) = SnapshotMember(
        uid = uid,
        name = name,
        level = 60,
        weeklyMerit = 120,
        totalMerit = 4_560,
        highScore = 789,
        totalScore = 1_234,
        lastLogin = 1_700_000_000,
    )

    private fun count(table: String, selection: String): Long =
        queryLong("SELECT COUNT(*) FROM $table WHERE $selection")

    private fun queryLong(sql: String): Long =
        database.readableDatabase.rawQuery(sql, null).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertForeignKeysValid() {
        database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

    private companion object {
        const val TEST_DATABASE = "platoon-membership-integrity-test.db"
        val TEST_SCOPE = PlatoonStorageScope("1".repeat(32))
        const val TARGET_UID = 1001L
        const val OTHER_UID = 2002L
        const val THIRD_UID = 3003L
        const val HOUR = 60L * 60L
        val T0: Instant = Instant.parse("2026-08-03T00:00:00Z")

        /** The 2.0.0 (schema v5) tables, before membership periods and calendar dates. */
        val SCHEMA_FIVE = listOf(
            """
            CREATE TABLE snapshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                captured_at INTEGER NOT NULL,
                source_file TEXT UNIQUE,
                game_version TEXT
            )
            """,
            """
            CREATE TABLE snapshot_members (
                snapshot_id INTEGER NOT NULL REFERENCES snapshots(id) ON DELETE CASCADE,
                uid INTEGER NOT NULL,
                name TEXT NOT NULL,
                level INTEGER NOT NULL,
                weekly_merit INTEGER NOT NULL,
                total_merit INTEGER NOT NULL,
                high_score INTEGER NOT NULL,
                total_score INTEGER NOT NULL,
                last_login INTEGER NOT NULL,
                PRIMARY KEY(snapshot_id, uid)
            )
            """,
            """
            CREATE TABLE members (
                uid INTEGER PRIMARY KEY,
                current_name TEXT NOT NULL,
                custom_name TEXT,
                current_level INTEGER NOT NULL,
                is_active INTEGER NOT NULL,
                first_seen_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL,
                note TEXT NOT NULL DEFAULT ''
            )
            """,
            """
            CREATE TABLE tenures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid INTEGER NOT NULL REFERENCES members(uid),
                joined_at INTEGER,
                left_at INTEGER,
                joined_precision TEXT NOT NULL,
                left_precision TEXT,
                joined_source TEXT NOT NULL,
                left_source TEXT,
                note TEXT NOT NULL DEFAULT ''
            )
            """,
            "CREATE INDEX tenures_uid ON tenures(uid, id DESC)",
            """
            CREATE TABLE member_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid INTEGER NOT NULL REFERENCES members(uid),
                event_type TEXT NOT NULL,
                occurred_at INTEGER,
                observed_at INTEGER NOT NULL,
                precision TEXT NOT NULL,
                source TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT ''
            )
            """,
            "CREATE INDEX member_events_time ON member_events(observed_at DESC)",
            """
            CREATE TABLE weekly_notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                period_start INTEGER NOT NULL,
                game_day INTEGER NOT NULL,
                text TEXT NOT NULL,
                event_id INTEGER REFERENCES member_events(id) ON DELETE SET NULL,
                is_automatic INTEGER NOT NULL DEFAULT 0
            )
            """,
            """
            CREATE TABLE weekly_overrides (
                uid INTEGER NOT NULL,
                period_start INTEGER NOT NULL,
                game_day INTEGER NOT NULL,
                merit_delta INTEGER,
                score_delta INTEGER,
                attempts INTEGER,
                attended INTEGER,
                daily_patrol INTEGER,
                PRIMARY KEY(uid, game_day)
            )
            """,
        )
    }
}
