package dev.gf2log.app

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import dev.gf2log.app.history.CaptureHistoryStore
import dev.gf2log.app.history.SavedHistoryStore
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.ui.AccessibleRow
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.Labels
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.badge
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.divider
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.sectionHeader
import dev.gf2log.app.ui.selectableRect
import dev.gf2log.app.ui.text
import java.io.File

/**
 * Home's parsed-packet history: the recent and saved tabs, a collapsed list, and a long-press
 * selection mode that saves or deletes entries. Back leaves selection before leaving Home.
 */
internal class HomePacketHistory(
    private val activity: ComponentActivity,
    private val work: BackgroundWork,
    private val scope: PlatoonStorageScope?,
) {
    val view: LinearLayout = activity.column()

    private val historyStore: CaptureHistoryStore?
    private val savedStore: SavedHistoryStore?
    private var showingSaved = false
    private var selecting = false
    private val selectedIds = linkedSetOf<String>()
    private var showAll = false
    private var recent = emptyList<CaptureHistoryStore.Entry>()
    private var saved = emptyList<CaptureHistoryStore.Entry>()
    private var selectionCount: TextView? = null
    private val selectionButtons = mutableListOf<View>()

    private val exitSelection = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setSelecting(false)
    }

    init {
        val root = scope?.rootDirectory(activity)
        historyStore = root?.let { CaptureHistoryStore(File(it, CaptureHistoryStore.HISTORY_DIRECTORY)) }
        savedStore = root?.let { SavedHistoryStore(File(it, SavedHistoryStore.SAVED_HISTORY_DIRECTORY)) }
        activity.onBackPressedDispatcher.addCallback(activity, exitSelection)
    }

    fun restoreState(state: Bundle?) {
        state ?: return
        showingSaved = state.getBoolean(STATE_SHOWING_SAVED)
        showAll = state.getBoolean(STATE_SHOW_ALL)
        selecting = state.getBoolean(STATE_SELECTING)
        state.getStringArrayList(STATE_SELECTED)?.let(selectedIds::addAll)
        exitSelection.isEnabled = selecting
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean(STATE_SHOWING_SAVED, showingSaved)
        outState.putBoolean(STATE_SHOW_ALL, showAll)
        outState.putBoolean(STATE_SELECTING, selecting)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selectedIds))
    }

    fun load() {
        val recentStore = historyStore
        val savedEntries = savedStore
        if (recentStore == null || savedEntries == null) {
            render()
            return
        }
        work.run(key = LOAD, task = { recentStore.list() to savedEntries.list() }) { result ->
            result.getOrNull()?.let { (recentEntries, savedList) ->
                recent = recentEntries
                saved = savedList
                val ids = (if (showingSaved) saved else recent).mapTo(mutableSetOf()) { it.id }
                selectedIds.retainAll(ids)
            }
            render()
        }
    }

    private fun render(): Unit = with(activity) {
        view.removeAllViews()
        val entries = if (showingSaved) saved else recent
        view.addView(row {
            addView(sectionHeader(getString(R.string.packet_history_section)), lp(width = 0, weight = 1f))
            if (entries.isNotEmpty()) {
                addView(
                    button(getString(if (selecting) R.string.done else R.string.select), Controls.Role.TERTIARY) {
                        setSelecting(!selecting)
                    },
                    lp(WRAP, WRAP, top = 8),
                )
            }
        }, lp())
        view.addView(Controls.segmentedControl(
            context = this,
            options = listOf(
                RECENT to getString(R.string.recent_packets_tab, recent.size, CaptureHistoryStore.MAX_ENTRIES),
                SAVED to getString(R.string.saved_packets_tab, saved.size, SavedHistoryStore.MAX_ENTRIES),
            ),
            selectedValue = if (showingSaved) SAVED else RECENT,
        ) { value ->
            showingSaved = value == SAVED
            showAll = false
            setSelecting(false)
        }, lp())
        if (entries.isEmpty()) {
            view.addView(text(
                getString(
                    when {
                        scope == null -> R.string.no_platoon_history
                        showingSaved -> R.string.no_saved_packets
                        else -> R.string.no_parsed_packets
                    },
                ),
                TextRole.SECONDARY,
            ) { setPadding(dp(4), dp(16), dp(4), dp(8)) }, lp())
            return@with
        }
        if (selecting) view.addView(selectionActions(), lp(top = 8))
        val visible = if (showAll) entries else entries.take(COLLAPSED_COUNT)
        view.addView(panel(padding = 4) {
            visible.forEachIndexed { index, entry ->
                if (index > 0) addView(divider(insetStartDp = 12))
                addView(historyRow(entry), lp())
            }
        }, lp(top = 8))
        if (!showAll && entries.size > COLLAPSED_COUNT) {
            view.addView(button(getString(R.string.show_all_count, entries.size), Controls.Role.TERTIARY) {
                showAll = true
                render()
            }, lp(top = 4))
        }
    }

    private fun historyRow(entry: CaptureHistoryStore.Entry): View = with(activity) {
        val tag = Labels.payloadTag(this, entry.payloadType)
        val mark = icon(R.drawable.ic_circle_outline, R.color.outline_strong, sizeDp = 22)
        val row = AccessibleRow(this)
        fun refresh() {
            val isSelected = selecting && entry.id in selectedIds
            row.isSelected = isSelected
            mark.setImageResource(if (isSelected) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            mark.imageTintList = ColorStateList.valueOf(
                getColor(if (isSelected) R.color.accent_text else R.color.outline_strong),
            )
            row.contentDescription = listOfNotNull(
                getString(R.string.selected).takeIf { isSelected },
                entry.title,
                getString(
                    R.string.payload_tag_description,
                    tag,
                    entry.payloadType?.toString() ?: getString(R.string.unknown_payload_type),
                ),
            ).joinToString(". ")
        }
        row.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(dp(12), dp(4), dp(8), dp(4))
            background = selectableRect()
            if (selecting) addView(mark, lp(22, 22, end = 12))
            addView(text(entry.title, TextRole.BODY) {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }, lp(width = 0, weight = 1f))
            addView(badge(tag).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, lp(WRAP, WRAP, start = 8))
            if (!selecting) addView(icon(R.drawable.ic_chevron_right, sizeDp = 20), lp(20, 20, start = 4))
            refresh()
            setOnClickListener {
                if (selecting) {
                    // Update in place so accessibility focus stays on this row.
                    if (!selectedIds.add(entry.id)) selectedIds.remove(entry.id)
                    refresh()
                    updateSelectionBar()
                } else {
                    open(entry)
                }
            }
            setOnLongClickListener {
                selectedIds.add(entry.id)
                if (selecting) {
                    refresh()
                    updateSelectionBar()
                } else {
                    setSelecting(true)
                }
                true
            }
        }
    }

    private fun selectionActions(): View = with(activity) {
        row {
            selectionButtons.clear()
            selectionCount = text(null, TextRole.LABEL)
            addView(selectionCount, lp(width = 0, weight = 1f, start = 4))
            if (!showingSaved) {
                addView(button(getString(R.string.save_selected_history_short), Controls.Role.SECONDARY, R.drawable.ic_save) {
                    saveSelected()
                }.also(selectionButtons::add), lp(WRAP, WRAP, end = 8))
            }
            addView(button(getString(R.string.delete), Controls.Role.DESTRUCTIVE_TEXT, R.drawable.ic_delete) {
                confirmDeleteSelected()
            }.also(selectionButtons::add), lp(WRAP, WRAP))
            updateSelectionBar()
        }
    }

    private fun updateSelectionBar() {
        selectionCount?.text = activity.resources.getQuantityString(R.plurals.selected_count, selectedIds.size, selectedIds.size)
        selectionButtons.forEach { it.isEnabled = selectedIds.isNotEmpty() }
    }

    private fun setSelecting(enabled: Boolean) {
        selecting = enabled
        if (!enabled) selectedIds.clear()
        exitSelection.isEnabled = enabled
        render()
    }

    private fun open(entry: CaptureHistoryStore.Entry) {
        activity.startActivity(
            Intent(activity, PacketHistoryActivity::class.java)
                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_ID, entry.id)
                .putExtra(PacketHistoryActivity.EXTRA_ENTRY_TITLE, entry.title)
                .putExtra(PacketHistoryActivity.EXTRA_SAVED_ENTRY, showingSaved)
                .putExtra(PacketHistoryActivity.EXTRA_STORAGE_ID, scope?.storageId),
        )
    }

    private fun saveSelected() {
        val source = historyStore ?: return
        val target = savedStore ?: return
        if (work.isBusy(WRITE)) return
        val ids = selectedIds.toList()
        work.run(key = WRITE, task = { target.saveFrom(source, ids) }) { result ->
            val saveResult = result.getOrNull()
            val resources = activity.resources
            Feedback.show(
                activity,
                when {
                    saveResult == null -> activity.getString(R.string.status_save_history_failed)
                    saveResult.saved > 0 && saveResult.limitReached -> resources.getQuantityString(
                        R.plurals.status_saved_history_at_limit,
                        saveResult.saved,
                        saveResult.saved,
                        SavedHistoryStore.MAX_ENTRIES,
                    )
                    saveResult.saved > 0 ->
                        resources.getQuantityString(R.plurals.status_saved_history, saveResult.saved, saveResult.saved)
                    saveResult.limitReached -> activity.getString(R.string.status_saved_history_limit, SavedHistoryStore.MAX_ENTRIES)
                    else -> activity.getString(R.string.status_history_already_saved)
                },
            )
            setSelecting(false)
            load()
        }
    }

    private fun confirmDeleteSelected() {
        val ids = selectedIds.toList()
        val fromSaved = showingSaved
        Dialogs.confirm(
            activity,
            activity.getString(R.string.delete_history_title),
            activity.resources.getQuantityString(R.plurals.delete_history_message, ids.size, ids.size),
            activity.getString(R.string.delete),
            destructive = true,
        ) confirm@{
            if (work.isBusy(WRITE)) return@confirm
            work.run(
                key = WRITE,
                task = { (if (fromSaved) savedStore?.delete(ids) else historyStore?.delete(ids)) ?: 0 },
            ) { result ->
                val deleted = result.getOrDefault(0)
                Feedback.show(
                    activity,
                    activity.resources.getQuantityString(
                        if (fromSaved) R.plurals.status_deleted_saved_history else R.plurals.status_deleted_history,
                        deleted,
                        deleted,
                    ),
                )
                setSelecting(false)
                load()
            }
        }
    }

    private companion object {
        const val LOAD = "history"
        const val WRITE = "history_write"
        const val RECENT = "recent"
        const val SAVED = "saved"
        const val COLLAPSED_COUNT = 20
        const val STATE_SHOWING_SAVED = "home.showing_saved"
        const val STATE_SHOW_ALL = "home.show_all"
        const val STATE_SELECTING = "home.selecting"
        const val STATE_SELECTED = "home.selected"
    }
}
