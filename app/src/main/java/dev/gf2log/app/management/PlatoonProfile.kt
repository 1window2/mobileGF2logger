package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant

/** Publisher identity resolved from Android's original VPN flow ownership. */
internal enum class PlatoonClient(val packageName: String, val displayName: String) {
    HAOPLAY(SupportedGamePackages.HAOPLAY, "HaoPlay"),
    DARKWINTER(SupportedGamePackages.DARKWINTER, "Darkwinter"),
    LEGACY("legacy", "Existing data"),
    ;

    companion object {
        fun fromPackage(packageName: String?): PlatoonClient? = entries
            .firstOrNull { it != LEGACY && it.packageName == packageName }
    }
}

/** Stable management scope for one publisher, server region, and Platoon ID. */
internal data class PlatoonProfile(
    val storageId: String,
    val client: PlatoonClient,
    val serverRegion: GameServerRegion,
    val platoonId: Long,
    val platoonName: String,
    val emblemPrimary: List<Long>,
    val emblemSecondary: List<Long>,
    val lastSeenAt: Instant,
    val legacy: Boolean = false,
) {
    init {
        require(PlatoonProfileIdentity.isValidStorageId(storageId))
        require(platoonId in 0L..UInt.MAX_VALUE.toLong())
        require(
            platoonName.isNotBlank() &&
                platoonName.length <= MAX_NAME_LENGTH &&
                platoonName.none(Char::isISOControl),
        )
        require(emblemPrimary.size <= MAX_EMBLEM_PARTS)
        require(emblemSecondary.size <= MAX_EMBLEM_PARTS)
        require(emblemPrimary.all { it in 0L..UInt.MAX_VALUE.toLong() })
        require(emblemSecondary.all { it in 0L..UInt.MAX_VALUE.toLong() })
        require(legacy == (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID))
        if (legacy) {
            require(client == PlatoonClient.LEGACY)
            require(serverRegion == GameServerRegion.MANUAL)
            require(platoonId == 0L)
            require(emblemPrimary.isEmpty() && emblemSecondary.isEmpty())
        } else {
            require(client != PlatoonClient.LEGACY)
            require(serverRegion != GameServerRegion.MANUAL)
            require(serverRegion in ClientServerRegionPreferences.allowedFor(client.packageName))
            require(platoonId > 0L)
        }
    }

    companion object {
        const val MAX_EMBLEM_PARTS = 32
        const val MAX_NAME_LENGTH = 128
    }
}

/** Creates and validates immutable private storage identifiers for isolated Platoon data. */
internal object PlatoonProfileIdentity {
    const val LEGACY_STORAGE_ID = "legacy"
    private val STORAGE_ID = Regex("(?:legacy|[0-9a-f]{32})")

    fun storageId(client: PlatoonClient, region: GameServerRegion, platoonId: Long): String {
        require(client != PlatoonClient.LEGACY)
        require(region != GameServerRegion.MANUAL)
        require(platoonId > 0L)
        val material = "${client.packageName}\u0000${region.storedValue}\u0000$platoonId"
            .toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(material)
            .take(16)
            .joinToString("") { value -> "%02x".format(value) }
    }

    fun isValidStorageId(value: String): Boolean = STORAGE_ID.matches(value)

    fun randomStorageId(): String = ByteArray(16)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { value -> "%02x".format(value) }
}

/** Resolves every database and retained-evidence path from one validated profile ID. */
internal data class PlatoonStorageScope(val storageId: String) {
    init {
        require(PlatoonProfileIdentity.isValidStorageId(storageId))
    }

    val databaseName: String
        get() = if (isLegacy) PlatoonSchema.DATABASE_NAME else "platoon-$storageId.db"

    val isLegacy: Boolean
        get() = storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID

    fun rootDirectory(context: Context): File = if (isLegacy) {
        context.applicationContext.filesDir
    } else {
        File(context.applicationContext.filesDir, "platoons/$storageId")
    }

    fun retainedCsvDirectory(context: Context): File =
        File(rootDirectory(context), PlatoonRepository.RETAINED_CSV_DIRECTORY)

    companion object {
        private val SCOPED_DATABASE = Regex("platoon-([0-9a-f]{32})\\.db")

        fun fromDatabaseName(databaseName: String): PlatoonStorageScope = when (databaseName) {
            PlatoonSchema.DATABASE_NAME -> PlatoonStorageScope(
                PlatoonProfileIdentity.LEGACY_STORAGE_ID,
            )
            else -> requireNotNull(SCOPED_DATABASE.matchEntire(databaseName)) {
                "Database name is not a recognized Platoon scope"
            }.groupValues[1].let(::PlatoonStorageScope)
        }
    }
}

/** Persists the small profile registry independently from every isolated management database. */
internal class PlatoonProfileRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun ensureInitialized(): List<PlatoonProfile> = synchronized(lock) {
        PlatoonProfileAdministration.recoverPending(appContext)
        retireLegacyMetadataLocked()
        readAllLocked().filterNot(PlatoonProfile::legacy)
    }

    fun list(): List<PlatoonProfile> = synchronized(lock) {
        ensureInitialized()
        readAllLocked().filterNot(PlatoonProfile::legacy).sortedWith(
            compareByDescending<PlatoonProfile> { it.lastSeenAt }.thenBy { it.storageId },
        )
    }

    fun active(): PlatoonProfile? = synchronized(lock) {
        ensureInitialized()
        val activeId = preferences.getString(KEY_ACTIVE, null)
        val current = readAllLocked().filterNot(PlatoonProfile::legacy)
        current.firstOrNull { it.storageId == activeId }
            ?: current.maxByOrNull(PlatoonProfile::lastSeenAt)
    }

    fun find(storageId: String): PlatoonProfile? = synchronized(lock) {
        ensureInitialized()
        readLocked(storageId)?.takeUnless(PlatoonProfile::legacy)
    }

    fun findByIdentity(
        client: PlatoonClient,
        region: GameServerRegion,
        platoonId: Long,
    ): PlatoonProfile? = synchronized(lock) {
        ensureInitialized()
        readAllLocked().firstOrNull {
            !it.legacy &&
                it.client == client &&
                it.serverRegion == region &&
                it.platoonId == platoonId
        }
    }

    fun findByClientAndPlatoonId(
        client: PlatoonClient,
        platoonId: Long,
    ): List<PlatoonProfile> = synchronized(lock) {
        ensureInitialized()
        readAllLocked().filter {
            !it.legacy && it.client == client && it.platoonId == platoonId
        }
    }

    fun activeScope(): PlatoonStorageScope = PlatoonStorageScope(
        requireNotNull(active()) { "No confirmed Platoon profile is selected" }.storageId,
    )

    fun setActive(storageId: String): Boolean = synchronized(lock) {
        require(PlatoonProfileIdentity.isValidStorageId(storageId))
        val selected = readLocked(storageId) ?: return@synchronized false
        if (selected.legacy) return@synchronized false
        val clientRegions = ClientServerRegionPreferences(appContext)
        val ownerPackage = selected.client.packageName
        val previousRegion = clientRegions.stored(ownerPackage)
        clientRegions.set(ownerPackage, selected.serverRegion)
        if (preferences.edit().putString(KEY_ACTIVE, storageId).commit()) {
            return@synchronized true
        }
        if (previousRegion == null) {
            clientRegions.clear(ownerPackage)
        } else {
            clientRegions.set(ownerPackage, previousRegion)
        }
        false
    }

    fun upsertDetected(
        ownerPackage: String,
        region: GameServerRegion,
        data: PlatoonProfileData,
        observedAt: Instant = Instant.now(),
    ): PlatoonProfile = synchronized(lock) {
        val client = requireNotNull(PlatoonClient.fromPackage(ownerPackage)) {
            "Unsupported Platoon client package"
        }
        require(region != GameServerRegion.MANUAL) { "A server region is required" }
        require(data.platoonId != 0u && data.platoonName.isNotBlank())
        val platoonId = data.platoonId.toLong()
        val existing = readAllLocked().firstOrNull {
            !it.legacy &&
                it.client == client &&
                it.serverRegion == region &&
                it.platoonId == platoonId
        }
        val storageId = existing?.storageId ?: allocateStorageIdLocked(client, region, platoonId)
        require(readLocked(storageId) != null || readAllLocked().size < MAX_PROFILES) {
            "Too many Platoon profiles are already registered"
        }
        val normalizedName = data.platoonName
            .replace(Regex("\\s+"), " ")
            .filterNot(Char::isISOControl)
            .trim()
            .take(PlatoonProfile.MAX_NAME_LENGTH)
        require(normalizedName.isNotBlank()) { "Platoon name is empty after normalization" }
        val profile = PlatoonProfile(
            storageId = storageId,
            client = client,
            serverRegion = region,
            platoonId = platoonId,
            platoonName = normalizedName,
            emblemPrimary = data.emblemPrimary.take(PlatoonProfile.MAX_EMBLEM_PARTS).map(UInt::toLong),
            emblemSecondary = data.emblemSecondary.take(PlatoonProfile.MAX_EMBLEM_PARTS).map(UInt::toLong),
            lastSeenAt = observedAt,
        )
        writeLocked(profile, setActive = preferences.getString(KEY_ACTIVE, null) == null)
        profile
    }

    /**
     * Creates an isolated profile from identity fields explicitly supplied by the user.
     * This is the only safe destination for a roster CSV that contains no 21905 identity.
     */
    fun createDeclared(
        client: PlatoonClient,
        region: GameServerRegion,
        platoonId: Long,
        platoonName: String,
        createdAt: Instant = Instant.now(),
    ): PlatoonProfile = synchronized(lock) {
        require(client != PlatoonClient.LEGACY) { "A supported client is required" }
        require(region in ClientServerRegionPreferences.allowedFor(client.packageName)) {
            "The server region does not belong to this client"
        }
        require(platoonId in 1L..UInt.MAX_VALUE.toLong()) { "Platoon ID is invalid" }
        require(readAllLocked().none {
            !it.legacy &&
                it.client == client &&
                it.serverRegion == region &&
                it.platoonId == platoonId
        }) { "That client/server Platoon profile already exists" }
        require(readAllLocked().size < MAX_PROFILES) {
            "Too many Platoon profiles are already registered"
        }
        val profile = PlatoonProfile(
            storageId = allocateStorageIdLocked(client, region, platoonId),
            client = client,
            serverRegion = region,
            platoonId = platoonId,
            platoonName = normalizeName(platoonName),
            emblemPrimary = emptyList(),
            emblemSecondary = emptyList(),
            lastSeenAt = createdAt,
        )
        writeLocked(profile, setActive = false)
        profile
    }

    /** Updates only mutable observed profile fields while retaining the isolated storage scope. */
    fun updateObserved(
        storageId: String,
        data: PlatoonProfileData,
        observedAt: Instant = Instant.now(),
    ): PlatoonProfile = synchronized(lock) {
        require(PlatoonProfilePolicyAdapter.isValid(data))
        val current = requireNotNull(readLocked(storageId)) { "Unknown Platoon profile" }
        require(!current.legacy && current.platoonId == data.platoonId.toLong()) {
            "Observed Platoon identity does not match the storage scope"
        }
        val updated = current.copy(
            platoonName = normalizeName(data.platoonName),
            emblemPrimary = data.emblemPrimary
                .take(PlatoonProfile.MAX_EMBLEM_PARTS)
                .map(UInt::toLong),
            emblemSecondary = data.emblemSecondary
                .take(PlatoonProfile.MAX_EMBLEM_PARTS)
                .map(UInt::toLong),
            lastSeenAt = observedAt,
        )
        writeLocked(updated, setActive = false)
        updated
    }

    /** Changes server metadata without moving or merging the profile's immutable data scope. */
    fun updateServerRegion(storageId: String, region: GameServerRegion): PlatoonProfile =
        synchronized(lock) {
            val current = requireNotNull(readLocked(storageId)) { "Unknown Platoon profile" }
            require(!current.legacy) { "Legacy data has no verified client/server identity" }
            require(region in ClientServerRegionPreferences.allowedFor(current.client.packageName)) {
                "The server region does not belong to this client"
            }
            require(
                readAllLocked().none {
                    it.storageId != storageId &&
                        !it.legacy &&
                        it.client == current.client &&
                        it.serverRegion == region &&
                        it.platoonId == current.platoonId
                },
            ) { "That client/server Platoon profile already exists" }
            val updated = current.copy(serverRegion = region)
            writeLocked(updated, setActive = false)
            if (preferences.getString(KEY_ACTIVE, null) == storageId) {
                runCatching {
                    ClientServerRegionPreferences(appContext).set(current.client.packageName, region)
                }.getOrElse { error ->
                    writeLocked(current, setActive = false)
                    throw error
                }
            }
            updated
        }

    fun upsertRestored(profile: PlatoonProfile): PlatoonProfile = synchronized(lock) {
        require(!profile.legacy) { "Legacy unscoped Platoon data cannot be restored" }
        requireCompatibleRestoreTargetLocked(profile)
        require(readLocked(profile.storageId) != null || readAllLocked().size < MAX_PROFILES) {
            "Too many Platoon profiles are already registered"
        }
        writeLocked(profile, setActive = false)
        profile
    }

    /** Rejects a new restore scope before any database or filesystem state is replaced. */
    fun requireRestoreCapacity(profile: PlatoonProfile) = synchronized(lock) {
        require(!profile.legacy) { "Legacy unscoped Platoon data cannot be restored" }
        requireCompatibleRestoreTargetLocked(profile)
        require(readLocked(profile.storageId) != null || readAllLocked().size < MAX_PROFILES) {
            "Too many Platoon profiles are already registered"
        }
    }

    /** Removes registry metadata introduced by a failed restore; scoped data is left untouched. */
    fun removeIfInactive(storageId: String): Boolean = synchronized(lock) {
        require(PlatoonProfileIdentity.isValidStorageId(storageId))
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) return@synchronized false
        if (preferences.getString(KEY_ACTIVE, null) == storageId) return@synchronized false
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        if (!ids.remove(storageId)) return@synchronized false
        val prefix = "$KEY_PROFILE.$storageId."
        preferences.edit()
            .putStringSet(KEY_IDS, ids)
            .remove(prefix + CLIENT)
            .remove(prefix + REGION)
            .remove(prefix + PLATOON_ID)
            .remove(prefix + NAME)
            .remove(prefix + EMBLEM_PRIMARY)
            .remove(prefix + EMBLEM_SECONDARY)
            .remove(prefix + LAST_SEEN)
            .remove(prefix + LEGACY)
            .commit()
    }

    /** Atomically restores only the registry entry and active pointer touched by a failed restore. */
    fun restoreTargetState(
        targetStorageId: String,
        previousProfile: PlatoonProfile?,
        previousActiveStorageId: String?,
    ) = synchronized(lock) {
        require(PlatoonProfileIdentity.isValidStorageId(targetStorageId))
        require(previousProfile == null || previousProfile.storageId == targetStorageId)
        require(
            previousActiveStorageId == null ||
                PlatoonProfileIdentity.isValidStorageId(previousActiveStorageId),
        )
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        val prefix = "$KEY_PROFILE.$targetStorageId."
        val editor = preferences.edit()
        if (previousProfile == null) {
            ids.remove(targetStorageId)
            editor.remove(prefix + CLIENT)
                .remove(prefix + REGION)
                .remove(prefix + PLATOON_ID)
                .remove(prefix + NAME)
                .remove(prefix + EMBLEM_PRIMARY)
                .remove(prefix + EMBLEM_SECONDARY)
                .remove(prefix + LAST_SEEN)
                .remove(prefix + LEGACY)
        } else {
            ids += targetStorageId
            editor.putString(prefix + CLIENT, previousProfile.client.name)
                .putString(prefix + REGION, previousProfile.serverRegion.storedValue)
                .putLong(prefix + PLATOON_ID, previousProfile.platoonId)
                .putString(prefix + NAME, previousProfile.platoonName)
                .putString(prefix + EMBLEM_PRIMARY, previousProfile.emblemPrimary.joinToString(","))
                .putString(prefix + EMBLEM_SECONDARY, previousProfile.emblemSecondary.joinToString(","))
                .putLong(prefix + LAST_SEEN, previousProfile.lastSeenAt.toEpochMilli())
                .putBoolean(prefix + LEGACY, previousProfile.legacy)
        }
        editor.putStringSet(KEY_IDS, ids)
        if (previousActiveStorageId != null && previousActiveStorageId in ids) {
            editor.putString(KEY_ACTIVE, previousActiveStorageId)
        } else {
            editor.remove(KEY_ACTIVE)
        }
        check(editor.commit()) { "Unable to restore the Platoon profile registry" }
    }

    /** Forgets selector metadata without deleting the isolated database or retained evidence. */
    fun forget(storageId: String): Boolean = synchronized(lock) {
        require(PlatoonProfileIdentity.isValidStorageId(storageId))
        if (storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID) return@synchronized false
        if (readLocked(storageId) == null) return@synchronized false
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        if (!ids.remove(storageId)) return@synchronized false
        val remaining = ids.mapNotNull(::readLocked)
        val prefix = "$KEY_PROFILE.$storageId."
        val editor = preferences.edit()
            .putStringSet(KEY_IDS, ids)
            .remove(prefix + CLIENT)
            .remove(prefix + REGION)
            .remove(prefix + PLATOON_ID)
            .remove(prefix + NAME)
            .remove(prefix + EMBLEM_PRIMARY)
            .remove(prefix + EMBLEM_SECONDARY)
            .remove(prefix + LAST_SEEN)
            .remove(prefix + LEGACY)
        if (preferences.getString(KEY_ACTIVE, null) == storageId) {
            val fallback = remaining.maxByOrNull(PlatoonProfile::lastSeenAt)
            if (fallback == null) {
                editor.remove(KEY_ACTIVE)
            } else {
                editor.putString(KEY_ACTIVE, fallback.storageId)
            }
        }
        editor.commit()
    }

    private fun readAllLocked(): List<PlatoonProfile> = preferences
        .getStringSet(KEY_IDS, emptySet())
        .orEmpty()
        .mapNotNull(::readLocked)

    /** Removes the pre-isolation selector only; its files remain quarantined for recovery. */
    private fun retireLegacyMetadataLocked() {
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        if (!ids.remove(PlatoonProfileIdentity.LEGACY_STORAGE_ID) &&
            preferences.getString(KEY_ACTIVE, null) != PlatoonProfileIdentity.LEGACY_STORAGE_ID
        ) return
        val prefix = "$KEY_PROFILE.${PlatoonProfileIdentity.LEGACY_STORAGE_ID}."
        val editor = preferences.edit()
            .putStringSet(KEY_IDS, ids)
            .remove(prefix + CLIENT)
            .remove(prefix + REGION)
            .remove(prefix + PLATOON_ID)
            .remove(prefix + NAME)
            .remove(prefix + EMBLEM_PRIMARY)
            .remove(prefix + EMBLEM_SECONDARY)
            .remove(prefix + LAST_SEEN)
            .remove(prefix + LEGACY)
        if (preferences.getString(KEY_ACTIVE, null) == PlatoonProfileIdentity.LEGACY_STORAGE_ID) {
            editor.remove(KEY_ACTIVE)
        }
        check(editor.commit()) { "Unable to retire legacy Platoon metadata" }
    }

    private fun readLocked(storageId: String): PlatoonProfile? = runCatching {
        if (!PlatoonProfileIdentity.isValidStorageId(storageId)) return@runCatching null
        val prefix = "$KEY_PROFILE.$storageId."
        PlatoonProfile(
            storageId = storageId,
            client = PlatoonClient.valueOf(requireNotNull(preferences.getString(prefix + CLIENT, null))),
            serverRegion = GameServerRegion.fromStored(preferences.getString(prefix + REGION, null)),
            platoonId = preferences.getLong(prefix + PLATOON_ID, -1L),
            platoonName = requireNotNull(preferences.getString(prefix + NAME, null)),
            emblemPrimary = parseLongList(preferences.getString(prefix + EMBLEM_PRIMARY, null)),
            emblemSecondary = parseLongList(preferences.getString(prefix + EMBLEM_SECONDARY, null)),
            lastSeenAt = Instant.ofEpochMilli(preferences.getLong(prefix + LAST_SEEN, 0L)),
            legacy = preferences.getBoolean(prefix + LEGACY, false),
        )
    }.getOrNull()

    private fun writeLocked(profile: PlatoonProfile, setActive: Boolean) {
        val ids = preferences.getStringSet(KEY_IDS, emptySet()).orEmpty().toMutableSet()
        ids += profile.storageId
        val prefix = "$KEY_PROFILE.${profile.storageId}."
        val editor = preferences.edit()
            .putStringSet(KEY_IDS, ids)
            .putString(prefix + CLIENT, profile.client.name)
            .putString(prefix + REGION, profile.serverRegion.storedValue)
            .putLong(prefix + PLATOON_ID, profile.platoonId)
            .putString(prefix + NAME, profile.platoonName)
            .putString(prefix + EMBLEM_PRIMARY, profile.emblemPrimary.joinToString(","))
            .putString(prefix + EMBLEM_SECONDARY, profile.emblemSecondary.joinToString(","))
            .putLong(prefix + LAST_SEEN, profile.lastSeenAt.toEpochMilli())
            .putBoolean(prefix + LEGACY, profile.legacy)
        if (setActive) editor.putString(KEY_ACTIVE, profile.storageId)
        check(editor.commit()) { "Unable to persist the Platoon profile registry" }
    }

    private fun requireCompatibleRestoreTargetLocked(profile: PlatoonProfile) {
        require(!profile.legacy) { "Legacy unscoped Platoon data cannot be restored" }
        val profiles = readAllLocked()
        profiles.firstOrNull { it.storageId == profile.storageId }?.let { existing ->
            require(
                existing.legacy == profile.legacy &&
                    (
                        existing.legacy ||
                            (
                                existing.client == profile.client &&
                                    existing.platoonId == profile.platoonId
                                )
                        ),
            ) { "Backup storage scope belongs to a different Platoon identity" }
        }
        require(
            profiles.none {
                it.storageId != profile.storageId &&
                    !it.legacy &&
                    !profile.legacy &&
                    it.client == profile.client &&
                    it.serverRegion == profile.serverRegion &&
                    it.platoonId == profile.platoonId
            },
        ) { "Backup Platoon identity already belongs to another storage scope" }
    }

    private fun allocateStorageIdLocked(
        client: PlatoonClient,
        region: GameServerRegion,
        platoonId: Long,
    ): String {
        val preferred = PlatoonProfileIdentity.storageId(client, region, platoonId)
        if (readLocked(preferred) == null) return preferred
        repeat(32) {
            val random = PlatoonProfileIdentity.randomStorageId()
            if (readLocked(random) == null) return random
        }
        error("Unable to allocate an isolated Platoon storage scope")
    }

    private fun normalizeName(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .filterNot(Char::isISOControl)
        .trim()
        .take(PlatoonProfile.MAX_NAME_LENGTH)
        .also { require(it.isNotBlank()) { "Platoon name is empty after normalization" } }

    private fun parseLongList(value: String?): List<Long> = value.orEmpty()
        .split(',')
        .filter(String::isNotBlank)
        .mapNotNull(String::toLongOrNull)
        .take(PlatoonProfile.MAX_EMBLEM_PARTS)

    internal companion object {
        internal const val MAX_PROFILES = 16
        private const val PREFERENCES = "platoon_profiles"
        private const val KEY_IDS = "profile_ids"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_PROFILE = "profile"
        private const val CLIENT = "client"
        private const val REGION = "region"
        private const val PLATOON_ID = "platoon_id"
        private const val NAME = "name"
        private const val EMBLEM_PRIMARY = "emblem_primary"
        private const val EMBLEM_SECONDARY = "emblem_secondary"
        private const val LAST_SEEN = "last_seen"
        private const val LEGACY = "legacy"
        private val lock = Any()
    }
}

/** Keeps management independent from the capture package's validation helper. */
private object PlatoonProfilePolicyAdapter {
    fun isValid(profile: PlatoonProfileData): Boolean =
        profile.platoonId != 0u &&
            profile.platoonName.isNotBlank() &&
            profile.platoonName.length <= PlatoonProfile.MAX_NAME_LENGTH &&
            profile.platoonName.none(Char::isISOControl)
}
