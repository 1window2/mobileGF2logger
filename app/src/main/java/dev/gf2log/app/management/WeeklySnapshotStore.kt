package dev.gf2log.app.management

import android.database.sqlite.SQLiteDatabase
import java.time.Instant

/**
 * Read-only SQL boundary for roster snapshots consumed by weekly projections.
 * Schema/migration and write transactions remain owned by PlatoonDatabase.
 */
internal object WeeklySnapshotStore {
    fun list(db: SQLiteDatabase, limit: Int): List<PlatoonSnapshot> = query(
        db = db,
        selectedSnapshotsSql =
            """
            SELECT id, captured_at, source_file, game_version
            FROM snapshots
            ORDER BY captured_at DESC, id DESC
            LIMIT ?
            """.trimIndent(),
        selectionArgs = arrayOf(limit.toString()),
    )

    fun listForPeriod(
        db: SQLiteDatabase,
        from: Instant,
        until: Instant,
    ): List<PlatoonSnapshot> = query(
        db = db,
        selectedSnapshotsSql =
            """
            SELECT id, captured_at, source_file, game_version
            FROM snapshots
            WHERE (captured_at >= ? AND captured_at <= ?)
               OR id = (
                   SELECT id
                   FROM snapshots
                   WHERE captured_at < ?
                   ORDER BY captured_at DESC, id DESC
                   LIMIT 1
               )
            ORDER BY captured_at DESC, id DESC
            """.trimIndent(),
        selectionArgs = arrayOf(
            from.toEpochMilli().toString(),
            until.toEpochMilli().toString(),
            from.toEpochMilli().toString(),
        ),
    )

    private fun query(
        db: SQLiteDatabase,
        selectedSnapshotsSql: String,
        selectionArgs: Array<String>,
    ): List<PlatoonSnapshot> = db.rawQuery(
        """
        SELECT selected.id, selected.captured_at, selected.source_file, selected.game_version,
               member.uid, member.name, member.level, member.weekly_merit,
               member.total_merit, member.high_score, member.total_score, member.last_login
        FROM ($selectedSnapshotsSql) AS selected
        LEFT JOIN snapshot_members AS member ON member.snapshot_id = selected.id
        ORDER BY selected.captured_at DESC, selected.id DESC,
                 member.name COLLATE NOCASE, member.uid
        """.trimIndent(),
        selectionArgs,
    ).use { cursor ->
        val snapshots = linkedMapOf<Long, SnapshotAccumulator>()
        while (cursor.moveToNext()) {
            val snapshotId = cursor.getLong(0)
            val snapshot = snapshots.getOrPut(snapshotId) {
                SnapshotAccumulator(
                    id = snapshotId,
                    capturedAt = Instant.ofEpochMilli(cursor.getLong(1)),
                    sourceFile = if (cursor.isNull(2)) null else cursor.getString(2),
                    gameVersion = if (cursor.isNull(3)) null else cursor.getString(3),
                )
            }
            if (!cursor.isNull(4)) {
                snapshot.members += SnapshotMember(
                    uid = cursor.getLong(4),
                    name = cursor.getString(5),
                    level = cursor.getLong(6),
                    weeklyMerit = cursor.getLong(7),
                    totalMerit = cursor.getLong(8),
                    highScore = cursor.getLong(9),
                    totalScore = cursor.getLong(10),
                    lastLogin = cursor.getLong(11),
                )
            }
        }
        snapshots.values.map(SnapshotAccumulator::toSnapshot)
    }

    private data class SnapshotAccumulator(
        val id: Long,
        val capturedAt: Instant,
        val sourceFile: String?,
        val gameVersion: String?,
        val members: MutableList<SnapshotMember> = mutableListOf(),
    ) {
        fun toSnapshot() = PlatoonSnapshot(
            id = id,
            capturedAt = capturedAt,
            sourceFile = sourceFile,
            gameVersion = gameVersion,
            members = members,
        )
    }
}
