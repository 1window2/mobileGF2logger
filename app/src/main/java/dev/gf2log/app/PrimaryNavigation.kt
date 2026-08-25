package dev.gf2log.app

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.gf2log.app.management.PlatoonProfileRegistry
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Shared Home, Platoon, and Weekly navigation shell for the three primary destinations. */
internal object PrimaryNavigation {
    enum class Destination {
        HOME,
        PLATOON,
        WEEKLY,
    }

    fun wrap(activity: Activity, content: View, selected: Destination): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(activity.getColor(R.color.app_background))
            addView(content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ))
            addView(View(activity).apply {
                setBackgroundColor(activity.getColor(R.color.outline))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1)))
            addView(navigationBar(activity, selected), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                activity.dp(64),
            ))
        }

    private fun navigationBar(activity: Activity, selected: Destination): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(activity.getColor(R.color.surface))
            addDestination(
                activity,
                Destination.HOME,
                selected,
                R.drawable.ic_home,
                R.string.nav_home,
                MainActivity::class.java,
            )
            addDestination(
                activity,
                Destination.PLATOON,
                selected,
                R.drawable.ic_group,
                R.string.nav_platoon,
                PlatoonActivity::class.java,
            )
            addDestination(
                activity,
                Destination.WEEKLY,
                selected,
                R.drawable.ic_calendar,
                R.string.nav_weekly,
                WeeklyReportActivity::class.java,
            )
        }

    private fun LinearLayout.addDestination(
        activity: Activity,
        destination: Destination,
        selected: Destination,
        @DrawableRes icon: Int,
        @StringRes label: Int,
        activityClass: Class<out Activity>,
    ) {
        val active = destination == selected
        val color = activity.getColor(if (active) R.color.accent else R.color.text_secondary)
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = !active
            isFocusable = true
            isSelected = active
            contentDescription = activity.getString(label)
            background = activity.selectableBackground()
            setPadding(0, activity.dp(6), 0, activity.dp(4))
            addView(ImageView(activity).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(activity.dp(24), activity.dp(24)))
            addView(TextView(activity).apply {
                text = activity.getString(label)
                textSize = 11f
                setTextColor(color)
                typeface = Typeface.create(
                    "sans-serif-medium",
                    if (active) Typeface.BOLD else Typeface.NORMAL,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = activity.dp(2) })
            if (!active) {
                setOnClickListener {
                    if (destination != Destination.HOME &&
                        PlatoonProfileRegistry(activity).active() == null
                    ) {
                        TransientMessage.show(
                            activity,
                            R.string.no_platoon_detected_detail,
                            android.widget.Toast.LENGTH_LONG,
                        )
                    } else {
                        activity.startActivity(
                            Intent(activity, activityClass).addFlags(
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                            ),
                        )
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    }

    private fun Activity.selectableBackground() = TypedValue().let { value ->
        theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
        getDrawable(value.resourceId) ?: GradientDrawable().apply {
            setColor(getColor(R.color.surface))
        }
    }
}
