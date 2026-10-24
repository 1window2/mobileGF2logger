package dev.gf2log.app.standby

import android.content.Context
import androidx.core.content.edit
import dev.gf2log.app.settings.GameServerRegion
import org.json.JSONArray
import org.json.JSONObject

/** How one planned recording ended. */
internal enum class StandbyOutcome {
    /** A roster of a Platoon on the planned server was saved. */
    RECORDED,

    /** The client logged in to a different saved Platoon than the planned server's. */
    OTHER_PLATOON,

    /** No roster arrived before the run's time ran out. */
    TIMED_OUT,

    /** The run never started because an earlier one or the deadline left no time. */
    MISSED,

    /** The accessibility service was off, so the game could not be operated. */
    NO_ACCESSIBILITY,

    /** VPN consent was missing or revoked, so capture could not start. */
    NO_VPN_CONSENT,

    /** The game client is not installed. */
    NOT_INSTALLED,

    /** The capture service failed to start or stopped on its own. */
    CAPTURE_FAILED,

    /** The user left standby or turned the screen off first. */
    CANCELLED,

    /** A scheduled recording found the phone in use (screen on or a call) and left it alone. */
    SKIPPED_IN_USE,

    /** A scheduled recording found a PIN, pattern, or password set, so scheduling stopped. */
    SCREEN_LOCK_SET,

    /** A scheduled recording could not get past the lock screen. */
    LOCK_SCREEN_STAYED,
}

internal data class StandbyResult(
    val packageName: String,
    val region: GameServerRegion,
    val outcome: StandbyOutcome,
    val atMillis: Long,
)

/** Recording results not yet acknowledged on Home, newest last, kept until the user closes them. */
internal object StandbyResults {

    /** Adds [results] after any still unseen, keeping the newest [MAX_KEPT]. */
    fun save(context: Context, results: List<StandbyResult>) {
        if (results.isEmpty()) return
        write(context, (unseen(context) + results).takeLast(MAX_KEPT))
    }

    private fun write(context: Context, results: List<StandbyResult>) {
        if (results.isEmpty()) return prefs(context).edit(commit = true) { remove(KEY_UNSEEN) }
        val json = JSONArray().apply {
            results.forEach { result ->
                put(
                    JSONObject()
                        .put(PACKAGE, result.packageName)
                        .put(REGION, result.region.storedValue)
                        .put(OUTCOME, result.outcome.name)
                        .put(AT, result.atMillis),
                )
            }
        }
        prefs(context).edit(commit = true) { putString(KEY_UNSEEN, json.toString()) }
    }

    /** The results not yet acknowledged on Home. */
    fun unseen(context: Context): List<StandbyResult> {
        val raw = prefs(context).getString(KEY_UNSEEN, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                StandbyResult(
                    packageName = item.getString(PACKAGE),
                    region = GameServerRegion.fromStored(item.getString(REGION)),
                    outcome = StandbyOutcome.valueOf(item.getString(OUTCOME)),
                    atMillis = item.getLong(AT),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Drops [shown] once the user has closed them; results added meanwhile stay. */
    fun markSeen(context: Context, shown: List<StandbyResult>) {
        write(context, unseen(context) - shown.toSet())
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val MAX_KEPT = 30
    private const val PREFS = "standby_mode"
    private const val KEY_UNSEEN = "unseen_results"
    private const val PACKAGE = "package"
    private const val REGION = "region"
    private const val OUTCOME = "outcome"
    private const val AT = "at"
}
