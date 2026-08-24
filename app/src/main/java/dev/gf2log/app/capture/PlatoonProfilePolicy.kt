package dev.gf2log.app.capture

import dev.gf2log.protocol.model.PlatoonProfileData

/** Validates that payload 21905 contains an identity that is safe to use for capture routing. */
internal object PlatoonProfilePolicy {
    private const val MAX_NAME_LENGTH = 128

    fun isValid(profile: PlatoonProfileData?): Boolean = profile != null &&
        profile.platoonId != 0u &&
        profile.platoonName.isNotBlank() &&
        profile.platoonName.length <= MAX_NAME_LENGTH &&
        profile.platoonName.none(Char::isISOControl)
}
