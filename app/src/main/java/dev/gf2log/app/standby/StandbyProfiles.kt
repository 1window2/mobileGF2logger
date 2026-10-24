package dev.gf2log.app.standby

import android.content.Context
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.GameTimeZonePreferences

/** Saved Platoons as standby and scheduled recording plan them. */
internal object StandbyProfiles {

    /** Reads storage, so call it off the main thread. */
    fun load(context: Context): List<StandbyProfile> =
        PlatoonProfileRegistry(context).list().map { profile ->
            StandbyProfile(
                storageId = profile.storageId,
                packageName = profile.client.packageName,
                region = profile.serverRegion,
                zone = GameTimeZonePreferences.get(context, profile.storageId),
                platoonName = profile.platoonName,
            )
        }
}
