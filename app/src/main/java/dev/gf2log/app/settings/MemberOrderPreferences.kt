package dev.gf2log.app.settings

import android.content.Context

class MemberOrderPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES,
        Context.MODE_PRIVATE,
    )

    fun read(): List<Long> = preferences.getString(KEY_ORDER, null)
        .orEmpty()
        .split(',')
        .mapNotNull(String::toLongOrNull)
        .distinct()

    fun write(uids: List<Long>) {
        preferences.edit().putString(KEY_ORDER, uids.distinct().joinToString(",")).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_ORDER).apply()
    }

    fun <T> apply(items: List<T>, uid: (T) -> Long): List<T> {
        val positions = read().withIndex().associate { it.value to it.index }
        if (positions.isEmpty()) return items
        return items.sortedWith(
            compareBy<T> { positions[uid(it)] ?: Int.MAX_VALUE }
                .thenBy { items.indexOf(it) },
        )
    }

    private companion object {
        const val PREFERENCES = "weekly_member_order"
        const val KEY_ORDER = "uids"
    }
}
