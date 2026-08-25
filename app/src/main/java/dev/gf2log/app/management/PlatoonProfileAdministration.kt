package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences

/** Owns destructive and derived-data maintenance for isolated Platoon profiles. */
internal class PlatoonProfileAdministration(context: Context) {
    private val appContext = context.applicationContext
    private val registry = PlatoonProfileRegistry(appContext)

    /**
     * Updates verified server metadata while retaining the profile's immutable storage scope.
     * Weekly history is rebuilt with the new reset zone and rolled back if rebuilding fails.
     */
    fun changeServerRegion(storageId: String, region: GameServerRegion): PlatoonProfile {
        val before = requireNotNull(registry.find(storageId)) { "Unknown Platoon profile" }
        require(!before.legacy)
        if (before.serverRegion == region) return before
        val wasAutomatic = GameTimeZonePreferences.isAutomatic(appContext, storageId)
        val previousOverride = GameTimeZonePreferences.region(appContext, storageId)
        val previousZone = GameTimeZonePreferences.get(appContext, storageId)
        val updated = registry.updateServerRegion(storageId, region)
        return try {
            GameTimeZonePreferences.clearRegionOverride(appContext, storageId)
            PlatoonRepository(appContext, PlatoonStorageScope(storageId))
                .rebuildWeeklyHistoryForTimeZoneChange(requireNotNull(region.serverZone))
            updated
        } catch (error: Exception) {
            runCatching { registry.updateServerRegion(storageId, before.serverRegion) }
                .onFailure(error::addSuppressed)
            runCatching {
                if (wasAutomatic) {
                    GameTimeZonePreferences.clearRegionOverride(appContext, storageId)
                } else {
                    GameTimeZonePreferences.setRegion(appContext, previousOverride, storageId)
                }
                PlatoonRepository(appContext, PlatoonStorageScope(storageId))
                    .rebuildWeeklyHistoryForTimeZoneChange(previousZone)
            }.onFailure(error::addSuppressed)
            throw error
        }
    }

    /**
     * Removes selector metadata first, then finishes bounded scoped deletion from a durable queue.
     * A process interruption can leave inaccessible staged data, but the next registry open resumes
     * its deletion before profiles are shown.
     */
    fun deleteProfile(storageId: String): Boolean {
        val profile = registry.find(storageId) ?: return false
        require(!profile.legacy) { "Legacy data cannot be deleted as a detected profile" }
        enqueue(appContext, storageId)
        if (!registry.forget(storageId)) {
            dequeue(appContext, storageId)
            return false
        }
        val removed = finishPending(appContext, storageId)
        synchronizeCaptureRegion(profile.client, registry)
        return removed
    }

    private fun synchronizeCaptureRegion(
        deletedClient: PlatoonClient,
        registry: PlatoonProfileRegistry,
    ) {
        val remaining = registry.list()
        val preferences = ClientServerRegionPreferences(appContext)
        if (remaining.none { !it.legacy && it.client == deletedClient }) {
            runCatching { preferences.clear(deletedClient.packageName) }
        }
        registry.active()?.takeUnless(PlatoonProfile::legacy)?.let { active ->
            runCatching { registry.setActive(active.storageId) }
        }
    }

    companion object {
        private const val PREFERENCES = "platoon_profile_deletions"
        private const val KEY_PENDING = "pending_storage_ids"

        fun recoverPending(context: Context) {
            val appContext = context.applicationContext
            pending(appContext).forEach { storageId ->
                if (PlatoonProfileIdentity.isValidStorageId(storageId) &&
                    storageId != PlatoonProfileIdentity.LEGACY_STORAGE_ID
                ) {
                    finishPending(appContext, storageId)
                } else {
                    dequeue(appContext, storageId)
                }
            }
        }

        private fun finishPending(context: Context, storageId: String): Boolean {
            val scope = PlatoonStorageScope(storageId)
            return runCatching {
                PlatoonRepository.withExclusiveDatabase(scope) {
                    val database = context.getDatabasePath(scope.databaseName)
                    if (database.exists()) {
                        check(context.deleteDatabase(scope.databaseName)) {
                            "Unable to delete the Platoon database"
                        }
                    }
                    val root = scope.rootDirectory(context).canonicalFile
                    val profilesRoot = java.io.File(context.filesDir, "platoons").canonicalFile
                    check(root.parentFile == profilesRoot) {
                        "Refusing to delete outside the isolated Platoon directory"
                    }
                    if (root.exists()) {
                        check(root.deleteRecursively()) { "Unable to delete Platoon evidence" }
                    }
                }
                MemberOrderPreferences(context, storageId).clear()
                WeeklyCutlinePreferences(context, storageId).clear()
                GameTimeZonePreferences.clearScope(context, storageId)
                dequeue(context, storageId)
                true
            }.getOrDefault(false)
        }

        private fun enqueue(context: Context, storageId: String) {
            val values = pending(context).toMutableSet().apply { add(storageId) }
            check(preferences(context).edit().putStringSet(KEY_PENDING, values).commit()) {
                "Unable to queue Platoon deletion"
            }
        }

        private fun dequeue(context: Context, storageId: String) {
            val values = pending(context).toMutableSet().apply { remove(storageId) }
            check(preferences(context).edit().putStringSet(KEY_PENDING, values).commit()) {
                "Unable to finish Platoon deletion"
            }
        }

        private fun pending(context: Context): Set<String> =
            preferences(context).getStringSet(KEY_PENDING, emptySet()).orEmpty().toSet()

        private fun preferences(context: Context) = context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    }
}
