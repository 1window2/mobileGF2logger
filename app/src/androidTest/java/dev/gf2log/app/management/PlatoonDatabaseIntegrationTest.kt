package dev.gf2log.app.management

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlatoonDatabaseIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: PlatoonDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(TEST_DATABASE)
        database = PlatoonDatabase(context, TEST_DATABASE)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun deletionRemovesMemberFootprintAndKeepsSourceIdentity() {
        val baselineAt = Instant.parse("2026-07-30T00:00:00Z")
        val capturedAt = Instant.parse("2026-07-31T00:00:00Z")
        val sourceFile = "gf2log_platoonmembers_20260731T000000Z.csv"
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = baselineAt,
                sourceFile = "gf2log_platoonmembers_20260730T000000Z.csv",
                members = listOf(member(OTHER_UID, "Other")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt,
                sourceFile = sourceFile,
                members = listOf(member(TARGET_UID, "Target"), member(OTHER_UID, "Other")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        val writable = database.writableDatabase
        val targetEventId = queryLong(
            "SELECT id FROM member_events WHERE uid = ? LIMIT 1",
            TARGET_UID,
        )
        writable.insertOrThrow(
            "weekly_overrides",
            null,
            ContentValues().apply {
                put("uid", TARGET_UID)
                put("period_start", 20665L)
                put("game_day", 20665L)
            },
        )
        writable.insertOrThrow(
            "weekly_notes",
            null,
            ContentValues().apply {
                put("period_start", 20665L)
                put("game_day", 20665L)
                put("text", "Target joined")
                put("event_id", targetEventId)
                put("is_automatic", 1)
            },
        )
        writable.insertOrThrow(
            "platoon_activity",
            null,
            ContentValues().apply {
                put("occurred_at", capturedAt.toEpochMilli())
                put("action_id", 802001L)
                put("kind", 1L)
                put("member_name", "Target")
                put("captured_at", capturedAt.toEpochMilli())
                put("resolved_uid", TARGET_UID)
                put("resolution", ActivityResolution.EXACT_UPDATE.name)
                put("member_event_id", targetEventId)
            },
        )

        assertTrue(database.deleteMember(TARGET_UID))
        assertFalse(database.deleteMember(TARGET_UID))

        assertEquals(0L, count("members", "uid = ?", TARGET_UID))
        assertEquals(0L, count("membership_periods", "uid = ?", TARGET_UID))
        assertEquals(0L, count("member_events", "uid = ?", TARGET_UID))
        assertEquals(0L, count("snapshot_members", "uid = ?", TARGET_UID))
        assertEquals(0L, count("weekly_overrides", "uid = ?", TARGET_UID))
        assertEquals(0L, count("platoon_activity", "resolved_uid = ?", TARGET_UID))
        assertEquals(0L, count("weekly_notes", "text = ?", "Target joined"))
        assertEquals(1L, count("members", "uid = ?", OTHER_UID))
        assertEquals(1L, count("snapshots", "source_file = ?", sourceFile))
        assertTrue(sourceFile in database.snapshotSourceFiles())
        assertForeignKeysValid()

        val duplicate = database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt,
                sourceFile = sourceFile,
                members = listOf(member(TARGET_UID, "Target"), member(OTHER_UID, "Other")),
            ),
            EvidenceSource.LEGACY_IMPORT,
        )
        assertTrue(duplicate.duplicate)
        assertEquals(0L, count("members", "uid = ?", TARGET_UID))

        val recreated = database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt.plusSeconds(60),
                sourceFile = "gf2log_platoonmembers_20260731T000100Z.csv",
                members = listOf(member(TARGET_UID, "Real Target"), member(OTHER_UID, "Other")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        assertFalse(recreated.duplicate)
        assertEquals(1L, count("members", "uid = ?", TARGET_UID))
        assertEquals("Real Target", database.listMemberStatuses().single { it.uid == TARGET_UID }.name)
        assertTrue(database.listMemberStatuses().single { it.uid == TARGET_UID }.isActive)
        assertEquals(0L, count("weekly_overrides", "uid = ?", TARGET_UID))
        assertEquals(0L, count("weekly_notes", "text = ?", "Target joined"))
    }

    @Test
    fun historicalSnapshotAddsWeeklyEvidenceAndAnInactiveMembershipHistory() {
        val currentTime = Instant.parse("2026-07-31T00:00:00Z")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime,
                sourceFile = "current.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )

        val historical = database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime.minusSeconds(86_400),
                sourceFile = "historical.csv",
                members = listOf(member(TARGET_UID, "Historical")),
            ),
            EvidenceSource.LEGACY_IMPORT,
            historicalOnly = true,
        )

        assertFalse(historical.duplicate)
        assertEquals(1L, count("snapshots", "source_file = ?", "historical.csv"))
        assertEquals(1L, count("snapshot_members", "uid = ?", TARGET_UID))
        assertEquals(1L, count("members", "uid = ?", TARGET_UID))
        assertEquals(1L, count("members", "uid = ?", OTHER_UID))
        val historicalStatus = database.listMemberStatuses().single { it.uid == TARGET_UID }
        assertFalse(historicalStatus.isActive)
        assertEquals(currentTime.minusSeconds(86_400), historicalStatus.firstSeenAt)
        assertEquals(1, historicalStatus.membershipPeriods.size)
        assertEquals(null, historicalStatus.membershipPeriods.single().joinedAt)
        assertEquals(currentTime, historicalStatus.membershipPeriods.single().leftAt)
        assertEquals(EvidencePrecision.UNKNOWN, historicalStatus.membershipPeriods.single().joinedPrecision)
        assertEquals(EvidencePrecision.INFERRED, historicalStatus.membershipPeriods.single().leftPrecision)
        assertForeignKeysValid()
    }

    @Test
    fun lateHistoricalSnapshotsReconstructWithdrawalAndRejoinWithoutChangingCurrentRoster() {
        val currentTime = Instant.parse("2026-07-31T00:00:00Z")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime,
                sourceFile = "current.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )

        listOf(
            currentTime.minusSeconds(4 * 86_400) to listOf(member(TARGET_UID, "Historical"), member(OTHER_UID, "Current")),
            currentTime.minusSeconds(3 * 86_400) to listOf(member(OTHER_UID, "Current")),
            currentTime.minusSeconds(2 * 86_400) to listOf(member(TARGET_UID, "Historical"), member(OTHER_UID, "Current")),
        ).forEachIndexed { index, (capturedAt, members) ->
            database.ingestSnapshot(
                PlatoonSnapshot(
                    id = 0,
                    capturedAt = capturedAt,
                    sourceFile = "historical-$index.csv",
                    members = members,
                ),
                EvidenceSource.LEGACY_IMPORT,
                historicalOnly = true,
            )
        }

        val target = database.listMemberStatuses().single { it.uid == TARGET_UID }
        assertFalse(target.isActive)
        assertEquals(2, target.membershipPeriods.size)
        val ordered = target.membershipPeriods.sortedBy { it.leftAt }
        assertEquals(null, ordered[0].joinedAt)
        assertEquals(currentTime.minusSeconds(3 * 86_400), ordered[0].leftAt)
        assertEquals(currentTime.minusSeconds(2 * 86_400), ordered[1].joinedAt)
        assertEquals(currentTime, ordered[1].leftAt)
        assertTrue(database.listMemberStatuses().single { it.uid == OTHER_UID }.isActive)
        assertForeignKeysValid()
    }

    @Test
    fun historicalRosterReplayPreservesExactBoundariesAndAddsOnlyTheLaterRun() {
        val currentTime = Instant.parse("2026-07-31T00:00:00Z")
        val exactJoin = currentTime.minusSeconds(5 * 86_400)
        val exactWithdraw = currentTime.minusSeconds(4 * 86_400)
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime,
                sourceFile = "current.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, exactJoin, TARGET_UID, "Historical")),
            exactJoin.plusSeconds(60),
        )
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, exactWithdraw, TARGET_UID, "Historical")),
            exactWithdraw.plusSeconds(60),
        )

        listOf(
            exactJoin.plusSeconds(3_600) to listOf(member(TARGET_UID, "Historical"), member(OTHER_UID, "Current")),
            exactWithdraw.plusSeconds(3_600) to listOf(member(OTHER_UID, "Current")),
            currentTime.minusSeconds(86_400) to listOf(member(TARGET_UID, "Historical"), member(OTHER_UID, "Current")),
        ).forEachIndexed { index, (capturedAt, members) ->
            database.ingestSnapshot(
                PlatoonSnapshot(
                    id = 0,
                    capturedAt = capturedAt,
                    sourceFile = "exact-history-$index.csv",
                    members = members,
                ),
                EvidenceSource.LEGACY_IMPORT,
                historicalOnly = true,
            )
        }

        val periods = database.listMemberStatuses()
            .single { it.uid == TARGET_UID }
            .membershipPeriods
            .sortedBy { it.joinedAt ?: Instant.MIN }
        assertEquals(2, periods.size)
        assertEquals(exactJoin, periods[0].joinedAt)
        assertEquals(exactWithdraw, periods[0].leftAt)
        assertEquals(EvidenceSource.GAME_UPDATES, periods[0].joinedSource)
        assertEquals(EvidenceSource.GAME_UPDATES, periods[0].leftSource)
        assertEquals(currentTime.minusSeconds(86_400), periods[1].joinedAt)
        assertEquals(currentTime, periods[1].leftAt)
        assertForeignKeysValid()
    }

    @Test
    fun historicalRosterReplayClosesAnExactOpenPeriodAndKeepsTheLaterRejoin() {
        val currentTime = Instant.parse("2026-07-31T00:00:00Z")
        val exactJoin = currentTime.minusSeconds(5 * 86_400)
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime,
                sourceFile = "current-gap.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, exactJoin, TARGET_UID, "Historical")),
            exactJoin.plusSeconds(60),
        )

        listOf(
            currentTime.minusSeconds(4 * 86_400) to
                listOf(member(TARGET_UID, "Historical"), member(OTHER_UID, "Current")),
            currentTime.minusSeconds(3 * 86_400) to listOf(member(OTHER_UID, "Current")),
            currentTime.minusSeconds(2 * 86_400) to
                listOf(member(TARGET_UID, "Historical"), member(OTHER_UID, "Current")),
        ).forEachIndexed { index, (capturedAt, members) ->
            database.ingestSnapshot(
                PlatoonSnapshot(
                    id = 0,
                    capturedAt = capturedAt,
                    sourceFile = "open-gap-$index.csv",
                    members = members,
                ),
                EvidenceSource.LEGACY_IMPORT,
                historicalOnly = true,
            )
        }

        val periods = database.listMemberStatuses()
            .single { it.uid == TARGET_UID }
            .membershipPeriods
            .sortedBy { it.joinedAt ?: Instant.MIN }
        assertEquals(2, periods.size)
        assertEquals(exactJoin, periods[0].joinedAt)
        assertEquals(currentTime.minusSeconds(3 * 86_400), periods[0].leftAt)
        assertEquals(EvidenceSource.GAME_UPDATES, periods[0].joinedSource)
        assertEquals(EvidenceSource.LEGACY_IMPORT, periods[0].leftSource)
        assertEquals(currentTime.minusSeconds(2 * 86_400), periods[1].joinedAt)
        assertEquals(currentTime, periods[1].leftAt)
        assertForeignKeysValid()
    }

    @Test
    fun historicalRosterReplayPreservesAUserNoteOnAWeakMembershipPeriod() {
        val currentTime = Instant.parse("2026-07-31T00:00:00Z")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime,
                sourceFile = "current-note.csv",
                members = listOf(member(TARGET_UID, "Annotated"), member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.writableDatabase.execSQL(
            "UPDATE membership_periods SET note = ? WHERE uid = ?",
            arrayOf<Any>("Preserve this note", TARGET_UID),
        )

        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = currentTime.minusSeconds(86_400),
                sourceFile = "historical-note.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.LEGACY_IMPORT,
            historicalOnly = true,
        )

        val periods = database.listMemberStatuses()
            .single { it.uid == TARGET_UID }
            .membershipPeriods
        assertEquals(1, periods.size)
        assertEquals("Preserve this note", periods.single().note)
        assertEquals(null, periods.single().joinedAt)
        assertForeignKeysValid()
    }

    @Test
    fun historicalRosterReplayExpandsAnAnnotatedWeakJoinWithoutLosingItsNote() {
        val initialTime = Instant.parse("2026-07-20T00:00:00Z")
        val inferredJoin = initialTime.plusSeconds(2 * 86_400)
        val historicalPresence = initialTime.plusSeconds(86_400)
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = initialTime,
                sourceFile = "annotated-initial.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = inferredJoin,
                sourceFile = "annotated-join.csv",
                members = listOf(member(TARGET_UID, "Annotated"), member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = inferredJoin.plusSeconds(86_400),
                sourceFile = "annotated-current.csv",
                members = listOf(member(TARGET_UID, "Annotated"), member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.writableDatabase.execSQL(
            "UPDATE membership_periods SET note = ? WHERE uid = ?",
            arrayOf<Any>("Preserve this note", TARGET_UID),
        )

        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = historicalPresence,
                sourceFile = "annotated-historical.csv",
                members = listOf(member(TARGET_UID, "Annotated"), member(OTHER_UID, "Current")),
            ),
            EvidenceSource.LEGACY_IMPORT,
            historicalOnly = true,
        )

        val period = database.listMemberStatuses()
            .single { it.uid == TARGET_UID }
            .membershipPeriods
            .single()
        assertEquals("Preserve this note", period.note)
        assertEquals(historicalPresence, period.joinedAt)
        assertEquals(EvidenceSource.LEGACY_IMPORT, period.joinedSource)
        assertForeignKeysValid()
    }

    @Test
    fun outerTransactionRollsBackNestedSnapshotIngest() {
        val failure = runCatching {
            database.runInTransaction {
                database.ingestSnapshot(
                    PlatoonSnapshot(
                        id = 0,
                        capturedAt = Instant.parse("2026-07-31T00:00:00Z"),
                        sourceFile = "rolled-back.csv",
                        members = listOf(member(TARGET_UID, "Rolled back")),
                    ),
                    EvidenceSource.LEGACY_IMPORT,
                )
                error("Force the outer import transaction to fail")
            }
        }

        assertEquals("Force the outer import transaction to fail", failure.exceptionOrNull()?.message)
        assertEquals(0L, count("snapshots", "id > ?", 0))
        assertEquals(0L, count("members", "uid = ?", TARGET_UID))
        assertForeignKeysValid()
    }

    @Test
    fun schemaSixAndSevenUpgradeDirectlyWithoutRecreatingActivityIndexes() {
        listOf(6, 7).forEach { legacyVersion ->
            val databaseName = "platoon-v$legacyVersion-upgrade-test.db"
            context.deleteDatabase(databaseName)
            try {
                createLegacyActivityDatabase(databaseName, legacyVersion)
                PlatoonDatabase(context, databaseName).use { upgraded ->
                    val writable = upgraded.writableDatabase
                    assertEquals(PlatoonSchema.CURRENT_VERSION, writable.version)
                    assertEquals(
                        1L,
                        writable.rawQuery(
                            "SELECT COUNT(*) FROM sqlite_master " +
                                "WHERE type = 'index' AND name = 'platoon_activity_exact_identity'",
                            null,
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            cursor.getLong(0)
                        },
                    )
                    assertEquals(
                        1L,
                        writable.rawQuery(
                            "SELECT COUNT(*) FROM platoon_activity",
                            null,
                        ).use { cursor ->
                            assertTrue(cursor.moveToFirst())
                            cursor.getLong(0)
                        },
                    )
                    writable.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                        assertFalse(cursor.moveToFirst())
                    }
                }
            } finally {
                context.deleteDatabase(databaseName)
            }
        }
    }

    @Test
    fun exactUpdatesSynchronizeCurrentStateWithoutOverridingNewerRoster() {
        val rosterAt = Instant.parse("2026-07-31T00:00:00Z")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = rosterAt,
                sourceFile = "current.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )

        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, rosterAt.plusSeconds(60), TARGET_UID, "Joined")),
            rosterAt.plusSeconds(120),
        )
        assertTrue(database.listMemberStatuses().single { it.uid == TARGET_UID }.isActive)

        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, rosterAt.plusSeconds(180), TARGET_UID, "Joined")),
            rosterAt.plusSeconds(240),
        )
        assertFalse(database.listMemberStatuses().single { it.uid == TARGET_UID }.isActive)
        val exactEventCount = count("member_events", "uid = ?", TARGET_UID)

        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, rosterAt.plusSeconds(180), TARGET_UID, "Joined")),
            rosterAt.plusSeconds(241),
        )
        assertEquals(exactEventCount, count("member_events", "uid = ?", TARGET_UID))

        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_JOIN, rosterAt.plusSeconds(120), TARGET_UID, "Stale")),
            rosterAt.plusSeconds(300),
        )
        assertFalse(database.listMemberStatuses().single { it.uid == TARGET_UID }.isActive)

        database.ingestPlatoonUpdates(
            listOf(update(PlatoonUpdateSemantics.KIND_WITHDRAW, rosterAt.minusSeconds(60), OTHER_UID, "Current")),
            rosterAt.plusSeconds(360),
        )
        assertTrue(database.listMemberStatuses().single { it.uid == OTHER_UID }.isActive)

        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = rosterAt.plusSeconds(420),
                sourceFile = "later.csv",
                members = listOf(member(TARGET_UID, "Roster name"), member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        val reconciled = database.listMemberStatuses().single { it.uid == TARGET_UID }
        assertTrue(reconciled.isActive)
        assertEquals("Roster name", reconciled.name)
        assertForeignKeysValid()
    }

    @Test(expected = IllegalArgumentException::class)
    fun weeklyOverrideRejectsMoreThanThreeDailyAttempts() {
        val periodStart = LocalDate.of(2026, 7, 19)
        database.replaceWeeklyOverrides(
            periodStart.toEpochDay(),
            listOf(
                WeeklyCellOverride(
                    uid = TARGET_UID,
                    periodStart = periodStart,
                    gameDay = periodStart,
                    meritDelta = 0,
                    scoreDelta = 0,
                    attempts = 4,
                    attended = true,
                    dailyPatrol = null,
                ),
            ),
        )
    }

    @Test
    fun weeklyEvidenceDiscoveryReturnsEveryBackedPeriodWithoutFillingGaps() {
        val zone = ZoneId.of("Asia/Seoul")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = Instant.parse("2026-07-05T00:00:00Z"),
                sourceFile = "early.csv",
                members = listOf(member(OTHER_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        database.ingestPlatoonActivity(
            listOf(
                PlatoonActivityObservation(
                    occurredAt = Instant.parse("2026-07-29T00:00:00Z"),
                    actionId = PlatoonDatabase.DAILY_PATROL_REWARD_ACTION_ID,
                    kind = 1,
                    memberName = "Current",
                ),
            ),
            Instant.parse("2026-07-29T00:01:00Z"),
        )
        database.addWeeklyNote(
            LocalDate.of(2026, 7, 19).toEpochDay(),
            LocalDate.of(2026, 7, 19).toEpochDay(),
            "Evidence",
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 5),
                LocalDate.of(2026, 7, 19),
                LocalDate.of(2026, 7, 26),
            ),
            WeeklyReportRange.periodStarts(database.listWeeklyEvidenceDays(zone)),
        )
    }

    @Test
    fun dateOnlyMembershipEvidenceUsesItsHistoricalWeekNotItsEntryWeek() {
        val zone = ZoneId.of("Asia/Seoul")
        val historicalDay = LocalDate.of(2026, 5, 4)
        val enteredAt = Instant.parse("2026-07-31T00:00:00Z")
        assertTrue(
            database.addWithdrawnMember(
                uid = TARGET_UID,
                name = "Historical",
                joined = MembershipBoundaryValue(historicalDay, enteredAt, timeKnown = false),
                withdrew = MembershipBoundaryValue(
                    historicalDay.plusDays(2),
                    enteredAt,
                    timeKnown = false,
                ),
                note = "Entered later",
            ),
        )

        assertEquals(
            listOf(LocalDate.of(2026, 5, 3)),
            WeeklyReportRange.periodStarts(database.listWeeklyEvidenceDays(zone)),
        )
    }

    @Test
    fun openingDatabaseTrimsAnOversizedActivityBacklogToTheNewestBound() {
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            repeat(PlatoonDatabase.MAX_STORED_ACTIVITY_OBSERVATIONS + 3) { index ->
                writable.insertOrThrow(
                    "platoon_activity",
                    null,
                    ContentValues().apply {
                        put("occurred_at", index.toLong())
                        put("action_id", index.toLong() + 1)
                        put("kind", 1L)
                        put("member_name", "Unresolved $index")
                        put("captured_at", index.toLong())
                        put("resolution", ActivityResolution.UNRESOLVED.name)
                    },
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        database.close()

        database = PlatoonDatabase(context, TEST_DATABASE)
        database.writableDatabase

        assertEquals(
            PlatoonDatabase.MAX_STORED_ACTIVITY_OBSERVATIONS.toLong(),
            count("platoon_activity", "id > ?", 0),
        )
        assertEquals(0L, count("platoon_activity", "action_id = ?", 1))
        assertEquals(1L, count("platoon_activity", "action_id = ?", 10_003))
    }

    @Test
    fun unresolvedActivityResolutionRotatesPastAnUnmatchableBatch() {
        val capturedAt = Instant.parse("2026-07-31T00:00:00Z")
        val writable = database.writableDatabase
        writable.beginTransaction()
        try {
            repeat(251) { index ->
                writable.insertOrThrow(
                    "platoon_activity",
                    null,
                    ContentValues().apply {
                        put("occurred_at", capturedAt.toEpochMilli() + index)
                        put("action_id", index.toLong() + 1)
                        put("kind", 1L)
                        put("member_name", if (index == 250) "Target" else "Blocked $index")
                        put("captured_at", capturedAt.toEpochMilli() + index)
                        put("resolution", ActivityResolution.UNRESOLVED.name)
                    },
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }

        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt,
                sourceFile = "resolution-first.csv",
                members = listOf(member(TARGET_UID, "Target")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        assertEquals(
            1L,
            count("platoon_activity", "action_id = ? AND resolved_uid IS NULL", 251),
        )

        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt.plusSeconds(86_400),
                sourceFile = "resolution-second.csv",
                members = listOf(member(TARGET_UID, "Target")),
            ),
            EvidenceSource.SNAPSHOT,
        )

        assertEquals(1L, count("platoon_activity", "action_id = 251 AND resolved_uid = ?", TARGET_UID))
        assertForeignKeysValid()
    }

    @Test
    fun membershipPeriodDeletionRequiresAReplacementAndRemovesLinkedEvents() {
        val capturedAt = Instant.parse("2026-07-31T00:00:00Z")
        database.ingestSnapshot(
            PlatoonSnapshot(
                id = 0,
                capturedAt = capturedAt,
                sourceFile = "current.csv",
                members = listOf(member(TARGET_UID, "Current")),
            ),
            EvidenceSource.SNAPSHOT,
        )
        val onlyPeriodId = queryLong(
            "SELECT id FROM membership_periods WHERE uid = ?",
            TARGET_UID,
        )
        assertFalse(database.deleteMembershipPeriod(onlyPeriodId))

        assertTrue(
            database.addMembershipPeriod(
                uid = TARGET_UID,
                joined = MembershipBoundaryValue(
                    LocalDate.of(2026, 1, 1),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    timeKnown = true,
                ),
                withdrew = MembershipBoundaryValue(
                    LocalDate.of(2026, 2, 1),
                    Instant.parse("2026-02-01T00:00:00Z"),
                    timeKnown = true,
                ),
                note = "Historical period",
            ),
        )
        val historicalId = database.listMemberStatuses()
            .single { it.uid == TARGET_UID }
            .membershipPeriods
            .single { it.note == "Historical period" }
            .id
        assertEquals(
            2L,
            count("member_events", "membership_period_id = ?", historicalId),
        )

        assertTrue(database.deleteMembershipPeriod(historicalId))
        val status = database.listMemberStatuses().single { it.uid == TARGET_UID }
        assertTrue(status.isActive)
        assertEquals(1, status.membershipPeriods.size)
        assertEquals(0L, count("member_events", "membership_period_id = ?", historicalId))
        assertForeignKeysValid()
    }

    private fun createLegacyActivityDatabase(databaseName: String, version: Int) {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { legacy ->
            listOf(
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
                    tenure_id INTEGER REFERENCES tenures(id),
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
                """
                CREATE TABLE platoon_activity (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    occurred_at INTEGER NOT NULL,
                    action_id INTEGER NOT NULL,
                    kind INTEGER NOT NULL,
                    member_name TEXT NOT NULL,
                    captured_at INTEGER NOT NULL,
                    resolved_uid INTEGER REFERENCES members(uid),
                    resolution TEXT NOT NULL DEFAULT 'UNRESOLVED',
                    member_event_id INTEGER REFERENCES member_events(id)
                )
                """,
                "CREATE INDEX platoon_activity_member_time " +
                    "ON platoon_activity(member_name, occurred_at)",
                "CREATE INDEX platoon_activity_action_time " +
                    "ON platoon_activity(action_id, occurred_at)",
            ).forEach { statement -> legacy.execSQL(statement.trimIndent()) }
            repeat(2) { index ->
                legacy.execSQL(
                    "INSERT INTO platoon_activity(" +
                        "occurred_at, action_id, kind, member_name, captured_at" +
                        ") VALUES(?, ?, ?, ?, ?)",
                    arrayOf<Any>(1_000L, 801_005L, 2L, "Duplicate", index.toLong()),
                )
            }
            legacy.version = version
        }
    }

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

    private fun count(table: String, selection: String, value: Any): Long =
        database.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $table WHERE $selection",
            arrayOf(value.toString()),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun queryLong(sql: String, value: Long): Long =
        database.readableDatabase.rawQuery(sql, arrayOf(value.toString())).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun assertForeignKeysValid() {
        database.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            assertFalse(cursor.moveToFirst())
        }
    }

    private companion object {
        const val TEST_DATABASE = "platoon-integration-test.db"
        const val TARGET_UID = 1001L
        const val OTHER_UID = 2002L
    }
}
