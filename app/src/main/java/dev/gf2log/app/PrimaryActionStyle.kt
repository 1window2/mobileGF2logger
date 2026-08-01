package dev.gf2log.app

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.widget.Button

fun Button.usePrimaryActionStyle() {
    backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.primary_action_background))
    setTextColor(context.getColor(R.color.primary_action_foreground))
    setTypeface(typeface, Typeface.BOLD)
    minHeight = (48 * resources.displayMetrics.density).toInt()
}
