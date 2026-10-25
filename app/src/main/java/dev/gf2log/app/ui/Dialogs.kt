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
    private const val DISABLED_ALPHA = 97 // 38%, the platform's disabled-content opacity

    fun builder(context: Context): AlertDialog.Builder = AlertDialog.Builder(context)

    /** Shows the dialog and applies the shared button palette once its buttons exist. */
    /** [color], dimmed while the button is disabled so an unavailable action looks unavailable. */
    private fun buttonColors(color: Int) = android.content.res.ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color, DISABLED_ALPHA), color),
    )

    fun show(
        builder: AlertDialog.Builder,
        destructive: Boolean = false,
        onShown: (AlertDialog) -> Unit = {},
    ): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            val context = dialog.context
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(buttonColors(context.getColor(if (destructive) R.color.destructive_action else R.color.accent_text)))
                isAllCaps = false
            }
            listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { which ->
                dialog.getButton(which)?.apply {
                    setTextColor(buttonColors(context.getColor(R.color.text_secondary)))
                    isAllCaps = false
                }
            }
            onShown(dialog)
        }
        return showTracked(dialog)
    }

    /**
     * Shows a platform dialog that is not built through [builder], such as a date or time picker,
     * and registers it with its screen so [dismissAll] closes it on recreation.
     */
    fun <D : android.app.Dialog> showTracked(dialog: D): D {
        val owner = owner(dialog.context)
        // Background results can arrive after finish(); a leaving screen gets no new window.
        if (owner != null && (owner.isFinishing || owner.isDestroyed)) return dialog
        dialog.show()
        owner?.let { open.getOrPut(it) { mutableListOf() }.add(java.lang.ref.WeakReference(dialog)) }
        return dialog
    }

    private val open = java.util.WeakHashMap<android.app.Activity, MutableList<java.lang.ref.WeakReference<android.app.Dialog>>>()

    private tailrec fun owner(context: Context): android.app.Activity? = when (context) {
        is android.app.Activity -> context
        is android.content.ContextWrapper -> owner(context.baseContext)
        else -> null
    }

    /** Closes every dialog a screen opened so a recreated screen never leaks its windows. */
    fun dismissAll(owner: android.app.Activity) {
        open.remove(owner)?.forEach { it.get()?.takeIf(android.app.Dialog::isShowing)?.dismiss() }
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

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun show(context: Context, message: CharSequence, long: Boolean = false) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            val appContext = context.applicationContext
            main.post { show(appContext, message, long) }
            return
        }
        active?.cancel()
        active = Toast.makeText(
            context.applicationContext,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
        ).also(Toast::show)
    }

    fun show(context: Context, @StringRes message: Int, long: Boolean = false) {
        // Only Activities carry the app's own language; anything else would show the device's.
        val localized = context as? android.app.Activity ?: dev.gf2log.app.LanguagePreferences.wrap(context.applicationContext)
        show(context, localized.getString(message), long)
    }

    /** Copies plain [value] under the clipboard [label] and confirms with [confirmation]. */
    fun copy(context: Context, label: CharSequence, value: CharSequence, @StringRes confirmation: Int) {
        context.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText(label, value))
        show(context, confirmation)
    }
}
