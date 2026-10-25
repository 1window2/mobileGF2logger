package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.TextUtils
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import dev.gf2log.app.capture.CaptureVpnService
import dev.gf2log.app.capture.PendingPlatoonAdmissionStore
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.text
import java.lang.ref.WeakReference

/**
 * Presents the process-memory admission gate for a newly detected Platoon. Screens observe the
 * pending store, so the prompt appears as soon as a candidate arrives and closes when it is
 * confirmed or discarded elsewhere. A confirmation keeps the prompt open while it runs, and a
 * failed one comes back to it with the reason the store recorded.
 */
internal object PendingPlatoonAdmissionPrompt {
    private var visible = WeakReference<AlertDialog>(null)
    private var owner = WeakReference<Activity>(null)
    private var visibleToken: String? = null
    private var refreshVisible: ((PendingPlatoonAdmissionStore.Summary) -> Unit)? = null
    private val selections = HashMap<String, GameServerRegion>()

    /**
     * The candidate's attempt count when a confirmation was sent. Until the worker claims it and
     * the count moves on, the request is still queued and an earlier failure is not shown.
     */
    private val requested = HashMap<String, Int>()

    fun onCandidatesChanged(activity: Activity, candidates: List<PendingPlatoonAdmissionStore.Summary>) {
        if (activity.isFinishing || activity.isDestroyed) return
        val showing = visible.get()?.takeIf { it.isShowing }
        if (showing != null) {
            val current = candidates.firstOrNull { it.token == visibleToken }
            if (current != null) {
                refreshVisible?.invoke(current)
                return
            }
            // The visible candidate was resolved; move on to the next queued one.
            showing.dismiss()
            clearVisible()
        }
        val tokens = candidates.mapTo(mutableSetOf()) { it.token }
        selections.keys.retainAll(tokens)
        requested.keys.retainAll(tokens)
        candidates.firstOrNull()?.let { show(activity, it) }
    }

    /** Dismisses the prompt only when [activity] owns it, so a newly started screen keeps its own. */
    fun dismiss(activity: Activity) {
        if (owner.get() !== activity) return
        visible.get()?.dismiss()
        clearVisible()
    }

    private fun clearVisible() {
        visible.clear()
        owner.clear()
        visibleToken = null
        refreshVisible = null
    }

    private fun show(activity: Activity, candidate: PendingPlatoonAdmissionStore.Summary) {
        val client = PlatoonClient.fromPackage(candidate.ownerPackage) ?: return
        val regions = ClientServerRegionPreferences.allowedFor(candidate.ownerPackage)
        // The server chosen in Settings is preselected; the user still confirms it explicitly.
        if (candidate.token !in selections) {
            ClientServerRegionPreferences(activity).configured(candidate.ownerPackage)
                ?.let { selections[candidate.token] = it }
        }
        val radios = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val status = activity.text(null, TextRole.CAPTION) {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val content = Dialogs.content(activity) {
            addView(activity.text(activity.getString(R.string.new_platoon_detected_message), TextRole.SECONDARY), activity.lp())
            addView(activity.panel(padding = 12) {
                addView(activity.text(candidate.profile.platoonName, TextRole.TITLE) {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, activity.lp())
                addView(activity.text(
                    activity.getString(R.string.pending_platoon_identity, client.displayName, candidate.profile.platoonId.toLong()),
                    TextRole.CAPTION,
                ), activity.lp(top = 2))
            }, activity.lp(top = 12))
            addView(activity.text(activity.getString(R.string.choose_server_region), TextRole.SUBTITLE), activity.lp(top = 16))
            addView(radios, activity.lp(top = 4))
            addView(status, activity.lp(top = 8))
            addView(activity.text(activity.getString(R.string.new_platoon_memory_only_notice), TextRole.CAPTION), activity.lp(top = 8))
        }
        var latest = candidate
        var render: (() -> Unit)? = null
        val dialog = Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.new_platoon_detected)
                .setView(content)
                .setCancelable(false)
                .setNegativeButton(R.string.discard, null)
                .setPositiveButton(R.string.confirm, null),
        ) { shown ->
            val confirm = shown.getButton(AlertDialog.BUTTON_POSITIVE)
            val discard = shown.getButton(AlertDialog.BUTTON_NEGATIVE)
            val ids = regions.map { region ->
                RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = Labels.region(activity, region)
                    textSize = 14f
                    setTextColor(activity.getColor(R.color.text_primary))
                    buttonTintList = Controls.controlTint(activity)
                    minimumHeight = activity.dp(48)
                    isChecked = selections[candidate.token] == region
                }.also { radios.addView(it) }.id
            }
            radios.setOnCheckedChangeListener { _, checkedId ->
                regions.getOrNull(ids.indexOf(checkedId))?.let { selections[candidate.token] = it }
                render?.invoke()
            }
            render = {
                if (requested[candidate.token].let { it != null && it < latest.attempts }) {
                    requested -= candidate.token
                }
                val busy = latest.admitting || candidate.token in requested
                val failure = latest.failure
                when {
                    busy -> {
                        status.text = activity.getString(R.string.confirming_platoon)
                        status.setTextColor(activity.getColor(R.color.text_secondary))
                        status.visibility = View.VISIBLE
                    }
                    failure != null -> {
                        status.text = activity.getString(failure.messageRes)
                        status.setTextColor(activity.getColor(R.color.destructive_action))
                        status.visibility = View.VISIBLE
                    }
                    else -> status.visibility = View.GONE
                }
                confirm.isEnabled = !busy && candidate.token in selections
                discard.isEnabled = !busy
            }
            render?.invoke()
            confirm.setOnClickListener {
                val region = selections[candidate.token] ?: return@setOnClickListener
                requested[candidate.token] = latest.attempts
                render?.invoke()
                activity.startService(
                    Intent(activity, CaptureVpnService::class.java)
                        .setAction(CaptureVpnService.ACTION_CONFIRM_PENDING_PLATOON)
                        .putExtra(CaptureVpnService.EXTRA_PENDING_TOKEN, candidate.token)
                        .putExtra(CaptureVpnService.EXTRA_SERVER_REGION, region.storedValue),
                )
            }
            discard.setOnClickListener { confirmDiscard(activity, candidate) }
        }
        dialog.setOnDismissListener {
            if (visible.get() === dialog) clearVisible()
        }
        visible = WeakReference(dialog)
        owner = WeakReference(activity)
        visibleToken = candidate.token
        refreshVisible = { summary ->
            latest = summary
            render?.invoke()
        }
    }

    private fun confirmDiscard(activity: Activity, candidate: PendingPlatoonAdmissionStore.Summary) {
        Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.discard_pending_platoon_title)
                .setMessage(R.string.discard_pending_platoon_message)
                .setNegativeButton(R.string.keep_choosing, null)
                .setPositiveButton(R.string.discard) { _, _ ->
                    activity.startService(
                        Intent(activity, CaptureVpnService::class.java)
                            .setAction(CaptureVpnService.ACTION_DISCARD_PENDING_PLATOON)
                            .putExtra(CaptureVpnService.EXTRA_PENDING_TOKEN, candidate.token),
                    )
                },
            destructive = true,
        )
    }
}
