package dev.gf2log.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.settings.PayloadHistoryPreferences
import dev.gf2log.protocol.PayloadCatalog

class OptionsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.payload_options)
        setContentView(buildContentView())
    }

    private fun buildContentView(): ScrollView {
        val spacing = dp(16)
        val preferences = PayloadHistoryPreferences(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)

            addView(TextView(context).apply {
                text = getString(R.string.payload_options)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.payload_options_description)
                textSize = 15f
                setPadding(0, dp(8), 0, spacing)
            }, matchWidth())

            PayloadCatalog.categories.forEach { category ->
                addView(CheckBox(context).apply {
                    text = getString(
                        R.string.payload_option_label,
                        category.name,
                        category.payloadType,
                    )
                    textSize = 17f
                    isChecked = preferences.isEnabled(category.payloadType)
                    isEnabled = !category.isRequired
                    if (category.isRequired) {
                        buttonTintList = ColorStateList(
                            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                            intArrayOf(Color.rgb(49, 93, 168), Color.rgb(49, 93, 168)),
                        )
                    }
                    setOnCheckedChangeListener { _, enabled ->
                        preferences.setEnabled(category.payloadType, enabled)
                    }
                }, matchWidth())
                addView(TextView(context).apply {
                    text = if (category.isRequired) {
                        getString(R.string.required_payload_description, category.description)
                    } else {
                        category.description
                    }
                    textSize = 14f
                    setPadding(dp(48), 0, 0, spacing)
                }, matchWidth())
            }
        }
        return ScrollView(this).apply { addView(container, matchWidth()) }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
