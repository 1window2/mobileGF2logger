package dev.gf2log.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.WeeklyReportHistoryEntry
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.Executors

/** Lists the bounded immutable history for one weekly table. */
class WeeklyTableHistoryActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2WeeklyHistory")
    }
    private val periodStart: LocalDate by lazy {
        LocalDate.ofEpochDay(intent.getLongExtra(EXTRA_PERIOD_START, Long.MIN_VALUE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.hasExtra(EXTRA_PERIOD_START)) {
            finish()
            return
        }
        repository = PlatoonRepository(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addHeader()
            addView(TextView(context).apply {
                text = getString(R.string.weekly_table_history_description)
                textSize = 13f
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(2), 0, dp(12))
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.weekly_report_loading)
                gravity = Gravity.CENTER
                setPadding(0, dp(28), 0, dp(28))
            }, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(body, matchWidth()) })
        loadHistory()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun LinearLayout.addHeader() {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_arrow_back)
                contentDescription = getString(R.string.back)
                useModernIconStyle()
                setPadding(dp(11), dp(11), dp(11), dp(11))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(4) })
            addView(TextView(context).apply {
                text = getString(R.string.weekly_table_history)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, matchWidth())
    }

    private fun loadHistory() {
        worker.execute {
            val entries = repository.listWeeklyReportHistory(periodStart)
            val models = entries.mapNotNull { entry ->
                repository.weeklyReportHistory(entry.id, periodStart)?.let { report ->
                    HistoryRow(entry, report.members.size)
                }
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderRows(models)
            }
        }
    }

    private fun renderRows(rows: List<HistoryRow>) {
        while (body.childCount > HEADER_CHILD_COUNT) body.removeViewAt(HEADER_CHILD_COUNT)
        if (rows.isEmpty()) {
            body.addView(TextView(this).apply {
                text = getString(R.string.weekly_table_history_empty)
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.text_secondary))
                setPadding(0, dp(28), 0, dp(28))
            }, matchWidth())
            return
        }
        val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(resources.configuration.locales[0])
            .withZone(ZoneId.systemDefault())
        rows.forEachIndexed { index, row ->
            body.addView(
                ModernUi.actionRow(
                    context = this,
                    title = formatter.format(row.entry.recordedAt),
                    detail = buildString {
                        append(getString(R.string.weekly_table_history_member_count, row.memberCount))
                        if (row.entry.active) {
                            append(" · ")
                            append(getString(R.string.weekly_table_history_active))
                        }
                    },
                    onClick = {
                        startActivityForResult(
                            Intent(this, WeeklyTableHistoryPreviewActivity::class.java)
                                .putExtra(EXTRA_PERIOD_START, periodStart.toEpochDay())
                                .putExtra(WeeklyTableHistoryPreviewActivity.EXTRA_HISTORY_ID, row.entry.id),
                            REQUEST_PREVIEW,
                        )
                    },
                ),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { if (index > 0) topMargin = dp(8) },
            )
        }
    }

    @Deprecated("Activity result is retained for API 26 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PREVIEW && resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            finish()
        }
    }

    private data class HistoryRow(
        val entry: WeeklyReportHistoryEntry,
        val memberCount: Int,
    )

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_PERIOD_START = "weekly_history_period_start"
        private const val REQUEST_PREVIEW = 80
        private const val HEADER_CHILD_COUNT = 2
    }
}
