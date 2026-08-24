package dev.gf2log.app.capture

import android.content.Context
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.protocol.model.ParsedPayload
import java.time.Instant

/** Owns all mutable capture-to-management state for one identified TCP flow. */
internal class PlatoonCaptureSession(
    context: Context,
    val profile: PlatoonProfile,
    private val onRosterCaptured: (String) -> Unit,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val scope = PlatoonStorageScope(profile.storageId)
    private val repository = PlatoonRepository(context, scope)
    private val writer = GuildMembersCsvWriter(
        scope.retainedCsvDirectory(context),
        onBatchClosed = { batch -> ingestCompletedRoster(batch) },
    )
    private val dispatcher = PlatoonPayloadDispatcher(
        onMembers = writer::accept,
        onActivity = { repository.ingestActivity(it).acceptedObservations > 0 },
        onUpdates = { repository.ingestUpdates(it).acceptedObservations > 0 },
    )

    fun dispatch(payload: ParsedPayload, flowEnded: Boolean = false): PlatoonPayloadDispatcher.Results =
        dispatcher.dispatch(payload, flowEnded)

    override fun close() = writer.close()

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
                onStatus(
                    "Updated ${profile.platoonName}: +${result.joined + result.rejoined}, " +
                        "-${result.left}",
                )
            }
            onRosterCaptured(profile.storageId)
            return
        }

        val recovery = runCatching {
            require(batch.file.isFile) { "Completed roster CSV was not published" }
            repository.reconcileRetainedCsvFiles(requireNotNull(batch.file.parentFile))
            check(repository.hasSnapshotSource(batch.file.name)) {
                "Completed roster CSV was not reconciled"
            }
        }
        if (recovery.isFailure) {
            throw directResult.exceptionOrNull()
                ?: recovery.exceptionOrNull()
                ?: IllegalStateException("Roster ingestion and recovery both failed")
        }
        onStatus("Recovered ${profile.platoonName} from the completed roster CSV")
        onRosterCaptured(profile.storageId)
    }
}
