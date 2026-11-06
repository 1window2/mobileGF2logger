package dev.gf2log.app

import android.content.Context
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope

/**
 * Pins one Activity instance to the profile scope used to construct its repositories and views.
 * A screen recreated while it holds a draft passes the storage that draft was typed for as
 * [pinnedStorageId] ([BaseActivity.pinnedStorage]); it binds to that Platoon while it still
 * exists, so the draft is never restored into the newly active one. Otherwise it binds to the
 * active Platoon.
 */
internal class ActivePlatoonScopeBinding(context: Context, pinnedStorageId: String? = null) {
    private val boundProfile = PlatoonProfileRegistry(context).let { registry ->
        pinnedStorageId?.let(registry::find) ?: registry.active()
    }
    val scope: PlatoonStorageScope? = boundProfile?.let { PlatoonStorageScope(it.storageId) }

    /** A draft was typed for a Platoon that no longer exists: it must not be restored here. */
    val lostPinnedStorage: Boolean = pinnedStorageId != null && boundProfile?.storageId != pinnedStorageId

    /**
     * Whether the selected Platoon still uses this screen's storage. Screens that do not show the
     * profile itself use this, so a renamed Platoon or a new banner never closes them mid-edit.
     */
    fun isSameStorage(context: Context): Boolean =
        PlatoonProfileRegistry(context).active()?.storageId == boundProfile?.storageId

    fun isCurrent(context: Context): Boolean =
        PlatoonProfileRegistry(context).active()?.let(::visualIdentity) ==
            boundProfile?.let(::visualIdentity)

    /**
     * UI ownership is pinned to both the data scope and its visible profile metadata. A profile
     * packet may update the name or banner while an Activity is behind the game client without
     * changing storageId; treating only storageId as current would leave stale selector artwork.
     */
    private fun visualIdentity(profile: PlatoonProfile): VisualIdentity =
        VisualIdentity(
            storageId = profile.storageId,
            client = profile.client,
            serverRegion = profile.serverRegion,
            platoonId = profile.platoonId,
            platoonName = profile.platoonName,
            bannerFrameId = profile.bannerFrameId,
            bannerMarkId = profile.bannerMarkId,
            legacy = profile.legacy,
        )

    private data class VisualIdentity(
        val storageId: String,
        val client: dev.gf2log.app.management.PlatoonClient,
        val serverRegion: dev.gf2log.app.settings.GameServerRegion,
        val platoonId: Long,
        val platoonName: String,
        val bannerFrameId: Long,
        val bannerMarkId: Long,
        val legacy: Boolean,
    )
}
