package dev.gf2log.app.settings

import android.content.Context
import dev.gf2log.app.management.PlatoonProfileIdentity
import dev.gf2log.app.management.PlatoonProfileRegistry

class MemberOrderPreferences(
    context: Context,
    private val storageId: String = PlatoonProfileRegistry(context).activeScope().storageId,
) {
    private val appContext = context.applicationContext
    private val scoped = appContext.getSharedPreferences(PROFILE_PREFERENCES, Context.MODE_PRIVATE)

    fun read(): List<Long> = if (isLegacy) {
        UserSettingsPreferences.memberOrder(appContext)
    } else {
        scoped.getString(storageId, null).orEmpty()
            .split(',')
            .mapNotNull(String::toLongOrNull)
            .filter { it > 0L }
            .distinct()
    }

    fun write(uids: List<Long>): Boolean = if (isLegacy) {
        UserSettingsPreferences.setMemberOrder(appContext, uids)
    } else {
        scoped.edit().putString(storageId, uids.filter { it > 0L }.distinct().joinToString(","))
            .commit()
    }

    fun clear() {
        if (isLegacy) {
            UserSettingsPreferences.clearMemberOrder(appContext)
        } else {
            scoped.edit().remove(storageId).apply()
        }
    }

    fun <T> apply(items: List<T>, uid: (T) -> Long): List<T> {
        val positions = read().withIndex().associate { it.value to it.index }
        if (positions.isEmpty()) return items
        return items.sortedWith(
            compareBy<T> { positions[uid(it)] ?: Int.MAX_VALUE }
                .thenBy { items.indexOf(it) },
        )
    }

    private val isLegacy: Boolean
        get() = storageId == PlatoonProfileIdentity.LEGACY_STORAGE_ID

    private companion object {
        const val PROFILE_PREFERENCES = "platoon_member_order"
    }
}
