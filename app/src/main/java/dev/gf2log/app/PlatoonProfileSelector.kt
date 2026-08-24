package dev.gf2log.app

import android.app.AlertDialog
import android.app.Activity
import android.text.TextUtils
import android.widget.Button
import android.widget.Toast
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.GameServerRegion

/** Shared, presentation-only selector for the active isolated Platoon scope. */
internal object PlatoonProfileSelector {
    fun button(activity: Activity, compact: Boolean = false): Button {
        val registry = PlatoonProfileRegistry(activity)
        return Button(activity).apply {
            text = (registry.active()?.let { label(activity, it) }
                ?: activity.getString(R.string.no_platoon_detected)) + "  ▾"
            contentDescription = activity.getString(R.string.select_platoon)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (compact) {
                textSize = 11f
                minHeight = 0
                minimumHeight = 0
                maxWidth = dp(activity, 210)
                setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4))
                background = ModernUi.panelBackground(activity).apply {
                    setStroke(dp(activity, 1), activity.getColor(R.color.outline))
                }
            } else {
                useNavigationActionStyle()
            }
            setOnClickListener { show(activity, registry) }
        }
    }

    private fun label(activity: Activity, profile: PlatoonProfile): String = if (profile.legacy) {
        activity.getString(R.string.existing_platoon_data)
    } else {
        "${profile.client.displayName} / ${regionCode(profile.serverRegion)} / " +
            "${profile.platoonName} / ${profile.platoonId}"
    }

    private fun show(activity: Activity, registry: PlatoonProfileRegistry) {
        val profiles = registry.list()
        if (profiles.isEmpty()) {
            Toast.makeText(activity, R.string.no_platoon_detected_detail, Toast.LENGTH_SHORT).show()
            return
        }
        val activeId = registry.active()?.storageId
        AlertDialog.Builder(activity)
            .setTitle(R.string.select_platoon)
            .setSingleChoiceItems(
                profiles.map { label(activity, it) }.toTypedArray(),
                profiles.indexOfFirst { it.storageId == activeId },
            ) { dialog, which ->
                val selected = profiles[which]
                dialog.dismiss()
                if (selected.storageId != activeId && registry.setActive(selected.storageId)) {
                    activity.recreate()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun regionCode(region: GameServerRegion): String = when (region) {
        GameServerRegion.MANUAL -> "Manual"
        GameServerRegion.DARKWINTER_GLOBAL -> "GL"
        GameServerRegion.DARKWINTER_CHINA -> "CN"
        GameServerRegion.HAOPLAY_GLOBAL -> "GL"
        GameServerRegion.HAOPLAY_JAPAN -> "JP"
        GameServerRegion.HAOPLAY_KOREA -> "KR"
        GameServerRegion.HAOPLAY_ASIA -> "ASIA"
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
