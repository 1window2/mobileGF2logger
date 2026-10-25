package dev.gf2log.app

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import dev.gf2log.app.capture.CaptureNotice
import dev.gf2log.app.capture.CapturePhase
import dev.gf2log.app.capture.CaptureSnapshot
import dev.gf2log.app.capture.CaptureStatus
import dev.gf2log.app.capture.format
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.text
import dev.gf2log.protocol.Gfl2PayloadDecoder

/**
 * Home's capture panel: the capture phase and its latest notice, the guided checklist, installed
 * clients, and the start or stop actions. It only renders [CaptureSnapshot]s; the hosting screen
 * owns permissions and the capture service.
 */
internal class HomeCapturePanel(
    private val context: Context,
    private val onStart: (once: Boolean) -> Unit,
    private val onStop: () -> Unit,
) {
    private val stateDot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val stateTitle = context.text(null, TextRole.HEADLINE) {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val stateDetail = context.text(null, TextRole.SECONDARY) { maxLines = 4 }
    private val checklist = context.column { visibility = View.GONE }
    private val captureActions = context.row(Gravity.TOP)
    private val clients = context.column()
    private var lastPhase: CapturePhase? = null

    val view: LinearLayout = context.panel(outlined = true) {
        addView(context.row {
            addView(stateDot, context.lp(10, 10, end = 8))
            addView(context.text(context.getString(R.string.capture_status_label), TextRole.LABEL), context.lp(width = 0, weight = 1f))
        }, context.lp())
        addView(stateTitle, context.lp(top = 6))
        addView(stateDetail, context.lp(top = 2))
        addView(checklist, context.lp(top = 8))
        addView(clients, context.lp(top = 12))
        addView(captureActions, context.lp(top = 12))
    }

    init {
        renderClients()
    }

    /** Rebuilt on every resume so installing or removing a client shows up immediately. */
    fun renderClients() = with(context) {
        clients.removeAllViews()
        SupportedGamePackages.all.forEachIndexed { index, packageName ->
            val installed = isInstalled(packageName)
            val client = clientName(packageName)
            val status = getString(if (installed) R.string.client_installed else R.string.client_not_installed)
            clients.addView(row {
                minimumHeight = dp(40)
                isFocusable = true
                contentDescription = "$client. $packageName. $status"
                addView(column {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(text(client, TextRole.BODY), lp())
                    addView(text(packageName, TextRole.CAPTION) {
                        typeface = Typeface.MONOSPACE
                        textSize = 11f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }, lp())
                }, lp(width = 0, weight = 1f))
                addView(badge(
                    status,
                    // Page-colored fill keeps the badge legible on the green running panel too.
                    if (installed) R.color.app_background else R.color.surface_variant,
                    if (installed) R.color.success_text else R.color.text_secondary,
                ).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(WRAP, WRAP, start = 8))
            }, lp(top = if (index == 0) 0 else 6))
        }
    }

    fun render(snapshot: CaptureSnapshot) = with(context) {
        val phase = snapshot.phase
        val (title, color) = when (phase) {
            CapturePhase.STOPPED -> R.string.capture_ready to R.color.text_primary
            CapturePhase.STARTING -> R.string.status_preparing to R.color.accent_text
            CapturePhase.RUNNING -> R.string.capture_running to R.color.success_text
            CapturePhase.STOPPING -> R.string.capture_stopping to R.color.text_secondary
        }
        stateTitle.text = getString(title)
        stateTitle.setTextColor(getColor(color))
        // A running capture tints the whole panel so its state reads from across the room.
        (view.background as GradientDrawable).apply {
            setColor(getColor(if (phase == CapturePhase.RUNNING) R.color.success_surface else R.color.surface))
            setStroke(dp(1), getColor(if (phase == CapturePhase.RUNNING) R.color.success_mark else R.color.outline))
        }
        (stateDot.background as GradientDrawable).setColor(
            getColor(
                when (phase) {
                    CapturePhase.STOPPED, CapturePhase.STOPPING -> R.color.outline_strong
                    CapturePhase.STARTING -> R.color.accent
                    CapturePhase.RUNNING -> R.color.success_mark
                },
            ),
        )
        val notice = snapshot.notice
        stateDetail.text = when {
            notice?.notice == CaptureNotice.CAPTURING -> getString(R.string.capturing_clients, clientNames(snapshot.capturedTargets))
            notice != null && notice.notice != CaptureNotice.PREPARING -> notice.format(this)
            phase == CapturePhase.STOPPED -> getString(R.string.capture_ready_detail)
            phase == CapturePhase.STOPPING -> getString(R.string.capture_stopping_detail)
            else -> getString(R.string.capture_preparing_detail)
        }
        stateDetail.setTextColor(
            getColor(
                when (notice?.notice?.severity) {
                    CaptureNotice.Severity.ERROR -> R.color.destructive_action
                    CaptureNotice.Severity.WARNING -> R.color.warning_text
                    else -> R.color.text_secondary
                },
            ),
        )
        renderChecklist(snapshot.guided)
        if (phase != lastPhase) {
            lastPhase = phase
            renderActions(phase)
        }
    }

    private fun clientNames(packages: List<String>): String {
        val names = packages.map(::clientName)
        return when (names.size) {
            0 -> context.getString(R.string.none)
            1 -> names.single()
            else -> context.getString(R.string.clients_and, names[0], names.drop(1).joinToString(", "))
        }
    }

    private fun clientName(packageName: String) = when (packageName) {
        SupportedGamePackages.HAOPLAY -> "HaoPlay"
        SupportedGamePackages.DARKWINTER -> "Darkwinter"
        else -> packageName
    }

    private fun renderChecklist(guided: CaptureStatus.GuidedProgress?) = with(context) {
        checklist.removeAllViews()
        checklist.visibility = if (guided == null) View.GONE else View.VISIBLE
        guided ?: return@with
        listOf(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE to R.string.checklist_identity,
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS to R.string.checklist_roster,
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY to R.string.checklist_activity,
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES to R.string.checklist_updates,
        ).forEach { (type, label) ->
            val done = type in guided.capturedPayloadTypes
            checklist.addView(row {
                minimumHeight = dp(32)
                isFocusable = true
                contentDescription = getString(label) + ". " + getString(if (done) R.string.captured else R.string.waiting)
                addView(icon(
                    if (done) R.drawable.ic_check_circle else R.drawable.ic_circle_outline,
                    if (done) R.color.success_mark else R.color.outline_strong,
                    sizeDp = 20,
                ), lp(20, 20, end = 10))
                addView(text(getString(label), TextRole.BODY) {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if (!done) setTextColor(getColor(R.color.text_secondary))
                }, lp(width = 0, weight = 1f))
            }, lp())
        }
    }

    private fun renderActions(phase: CapturePhase) = with(context) {
        captureActions.removeAllViews()
        if (phase == CapturePhase.STOPPED) {
            captureActions.addView(
                button(getString(R.string.capture_one_roster), Controls.Role.PRIMARY, R.drawable.ic_bolt) { onStart(true) },
                lp(width = 0, weight = 1f, end = 4),
            )
            captureActions.addView(
                button(getString(R.string.prepare_capture), Controls.Role.CAPTURE, R.drawable.ic_play) { onStart(false) },
                lp(width = 0, weight = 1f, start = 4),
            )
        } else {
            captureActions.addView(
                button(getString(R.string.stop_capture), Controls.Role.DESTRUCTIVE, R.drawable.ic_stop) { onStop() }.apply {
                    // Already stopping: nothing more to request until the teardown finishes.
                    isEnabled = phase != CapturePhase.STOPPING
                },
                lp(),
            )
        }
    }

    private fun isInstalled(packageName: String): Boolean = runCatching {
        val packages = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packages.getPackageInfo(packageName, 0)
        }
    }.isSuccess
}
