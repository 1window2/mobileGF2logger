package dev.gf2log.app

import android.app.Activity
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
import dev.gf2log.app.history.CaptureHistoryStore
import java.io.File

class PacketHistoryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_ENTRY_TITLE).orEmpty()
        val content = CaptureHistoryStore(
            File(filesDir, CaptureHistoryStore.HISTORY_DIRECTORY),
        ).read(entryId)
        if (content == null) {
            finish()
            return
        }

        val spacing = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(spacing, spacing, spacing, spacing)
            addView(TextView(context).apply {
                text = title
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.copy_parsed_packet)
                setOnClickListener { copyToClipboard(content) }
            }, matchWidth())
            addView(TextView(context).apply {
                text = content
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, spacing, 0, 0)
            }, matchWidth())
        }

        setContentView(ScrollView(this).apply { addView(container, matchWidth()) })
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
    }
}
