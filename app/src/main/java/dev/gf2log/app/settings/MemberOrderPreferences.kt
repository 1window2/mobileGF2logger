package dev.gf2log.app.settings

import android.content.Context
import android.content.SharedPreferences
import dev.gf2log.app.management.PlatoonProfileIdentity

class MemberOrderPreferences(
    context: Context,
    private val storageId: String,
) {
    private val appContext = context.applicationContext
    private val scoped = ScopedMemberOrder(
        appContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE),
        storageId,
    )

    fun read(): List<Long> = if (isLegacy) {
        UserSettingsPreferences.memberOrder(appContext)
    } else {
        scoped.read()
    }

    fun write(uids: List<Long>): Boolean = if (isLegacy) {
        UserSettingsPreferences.setMemberOrder(appContext, uids)
    } else {
        scoped.write(uids)
    }

    /**
     * Saves a new arrangement of the [active] members. Saved UIDs of members who are not active
     * stay next to their previous neighbour, so past weeks and a rejoining member keep their place.
     */
    fun writeActive(active: List<Long>): Boolean = write(MemberOrderMerge.keepingInactive(read(), active))

    fun clear() {
        if (isLegacy) {
            UserSettingsPreferences.clearMemberOrder(appContext)
        } else {
            check(scoped.clear()) { "Unable to clear the member order" }
        }
    }

    /** Saved UIDs first in saved order; everyone else keeps their incoming order (stable sort). */
    fun <T> apply(items: List<T>, uid: (T) -> Long): List<T> = MemberOrderMerge.apply(read(), items, uid)

    private val isLegacy: Boolean
        get() = storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID

    private companion object {
        const val PROFILE_PREFERENCES = "platoon_member_order"
    }
}

/** One Platoon's order inside the shared per-Platoon file, stored as comma-separated UIDs. */
internal class ScopedMemberOrder(
    private val preferences: SharedPreferences,
    private val key: String,
) {
    fun read(): List<Long> = preferences.getString(key, null).orEmpty()
        .split(',')
        .mapNotNull(String::toLongOrNull)
        .filter { it > 0L }
        .distinct()

    fun write(uids: List<Long>): Boolean =
        preferences.commitOrRevert(key) { putString(key, uids.filter { it > 0L }.distinct().joinToString(",")) }

    fun clear(): Boolean = preferences.commitOrRevert(key) { remove(key) }
}

/** Pure ordering rules shared by the weekly tables and the Member order screen. */
internal object MemberOrderMerge {
    /** Saved UIDs first in saved order; everyone else keeps their incoming order (stable sort). */
    fun <T> apply(saved: List<Long>, items: List<T>, uid: (T) -> Long): List<T> {
        val positions = saved.withIndex().associate { it.value to it.index }
        if (positions.isEmpty()) return items
        return items.sortedBy { positions[uid(it)] ?: Int.MAX_VALUE }
    }

    /**
     * Whether a finished drag rearranged anyone. Releasing a handle without moving it keeps
     * [shown], so it must not save: with no saved order that would replace the tables' ranking.
     */
    fun changed(shown: List<Long>, current: List<Long>): Boolean = shown != current

    /**
     * [active] in its new order, with every [stored] UID that is not active placed right after its
     * nearest earlier stored neighbour (or first when it has none).
     */
    fun keepingInactive(stored: List<Long>, active: List<Long>): List<Long> {
        val result = active.distinct().toMutableList()
        stored.forEachIndexed { index, uid ->
            if (uid in result) return@forEachIndexed
            // Every earlier stored UID is already placed, active or not.
            val after = if (index == 0) -1 else result.indexOf(stored[index - 1])
            result.add(after + 1, uid)
        }
        return result
    }
}

/**
 * Commits [change] to [keys]. A failed commit has already replaced the in-memory values that every
 * later read in this process sees, so the previous values are put back before failure is reported.
 */
internal fun SharedPreferences.commitOrRevert(
    vararg keys: String,
    change: SharedPreferences.Editor.() -> Unit,
): Boolean = commitOrRevert(keys.toList(), change)

internal fun SharedPreferences.commitOrRevert(
    keys: Collection<String>,
    change: SharedPreferences.Editor.() -> Unit,
): Boolean {
    val stored = all
    val previous = keys.associateWith { stored[it] }
    if (edit().run { change(); commit() }) return true
    edit().run {
        previous.forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Long -> putLong(key, value)
                is Int -> putInt(key, value)
                is Boolean -> putBoolean(key, value)
                is Float -> putFloat(key, value)
                is Set<*> -> putStringSet(key, value as Set<String>)
            }
        }
        // The in-memory revert is what matters; the disk already holds the previous values.
        commit()
    }
    return false
}
