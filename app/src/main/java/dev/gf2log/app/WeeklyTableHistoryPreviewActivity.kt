package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.WeeklyShareProjection
import java.time.LocalDate
import java.util.concurrent.Executors

/** Shows one immutable history projection and provides the explicit restore action. */
class WeeklyTableHistoryPreviewActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private lateinit var body: LinearLayout
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2WeeklyHistoryPreview")
    }
    private var bitmap: Bitmap? = null
    private val periodStart: LocalDate by lazy {
        LocalDate.ofEpochDay(intent.getLongExtra(WeeklyTableHistoryActivity.EXTRA_PERIOD_START, 0L))
    }
    private val historyId: Long by lazy { intent.getLongExtra(EXTRA_HISTORY_ID, -1L) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (historyId < 0L || !intent.hasExtra(WeeklyTableHistoryActivity.EXTRA_PERIOD_START)) {
            finish()
            return
        }
        repository = PlatoonRepository(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            addView(buildHeader(), matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.weekly_report_loading)
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, dp(32))
            }, matchWidth())
        }
        setContentView(ScrollView(this).apply { addView(body, matchWidth()) })
        loadPreview()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        bitmap?.recycle()
        bitmap = null
        super.onDestroy()
    }

    private fun buildHeader() = LinearLayout(this).apply {
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
            text = getString(R.string.weekly_table_history_preview)
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun loadPreview() {
        worker.execute {
            val report = repository.weeklyReportHistory(historyId, periodStart)
            val rendered = report?.let {
                WeeklyReportPngRenderer.render(
                    WeeklyShareProjection.build(
                        report = it,
                        displayedMembers = it.members,
                        privateNotesByUid = emptyMap(),
                        privacy = WeeklyShareProjection.Privacy(),
                    ),
                )
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) {
                    rendered?.recycle()
                } else if (rendered == null) {
                    showUnavailable()
                } else {
                    bitmap = rendered
                    renderPreview(rendered)
                }
            }
        }
    }

    private fun renderPreview(image: Bitmap) {
        while (body.childCount > 1) body.removeViewAt(1)
        body.addView(HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(ImageView(context).apply {
                setImageBitmap(image)
                adjustViewBounds = true
                contentDescription = getString(R.string.weekly_table_history_preview)
            }, ViewGroup.LayoutParams(image.width, image.height))
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8) })
        body.addView(Button(this).apply {
            text = getString(R.string.weekly_table_history_restore)
            usePrimaryActionStyle()
            setOnClickListener { confirmRestore() }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(48),
        ).apply { topMargin = dp(16) })
    }

    private fun showUnavailable() {
        while (body.childCount > 1) body.removeViewAt(1)
        body.addView(TextView(this).apply {
            text = getString(R.string.weekly_table_history_restore_failed)
            gravity = Gravity.CENTER
            setPadding(0, dp(32), 0, dp(32))
        }, matchWidth())
    }

    private fun confirmRestore() {
        AlertDialog.Builder(this)
            .setTitle(R.string.weekly_table_history_restore)
            .setMessage(R.string.weekly_table_history_restore_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.weekly_table_history_restore) { _, _ -> restore() }
            .show()
    }

    private fun restore() {
        worker.execute {
            val restored = repository.restoreWeeklyReportHistory(historyId, periodStart)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (restored) {
                    TransientMessage.show(this, R.string.weekly_table_history_restored)
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    TransientMessage.show(
                        this,
                        R.string.weekly_table_history_restore_failed,
                        Toast.LENGTH_LONG,
                    )
                }
            }
        }
    }

    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_HISTORY_ID = "weekly_history_id"
    }
}
