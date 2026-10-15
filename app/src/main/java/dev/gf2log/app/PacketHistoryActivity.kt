package dev.gf2log.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import dev.gf2log.app.discord.DiscordWebhookSecretStore
import dev.gf2log.app.discord.DiscordWebhookSender
import dev.gf2log.app.discord.OriginalCsvPayload
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.BarAction
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.MATCH
import dev.gf2log.app.ui.MenuEntry
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.text
import dev.gf2log.protocol.ParsedPacketTableParser
import java.io.File

/** One parsed packet as a bounded table or its complete decoded text. */
class PacketHistoryActivity : BaseActivity() {

    private lateinit var screen: Screen
    private val work = BackgroundWork(this, "GF2PacketHistory")
    private val body by lazy { FrameLayout(this) }
    private var rawContent: String? = null
    private var table: ParsedPacketTableParser.Table? = null
    private var showingRaw = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val entryId = intent.getStringExtra(EXTRA_ENTRY_ID).orEmpty()
        val storageId = intent.getStringExtra(EXTRA_STORAGE_ID)
        if (storageId == null || PlatoonProfileRegistry(this).active()?.storageId != storageId) {
            finish()
            return
        }
        showingRaw = savedInstanceState?.getBoolean(STATE_RAW) == true
        screen = Screen(this)
        screen.topBar.setNavigation(::finish).setTitle(intent.getStringExtra(EXTRA_ENTRY_TITLE).orEmpty())
        screen.setBody(column {
            addView(body, lp(height = 0, weight = 1f))
        })
        body.addView(StateViews.loading(this, getString(R.string.loading_packet)))
        val root = PlatoonStorageScope(storageId).rootDirectory(this)
        val saved = intent.getBooleanExtra(EXTRA_SAVED_ENTRY, false)
        work.run(
            task = {
                val content = if (saved) {
                    SavedHistoryStore(File(root, SavedHistoryStore.SAVED_HISTORY_DIRECTORY)).read(entryId)
                } else {
                    CaptureHistoryStore(File(root, CaptureHistoryStore.HISTORY_DIRECTORY)).read(entryId)
                }
                content?.let { it to ParsedPacketTableParser.parse(it) }
            },
        ) { result ->
            val loaded = result.getOrNull()
            if (loaded == null) {
                body.removeAllViews()
                body.addView(StateViews.empty(this, R.drawable.ic_warning, getString(R.string.packet_unavailable)))
                return@run
            }
            rawContent = loaded.first
            table = loaded.second
            if (table == null) showingRaw = true
            render()
        }
    }

    override fun onScreenResumed() {
        // The entry belongs to the Platoon it was opened from; a switch elsewhere closes it.
        if (PlatoonProfileRegistry(this).active()?.storageId != intent.getStringExtra(EXTRA_STORAGE_ID)) finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_RAW, showingRaw)
        super.onSaveInstanceState(outState)
    }

    private fun render() {
        val content = rawContent ?: return
        val hasTable = table != null
        screen.topBar.setActions(
            listOf(BarAction(R.drawable.ic_copy, getString(R.string.copy_parsed_packet)) { copy(content) }),
            overflow = listOf(MenuEntry(getString(R.string.send_original_csv), hasTable) { confirmSend(content) }),
        )
        body.removeAllViews()
        body.addView(column {
            if (hasTable) {
                addView(Controls.segmentedControl(
                    context = this@PacketHistoryActivity,
                    options = listOf(TABLE to getString(R.string.packet_view_table), RAW to getString(R.string.packet_view_raw)),
                    selectedValue = if (showingRaw) RAW else TABLE,
                ) {
                    showingRaw = it == RAW
                    render()
                }, lp(start = 16, end = 16, top = 4, bottom = 8))
            }
            addView(if (showingRaw) rawView(content) else tableView(requireNotNull(table)), lp(height = 0, weight = 1f))
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun rawView(content: String) = ScrollView(this).apply {
        clipToPadding = false
        setPadding(dp(16), 0, dp(16), dp(24))
        addView(text(content, TextRole.BODY) {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        })
    }

    private fun tableView(parsed: ParsedPacketTableParser.Table) = ScrollView(this).apply {
        clipToPadding = false
        setPadding(0, 0, 0, dp(24))
        addView(HorizontalScrollView(context).apply {
            clipToPadding = false
            setPadding(dp(16), 0, dp(16), 0)
            addView(TableLayout(context).apply {
                setBackgroundColor(getColor(R.color.outline))
                setPadding(1, 1, 1, 1)
                addView(tableRow(parsed.header, header = true))
                parsed.rows.forEach { addView(tableRow(it, header = false)) }
            })
        }, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    private fun tableRow(values: List<String>, header: Boolean): TableRow = TableRow(this).apply {
        values.forEach { value ->
            addView(text(value, if (header) TextRole.LABEL else TextRole.BODY) {
                if (header) setTextColor(getColor(R.color.text_primary))
                textSize = 13f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setBackgroundColor(getColor(if (header) R.color.table_header else R.color.table_cell))
                setTextIsSelectable(!header)
            }, TableRow.LayoutParams(WRAP, MATCH).apply { setMargins(0, 0, 1, 1) })
        }
    }

    private fun copy(content: String) {
        Feedback.copy(this, getString(R.string.clipboard_label), content, R.string.status_packet_copied)
    }

    private fun confirmSend(content: String) {
        val csv = runCatching { OriginalCsvPayload.extract(content) }.getOrElse {
            Feedback.show(this, R.string.discord_csv_invalid, long = true)
            return
        }
        work.run(task = { DiscordWebhookSecretStore(this).read() }) { result ->
            val webhook = result.getOrNull()
            if (webhook == null) {
                Dialogs.show(
                    Dialogs.builder(this)
                        .setTitle(R.string.discord_webhook_not_configured)
                        .setMessage(R.string.discord_webhook_required)
                        .setNegativeButton(R.string.cancel, null)
                        .setPositiveButton(R.string.open_options) { _, _ ->
                            startActivity(Intent(this, OptionsActivity::class.java))
                        },
                )
                return@run
            }
            Dialogs.confirm(this, R.string.send_original_csv, R.string.send_original_csv_warning, R.string.send) confirm@{
                if (work.isBusy(SEND)) return@confirm
                Feedback.show(this, R.string.discord_csv_sending)
                work.run(key = SEND, task = { DiscordWebhookSender().send(webhook, csv) }) { sent ->
                    Feedback.show(this, if (sent.isSuccess) R.string.discord_csv_sent else R.string.discord_csv_send_failed, long = true)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_ENTRY_TITLE = "entry_title"
        const val EXTRA_SAVED_ENTRY = "saved_entry"
        const val EXTRA_STORAGE_ID = "storage_id"
        private const val TABLE = "table"
        private const val SEND = "send"
        private const val RAW = "raw"
        private const val STATE_RAW = "packet.raw"
    }
}
