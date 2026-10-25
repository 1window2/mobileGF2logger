package dev.gf2log.app

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.group
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.navigationRow
import dev.gf2log.app.ui.text
import java.time.LocalDate

/** Lists the bounded immutable revisions recorded for one weekly table. */
class WeeklyTableHistoryActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var repository: PlatoonRepository
    private lateinit var content: LinearLayout
    private val work = BackgroundWork(this, "GF2WeeklyHistory")
    private val periodStart: LocalDate by lazy {
        LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_PERIOD_START, Long.MIN_VALUE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope
        if (!intent.hasExtra(EXTRA_PERIOD_START) || scope == null) {
            finish()
            return
        }
        repository = PlatoonRepository(this, scope)
        val screen = Screen(this)
        screen.topBar.setNavigation(::finish).setTitle(
            getString(R.string.weekly_table_history),
            getString(
                R.string.week_range,
                Labels.format(Labels.DATE, periodStart),
                Labels.format(Labels.DATE, periodStart.plusDays(6)),
            ),
        )
        content = column()
        screen.setBody(Screen.scrollingBody(this, content))
    }

    override fun onScreenResumed() {
        if (!profileBinding.isSameStorage(this)) {
            finish()
            return
        }
        load()
    }

    private fun load() {
        if (content.childCount == 0) content.addView(StateViews.loading(this, getString(R.string.weekly_report_loading)))
        work.run(
            key = LOAD,
            task = {
                repository.listWeeklyReportHistory(periodStart) to
                    GameTimeZonePreferences.get(this, requireNotNull(profileBinding.scope).storageId)
            },
        ) { result ->
            content.removeAllViews()
            val (entries, zone) = result.getOrElse {
                content.addView(StateViews.failure(this, getString(R.string.weekly_report_load_failed)) { load() })
                return@run
            }
            content.addView(text(getString(R.string.weekly_table_history_description), TextRole.SECONDARY), lp(top = 4, bottom = 12))
            if (entries.isEmpty()) {
                content.addView(StateViews.empty(this, R.drawable.ic_history_restore, getString(R.string.weekly_table_history_empty)))
                return@run
            }
            content.addView(group(entries.map { entry ->
                navigationRow(
                    title = Labels.format(Labels.DATE_TIME_SECONDS, entry.recordedAt, zone),
                    detail = if (entry.active) getString(R.string.weekly_table_history_active) else null,
                    icon = if (entry.active) R.drawable.ic_check_circle else R.drawable.ic_history_restore,
                ) {
                    startActivity(
                        Intent(this, WeeklyTableHistoryPreviewActivity::class.java)
                            .putExtra(EXTRA_PERIOD_START, periodStart.toEpochDay())
                            .putExtra(WeeklyTableHistoryPreviewActivity.EXTRA_HISTORY_ID, entry.id)
                            .putExtra(WeeklyTableHistoryPreviewActivity.EXTRA_RECORDED_AT, entry.recordedAt.toEpochMilli()),
                    )
                }
            }), lp())
        }
    }

    companion object {
        const val EXTRA_PERIOD_START = "weekly_history_period_start"
        private const val LOAD = "load"
    }
}
