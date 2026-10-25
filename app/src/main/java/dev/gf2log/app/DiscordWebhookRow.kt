package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import dev.gf2log.app.discord.DiscordWebhookSecretStore
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.errorLine
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.showMessage
import dev.gf2log.app.ui.text
import dev.gf2log.app.ui.textField

/** Settings row for the Discord webhook: shows whether one is stored, and edits or clears it. */
internal class DiscordWebhookRow(private val activity: Activity, private val work: BackgroundWork) {
    val view = activity.column()

    init {
        refresh()
    }

    private fun refresh() {
        work.run(task = { DiscordWebhookSecretStore(activity).read() != null }) { result ->
            val configured = result.getOrDefault(false)
            view.removeAllViews()
            view.addView(activity.group(listOf(
                activity.navigationRow(
                    activity.getString(if (configured) R.string.discord_webhook_configured else R.string.discord_webhook_not_configured),
                    activity.getString(R.string.discord_webhook_summary),
                    R.drawable.ic_discord,
                    trailing = activity.getString(if (configured) R.string.change else R.string.set_up),
                ) { edit(configured) },
            )), activity.lp())
        }
    }

    private fun edit(configured: Boolean) = with(activity) {
        val input = textField(
            getString(R.string.discord_webhook_hint),
            id = R.id.webhook_input,
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val error = errorLine()
        val builder = Dialogs.builder(this)
            .setTitle(R.string.discord_webhook)
            .setView(Dialogs.content(this) {
                addView(text(getString(R.string.discord_webhook_description), TextRole.SECONDARY), lp())
                addView(input, lp(top = 12))
                addView(error, lp(top = 4))
            })
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save_discord_webhook, null)
        if (configured) builder.setNeutralButton(R.string.clear_discord_webhook, null)
        Dialogs.show(builder) { dialog ->
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (work.isBusy(WRITE)) return@setOnClickListener
                work.run(key = WRITE, task = { DiscordWebhookSecretStore(this).save(value) }) { result ->
                    if (result.isSuccess) {
                        dialog.dismiss()
                        Feedback.show(this, R.string.discord_webhook_saved)
                        refresh()
                    } else {
                        error.showMessage(getString(R.string.discord_webhook_invalid))
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
                setTextColor(getColor(R.color.destructive_action))
                setOnClickListener {
                    if (work.isBusy(WRITE)) return@setOnClickListener
                    // The address cannot be shown again once removed, so removal asks first.
                    Dialogs.confirm(
                        activity,
                        R.string.clear_discord_webhook,
                        R.string.clear_discord_webhook_confirm,
                        R.string.clear_discord_webhook,
                        destructive = true,
                    ) {
                        if (work.isBusy(WRITE)) return@confirm
                        work.run(key = WRITE, task = { DiscordWebhookSecretStore(activity).clear() }) { result ->
                            dialog.dismiss()
                            Feedback.show(
                                activity,
                                if (result.isSuccess) R.string.discord_webhook_cleared else R.string.discord_webhook_clear_failed,
                            )
                            refresh()
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val WRITE = "webhook"
    }
}
