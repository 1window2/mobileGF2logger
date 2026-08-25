package dev.gf2log.app.management

import dev.gf2log.app.settings.GameServerRegion
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant

/** Durable rollback state for profile metadata changed by a scoped backup restore. */
internal data class PlatoonProfileRestoreJournal(
    val targetStorageId: String,
    val previousProfile: PlatoonProfile?,
    val previousActiveStorageId: String?,
    val ownerPackage: String?,
    val previousCaptureRegion: GameServerRegion?,
)

internal object PlatoonProfileRestoreJournalCodec {
    fun encode(value: PlatoonProfileRestoreJournal): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(VERSION)
            output.writeUTF(value.targetStorageId)
            output.writeNullable(value.previousActiveStorageId)
            output.writeNullable(value.ownerPackage)
            output.writeNullable(value.previousCaptureRegion?.storedValue)
            output.writeBoolean(value.previousProfile != null)
            value.previousProfile?.let { profile ->
                output.writeUTF(profile.storageId)
                output.writeUTF(profile.client.name)
                output.writeUTF(profile.serverRegion.storedValue)
                output.writeLong(profile.platoonId)
                output.writeUTF(profile.platoonName)
                output.writeLongList(profile.emblemPrimary)
                output.writeLongList(profile.emblemSecondary)
                output.writeLong(profile.lastSeenAt.toEpochMilli())
                output.writeBoolean(profile.legacy)
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PlatoonProfileRestoreJournal =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported profile restore journal" }
            val targetStorageId = input.readUTF()
            require(PlatoonProfileIdentity.isValidStorageId(targetStorageId))
            val previousActive = input.readNullable()?.also {
                require(PlatoonProfileIdentity.isValidStorageId(it))
            }
            val ownerPackage = input.readNullable()
            val previousRegion = input.readNullable()?.let(GameServerRegion::fromStored)
            val previousProfile = if (input.readBoolean()) {
                PlatoonProfile(
                    storageId = input.readUTF(),
                    client = PlatoonClient.valueOf(input.readUTF()),
                    serverRegion = GameServerRegion.fromStored(input.readUTF()),
                    platoonId = input.readLong(),
                    platoonName = input.readUTF(),
                    emblemPrimary = input.readLongList(),
                    emblemSecondary = input.readLongList(),
                    lastSeenAt = Instant.ofEpochMilli(input.readLong()),
                    legacy = input.readBoolean(),
                )
            } else {
                null
            }
            require(input.read() == -1) { "Profile restore journal contains trailing data" }
            PlatoonProfileRestoreJournal(
                targetStorageId,
                previousProfile,
                previousActive,
                ownerPackage,
                previousRegion,
            )
        }

    private fun DataOutputStream.writeNullable(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeUTF(value)
    }

    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readUTF() else null

    private fun DataOutputStream.writeLongList(values: List<Long>) {
        writeInt(values.size)
        values.forEach(::writeLong)
    }

    private fun DataInputStream.readLongList(): List<Long> {
        val size = readInt()
        require(size in 0..PlatoonProfile.MAX_EMBLEM_PARTS)
        return List(size) { readLong() }
    }

    private const val VERSION = 1
}
