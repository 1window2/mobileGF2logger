package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.PendingPlatoonAdmissionStore
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import java.lang.ref.WeakReference

/** Presents the process-memory admission gate without moving capture policy into an Activity. */
internal object PendingPlatoonAdmissionPrompt {
    private var visibleDialog = WeakReference<AlertDialog>(null)

    fun showIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (visibleDialog.get()?.isShowing == true) return
        val candidate = PendingPlatoonAdmissionStore.summaries().firstOrNull() ?: return
        show(activity, candidate)
    }

    private fun show(
        activity: Activity,
        candidate: PendingPlatoonAdmissionStore.Summary,
    ) {
        val client = PlatoonClient.fromPackage(candidate.ownerPackage) ?: return
        val regions = ClientServerRegionPreferences.allowedFor(candidate.ownerPackage)
        var selected: GameServerRegion? = null
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(4), activity.dp(20), 0)
            addView(TextView(context).apply {
                text = activity.getString(R.string.new_platoon_detected_message)
                textSize = 14f
                setTextColor(context.getColor(R.color.text_secondary))
            }, matchWidth())
            addView(profilePanel(activity, candidate, client), matchWidth().apply {
                topMargin = activity.dp(16)
                bottomMargin = activity.dp(12)
            })
            addView(TextView(context).apply {
                text = activity.getString(R.string.choose_server_region)
                textSize = 15f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, matchWidth())
            addView(RadioGroup(context).apply {
                orientation = RadioGroup.VERTICAL
                regions.forEach { region ->
                    addView(RadioButton(context).apply {
                        id = android.view.View.generateViewId()
                        text = activity.serverRegionLabel(region)
                        textSize = 14f
                        minimumHeight = activity.dp(48)
                        setOnCheckedChangeListener { _, checked ->
                            if (checked) selected = region
                        }
                    }, matchWidth())
                }
            }, matchWidth().apply { topMargin = activity.dp(4) })
            addView(TextView(context).apply {
                text = activity.getString(R.string.new_platoon_memory_only_notice)
                textSize = 12f
                setTextColor(context.getColor(R.color.text_secondary))
                setPadding(0, activity.dp(8), 0, 0)
            }, matchWidth())
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.new_platoon_detected)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.confirm, null)
            .setCancelable(false)
            .create()
        visibleDialog = WeakReference(dialog)
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                isEnabled = false
                usePrimaryActionStyle()
            }
            val radioGroup = content.getChildAt(3) as RadioGroup
            radioGroup.setOnCheckedChangeListener { _, checkedId ->
                val index = (0 until radioGroup.childCount)
                    .indexOfFirst { radioGroup.getChildAt(it).id == checkedId }
                selected = regions.getOrNull(index)
                confirm.isEnabled = selected != null
            }
            confirm.setOnClickListener {
                val region = selected ?: return@setOnClickListener
                if (!PendingPlatoonAdmissionStore.contains(candidate.token)) {
                    dialog.dismiss()
                    showIfNeeded(activity)
                    return@setOnClickListener
                }
                activity.startService(
                    Intent(activity, CaptureVpnService::class.java)
                        .setAction(CaptureVpnService.ACTION_CONFIRM_PENDING_PLATOON)
                        .putExtra(CaptureVpnService.EXTRA_PENDING_TOKEN, candidate.token)
                        .putExtra(CaptureVpnService.EXTRA_SERVER_REGION, region.storedValue),
                )
                dialog.dismiss()
                refreshAfterAdmission(activity, candidate.token)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                confirmDiscard(activity, dialog, candidate)
            }
        }
        dialog.setOnDismissListener {
            if (visibleDialog.get() === dialog) visibleDialog.clear()
        }
        dialog.show()
    }

    private fun profilePanel(
        activity: Activity,
        candidate: PendingPlatoonAdmissionStore.Summary,
        client: PlatoonClient,
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = ModernUi.panelBackground(context)
        setPadding(activity.dp(14), activity.dp(12), activity.dp(14), activity.dp(12))
        addView(TextView(context).apply {
            text = candidate.profile.platoonName
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, matchWidth())
        addView(TextView(context).apply {
            text = activity.getString(
                R.string.pending_platoon_identity,
                client.displayName,
                candidate.profile.platoonId.toLong(),
            )
            textSize = 13f
            setTextColor(context.getColor(R.color.text_secondary))
            setPadding(0, activity.dp(4), 0, 0)
        }, matchWidth())
    }

    private fun confirmDiscard(
        activity: Activity,
        parent: AlertDialog,
        candidate: PendingPlatoonAdmissionStore.Summary,
    ) {
        val warning = AlertDialog.Builder(activity)
            .setTitle(R.string.discard_pending_platoon_title)
            .setMessage(R.string.discard_pending_platoon_message)
            .setNegativeButton(R.string.keep_choosing, null)
            .setPositiveButton(R.string.discard, null)
            .create()
        warning.setOnShowListener {
            warning.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                useDestructiveActionStyle()
                setOnClickListener {
                    activity.startService(
                        Intent(activity, CaptureVpnService::class.java)
                            .setAction(CaptureVpnService.ACTION_DISCARD_PENDING_PLATOON)
                            .putExtra(CaptureVpnService.EXTRA_PENDING_TOKEN, candidate.token),
                    )
                    warning.dismiss()
                    parent.dismiss()
                    parent.window?.decorView?.postDelayed(
                        { showIfNeeded(activity) },
                        PROMPT_REFRESH_DELAY_MILLIS,
                    )
                }
            }
        }
        warning.show()
    }

    private fun refreshAfterAdmission(activity: Activity, token: String, attempt: Int = 0) {
        activity.window.decorView.postDelayed(
            {
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed
                if (!PendingPlatoonAdmissionStore.contains(token)) {
                    activity.recreate()
                } else if (attempt < MAX_REFRESH_ATTEMPTS) {
                    refreshAfterAdmission(activity, token, attempt + 1)
                } else {
                    showIfNeeded(activity)
                }
            },
            PROMPT_REFRESH_DELAY_MILLIS,
        )
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

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private const val PROMPT_REFRESH_DELAY_MILLIS = 250L
    private const val MAX_REFRESH_ATTEMPTS = 20
}
