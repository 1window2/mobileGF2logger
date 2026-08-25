package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileAdministration
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import java.util.concurrent.Executors

/** Shared, presentation-only selector and manager for isolated Platoon scopes. */
internal object PlatoonProfileSelector {
    fun controls(activity: Activity, compact: Boolean = false): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                selectorButton(activity, compact),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                manageButton(activity),
                LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply {
                    marginStart = dp(activity, 8)
                },
            )
        }

    private fun selectorButton(activity: Activity, compact: Boolean): Button {
        val registry = PlatoonProfileRegistry(activity)
        return Button(activity).apply {
            text = (registry.active()?.let { label(activity, it) }
                ?: activity.getString(R.string.no_platoon_detected)) + "  ▾"
            contentDescription = activity.getString(R.string.select_platoon)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            if (compact) {
                textSize = 11f
                maxWidth = dp(activity, 210)
                setPadding(dp(activity, 10), 0, dp(activity, 10), 0)
                background = ModernUi.panelBackground(activity).apply {
                    setStroke(dp(activity, 1), activity.getColor(R.color.outline))
                }
            } else {
                useNavigationActionStyle()
            }
            setOnClickListener { showSelector(activity, registry) }
        }
    }

    private fun manageButton(activity: Activity) = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_edit)
        contentDescription = activity.getString(R.string.manage_platoon_profiles)
        useModernIconStyle()
        setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
        setOnClickListener { showManagement(activity, PlatoonProfileRegistry(activity)) }
    }

    private fun label(activity: Activity, profile: PlatoonProfile): String = if (profile.legacy) {
        activity.getString(R.string.existing_platoon_data)
    } else {
        "${profile.client.displayName} / ${regionCode(profile.serverRegion)} / " +
            "${profile.platoonName} / ${profile.platoonId}"
    }

    private fun showSelector(activity: Activity, registry: PlatoonProfileRegistry) {
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

    private fun showManagement(activity: Activity, registry: PlatoonProfileRegistry) {
        val profiles = registry.list()
        if (profiles.isEmpty()) {
            Toast.makeText(activity, R.string.no_platoon_detected_detail, Toast.LENGTH_SHORT).show()
            return
        }
        val activeId = registry.active()?.storageId
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 12), dp(activity, 4), dp(activity, 12), dp(activity, 4))
            profiles.forEachIndexed { index, profile ->
                addView(profileManagementRow(activity, profile, profile.storageId == activeId))
                if (index != profiles.lastIndex) {
                    addView(
                        View(context).apply {
                            setBackgroundColor(context.getColor(R.color.outline))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(activity, 1),
                        ).apply {
                            marginStart = dp(activity, 12)
                            marginEnd = dp(activity, 12)
                        },
                    )
                }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.manage_platoon_profiles)
            .setMessage(R.string.manage_platoon_profiles_detail)
            .setView(ScrollView(activity).apply { addView(list) })
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun profileManagementRow(
        activity: Activity,
        profile: PlatoonProfile,
        active: Boolean,
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(activity, 64)
        setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 4), dp(activity, 8))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = profile.platoonName
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, matchWidth())
                addView(TextView(context).apply {
                    text = if (profile.legacy) {
                        activity.getString(R.string.existing_platoon_data)
                    } else {
                        activity.getString(
                            R.string.profile_management_identity,
                            profile.client.displayName,
                            regionCode(profile.serverRegion),
                            profile.platoonId,
                        )
                    } + if (active) " · ${activity.getString(R.string.active_platoon)}" else ""
                    textSize = 12f
                    setTextColor(context.getColor(R.color.text_secondary))
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, matchWidth())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        if (!profile.legacy) {
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_edit)
                contentDescription = activity.getString(
                    R.string.edit_platoon_server_description,
                    profile.platoonName,
                )
                useModernIconStyle()
                setOnClickListener { chooseProfileRegion(activity, profile) }
            }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply {
                marginStart = dp(activity, 4)
            })
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_delete)
                imageTintList = ColorStateList.valueOf(context.getColor(R.color.destructive_action))
                contentDescription = activity.getString(
                    R.string.delete_platoon_description,
                    profile.platoonName,
                )
                background = ModernUi.panelBackground(context)
                setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12))
                setOnClickListener { confirmDeleteFirst(activity, profile) }
            }, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 48)).apply {
                marginStart = dp(activity, 4)
            })
        }
    }

    private fun chooseProfileRegion(activity: Activity, profile: PlatoonProfile) {
        if (CaptureStatus.isRunning) {
            Toast.makeText(activity, R.string.stop_capture_before_profile_change, Toast.LENGTH_LONG)
                .show()
            return
        }
        val regions = ClientServerRegionPreferences.allowedFor(profile.client.packageName)
        AlertDialog.Builder(activity)
            .setTitle(R.string.edit_platoon_server)
            .setMessage(
                activity.getString(
                    R.string.edit_platoon_server_message,
                    profile.client.displayName,
                    profile.platoonName,
                ),
            )
            .setSingleChoiceItems(
                regions.map { activity.serverRegionLabel(it) }.toTypedArray(),
                regions.indexOf(profile.serverRegion),
            ) { dialog, which ->
                dialog.dismiss()
                val selected = regions[which]
                if (selected != profile.serverRegion) {
                    runMaintenance(activity, R.string.platoon_server_update_failed) {
                        PlatoonProfileAdministration(activity)
                            .changeServerRegion(profile.storageId, selected)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteFirst(activity: Activity, profile: PlatoonProfile) {
        if (CaptureStatus.isRunning) {
            Toast.makeText(activity, R.string.stop_capture_before_profile_change, Toast.LENGTH_LONG)
                .show()
            return
        }
        val warning = AlertDialog.Builder(activity)
            .setTitle(R.string.delete_platoon_profile)
            .setMessage(activity.getString(R.string.delete_platoon_first_warning, profile.platoonName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()
        warning.setOnShowListener {
            warning.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                useDestructiveActionStyle()
                setOnClickListener {
                    warning.dismiss()
                    confirmDeleteByName(activity, profile)
                }
            }
        }
        warning.show()
    }

    private fun confirmDeleteByName(activity: Activity, profile: PlatoonProfile) {
        val input = EditText(activity).apply {
            hint = profile.platoonName
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            setSingleLine(true)
            contentDescription = activity.getString(R.string.type_platoon_name)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 4), dp(activity, 20), 0)
            addView(TextView(context).apply {
                text = activity.getString(R.string.delete_platoon_name_warning, profile.platoonName)
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
            }, matchWidth())
            addView(input, matchWidth().apply { topMargin = dp(activity, 12) })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.delete_platoon_profile)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete, null)
            .create()
        dialog.setOnShowListener {
            val delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                useDestructiveActionStyle()
                setOnClickListener {
                    if (input.text.toString() != profile.platoonName) return@setOnClickListener
                    dialog.dismiss()
                    runMaintenance(activity, R.string.platoon_delete_failed) {
                        check(PlatoonProfileAdministration(activity).deleteProfile(profile.storageId)) {
                            "Unable to delete the Platoon profile"
                        }
                    }
                }
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    delete.isEnabled = s?.toString() == profile.platoonName
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        dialog.show()
    }

    private fun runMaintenance(
        activity: Activity,
        failureMessage: Int,
        operation: () -> Unit,
    ) {
        maintenanceExecutor.execute {
            val result = runCatching(operation)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (result.isSuccess) {
                    activity.recreate()
                } else {
                    Toast.makeText(activity, failureMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun Activity.serverRegionLabel(region: GameServerRegion): String = getString(
        when (region) {
            GameServerRegion.MANUAL -> R.string.server_region_manual
            GameServerRegion.DARKWINTER_GLOBAL -> R.string.server_region_darkwinter_global
            GameServerRegion.DARKWINTER_CHINA -> R.string.server_region_darkwinter_china
            GameServerRegion.HAOPLAY_GLOBAL -> R.string.server_region_haoplay_global
            GameServerRegion.HAOPLAY_JAPAN -> R.string.server_region_haoplay_japan
            GameServerRegion.HAOPLAY_KOREA -> R.string.server_region_haoplay_korea
            GameServerRegion.HAOPLAY_ASIA -> R.string.server_region_haoplay_asia
        },
    )

    private fun regionCode(region: GameServerRegion): String = when (region) {
        GameServerRegion.MANUAL -> "Manual"
        GameServerRegion.DARKWINTER_GLOBAL -> "GL"
        GameServerRegion.DARKWINTER_CHINA -> "CN"
        GameServerRegion.HAOPLAY_GLOBAL -> "GL"
        GameServerRegion.HAOPLAY_JAPAN -> "JP"
        GameServerRegion.HAOPLAY_KOREA -> "KR"
        GameServerRegion.HAOPLAY_ASIA -> "ASIA"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private val maintenanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2ProfileMaintenance")
    }
}
