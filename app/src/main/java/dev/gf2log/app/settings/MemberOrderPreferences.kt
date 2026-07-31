package dev.gf2log.app.settings

import android.content.Context

class MemberOrderPreferences(context: Context) {
    private val appContext = context.applicationContext

    fun read(): List<Long> = UserSettingsPreferences.memberOrder(appContext)

    fun write(uids: List<Long>): Boolean =
        UserSettingsPreferences.setMemberOrder(appContext, uids)

    fun clear() {
        UserSettingsPreferences.clearMemberOrder(appContext)
    }

    fun <T> apply(items: List<T>, uid: (T) -> Long): List<T> {
        val positions = read().withIndex().associate { it.value to it.index }
        if (positions.isEmpty()) return items
        return items.sortedWith(
            compareBy<T> { positions[uid(it)] ?: Int.MAX_VALUE }
                .thenBy { items.indexOf(it) },
        )
    }
}
