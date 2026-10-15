package dev.gf2log.app.management

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Snapshot-derived evidence is weak: replay may rebuild it from retained rosters. */
internal val SNAPSHOT_EVENT_SOURCES = setOf(EvidenceSource.SNAPSHOT, EvidenceSource.LEGACY_IMPORT)

/** One side of a membership period with its column prefix and the events that record it. */
internal enum class MembershipBoundary(
    val prefix: String,
    val eventTypes: List<MemberEventType>,
) {
    JOIN("joined", listOf(MemberEventType.JOINED, MemberEventType.REJOINED)),
    WITHDRAW("left", listOf(MemberEventType.LEFT, MemberEventType.REMOVED)),
    ;

    val atColumn: String
        get() = "${prefix}_at"

    val precisionColumn: String
        get() = "${prefix}_precision"

    val sourceColumn: String
        get() = "${prefix}_source"

    val opposite: MembershipBoundary
        get() = if (this == JOIN) WITHDRAW else JOIN
}

/**
 * Commits only when [block] completes normally. An exception or a non-local `return` rolls the
 * transaction back; an early result that must commit returns through `return@inTransaction`.
 * A nested block that rolls back discards its whole outer transaction, so read-only early exits
 * also return through `return@inTransaction`.
 */
internal inline fun <T> SQLiteDatabase.inTransaction(block: () -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally {
        endTransaction()
    }
}

internal fun SQLiteDatabase.exists(sql: String, vararg arguments: Any): Boolean =
    rawQuery(sql, arguments.map(Any::toString).toTypedArray()).use(Cursor::moveToFirst)

internal fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun ContentValues.putMembershipBoundary(
    prefix: String,
    boundary: MembershipBoundaryValue,
) {
    put("${prefix}_at", boundary.instant.toEpochMilli())
    put("${prefix}_date", boundary.date.toEpochDay())
    put("${prefix}_time_known", if (boundary.timeKnown) 1 else 0)
}

internal fun ContentValues.clearMembershipBoundary(prefix: String) {
    putNull("${prefix}_at")
    putNull("${prefix}_date")
    putNull("${prefix}_time_known")
}

internal fun ContentValues.putNullableInt(key: String, value: Int?) {
    if (value == null) putNull(key) else put(key, value)
}

internal fun ContentValues.putNullableBoolean(key: String, value: Boolean?) {
    if (value == null) putNull(key) else put(key, if (value) 1 else 0)
}

internal fun Cursor.getNullableLong(index: Int): Long? =
    if (isNull(index)) null else getLong(index)

internal fun Cursor.getNullableInt(index: Int): Int? =
    if (isNull(index)) null else getInt(index)

internal fun Cursor.getNullableBoolean(index: Int): Boolean? =
    if (isNull(index)) null else getInt(index) != 0

internal fun Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

internal fun Cursor.getNullableInstant(index: Int): Instant? =
    getNullableLong(index)?.let(Instant::ofEpochMilli)

internal fun Cursor.membershipBoundaryValue(
    instantIndex: Int,
    dateIndex: Int,
    timeKnownIndex: Int,
    zoneId: ZoneId,
): MembershipBoundaryValue? {
    val instant = getNullableInstant(instantIndex) ?: return null
    val date = getNullableLong(dateIndex)?.let(LocalDate::ofEpochDay)
        ?: instant.atZone(zoneId).toLocalDate()
    return MembershipBoundaryValue(
        date = date,
        instant = instant,
        timeKnown = getNullableInt(timeKnownIndex)?.let { it != 0 } ?: true,
    )
}
