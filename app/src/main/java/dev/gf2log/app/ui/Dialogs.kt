package dev.gf2log.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.annotation.StringRes
import dev.gf2log.app.R

/** App-owned dialogs: one surface, one accent, and an explicit red treatment for destruction. */
internal object Dialogs {
    fun builder(context: Context): AlertDialog.Builder = AlertDialog.Builder(context)

    /** Shows the dialog and applies the shared button palette once its buttons exist. */
    fun show(
        builder: AlertDialog.Builder,
        destructive: Boolean = false,
        onShown: (AlertDialog) -> Unit = {},
    ): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            val context = dialog.context
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(context.getColor(if (destructive) R.color.destructive_action else R.color.accent_text))
                isAllCaps = false
            }
            listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
                dialog.getButton(which)?.apply {
                    setTextColor(context.getColor(R.color.text_secondary))
                    isAllCaps = false
                }
            }
            onShown(dialog)
        }
        dialog.show()
        return dialog
    }

    fun confirm(
        context: Context,
        title: CharSequence,
        message: CharSequence?,
        confirmLabel: CharSequence,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ): AlertDialog = show(
        builder(context)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm() },
        destructive = destructive,
    )

    fun confirm(
        context: Context,
        @StringRes title: Int,
        @StringRes message: Int,
        @StringRes confirmLabel: Int,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ): AlertDialog = confirm(
        context,
        context.getString(title),
        context.getString(message),
        context.getString(confirmLabel),
        destructive,
        onConfirm,
    )

    fun choose(
        context: Context,
        title: CharSequence,
        labels: List<CharSequence>,
        checked: Int,
        onChosen: (Int) -> Unit,
    ): AlertDialog = show(
        builder(context)
            .setTitle(title)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                dialog.dismiss()
                onChosen(which)
            }
            .setNegativeButton(R.string.cancel, null),
    )

    fun message(context: Context, title: CharSequence, message: CharSequence): AlertDialog = show(
        builder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null),
    )

    /** Padded, scrollable dialog body aligned with the platform title inset. */
    fun content(context: Context, block: LinearLayout.() -> Unit): View = ScrollView(context).apply {
        addView(context.column {
            setPadding(context.dp(24), context.dp(8), context.dp(24), context.dp(8))
            block()
        })
    }
}

/**
 * Replaces the visible transient message instead of extending Android's Toast queue, so rapid
 * repeated actions keep their feedback current.
 */
internal object Feedback {
    private var active: Toast? = null

    @Synchronized
    fun show(context: Context, message: CharSequence, long: Boolean = false) {
        active?.cancel()
        active = Toast.makeText(
            context.applicationContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    fun show(context: Context, @StringRes message: Int, long: Boolean = false) {
        show(context, context.getString(message), long)
    }
}
