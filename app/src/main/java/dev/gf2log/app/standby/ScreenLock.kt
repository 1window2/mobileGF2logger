package dev.gf2log.app.standby

import android.app.KeyguardManager
import android.content.Context

/**
 * Whether a PIN, pattern, or password is set. Unknown counts as set: scheduled recording is never
 * armed and the standby warning never skipped because the service is missing.
 */
internal fun hasScreenLock(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != false
