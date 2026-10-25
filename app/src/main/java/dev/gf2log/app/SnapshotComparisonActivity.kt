package dev.gf2log.app

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.SnapshotComparison
import dev.gf2log.app.management.SnapshotComparisonCsv
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.text
import java.time.ZoneId

/** Joins, withdrawals, and counter changes between the two latest roster snapshots. */
class SnapshotComparisonActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2SnapshotComparison")
    private val content by lazy { column() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope ?: run {
            finish()
            return
        }
        screen = Screen(this)
        screen.topBar.setNavigation(::finish).setTitle(getString(R.string.snapshot_comparison))
        screen.setBody(Screen.scrollingBody(this, content))
        content.addView(StateViews.loading(this, getString(R.string.loading_comparison)))
        work.run(
            task = {
                val snapshots = PlatoonRepository(this, scope).listSnapshots(2)
                snapshots.takeIf { it.size == 2 }?.let { SnapshotComparison.compare(it[1], it[0]) }
            },
        ) { result ->
            content.removeAllViews()
            result.fold(
                onSuccess = ::render,
                onFailure = { content.addView(StateViews.failure(this, getString(R.string.platoon_load_failed)) { recreate() }) },
            )
        }
    }

    override fun onScreenResumed() {
        if (!profileBinding.isSameStorage(this)) finish()
    }

    private fun render(result: SnapshotComparison.Result?) {
        // Capture instants are shown in device time, like packet history.
        val zone = ZoneId.systemDefault()
        if (result == null) {
            content.addView(StateViews.empty(this, R.drawable.ic_compare, getString(R.string.need_two_snapshots)))
            return
        }
        screen.topBar.setActions(listOf(BarAction(R.drawable.ic_copy, getString(R.string.copy_csv)) {
            Feedback.copy(
                this,
                getString(R.string.snapshot_csv_clipboard_label),
                SnapshotComparisonCsv.format(result),
                R.string.snapshot_csv_copied,
            )
        }))
        content.addView(panel {
            addView(text(getString(R.string.comparison_window), TextRole.LABEL), lp())
            addView(text(
                getString(
                    R.string.comparison_period,
                    Labels.format(Labels.DATE_TIME, result.older.capturedAt, zone),
                    Labels.format(Labels.DATE_TIME, result.newer.capturedAt, zone),
                ),
                TextRole.SUBTITLE,
            ), lp(top = 2))
            addView(text(
                getString(R.string.comparison_counts, result.joined.size, result.left.size, result.changes.size),
                TextRole.CAPTION,
            ), lp(top = 4))
        }, lp(top = 4))
        section(getString(R.string.joined_members), result.joined.size, result.joined.map { it.name to Labels.uid(this, it.uid) })
        section(getString(R.string.departed_members), result.left.size, result.left.map { it.name to Labels.uid(this, it.uid) })
        content.addView(sectionHeader(getString(R.string.member_changes_count, result.changes.size)), lp())
        if (result.changes.isEmpty()) {
            content.addView(text(getString(R.string.none), TextRole.SECONDARY), lp(start = 4))
            return
        }
        content.addView(panel(padding = 8) {
            result.changes.forEachIndexed { index, change ->
                if (index > 0) addView(divider(insetStartDp = 8))
                addView(column {
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    isFocusable = true
                    addView(row {
                        addView(text(change.name, TextRole.SUBTITLE) {
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                        }, lp(width = 0, weight = 1f))
                        addView(text(Labels.uid(context, change.uid), TextRole.CAPTION), lp(WRAP, WRAP, start = 8))
                    }, lp())
                    addView(row {
                        listOf(
                            getString(R.string.merit_this_week_short) to change.weeklyMeritDelta,
                            getString(R.string.total_merit) to change.totalMeritDelta,
                            getString(R.string.gunsmoke_score) to change.totalScoreDelta,
                        ).forEachIndexed { badgeIndex, (label, delta) ->
                            addView(badge(
                                "$label +$delta",
                                if (delta > 0) R.color.accent_surface else R.color.surface_variant,
                                if (delta > 0) R.color.accent_text else R.color.text_secondary,
                            ), lp(WRAP, WRAP, start = if (badgeIndex == 0) 0 else 6))
                        }
                    }, lp(top = 6))
                    contentDescription = getString(
                        R.string.member_change_description,
                        change.name,
                        change.weeklyMeritDelta,
                        change.totalMeritDelta,
                        change.totalScoreDelta,
                    )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }, lp())
            }
        }, lp())
    }

    private fun section(title: String, count: Int, members: List<Pair<String, String>>) {
        content.addView(sectionHeader("$title · $count"), lp())
        if (members.isEmpty()) {
            content.addView(text(getString(R.string.none), TextRole.SECONDARY), lp(start = 4))
            return
        }
        content.addView(panel(padding = 8) {
            members.forEachIndexed { index, (name, uid) ->
                if (index > 0) addView(divider(insetStartDp = 8))
                addView(row {
                    minimumHeight = dp(44)
                    setPadding(dp(8), 0, dp(8), 0)
                    addView(text(name, TextRole.BODY), lp(width = 0, weight = 1f))
                    addView(text(uid, TextRole.CAPTION), lp(WRAP, WRAP))
                }, lp())
            }
        }, lp())
    }

}
