package dev.gf2log.app

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes

/** Keeps app-owned dialogs visually consistent while preserving Android dialog behavior. */
internal fun AlertDialog.applyModernDialogStyle(): AlertDialog = apply {
    window?.setBackgroundDrawable(ModernUi.dialogBackground(context))
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(context.getColor(R.color.accent))
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(context.getColor(R.color.text_secondary))
    getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(context.getColor(R.color.text_secondary))
}

internal fun AlertDialog.Builder.showModern(): AlertDialog = show().applyModernDialogStyle()

/**
 * Replaces the currently visible transient message instead of extending a long Toast queue.
 * Android does not expose its internal queue, so cancelling the prior app-owned Toast is the
 * deterministic way to keep rapid repeated actions responsive.
 */
internal object TransientMessage {
    private var active: Toast? = null

    @Synchronized
    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        active?.cancel()
        active = Toast.makeText(context.applicationContext, message, duration).also(Toast::show)
    }

    fun show(context: Context, @StringRes message: Int, duration: Int = Toast.LENGTH_SHORT) {
        show(context, context.getString(message), duration)
    }
}
