package dev.gf2log.app.management

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded, versioned representation of one immutable weekly projection. */
internal object WeeklyReportHistoryCodec {
    data class Encoded(val payload: ByteArray, val fingerprint: String)

    fun encode(report: WeeklyReportBuilder.Report): Encoded {
        require(report.days.size == DAYS_PER_WEEK)
        require(report.members.size <= MAX_MEMBERS)
        val raw = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeLong(report.periodStart.toEpochDay())
                output.writeLong(report.periodEnd.toEpochDay())
                output.writeBoolean(report.isGunsmokeWeek)
                output.writeInt(report.days.size)
                report.days.forEach { output.writeLong(it.toEpochDay()) }
                output.writeInt(report.members.size)
                report.members.forEach { output.writeMember(it) }
            }
            buffer.toByteArray()
        }
        val payload = ByteArrayOutputStream().use { compressed ->
            GZIPOutputStream(compressed).use { it.write(raw) }
            compressed.toByteArray()
        }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Weekly history payload is too large" }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(raw)
            .joinToString("") { "%02x".format(it) }
        return Encoded(payload, fingerprint)
    }

    fun decode(payload: ByteArray): WeeklyReportBuilder.Report {
        require(payload.size in 1..MAX_PAYLOAD_BYTES) { "Invalid weekly history payload size" }
        val raw = GZIPInputStream(ByteArrayInputStream(payload)).use { compressed ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            var total = 0
            while (true) {
                val read = compressed.read(buffer)
                if (read == -1) break
                total += read
                require(total <= MAX_DECOMPRESSED_BYTES) {
                    "Weekly history payload expands beyond its limit"
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt() == MAGIC) { "Invalid weekly history header" }
            require(input.readInt() == FORMAT_VERSION) { "Unsupported weekly history format" }
            val periodStart = LocalDate.ofEpochDay(input.readLong())
            val periodEnd = LocalDate.ofEpochDay(input.readLong())
            val gunsmoke = input.readBoolean()
            val dayCount = input.readBoundedCount(DAYS_PER_WEEK)
            require(dayCount == DAYS_PER_WEEK)
            val days = List(dayCount) { LocalDate.ofEpochDay(input.readLong()) }
            require(days == List(DAYS_PER_WEEK) { periodStart.plusDays(it.toLong()) })
            val memberCount = input.readBoundedCount(MAX_MEMBERS)
            val members = List(memberCount) { input.readMember(gunsmoke, periodStart) }
            require(input.read() == -1) { "Trailing weekly history data" }
            WeeklyReportBuilder.Report(periodStart, periodEnd, gunsmoke, days, members)
        }
    }

    private fun DataOutputStream.writeMember(member: WeeklyReportBuilder.MemberRow) {
        writeLong(member.uid)
        writeBoundedString(member.name)
        writeLong(member.totalMerit)
        writeLong(member.totalScore)
        writeBoolean(member.isGunsmokeWeek)
        writeBoolean(member.hasFinalGunsmokeScore)
        writeNullableInt(member.gunsmokeAttemptsFloor)
        writeGunsmokeTotals(member.resolvedGunsmokeTotals)
        writeStandardTotals(member.resolvedStandardTotals)
        require(member.days.size == DAYS_PER_WEEK)
        writeInt(member.days.size)
        member.days.forEach { writeCell(it) }
    }

    private fun DataInputStream.readMember(
        reportIsGunsmoke: Boolean,
        periodStart: LocalDate,
    ): WeeklyReportBuilder.MemberRow {
        val uid = readLong()
        val name = readBoundedString()
        val totalMerit = readLong()
        val totalScore = readLong()
        val gunsmoke = readBoolean()
        require(gunsmoke == reportIsGunsmoke)
        val hasFinal = readBoolean()
        val attemptsFloor = readNullableInt()
        val gunsmokeTotals = readGunsmokeTotals()
        val standardTotals = readStandardTotals()
        val dayCount = readBoundedCount(DAYS_PER_WEEK)
        require(dayCount == DAYS_PER_WEEK)
        val cells = List(dayCount) { index -> readCell(periodStart.plusDays(index.toLong()), gunsmoke) }
        return WeeklyReportBuilder.MemberRow(
            uid = uid,
            name = name,
            days = cells,
            totalMerit = totalMerit,
            totalScore = totalScore,
            isGunsmokeWeek = gunsmoke,
            hasFinalGunsmokeScore = hasFinal,
            resolvedGunsmokeTotals = gunsmokeTotals,
            resolvedStandardTotals = standardTotals,
            gunsmokeAttemptsFloor = attemptsFloor,
        )
    }

    private fun DataOutputStream.writeCell(cell: WeeklyReportBuilder.DayCell) {
        writeLong(cell.gameDay.toEpochDay())
        writeNullableLong(cell.meritDelta)
        writeNullableLong(cell.scoreDelta)
        writeInt(cell.evidence.ordinal)
        writeBoolean(cell.hasDailyPatrolFact)
        writeBoolean(cell.hasLoginFact)
        writeBoolean(cell.hasFinalGunsmokeScore)
        writeBoolean(cell.isGunsmokeWeek)
        writeNullableLong(cell.metricObservedAt?.toEpochMilli())
        writeBoolean(cell.hasClosingBoundary)
        writeNullableInt(cell.attempts)
        writeInt(cell.meritCertainty.ordinal)
        writeInt(cell.scoreCertainty.ordinal)
        writeInt(cell.attemptsCertainty.ordinal)
        writeNullableBoolean(cell.attended)
        writeNullableBoolean(cell.dailyPatrol)
    }

    private fun DataInputStream.readCell(
        expectedDay: LocalDate,
        reportIsGunsmoke: Boolean,
    ): WeeklyReportBuilder.DayCell {
        val day = LocalDate.ofEpochDay(readLong())
        require(day == expectedDay)
        val merit = readNullableLong()
        val score = readNullableLong()
        val evidence = readEnum<DailyEvidence>()
        val patrolFact = readBoolean()
        val loginFact = readBoolean()
        val finalScore = readBoolean()
        val gunsmoke = readBoolean()
        require(gunsmoke == reportIsGunsmoke)
        val observedAt = readNullableLong()?.let(Instant::ofEpochMilli)
        val closing = readBoolean()
        val attempts = readNullableInt()
        val meritCertainty = readEnum<MetricCertainty>()
        val scoreCertainty = readEnum<MetricCertainty>()
        val attemptsCertainty = readEnum<MetricCertainty>()
        val attended = readNullableBoolean()
        val patrol = readNullableBoolean()
        return WeeklyReportBuilder.DayCell(
            gameDay = day,
            meritDelta = merit,
            scoreDelta = score,
            inference = if (merit != null && score != null) {
                ActivityInference.infer(merit, score, gunsmoke)
            } else {
                null
            },
            evidence = evidence,
            hasDailyPatrolFact = patrolFact,
            hasLoginFact = loginFact,
            hasFinalGunsmokeScore = finalScore,
            isGunsmokeWeek = gunsmoke,
            metricObservedAt = observedAt,
            hasClosingBoundary = closing,
            solvedAttempts = attempts,
            solvedMeritCertainty = meritCertainty,
            solvedScoreCertainty = scoreCertainty,
            solvedAttemptsCertainty = attemptsCertainty,
            solvedAttended = attended,
            solvedDailyPatrol = patrol,
        )
    }

    private fun DataOutputStream.writeGunsmokeTotals(
        value: WeeklyReportBuilder.ResolvedGunsmokeTotals?,
    ) {
        writeBoolean(value != null)
        if (value == null) return
        writeLong(value.merit)
        writeInt(value.meritCertainty.ordinal)
        writeInt(value.attempts)
        writeInt(value.attemptsCertainty.ordinal)
        writeInt(value.loginDays)
        writeInt(value.loginDaysCertainty.ordinal)
        writeInt(value.patrolDays)
        writeInt(value.patrolDaysCertainty.ordinal)
    }

    private fun DataInputStream.readGunsmokeTotals(): WeeklyReportBuilder.ResolvedGunsmokeTotals? {
        if (!readBoolean()) return null
        return WeeklyReportBuilder.ResolvedGunsmokeTotals(
            merit = readLong(),
            meritCertainty = readEnum(),
            attempts = readInt(),
            attemptsCertainty = readEnum(),
            loginDays = readInt(),
            loginDaysCertainty = readEnum(),
            patrolDays = readInt(),
            patrolDaysCertainty = readEnum(),
        )
    }

    private fun DataOutputStream.writeStandardTotals(
        value: WeeklyReportBuilder.ResolvedStandardTotals?,
    ) {
        writeBoolean(value != null)
        if (value == null) return
        writeLong(value.merit)
        writeInt(value.meritCertainty.ordinal)
        writeInt(value.loginDays)
        writeInt(value.loginDaysCertainty.ordinal)
        writeInt(value.patrolDays)
        writeInt(value.patrolDaysCertainty.ordinal)
    }

    private fun DataInputStream.readStandardTotals(): WeeklyReportBuilder.ResolvedStandardTotals? {
        if (!readBoolean()) return null
        return WeeklyReportBuilder.ResolvedStandardTotals(
            merit = readLong(),
            meritCertainty = readEnum(),
            loginDays = readInt(),
            loginDaysCertainty = readEnum(),
            patrolDays = readInt(),
            patrolDaysCertainty = readEnum(),
        )
    }

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedString(): String {
        val size = readBoundedCount(MAX_STRING_BYTES)
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null

    private fun DataOutputStream.writeNullableInt(value: Int?) {
        writeBoolean(value != null)
        if (value != null) writeInt(value)
    }

    private fun DataInputStream.readNullableInt(): Int? =
        if (readBoolean()) readInt() else null

    private fun DataOutputStream.writeNullableBoolean(value: Boolean?) = writeByte(
        when (value) {
            null -> -1
            false -> 0
            true -> 1
        },
    )

    private fun DataInputStream.readNullableBoolean(): Boolean? = when (val value = readByte().toInt()) {
        -1 -> null
        0 -> false
        1 -> true
        else -> error("Invalid nullable boolean $value")
    }

    private fun DataInputStream.readBoundedCount(maximum: Int): Int =
        readInt().also { require(it in 0..maximum) }

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val values = enumValues<T>()
        return values.getOrNull(readInt()) ?: error("Invalid ${T::class.java.simpleName}")
    }

    private const val MAGIC = 0x47463248
    private const val FORMAT_VERSION = 1
    private const val DAYS_PER_WEEK = 7
    private const val MAX_MEMBERS = 256
    private const val MAX_STRING_BYTES = 1_024
    private const val MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024
    private const val STREAM_BUFFER_BYTES = 8 * 1024
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024
}

data class WeeklyReportHistoryEntry(
    val id: Long,
    val periodStart: LocalDate,
    val recordedAt: Instant,
    val active: Boolean,
)
