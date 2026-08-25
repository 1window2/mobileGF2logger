package dev.gf2log.app

import android.content.Context
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope

/** Pins one Activity instance to the profile scope used to construct its repositories and views. */
internal class ActivePlatoonScopeBinding(context: Context) {
    val scope: PlatoonStorageScope? = PlatoonProfileRegistry(context).active()
        ?.let { PlatoonStorageScope(it.storageId) }

    fun isCurrent(context: Context): Boolean =
        PlatoonProfileRegistry(context).active()?.storageId == scope?.storageId
}
