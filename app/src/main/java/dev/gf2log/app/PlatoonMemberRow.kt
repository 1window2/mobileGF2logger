package dev.gf2log.app

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.SnapshotMember
import dev.gf2log.app.ui.AccessibleRow
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.MATCH
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import java.time.Instant
import java.time.ZoneId

/** One recyclable roster row: name, status, level and last login, and this week's merit. */
internal class PlatoonMemberRow(private val context: Context) {
    val view = AccessibleRow(context)
    private val check = CheckBox(context).apply {
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        buttonTintList = Controls.controlTint(context)
    }
    private val name = context.text(null, TextRole.SUBTITLE) {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status = context.badge("")
    private val detail = context.text(null, TextRole.CAPTION) {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val merit = context.text(null, TextRole.SUBTITLE) { gravity = Gravity.END }

    init {
        with(context) {
            view.orientation = LinearLayout.HORIZONTAL
            view.gravity = Gravity.CENTER_VERTICAL
            view.minimumHeight = dp(64)
            view.setPadding(dp(8), dp(8), dp(8), dp(8))
            view.background = selectableRect()
            view.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(2) }
            view.addView(check, lp(WRAP, WRAP, end = 4))
            view.addView(column {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(row {
                    addView(name, lp(width = 0, weight = 1f))
                    addView(status, lp(WRAP, WRAP, start = 8))
                }, lp())
                addView(detail, lp(top = 2))
            }, lp(width = 0, weight = 1f))
            view.addView(column {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                addView(merit, lp())
                addView(text(getString(R.string.merit_this_week_short), TextRole.CAPTION) { gravity = Gravity.END }, lp())
            }, lp(WRAP, WRAP, start = 12))
            view.addView(icon(R.drawable.ic_chevron_right, sizeDp = 20), lp(20, 20, start = 4))
        }
    }

    fun bind(
        member: MemberStatus,
        latest: SnapshotMember?,
        zone: ZoneId,
        selecting: Boolean,
        selected: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) = with(context) {
        check.visibility = if (selecting) View.VISIBLE else View.GONE
        check.isChecked = selected
        name.text = member.name
        status.text = getString(if (member.isActive) R.string.active_member else R.string.departed_member)
        status.visibility = if (member.isActive) View.GONE else View.VISIBLE
        detail.text = buildList {
            add(Labels.uid(context, member.uid))
            add(getString(R.string.level_short, latest?.level ?: member.level))
            latest?.lastLogin?.takeIf { it > 0 }?.let {
                add(getString(R.string.last_login_short, Labels.format(Labels.DATE_TIME, Instant.ofEpochSecond(it), zone)))
            }
        }.joinToString(" · ")
        merit.text = latest?.weeklyMerit?.toString() ?: "—"
        view.isSelected = selecting && selected
        view.contentDescription = listOfNotNull(
            getString(R.string.selected).takeIf { view.isSelected },
            member.name,
            status.text,
            detail.text,
            Labels.labeled(context, getString(R.string.merit_this_week), merit.text),
        ).joinToString(". ")
        view.setOnClickListener { onClick() }
        view.setOnLongClickListener {
            onLongClick()
            true
        }
    }
}
