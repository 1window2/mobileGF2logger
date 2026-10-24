package dev.gf2log.app

import android.app.AlertDialog
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.annotation.StringRes
import dev.gf2log.app.management.PlatoonClient
import dev.gf2log.app.standby.StandbyPlanner
import dev.gf2log.app.standby.StandbyServer
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.checkBox
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.text
import java.time.Instant
import java.time.ZoneId

/**
 * The server choice shared by standby mode and scheduled recording: clients separated by a
 * full-width divider, at most one server per client, each labelled with the start the current
 * selection would really get.
 */
internal object StandbyServerDialog {

    fun show(
        activity: BaseActivity,
        servers: List<StandbyServer>,
        preselected: Set<String>,
        @StringRes message: Int,
        @StringRes confirm: Int,
        onCancel: () -> Unit = {},
        onChosen: (List<StandbyServer>) -> Unit,
    ) {
        val boxes = linkedMapOf<StandbyServer, CheckBox>()
        lateinit var dialog: AlertDialog
        val now = Instant.now()
        val zone = ZoneId.systemDefault()

        fun label(server: StandbyServer, start: Instant): String = activity.getString(
            R.string.standby_server_option,
            Labels.regionShort(activity, server.region),
            server.platoonNames.joinToString(", "),
            Labels.format(Labels.TIME, start, zone),
        )

        // Servers sharing a reset start one after another, so each label shows the start the
        // current selection would really get.
        fun refresh() {
            val checked = boxes.filterValues(CheckBox::isChecked).keys
            val valid = StandbyPlanner.isValidSelection(checked)
            val planned = if (valid) StandbyPlanner.plan(checked, now).associate { it.server to it.startAt } else emptyMap()
            boxes.forEach { (server, box) ->
                box.text = label(server, planned[server] ?: StandbyPlanner.nextReset(server.zone, now).minus(StandbyPlanner.LEAD))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = valid
        }

        val body = activity.column {
            setPadding(activity.dp(24), activity.dp(8), activity.dp(24), activity.dp(8))
            clipToPadding = false
            addView(activity.text(activity.getString(message), TextRole.SECONDARY), activity.lp(bottom = 8))
            servers.groupBy(StandbyServer::packageName).values.forEachIndexed { clientIndex, clientServers ->
                if (clientIndex > 0) addView(fullWidthDivider(activity))
                val packageName = clientServers.first().packageName
                addView(
                    activity.text(PlatoonClient.fromPackage(packageName)?.displayName ?: packageName, TextRole.LABEL),
                    activity.lp(top = 4),
                )
                // Default to the preselected server of this client, if it still exists.
                val preferred = clientServers.firstOrNull { it.key in preselected }
                clientServers.forEach { server ->
                    val start = StandbyPlanner.nextReset(server.zone, now).minus(StandbyPlanner.LEAD)
                    val box = activity.checkBox(label(server, start), server == preferred) { checked ->
                        // A client logs in to one server at a time: checking one clears its siblings.
                        if (checked) {
                            clientServers.filter { it != server }.forEach { boxes[it]?.isChecked = false }
                        }
                        refresh()
                    }
                    boxes[server] = box
                    addView(box, activity.lp())
                }
            }
        }
        dialog = Dialogs.show(
            Dialogs.builder(activity)
                .setTitle(R.string.standby_servers_title)
                .setView(ScrollView(activity).apply { addView(body) })
                .setOnCancelListener { onCancel() }
                .setNegativeButton(R.string.cancel) { _, _ -> onCancel() }
                .setPositiveButton(confirm) { _, _ ->
                    val chosen = boxes.filterValues(CheckBox::isChecked).keys.toList()
                    if (StandbyPlanner.isValidSelection(chosen)) onChosen(chosen)
                },
        ) { refresh() }
    }

    /** A divider spanning the whole dialog width, through the content's side padding. */
    private fun fullWidthDivider(activity: BaseActivity): View = View(activity).apply {
        setBackgroundColor(activity.getColor(R.color.outline_strong))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, activity.dp(1))).apply {
            marginStart = -activity.dp(24)
            marginEnd = -activity.dp(24)
            topMargin = activity.dp(12)
            bottomMargin = activity.dp(8)
        }
    }
}
