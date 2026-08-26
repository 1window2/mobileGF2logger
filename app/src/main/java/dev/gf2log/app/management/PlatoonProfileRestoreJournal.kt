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
                output.writeLong(profile.bannerFrameId)
                output.writeLong(profile.bannerMarkId)
                output.writeLong(profile.lastSeenAt.toEpochMilli())
                output.writeBoolean(profile.legacy)
            }
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): PlatoonProfileRestoreJournal =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt()
            require(version in setOf(LEGACY_VERSION, VERSION)) {
                "Unsupported profile restore journal"
            }
            val targetStorageId = input.readUTF()
            require(PlatoonProfileIdentity.isValidStorageId(targetStorageId))
            val previousActive = input.readNullable()?.also {
                require(PlatoonProfileIdentity.isValidStorageId(it))
            }
            val ownerPackage = input.readNullable()
            val previousRegion = input.readNullable()?.let(GameServerRegion::fromStored)
            val previousProfile = if (input.readBoolean()) {
                val storageId = input.readUTF()
                val client = PlatoonClient.valueOf(input.readUTF())
                val serverRegion = GameServerRegion.fromStored(input.readUTF())
                val platoonId = input.readLong()
                val platoonName = input.readUTF()
                val bannerFrameId: Long
                val bannerMarkId: Long
                if (version == LEGACY_VERSION) {
                    input.readLegacyLongList()
                    input.readLegacyLongList()
                    bannerFrameId = 0L
                    bannerMarkId = 0L
                } else {
                    bannerFrameId = input.readLong()
                    bannerMarkId = input.readLong()
                }
                PlatoonProfile(
                    storageId = storageId,
                    client = client,
                    serverRegion = serverRegion,
                    platoonId = platoonId,
                    platoonName = platoonName,
                    bannerFrameId = bannerFrameId,
                    bannerMarkId = bannerMarkId,
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

    private fun DataInputStream.readLegacyLongList() {
        val size = readInt()
        require(size in 0..LEGACY_MAX_EMBLEM_PARTS)
        repeat(size) { readLong() }
    }

    private const val LEGACY_VERSION = 1
    private const val VERSION = 2
    private const val LEGACY_MAX_EMBLEM_PARTS = 32
}
