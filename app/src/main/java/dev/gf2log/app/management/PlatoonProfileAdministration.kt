package dev.gf2log.app.management

import android.content.Context
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import java.io.File
import java.time.ZoneId

/** Owns destructive and derived-data maintenance for isolated Platoon profiles. */
internal class PlatoonProfileAdministration internal constructor(
    context: Context,
    private val rebuildWeeklyHistory: (Context, PlatoonStorageScope, ZoneId) -> Unit,
) {
    constructor(context: Context) : this(context, { appContext, scope, zone ->
        PlatoonRepository(appContext, scope).rebuildWeeklyHistoryForTimeZoneChange(zone)
    })

    private val appContext = context.applicationContext
    private val registry = PlatoonProfileRegistry(appContext)

    /**
     * Updates verified server metadata while retaining the profile's immutable storage scope.
     * Weekly history is rebuilt with the new reset zone. If rebuilding fails, the registry entry
     * and the exact scoped timezone keys, including a restored manual zone, are put back.
     */
    fun changeServerRegion(storageId: String, region: GameServerRegion): PlatoonProfile {
        val before = requireNotNull(registry.find(storageId)) { "Unknown Platoon profile" }
        require(!before.legacy)
        if (before.serverRegion == region) return before
        val scope = PlatoonStorageScope(storageId)
        val previousZoneState = GameTimeZonePreferences.scopedState(appContext, storageId)
        val previousZone = GameTimeZonePreferences.get(appContext, storageId)
        val updated = registry.updateServerRegion(storageId, region)
        return try {
            GameTimeZonePreferences.clearRegionOverride(appContext, storageId)
            rebuildWeeklyHistory(appContext, scope, requireNotNull(region.serverZone))
            updated
        } catch (error: Exception) {
            runCatching { registry.updateServerRegion(storageId, before.serverRegion) }
                .onFailure(error::addSuppressed)
            runCatching {
                GameTimeZonePreferences.restoreScopedState(appContext, storageId, previousZoneState)
                rebuildWeeklyHistory(appContext, scope, previousZone)
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
            // A thread already reading a repository cannot take the exclusive gate; the
            // ReentrantReadWriteLock cannot upgrade. The next registry access finishes the work.
            if (PlatoonRepository.holdsSharedDatabaseAccess()) return
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

        /** Whether [storageId] still waits for deletion, so it must not be allocated again. */
        internal fun isPendingDeletion(context: Context, storageId: String): Boolean =
            storageId in pending(context)

        /**
         * Deletes one queued scope. A scope the registry lists again, as a committed restore of
         * the deleted Platoon does, is live data: it leaves the queue instead of being deleted.
         * The check and every deletion step run under the exclusive gate a restore also holds,
         * and a rolled-back restore leaves the scope unlisted, so its deletion simply resumes.
         */
        private fun finishPending(context: Context, storageId: String): Boolean {
            val scope = PlatoonStorageScope(storageId)
            return runCatching {
                PlatoonRepository.withExclusiveDatabase(scope) {
                    if (PlatoonProfileRegistry(context).isRegistered(storageId)) {
                        dequeue(context, storageId)
                        return@withExclusiveDatabase false
                    }
                    val database = context.getDatabasePath(scope.databaseName)
                    if (database.exists()) {
                        check(context.deleteDatabase(scope.databaseName)) {
                            "Unable to delete the Platoon database"
                        }
                    }
                    val root = scope.rootDirectory(context).canonicalFile
                    val profilesRoot = File(context.filesDir, "platoons").canonicalFile
                    check(root.parentFile == profilesRoot) {
                        "Refusing to delete outside the isolated Platoon directory"
                    }
                    if (root.exists()) {
                        check(root.deleteRecursively()) { "Unable to delete Platoon evidence" }
                    }
                    MemberOrderPreferences(context, storageId).clear()
                    WeeklyCutlinePreferences(context, storageId).clear()
                    GameTimeZonePreferences.clearScope(context, storageId)
                    dequeue(context, storageId)
                    true
                }
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
