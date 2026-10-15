package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.management.PlatoonProfileAdministration
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.ui.AccessibleRow
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.dpf
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.iconButton
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/** Shared, presentation-only selector and manager for isolated Platoon scopes. */
internal object PlatoonProfileSelector {
    /**
     * The active-Platoon card: emblem, name, and verified identity. Tapping it opens the selector;
     * a profile switch recreates the hosting screen so every repository rebinds to the new scope.
     */
    fun card(activity: Activity, compact: Boolean = false): View {
        val profile = PlatoonProfileRegistry(activity).active()
        return AccessibleRow(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.dp(if (compact) 56 else 64)
            setPadding(activity.dp(12), activity.dp(8), activity.dp(8), activity.dp(8))
            background = outlinedSurface(activity)
            contentDescription = activity.getString(R.string.select_platoon) + ". " +
                (profile?.let { "${it.platoonName}. ${Labels.profileIdentity(activity, it)}" }
                    ?: activity.getString(R.string.no_platoon_detected))
            setOnClickListener { showSelector(activity) }
            addView(emblem(activity, profile, if (compact) 36 else 44), activity.lp(
                width = if (compact) 36 else 44,
                height = if (compact) 36 else 44,
                end = 12,
            ))
            addView(activity.column {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(activity.text(
                    profile?.platoonName ?: activity.getString(R.string.no_platoon_detected),
                    if (compact) TextRole.SUBTITLE else TextRole.TITLE,
                ) {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, activity.lp())
                addView(activity.text(
                    profile?.let { Labels.profileIdentity(activity, it) }
                        ?: activity.getString(R.string.no_platoon_detected_detail),
                    TextRole.CAPTION,
                ) {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, activity.lp())
            }, activity.lp(width = 0, weight = 1f))
            addView(activity.icon(R.drawable.ic_expand_more), activity.lp(24, 24, start = 8))
        }
    }

    /** Transitional entry point for screens that still embed the selector inline. */
    fun controls(activity: Activity, compact: Boolean = false, showManageButton: Boolean = true): View =
        card(activity, compact)

    fun showSelector(activity: Activity) {
        val registry = PlatoonProfileRegistry(activity)
        val profiles = registry.list()
        if (profiles.isEmpty()) {
            Feedback.show(activity, R.string.no_platoon_detected_detail, long = true)
            return
        }
        val activeId = registry.active()?.storageId
        lateinit var dialog: AlertDialog
        val content = Dialogs.content(activity) {
            setPadding(activity.dp(12), activity.dp(4), activity.dp(12), activity.dp(4))
            profiles.forEach { profile ->
                addView(profileRow(activity, profile, selected = profile.storageId == activeId) {
                    dialog.dismiss()
                    if (profile.storageId != activeId && registry.setActive(profile.storageId)) {
                        activity.recreate()
                    }
                }, activity.lp(top = 2, bottom = 2))
            }
        }
        dialog = Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.select_platoon)
                .setView(content)
                .setNeutralButton(R.string.manage_platoon_profiles) { _, _ -> showManagement(activity) }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    fun showManagement(activity: Activity) {
        val registry = PlatoonProfileRegistry(activity)
        val profiles = registry.list()
        if (profiles.isEmpty()) {
            Feedback.show(activity, R.string.no_platoon_detected_detail, long = true)
            return
        }
        val activeId = registry.active()?.storageId
        lateinit var dialog: AlertDialog
        val content = Dialogs.content(activity) {
            setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(4))
            addView(activity.text(activity.getString(R.string.manage_platoon_profiles_detail), TextRole.SECONDARY) {
                setPadding(activity.dp(12), 0, activity.dp(12), activity.dp(8))
            }, activity.lp())
            profiles.forEachIndexed { index, profile ->
                if (index > 0) addView(activity.divider(insetStartDp = 64))
                addView(managementRow(activity, profile, profile.storageId == activeId) { dialog.dismiss() }, activity.lp())
            }
        }
        dialog = Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.manage_platoon_profiles)
                .setView(content)
                .setPositiveButton(R.string.done, null),
        )
    }

    private fun profileRow(
        activity: Activity,
        profile: PlatoonProfile,
        selected: Boolean,
        onClick: () -> Unit,
    ): View = AccessibleRow(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = activity.dp(64)
        setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8))
        background = if (selected) outlinedSurface(activity, accent = true) else activity.selectableRect()
        isSelected = selected
        contentDescription = "${profile.platoonName}. ${Labels.profileIdentity(activity, profile)}" +
            if (selected) ". " + activity.getString(R.string.active_platoon) else ""
        setOnClickListener { onClick() }
        addView(emblem(activity, profile, 40), activity.lp(40, 40, end = 12))
        addView(activity.column {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(activity.text(profile.platoonName, TextRole.SUBTITLE) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, activity.lp())
            addView(activity.text(Labels.profileIdentity(activity, profile), TextRole.CAPTION), activity.lp())
        }, activity.lp(width = 0, weight = 1f))
        if (selected) addView(activity.icon(R.drawable.ic_check, R.color.accent_text), activity.lp(24, 24, start = 8))
    }

    private fun managementRow(
        activity: Activity,
        profile: PlatoonProfile,
        active: Boolean,
        dismiss: () -> Unit,
    ): View = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = activity.dp(64)
        setPadding(activity.dp(12), activity.dp(8), 0, activity.dp(8))
        addView(emblem(activity, profile, 40), activity.lp(40, 40, end = 12))
        addView(activity.column {
            addView(activity.text(profile.platoonName, TextRole.SUBTITLE) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, activity.lp())
            addView(activity.text(
                Labels.profileIdentity(activity, profile) +
                    if (active) " · " + activity.getString(R.string.active_platoon) else "",
                TextRole.CAPTION,
            ), activity.lp())
        }, activity.lp(width = 0, weight = 1f))
        addView(activity.iconButton(
            R.drawable.ic_edit,
            activity.getString(R.string.edit_platoon_server_description, profile.platoonName),
        ) {
            dismiss()
            chooseProfileRegion(activity, profile)
        }, activity.lp(48, 48))
        addView(activity.iconButton(
            R.drawable.ic_delete,
            activity.getString(R.string.delete_platoon_description, profile.platoonName),
            tint = R.color.destructive_action,
        ) {
            dismiss()
            confirmDeleteFirst(activity, profile)
        }, activity.lp(48, 48))
    }

    private fun chooseProfileRegion(activity: Activity, profile: PlatoonProfile) {
        if (CaptureStatus.isRunning) {
            Feedback.show(activity, R.string.stop_capture_before_profile_change, long = true)
            return
        }
        val regions = ClientServerRegionPreferences.allowedFor(profile.client.packageName)
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(activity.getString(R.string.edit_platoon_server_title, profile.platoonName))
                .setSingleChoiceItems(
                    regions.map { Labels.region(activity, it) }.toTypedArray(),
                    regions.indexOf(profile.serverRegion),
                ) { dialog, which ->
                    dialog.dismiss()
                    val selected = regions[which]
                    if (selected != profile.serverRegion) {
                        runMaintenance(activity, R.string.platoon_server_update_failed) {
                            PlatoonProfileAdministration(activity).changeServerRegion(profile.storageId, selected)
                        }
                    }
                }
                .setNegativeButton(R.string.cancel, null),
        )
    }

    private fun confirmDeleteFirst(activity: Activity, profile: PlatoonProfile) {
        if (CaptureStatus.isRunning) {
            Feedback.show(activity, R.string.stop_capture_before_profile_change, long = true)
            return
        }
        Dialogs.confirm(
            activity,
            activity.getString(R.string.delete_platoon_profile),
            activity.getString(R.string.delete_platoon_first_warning, profile.platoonName),
            activity.getString(R.string.continue_action),
            destructive = true,
        ) { confirmDeleteByName(activity, profile) }
    }

    private fun confirmDeleteByName(activity: Activity, profile: PlatoonProfile) {
        val input = activity.textField(activity.getString(R.string.type_platoon_name), inputType = InputType.TYPE_CLASS_TEXT)
        val content = Dialogs.content(activity) {
            addView(activity.text(
                activity.getString(R.string.delete_platoon_name_warning, profile.platoonName),
                TextRole.SECONDARY,
            ), activity.lp())
            addView(input, activity.lp(top = 12))
        }
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.delete_platoon_profile)
                .setView(content)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, null),
            destructive = true,
        ) { dialog ->
            val delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            delete.isEnabled = false
            delete.setOnClickListener {
                if (input.text.toString() != profile.platoonName) return@setOnClickListener
                dialog.dismiss()
                runMaintenance(activity, R.string.platoon_delete_failed) {
                    check(PlatoonProfileAdministration(activity).deleteProfile(profile.storageId)) {
                        "Unable to delete the Platoon profile"
                    }
                }
            }
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    delete.isEnabled = s?.toString() == profile.platoonName
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }

    /**
     * Profile maintenance outlives a rotation: the result only refreshes the screen that is still
     * showing, and any later screen rebinds itself through [ActivePlatoonScopeBinding].
     */
    private fun runMaintenance(activity: Activity, failureMessage: Int, operation: () -> Unit) {
        val screen = WeakReference(activity)
        val appContext = activity.applicationContext
        maintenanceExecutor.execute {
            val result = runCatching(operation)
            // A rotation may have replaced the caller; refresh whichever screen is showing now.
            val current = screen.get()?.takeUnless { it.isFinishing || it.isDestroyed }
                ?: BaseActivity.foregroundScreen()
            if (current == null) {
                if (result.isFailure) Feedback.show(appContext, failureMessage, long = true)
                return@execute
            }
            current.runOnUiThread {
                if (current.isFinishing || current.isDestroyed) return@runOnUiThread
                if (result.isSuccess) current.recreate() else Feedback.show(current, failureMessage, long = true)
            }
        }
    }

    fun emblem(activity: Activity, profile: PlatoonProfile?, sizeDp: Int): View =
        ImageView(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val artwork = profile?.let { PlatoonBannerArtwork.drawable(activity, it) }
            if (artwork != null) {
                setImageDrawable(artwork)
            } else {
                setImageResource(R.drawable.ic_group)
                imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.text_secondary))
                scaleType = ImageView.ScaleType.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(activity.getColor(R.color.surface_variant))
                }
            }
            minimumWidth = activity.dp(sizeDp)
            minimumHeight = activity.dp(sizeDp)
        }

    private fun outlinedSurface(activity: Activity, accent: Boolean = false) =
        android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(activity.getColor(R.color.control_ripple)),
            GradientDrawable().apply {
                setColor(activity.getColor(if (accent) R.color.accent_surface else R.color.surface))
                cornerRadius = activity.dpf(16f)
                setStroke(activity.dp(1), activity.getColor(if (accent) R.color.accent else R.color.outline))
            },
            null,
        )

    private val maintenanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2ProfileMaintenance")
    }
}
