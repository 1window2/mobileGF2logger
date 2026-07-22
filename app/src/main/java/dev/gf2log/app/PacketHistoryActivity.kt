package dev.gf2log.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.protocol.ParsedPacketTableParser
import java.io.File

class PacketHistoryActivity : Activity() {
    private lateinit var actionButton: Button
    private lateinit var contentContainer: LinearLayout
    private lateinit var rawContent: String
    private var table: ParsedPacketTableParser.Table? = null
    private var showingRaw = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_ENTRY_TITLE).orEmpty()
        rawContent = if (intent.getBooleanExtra(EXTRA_SAVED_ENTRY, false)) {
            SavedHistoryStore(
                File(filesDir, SavedHistoryStore.SAVED_HISTORY_DIRECTORY),
            ).read(entryId)
        } else {
            CaptureHistoryStore(
                File(filesDir, CaptureHistoryStore.HISTORY_DIRECTORY),
            ).read(entryId)
        } ?: run {
            finish()
            return
        }
        table = ParsedPacketTableParser.parse(rawContent)

        val spacing = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            addView(TextView(context).apply {
                text = title
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            actionButton = Button(context).apply {
                setOnClickListener {
                    if (showingRaw) copyToClipboard(rawContent) else showRawContent()
                }
            }
            addView(actionButton, matchWidth())
            contentContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, spacing, 0, 0)
            }
            addView(contentContainer, matchWidth())
        }

        setContentView(ScrollView(this).apply { addView(container, matchWidth()) })
        if (table == null) showRawContent() else showTable()
    }

    private fun showTable() {
        val parsed = table ?: return
        showingRaw = false
        actionButton.text = getString(R.string.show_raw_csv_file)
        contentContainer.removeAllViews()
        contentContainer.addView(HorizontalScrollView(this).apply {
            isFillViewport = true
            addView(TableLayout(context).apply {
                isStretchAllColumns = false
                addView(tableRow(parsed.header, header = true))
                parsed.rows.forEach { addView(tableRow(it, header = false)) }
            })
        }, matchWidth())
    }

    private fun showRawContent() {
        showingRaw = true
        actionButton.text = getString(R.string.copy_parsed_packet)
        contentContainer.removeAllViews()
        contentContainer.addView(TextView(this).apply {
            text = rawContent
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }, matchWidth())
    }

    private fun tableRow(values: List<String>, header: Boolean): TableRow = TableRow(this).apply {
        values.forEach { value ->
            addView(TextView(context).apply {
                text = value
                textSize = if (header) 14f else 13f
                gravity = Gravity.CENTER_VERTICAL
                if (header) setTypeface(typeface, Typeface.BOLD)
                val horizontal = (10 * resources.displayMetrics.density).toInt()
                val vertical = (8 * resources.displayMetrics.density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                background = GradientDrawable().apply {
                    setColor(if (header) Color.rgb(225, 231, 241) else Color.WHITE)
                    setStroke(1, Color.rgb(160, 170, 185))
                }
            }, TableRow.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
    }

    private fun copyToClipboard(content: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_label), content))
        Toast.makeText(this, getString(R.string.status_packet_copied), Toast.LENGTH_SHORT).show()
    }

    private fun matchWidth(): ViewGroup.LayoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_TITLE = "entry_title"
        const val EXTRA_SAVED_ENTRY = "saved_entry"
    }
}
