package dev.gf2log.app.management

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Resolves the standard-week merit counter independently from report assembly.
 * It exposes only values shared by every compatible daily allocation and keeps
 * ambiguous closed days unknown.
 */
object StandardWeekSolver {
    fun resolve(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<WeeklyReportBuilder.DayCell>,
        asOf: Instant,
    ): List<WeeklyReportBuilder.DayCell> {
        val mutable = inferSundayMerit(
            uid = uid,
            days = days,
            zoneId = zoneId,
            snapshots = snapshots,
            cells = cells,
        ).toMutableList()
        val observations = snapshots.mapNotNull { snapshot ->
            snapshot.member(uid)?.let { member ->
                CounterObservation(
                    capturedAt = snapshot.capturedAt,
                    gameDay = PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId),
                    counter = member.weeklyMerit,
                    lastLoginDay = member.lastLogin
                        .takeIf { it > 0L }
                        ?.let(Instant::ofEpochSecond)
                        ?.let { PlatoonPeriods.gameDay(it, zoneId) },
                )
            }
        }
        val monday = days.first().plusDays(1)
        val observationsInCounterWeek = observations.filter { it.gameDay in monday..days.last() }
        val latest = observationsInCounterWeek.maxByOrNull(CounterObservation::capturedAt)
            ?: return mutable
        val latestDay = latest.gameDay
        val firstObserved = observationsInCounterWeek.minBy(CounterObservation::capturedAt)
        val knownAbsentBeforeFirstObservation = snapshots.any { snapshot ->
            val gameDay = PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId)
            !gameDay.isBefore(monday) &&
                gameDay.isBefore(firstObserved.gameDay) &&
                snapshot.capturedAt.isBefore(firstObserved.capturedAt) &&
                snapshot.member(uid) == null
        }
        val activeStart = if (knownAbsentBeforeFirstObservation) firstObserved.gameDay else monday
        val activeIndexes = days.indices.filter { index -> days[index] in activeStart..latestDay }
        if (activeIndexes.isEmpty()) return mutable

        val confirmedNoLoginDays = activeIndexes.mapNotNull { index ->
            val day = days[index]
            val start = PlatoonPeriods.periodStartInstant(day, zoneId)
            val end = PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId)
            day.takeIf {
                !end.isAfter(asOf) &&
                    snapshots.any { snapshot ->
                        !snapshot.capturedAt.isBefore(end) &&
                            snapshot.member(uid)?.lastLogin?.takeIf { it > 0L }?.let {
                                Instant.ofEpochSecond(it).isBefore(start)
                            } == true
                    }
            }
        }.toSet()
        val compatible = compatibleAllocations(
            indexes = activeIndexes,
            days = days,
            cells = mutable,
            observations = observationsInCounterWeek,
            confirmedNoLoginDays = confirmedNoLoginDays,
        )
        if (compatible.isEmpty()) return mutable

        val selected = compatible.minWith(
            compareBy<List<Long>> { it.sum() }
                .thenComparator { left, right ->
                    left.indices.firstNotNullOfOrNull { index ->
                        java.lang.Long.compare(right[index], left[index]).takeIf { it != 0 }
                    } ?: 0
                },
        )
        activeIndexes.forEachIndexed { position, index ->
            val existing = mutable[index]
            if (existing.manualOverride != null) return@forEachIndexed
            val selectedMerit = selected[position]
            val exactAcrossCandidates = compatible.all { it[position] == selectedMerit }
            val day = days[index]
            val dayClosed = !PlatoonPeriods.periodStartInstant(day.plusDays(1), zoneId).isAfter(asOf)
            val latestCheckpoint = observationsInCounterWeek
                .filter { it.gameDay == day }
                .maxByOrNull(CounterObservation::capturedAt)
            val stageValues = latestCheckpoint?.let { checkpoint ->
                compatible.mapNotNull { allocation ->
                    checkpointStage(
                        allocation = allocation,
                        indexes = activeIndexes,
                        days = days,
                        checkpoint = checkpoint,
                    )
                }
            }.orEmpty()
            val currentLowerBound = stageValues.minOrNull()?.takeIf { it > 0L }
            val openLatestDay = day == latestDay && !dayClosed
            val merit = when {
                existing.hasDailyPatrolFact -> MAX_DAILY_MERIT
                openLatestDay -> currentLowerBound
                exactAcrossCandidates -> selectedMerit
                else -> null
            }
            val evidence = when {
                existing.hasDailyPatrolFact -> DailyEvidence.ATTRIBUTED
                openLatestDay && currentLowerBound == MAX_DAILY_MERIT -> DailyEvidence.ATTRIBUTED
                openLatestDay -> DailyEvidence.PARTIAL_DAY
                exactAcrossCandidates -> DailyEvidence.ATTRIBUTED
                else -> DailyEvidence.SPARSE_INFERRED
            }
            mutable[index] = existing.copy(
                meritDelta = merit,
                scoreDelta = 0L,
                inference = merit?.let {
                    ActivityInference.infer(
                        meritDelta = it,
                        scoreDelta = 0L,
                        gunsmokeActive = false,
                    )
                },
                evidence = evidence,
            )
        }
        return mutable
    }

    private fun inferSundayMerit(
        uid: Long,
        days: List<LocalDate>,
        zoneId: ZoneId,
        snapshots: List<PlatoonSnapshot>,
        cells: List<WeeklyReportBuilder.DayCell>,
    ): List<WeeklyReportBuilder.DayCell> {
        val sundayCell = cells.first()
        if (sundayCell.manualOverride != null || sundayCell.evidence == DailyEvidence.ATTRIBUTED) {
            return cells
        }
        val monday = days.first().plusDays(1)
        val mondayStart = PlatoonPeriods.periodStartInstant(monday, zoneId)
        val sundayObservation = snapshots.lastOrNull { snapshot ->
            snapshot.capturedAt.isBefore(mondayStart) &&
                PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId) == days.first() &&
                snapshot.member(uid) != null
        } ?: return cells
        val laterObservation = snapshots.firstOrNull { snapshot ->
            !snapshot.capturedAt.isBefore(mondayStart) &&
                PlatoonPeriods.gameDay(snapshot.capturedAt, zoneId) in monday..days.last() &&
                snapshot.member(uid) != null
        } ?: return cells
        val sundayMember = requireNotNull(sundayObservation.member(uid))
        val laterMember = requireNotNull(laterObservation.member(uid))
        val remainingSundayMerit = laterMember.totalMerit -
            sundayMember.totalMerit -
            laterMember.weeklyMerit
        if (remainingSundayMerit !in DAILY_STAGE) return cells

        val observedSundayPrefix = sundayCell.meritDelta ?: return cells
        val merit = observedSundayPrefix + remainingSundayMerit
        if (merit !in DAILY_STAGE) return cells
        return cells.toMutableList().apply {
            this[0] = sundayCell.copy(
                meritDelta = merit,
                scoreDelta = 0L,
                inference = ActivityInference.infer(
                    meritDelta = merit,
                    scoreDelta = 0L,
                    gunsmokeActive = false,
                ),
                evidence = DailyEvidence.ATTRIBUTED,
            )
        }
    }

    private fun compatibleAllocations(
        indexes: List<Int>,
        days: List<LocalDate>,
        cells: List<WeeklyReportBuilder.DayCell>,
        observations: List<CounterObservation>,
        confirmedNoLoginDays: Set<LocalDate>,
    ): List<List<Long>> {
        val results = mutableListOf<List<Long>>()
        val candidate = mutableListOf<Long>()

        fun search(position: Int) {
            if (position == indexes.size) {
                if (observations.all { checkpointStage(candidate, indexes, days, it) != null }) {
                    results += candidate.toList()
                }
                return
            }
            val index = indexes[position]
            val cell = cells[index]
            val fixed = cell.manualOverride?.meritDelta
                ?: PATROL_MERIT.takeIf { cell.hasDailyPatrolFact }
                ?: 0L.takeIf { days[index] in confirmedNoLoginDays }
            val options = when {
                fixed != null -> longArrayOf(fixed)
                cell.hasLoginFact -> longArrayOf(MAX_DAILY_MERIT, LOGIN_MERIT)
                else -> DAILY_MERIT
            }
            options.forEach { merit ->
                candidate += merit
                val partialIsCompatible = observations
                    .filter { observation ->
                        val observationIndex = days.indexOf(observation.gameDay)
                        observationIndex >= 0 && observationIndex <= index
                    }
                    .all { observation ->
                        val observationIndex = days.indexOf(observation.gameDay)
                        observationIndex > index || checkpointStage(
                            candidate,
                            indexes.take(candidate.size),
                            days,
                            observation,
                        ) != null
                    }
                if (partialIsCompatible) search(position + 1)
                candidate.removeAt(candidate.lastIndex)
            }
        }

        search(position = 0)
        return results
    }

    private fun checkpointStage(
        allocation: List<Long>,
        indexes: List<Int>,
        days: List<LocalDate>,
        checkpoint: CounterObservation,
    ): Long? {
        val checkpointIndex = days.indexOf(checkpoint.gameDay)
        val position = indexes.indexOf(checkpointIndex)
        if (position !in allocation.indices) return null
        val completedPrefix = allocation.take(position).sum()
        val stage = checkpoint.counter - completedPrefix
        val finalForDay = allocation[position]
        if (stage !in DAILY_STAGE || stage > finalForDay) return null
        val loginStageCompatible = when {
            checkpoint.lastLoginDay == checkpoint.gameDay ->
                stage == LOGIN_MERIT || stage == PATROL_MERIT
            checkpoint.lastLoginDay != null &&
                checkpoint.lastLoginDay.isBefore(checkpoint.gameDay) -> stage == 0L
            else -> true
        }
        return stage.takeIf { loginStageCompatible }
    }

    private fun PlatoonSnapshot.member(uid: Long): SnapshotMember? =
        members.firstOrNull { it.uid == uid }

    private data class CounterObservation(
        val capturedAt: Instant,
        val gameDay: LocalDate,
        val counter: Long,
        val lastLoginDay: LocalDate?,
    )

    private const val MAX_DAILY_MERIT = 90L
    private const val LOGIN_MERIT = 50L
    private const val PATROL_MERIT = 90L
    private val DAILY_MERIT = longArrayOf(90L, 50L, 0L)
    private val DAILY_STAGE = setOf(0L, 50L, 90L)
}
