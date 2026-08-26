package dev.gf2log.app

import android.content.Context
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope

/** Pins one Activity instance to the profile scope used to construct its repositories and views. */
internal class ActivePlatoonScopeBinding(context: Context) {
    private val boundProfile = PlatoonProfileRegistry(context).active()
    val scope: PlatoonStorageScope? = boundProfile?.let { PlatoonStorageScope(it.storageId) }

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
