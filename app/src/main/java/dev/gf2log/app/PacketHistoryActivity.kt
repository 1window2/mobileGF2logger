package dev.gf2log.app

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.app.discord.DiscordWebhookSecretStore
import dev.gf2log.app.discord.DiscordWebhookSender
import dev.gf2log.app.discord.OriginalCsvPayload
import java.util.concurrent.Executors
import dev.gf2log.protocol.ParsedPacketTableParser
import java.io.File

class PacketHistoryActivity : LocalizedActivity() {
    private lateinit var actionButton: Button
    private lateinit var contentContainer: LinearLayout
    private lateinit var sendButton: Button
    private lateinit var sendUnavailableOverlay: View
    private lateinit var rawContent: String
    private var table: ParsedPacketTableParser.Table? = null
    private var showingRaw = false

    private val sendExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GF2DiscordWebhook")
    }
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
            sendButton = Button(context).apply {
                text = getString(R.string.send_original_csv)
                usePrimaryActionStyle()
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_discord, 0, 0, 0,
                )
                compoundDrawableTintList = ColorStateList.valueOf(
                    context.getColor(R.color.primary_action_foreground),
                )
                compoundDrawablePadding = dp(8)
                setPaddingRelative(
                    paddingStart, paddingTop, paddingEnd + dp(32), paddingBottom,
                )
                setOnClickListener { confirmSendOriginalCsv() }
            }
            addView(FrameLayout(context).apply {
                addView(
                    sendButton,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
                sendUnavailableOverlay = View(context).apply {
                    isClickable = true
                    isFocusable = true
                    contentDescription = getString(R.string.discord_webhook_not_configured)
                    setOnClickListener { showWebhookRequiredDialog() }
                }
                addView(
                    sendUnavailableOverlay,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }, matchWidth())
            contentContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, spacing, 0, 0)
            }
            addView(contentContainer, matchWidth())
        }

        setContentView(ScrollView(this).apply { addView(container, matchWidth()) })
        if (table == null) showRawContent() else showTable()
        refreshSendAvailability()
    }


    override fun onResume() {
        super.onResume()
        if (::sendButton.isInitialized) refreshSendAvailability()
    }

    private fun refreshSendAvailability() {
        val hasCsvTable = table != null
        val webhookConfigured = runCatching {
            DiscordWebhookSecretStore(this).read() != null
        }.getOrDefault(false)
        val unavailable = hasCsvTable && !webhookConfigured
        sendButton.isEnabled = hasCsvTable && webhookConfigured
        sendButton.alpha = if (sendButton.isEnabled) 1f else 0.5f
        sendUnavailableOverlay.visibility = if (unavailable) View.VISIBLE else View.GONE
    }

    private fun showWebhookRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.discord_webhook_not_configured)
            .setMessage(R.string.discord_webhook_required)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open_options) { _, _ ->
                startActivity(Intent(this, OptionsActivity::class.java))
            }
            .show()
    }

    override fun onDestroy() {
        sendExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun confirmSendOriginalCsv() {
        val csv = runCatching { OriginalCsvPayload.extract(rawContent) }.getOrElse {
            Toast.makeText(this, R.string.discord_csv_invalid, Toast.LENGTH_LONG).show()
            return
        }
        val webhook = DiscordWebhookSecretStore(this).read() ?: run {
            showWebhookRequiredDialog()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.send_original_csv)
            .setMessage(R.string.send_original_csv_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.send_original_csv) { _, _ ->
                sendOriginalCsv(webhook, csv)
            }
            .show()
    }

    // Function Name: sendOriginalCsv
    // Description:
    // - Sends only the validated original CSV body after explicit confirmation.
    // - Runs bounded HTTPS I/O away from the main thread and never logs the secret URL.
    // Parameters:
    // - webhook: Decrypted, policy-validated Discord incoming-webhook URL.
    // - csv: Validated original CSV body with history metadata removed.
    // Returns:
    // - Unit after scheduling the request and result notification.
    private fun sendOriginalCsv(webhook: String, csv: String) {
        sendButton.alpha = 0.5f
        sendButton.isEnabled = false
        Toast.makeText(this, R.string.discord_csv_sending, Toast.LENGTH_SHORT).show()
        sendExecutor.execute {
            val sent = runCatching { DiscordWebhookSender().send(webhook, csv) }.isSuccess
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                refreshSendAvailability()
                Toast.makeText(
                    this,
                    if (sent) R.string.discord_csv_sent else R.string.discord_csv_send_failed,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
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
                setTextColor(getColor(R.color.text_primary))
                if (header) setTypeface(typeface, Typeface.BOLD)
                val horizontal = (10 * resources.displayMetrics.density).toInt()
                val vertical = (8 * resources.displayMetrics.density).toInt()
                setPadding(horizontal, vertical, horizontal, vertical)
                background = GradientDrawable().apply {
                    setColor(getColor(if (header) R.color.table_header else R.color.table_cell))
                    setStroke(1, getColor(R.color.outline_strong))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
