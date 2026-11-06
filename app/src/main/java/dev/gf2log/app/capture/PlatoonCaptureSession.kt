package dev.gf2log.app.capture

import android.content.Context
import dev.gf2log.app.management.PlatoonAccessFailure
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.protocol.model.ParsedPayload
import java.time.Instant

/** Routes one identified TCP flow's payloads into its verified Platoon scope. */
internal class PlatoonCaptureSession(
    context: Context,
    val profile: PlatoonProfile,
    private val onRosterCaptured: (String) -> Unit,
) {
    private val scope = PlatoonStorageScope(profile.storageId)
    private val repository = PlatoonRepository(context, scope)
    private val writer = GuildMembersCsvWriter(
        scope.retainedCsvDirectory(context),
        onBatchClosed = { batch -> ingestCompletedRoster(batch) },
    )
    private val dispatcher = PlatoonPayloadDispatcher(
        onMembers = writer::accept,
        onActivity = { data, receivedAt -> repository.ingestActivity(data, receivedAt).acceptedObservations > 0 },
        onUpdates = { data, receivedAt -> repository.ingestUpdates(data, receivedAt).acceptedObservations > 0 },
    )

    /** [receivedAt] dates what [payload] records, even when it was held until identity was known. */
    fun dispatch(payload: ParsedPayload, receivedAt: Instant): PlatoonPayloadDispatcher.Results =
        dispatcher.dispatch(payload, receivedAt)

    private fun ingestCompletedRoster(batch: GuildMembersCsvWriter.CompletedBatch) {
        val directResult = runCatching {
            repository.ingest(
                capturedAt = Instant.parse(batch.logTime),
                members = batch.members,
                sourceFile = batch.file.name,
            )
        }
        if (directResult.isSuccess) {
            val result = directResult.getOrThrow()
            if (!result.duplicate) {
                CaptureStatus.report(
                    CaptureNotice.ROSTER_UPDATED,
                    profile.platoonName,
                    (result.joined + result.rejoined).toString(),
                    result.left.toString(),
                )
            }
            onRosterCaptured(profile.storageId)
            return
        }

        val directFailure = directResult.exceptionOrNull()
            ?: IllegalStateException("Roster ingestion failed")
        // The published CSV stays retained either way, so a later reconcile still ingests it.
        if (!recoversByReconcile(directFailure)) throw directFailure
        val recovery = runCatching {
            require(batch.file.isFile) { "Completed roster CSV was not published" }
            repository.reconcileRetainedCsvFiles(requireNotNull(batch.file.parentFile))
            check(repository.hasSnapshotSource(batch.file.name)) {
                "Completed roster CSV was not reconciled"
            }
        }
        recovery.exceptionOrNull()?.let { recoveryFailure ->
            // Damage met only by the recovery is still what the notice must name.
            throw if (recoversByReconcile(recoveryFailure)) {
                directFailure.apply { addSuppressed(recoveryFailure) }
            } else {
                recoveryFailure.apply { addSuppressed(directFailure) }
            }
        }
        CaptureStatus.report(CaptureNotice.ROSTER_RECOVERED, profile.platoonName)
        onRosterCaptured(profile.storageId)
    }

    internal companion object {
        /**
         * Whether a failed ingest may be recovered by reconciling the retained CSVs. Never for a
         * damaged database, which a reconcile would rebuild as roster-only data reported as
         * recovered, nor while a CSV import holds the scope and refuses the reconcile as well.
         */
        fun recoversByReconcile(failure: Throwable): Boolean =
            PlatoonAccessFailure.of(failure) == PlatoonAccessFailure.OTHER

        /**
         * The notice for a failed save into [platoonName]'s scope: damage is named, since every
         * later save fails the same way until a restore or rebuild; anything else keeps [fallback].
         */
        fun saveFailureNotice(
            failure: Throwable,
            platoonName: String,
            fallback: CaptureNotice,
        ): Pair<CaptureNotice, List<String>> =
            if (PlatoonAccessFailure.of(failure) == PlatoonAccessFailure.DATABASE_DAMAGED) {
                CaptureNotice.DATABASE_DAMAGED to listOf(platoonName)
            } else {
                fallback to emptyList()
            }
    }
}
