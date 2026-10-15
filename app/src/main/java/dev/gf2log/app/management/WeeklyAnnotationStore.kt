package dev.gf2log.app.management

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.time.LocalDate

/** Weekly notes and manual cell overrides. */
internal object WeeklyAnnotationStore {
    fun addWeeklyNote(
        db: SQLiteDatabase,
        periodStartEpochDay: Long,
        gameDayEpochDay: Long,
        text: String,
    ): Long {
        require(text.isNotBlank())
        val manualNoteCount = db.rawQuery(
            "SELECT COUNT(*) FROM weekly_notes WHERE period_start = ? AND is_automatic = 0",
            arrayOf(periodStartEpochDay.toString()),
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }
        if (!WeeklyNotePolicy.canAdd(manualNoteCount)) throw WeeklyNoteLimitException()
        return db.insertOrThrow(
            "weekly_notes",
            null,
            ContentValues().apply {
                put("period_start", periodStartEpochDay)
                put("game_day", gameDayEpochDay)
                put("text", text.trim())
                putNull("event_id")
                put("is_automatic", 0)
            },
        )
    }

    fun listWeeklyNotes(db: SQLiteDatabase, periodStartEpochDay: Long): List<WeeklyNote> =
        db.query(
            "weekly_notes",
            WEEKLY_NOTE_COLUMNS,
            "period_start = ?",
            arrayOf(periodStartEpochDay.toString()),
            null,
            null,
            "game_day, id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WeeklyNote(
                            id = cursor.getLong(0),
                            periodStart = LocalDate.ofEpochDay(cursor.getLong(1)),
                            gameDay = LocalDate.ofEpochDay(cursor.getLong(2)),
                            text = cursor.getString(3),
                            eventId = cursor.getNullableLong(4),
                            isAutomatic = cursor.getInt(5) != 0,
                        ),
                    )
                }
            }
        }

    fun weeklyNotePeriodStart(db: SQLiteDatabase, id: Long): LocalDate? = db.rawQuery(
        "SELECT period_start FROM weekly_notes WHERE id = ? AND is_automatic = 0",
        arrayOf(id.toString()),
    ).use { cursor ->
        if (cursor.moveToFirst()) LocalDate.ofEpochDay(cursor.getLong(0)) else null
    }

    fun notePeriodsCitingMember(db: SQLiteDatabase, uid: Long): Set<LocalDate> = db.rawQuery(
        "SELECT DISTINCT period_start FROM weekly_notes WHERE is_automatic = 0 AND event_id IN " +
            "(SELECT id FROM member_events WHERE uid = ?)",
        arrayOf(uid.toString()),
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(LocalDate.ofEpochDay(cursor.getLong(0))) }
    }

    fun deleteWeeklyNote(db: SQLiteDatabase, id: Long): Boolean =
        db.delete(
            "weekly_notes",
            "id = ? AND is_automatic = 0",
            arrayOf(id.toString()),
        ) == 1

    fun listWeeklyOverrides(db: SQLiteDatabase, periodStartEpochDay: Long): List<WeeklyCellOverride> =
        db.query(
            "weekly_overrides",
            WEEKLY_OVERRIDE_COLUMNS,
            "period_start = ?",
            arrayOf(periodStartEpochDay.toString()),
            null,
            null,
            "game_day, uid",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        WeeklyCellOverride(
                            uid = cursor.getLong(0),
                            periodStart = LocalDate.ofEpochDay(cursor.getLong(1)),
                            gameDay = LocalDate.ofEpochDay(cursor.getLong(2)),
                            meritDelta = cursor.getNullableLong(3),
                            scoreDelta = cursor.getNullableLong(4),
                            attempts = cursor.getNullableInt(5),
                            attended = cursor.getNullableBoolean(6),
                            dailyPatrol = cursor.getNullableBoolean(7),
                        ),
                    )
                }
            }
        }

    fun replaceWeeklyOverrides(
        db: SQLiteDatabase,
        periodStartEpochDay: Long,
        overrides: List<WeeklyCellOverride>,
    ) {
        require(overrides.all { it.periodStart.toEpochDay() == periodStartEpochDay })
        require(
            overrides.all {
                it.attempts == null || it.attempts in 0..ActivityInference.MAX_DAILY_ATTEMPTS
            },
        )
        require(overrides.none { it.attended == false && it.dailyPatrol == true })
        db.inTransaction {
            db.delete(
                "weekly_overrides",
                "period_start = ?",
                arrayOf(periodStartEpochDay.toString()),
            )
            overrides.forEach { override ->
                db.insertOrThrow(
                    "weekly_overrides",
                    null,
                    ContentValues().apply {
                        put("uid", override.uid)
                        put("period_start", periodStartEpochDay)
                        put("game_day", override.gameDay.toEpochDay())
                        putNullableLong("merit_delta", override.meritDelta)
                        putNullableLong("score_delta", override.scoreDelta)
                        putNullableInt("attempts", override.attempts)
                        putNullableBoolean("attended", override.attended)
                        putNullableBoolean("daily_patrol", override.dailyPatrol)
                    },
                )
            }
        }
    }

    private val WEEKLY_NOTE_COLUMNS = arrayOf(
        "id",
        "period_start",
        "game_day",
        "text",
        "event_id",
        "is_automatic",
    )
    private val WEEKLY_OVERRIDE_COLUMNS = arrayOf(
        "uid",
        "period_start",
        "game_day",
        "merit_delta",
        "score_delta",
        "attempts",
        "attended",
        "daily_patrol",
    )
}
