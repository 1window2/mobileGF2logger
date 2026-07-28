package dev.gf2log.app

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.SnapshotComparison
import dev.gf2log.app.management.SnapshotComparisonCsv
import dev.gf2log.app.management.PlatoonRepository
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SnapshotComparisonActivity : LocalizedActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snapshots = PlatoonRepository(this).listSnapshots(2)
        setContentView(ScrollView(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(heading(getString(R.string.snapshot_comparison), 28f))
                if (snapshots.size < 2) {
                    addView(text(getString(R.string.need_two_snapshots)))
                } else {
                    val result = SnapshotComparison.compare(snapshots[1], snapshots[0])
                    val zone = ZoneId.systemDefault()
                    addView(
                        text(
                            getString(
                                R.string.comparison_period,
                                DISPLAY_TIME.format(result.older.capturedAt.atZone(zone)),
                                DISPLAY_TIME.format(result.newer.capturedAt.atZone(zone)),
                            ),
                        ),
                    )
                    addView(Button(context).apply {
                        text = getString(R.string.copy_csv)
                        textSize = 12f
                        minHeight = 0
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        setOnClickListener {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText(
                                    getString(R.string.snapshot_csv_clipboard_label),
                                    SnapshotComparisonCsv.format(result),
                                ),
                            )
                            Toast.makeText(
                                this@SnapshotComparisonActivity,
                                R.string.snapshot_csv_copied,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    })
                    addSection(
                        getString(R.string.joined_members),
                        result.joined.map { "${it.name} (#${it.uid})" },
                    )
                    addSection(
                        getString(R.string.departed_members),
                        result.left.map { "${it.name} (#${it.uid})" },
                    )
                    addSection(
                        getString(R.string.member_changes),
                        result.changes.map {
                            "${it.name} (#${it.uid})  " +
                                "${getString(R.string.merit_this_week)} +${it.weeklyMeritDelta}, " +
                                "${getString(R.string.total_merit)} +${it.totalMeritDelta}, " +
                                "${getString(R.string.gunsmoke_score)} +${it.totalScoreDelta}"
                        },
                    )
                }
            }, matchWidth())
        })
    }

    private fun LinearLayout.addSection(title: String, lines: List<String>) {
        addView(heading(title, 20f))
        addView(text(lines.ifEmpty { listOf(getString(R.string.none)) }.joinToString("\n")))
    }

    private fun heading(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun text(value: String) = TextView(this).apply {
        text = value
        textSize = 15f
        setPadding(0, dp(4), 0, dp(8))
        setTextIsSelectable(true)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val DISPLAY_TIME = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
    }
}
