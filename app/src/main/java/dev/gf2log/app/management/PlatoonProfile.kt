package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.File
import java.security.MessageDigest
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
            require(platoonId > 0L)
            require(storageId == PlatoonProfileIdentity.storageId(client, serverRegion, platoonId))
        }
    }

    companion object {
        const val MAX_EMBLEM_PARTS = 32
        const val MAX_NAME_LENGTH = 128
    }
}

/** Deterministically maps an authoritative composite identity to a safe storage identifier. */
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
            else -> SCOPED_DATABASE.matchEntire(databaseName)
                ?.groupValues
                ?.get(1)
                ?.let(::PlatoonStorageScope)
                ?: PlatoonStorageScope(PlatoonProfileIdentity.LEGACY_STORAGE_ID)
        }
    }
}

/** Persists the small profile registry independently from every isolated management database. */
internal class PlatoonProfileRegistry(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun ensureInitialized(): List<PlatoonProfile> = synchronized(lock) {
        val current = readAllLocked()
        if (current.isNotEmpty()) return@synchronized current
        val legacyDatabase = appContext.getDatabasePath(PlatoonSchema.DATABASE_NAME)
        val hasLegacyDatabaseData = legacyDatabase.isFile && runCatching {
            PlatoonDatabase(appContext).use(PlatoonDatabase::hasManagementData)
        }.getOrDefault(false)
        val hasRetainedCsvData = File(
            appContext.filesDir,
            PlatoonRepository.RETAINED_CSV_DIRECTORY,
        ).listFiles { file ->
            file.isFile && file.length() > 0L && file.extension.equals("csv", ignoreCase = true)
        }.orEmpty().isNotEmpty()
        val hasLegacyData = hasLegacyDatabaseData || hasRetainedCsvData
        if (!hasLegacyData) return@synchronized emptyList()
        val legacy = legacyProfile()
        writeLocked(legacy, setActive = true)
        listOf(legacy)
    }

    fun ensureLegacyProfile(): PlatoonProfile = synchronized(lock) {
        readLocked(PlatoonProfileIdentity.LEGACY_STORAGE_ID)?.let { return@synchronized it }
        val legacy = legacyProfile()
        writeLocked(legacy, setActive = preferences.getString(KEY_ACTIVE, null) == null)
        legacy
    }

    fun list(): List<PlatoonProfile> = synchronized(lock) {
        ensureInitialized()
        readAllLocked().sortedWith(
            compareByDescending<PlatoonProfile> { it.lastSeenAt }.thenBy { it.storageId },
        )
    }

    fun active(): PlatoonProfile? = synchronized(lock) {
        ensureInitialized()
        val activeId = preferences.getString(KEY_ACTIVE, null)
        readAllLocked().firstOrNull { it.storageId == activeId }
            ?: readAllLocked().maxByOrNull(PlatoonProfile::lastSeenAt)
    }

    fun find(storageId: String): PlatoonProfile? = synchronized(lock) {
        ensureInitialized()
        readLocked(storageId)
    }

    fun activeScope(): PlatoonStorageScope =
        PlatoonStorageScope(active()?.storageId ?: PlatoonProfileIdentity.LEGACY_STORAGE_ID)

    fun setActive(storageId: String): Boolean = synchronized(lock) {
        require(PlatoonProfileIdentity.isValidStorageId(storageId))
        if (readLocked(storageId) == null) return@synchronized false
        preferences.edit().putString(KEY_ACTIVE, storageId).commit()
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
        val storageId = PlatoonProfileIdentity.storageId(client, region, platoonId)
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

    fun upsertRestored(profile: PlatoonProfile): PlatoonProfile = synchronized(lock) {
        if (!profile.legacy) {
            require(
                profile.storageId == PlatoonProfileIdentity.storageId(
                    profile.client,
                    profile.serverRegion,
                    profile.platoonId,
                ),
            ) { "Restored Platoon identity is inconsistent" }
        }
        require(readLocked(profile.storageId) != null || readAllLocked().size < MAX_PROFILES) {
            "Too many Platoon profiles are already registered"
        }
        writeLocked(profile, setActive = false)
        profile
    }

    /** Rejects a new restore scope before any database or filesystem state is replaced. */
    fun requireRestoreCapacity(profile: PlatoonProfile) = synchronized(lock) {
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

    private fun readAllLocked(): List<PlatoonProfile> = preferences
        .getStringSet(KEY_IDS, emptySet())
        .orEmpty()
        .mapNotNull(::readLocked)

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

    private fun parseLongList(value: String?): List<Long> = value.orEmpty()
        .split(',')
        .filter(String::isNotBlank)
        .mapNotNull(String::toLongOrNull)
        .take(PlatoonProfile.MAX_EMBLEM_PARTS)

    private fun legacyProfile() = PlatoonProfile(
        storageId = PlatoonProfileIdentity.LEGACY_STORAGE_ID,
        client = PlatoonClient.LEGACY,
        serverRegion = GameServerRegion.MANUAL,
        platoonId = 0L,
        platoonName = LEGACY_NAME,
        emblemPrimary = emptyList(),
        emblemSecondary = emptyList(),
        lastSeenAt = Instant.EPOCH,
        legacy = true,
    )

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
        private const val LEGACY_NAME = "Existing platoon data"
        private val lock = Any()
    }
}
