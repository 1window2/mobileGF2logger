package dev.gf2log.app.management

import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences

/**
 * Keeps a restored profile's server region and its scoped reset-zone override consistent. A
 * complete backup records only the effective reset region, so an override equal to the restored
 * profile region is the archived automatic choice and is cleared instead of becoming explicit. A
 * Platoon-only restore that changes the profile region drops the override like a server change
 * in Settings does, so the old region's zone cannot outlive the region it was chosen for.
 */
internal object RestoredTimeZonePolicy {
    fun clearsOverride(
        state: GameTimeZonePreferences.ScopedZoneState,
        previousRegion: GameServerRegion?,
        restoredRegion: GameServerRegion,
        restoredSettings: Boolean,
    ): Boolean {
        if (state.region == null && state.zone == null) return false
        return if (restoredSettings) {
            state.region != null && GameServerRegion.fromStored(state.region) == restoredRegion
        } else {
            previousRegion != restoredRegion
        }
    }
}
