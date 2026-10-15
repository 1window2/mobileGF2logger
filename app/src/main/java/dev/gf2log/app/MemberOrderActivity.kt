package dev.gf2log.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dev.gf2log.app.management.MemberOrderSorter
import dev.gf2log.app.management.MemberSortDirection
import dev.gf2log.app.management.MemberSortField
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.SnapshotMember
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.ui.Controls
import dev.gf2log.app.ui.BackgroundWork
import dev.gf2log.app.ui.Dialogs
import dev.gf2log.app.ui.Feedback
import dev.gf2log.app.ui.MATCH
import dev.gf2log.app.ui.MenuEntry
import dev.gf2log.app.ui.Screen
import dev.gf2log.app.ui.SingleViewAdapter
import dev.gf2log.app.ui.StateViews
import dev.gf2log.app.ui.TextRole
import dev.gf2log.app.ui.WRAP
import dev.gf2log.app.ui.button
import dev.gf2log.app.ui.column
import dev.gf2log.app.ui.dp
import dev.gf2log.app.ui.dpf
import dev.gf2log.app.ui.icon
import dev.gf2log.app.ui.lp
import dev.gf2log.app.ui.panel
import dev.gf2log.app.ui.row
import dev.gf2log.app.ui.text

/**
 * Persists the member order used by weekly tables. Members can be sorted once, then fine-tuned by
 * dragging the handle or through the accessibility "move up/down" actions; every change saves.
 */
class MemberOrderActivity : BaseActivity() {

    private lateinit var profileBinding: ActivePlatoonScopeBinding
    private lateinit var repository: PlatoonRepository
    private lateinit var preferences: MemberOrderPreferences
    private val work = BackgroundWork(this, "GF2MemberOrder")
    private val adapter = OrderAdapter()
    private val stateAdapter = SingleViewAdapter()
    private lateinit var touchHelper: ItemTouchHelper
    private var members = mutableListOf<MemberStatus>()
    private var latestMembers = emptyMap<Long, SnapshotMember>()
    private var sortField = MemberSortField.JOIN_DATE
    private var sortDirection = MemberSortDirection.ASCENDING
    private lateinit var fieldButton: TextView
    private lateinit var directionButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileBinding = ActivePlatoonScopeBinding(this)
        val scope = profileBinding.scope ?: run {
            finish()
            return
        }
        repository = PlatoonRepository(this, scope)
        preferences = MemberOrderPreferences(this, scope.storageId)
        val screen = Screen(this)
        screen.topBar
            .setNavigation(::finish)
            .setTitle(getString(R.string.member_order))
            .setActions(
                emptyList(),
                overflow = listOf(MenuEntry(getString(R.string.reset_member_order)) { confirmReset() }),
            )
        val list = RecyclerView(this).apply {
            id = R.id.screen_list
            layoutManager = LinearLayoutManager(context)
            adapter = ConcatAdapter(SingleViewAdapter(buildHeader()), this@MemberOrderActivity.adapter, stateAdapter)
            setPadding(dp(16), 0, dp(16), dp(24))
        }
        touchHelper = ItemTouchHelper(DragCallback()).apply { attachToRecyclerView(list) }
        screen.setBody(list)
        stateAdapter.view = StateViews.loading(this, getString(R.string.loading_members))
        load()
    }

    override fun onScreenResumed() {
        if (!profileBinding.isCurrent(this)) finish()
    }

    private fun buildHeader(): View = column {
        addView(text(getString(R.string.member_order_description), TextRole.SECONDARY), lp(top = 4))
        fieldButton = choiceChip { chooseField() }
        directionButton = choiceChip { toggleDirection() }
        addView(panel(padding = 12) {
            addView(text(getString(R.string.sort_once), TextRole.LABEL), lp())
            addView(row {
                addView(fieldButton, lp(width = 0, height = 48, weight = 1.4f))
                addView(directionButton, lp(width = 0, height = 48, weight = 1f, start = 8))
            }, lp(top = 8))
            addView(button(getString(R.string.apply_sort), Controls.Role.SECONDARY) { applySort() }, lp(top = 8))
        }, lp(top = 12, bottom = 8))
        updateSortLabels()
    }

    private fun choiceChip(onClick: () -> Unit): TextView = text(null, TextRole.BODY) {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(8), 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(getColor(R.color.surface_variant))
            cornerRadius = dpf(12f)
        }
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_expand_more, 0)
        compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.text_secondary))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun load() {
        work.run(
            task = {
                val active = repository.listMemberStatuses(activeOnly = true)
                val defaults = MemberOrderSorter.sort(active, emptyMap(), MemberSortField.JOIN_DATE, MemberSortDirection.ASCENDING)
                val ordered = if (preferences.read().isEmpty()) defaults else preferences.apply(defaults, MemberStatus::uid)
                ordered to repository.listSnapshots(1).firstOrNull()?.members.orEmpty().associateBy(SnapshotMember::uid)
            },
        ) { result ->
            result.fold(
                onSuccess = { (ordered, latest) ->
                    members = ordered.toMutableList()
                    latestMembers = latest
                    stateAdapter.view = if (members.isEmpty()) {
                        StateViews.empty(this, R.drawable.ic_group, getString(R.string.no_active_members))
                    } else {
                        null
                    }
                    adapter.notifyDataSetChanged()
                },
                onFailure = { stateAdapter.view = StateViews.failure(this, getString(R.string.platoon_load_failed)) { load() } },
            )
        }
    }

    private fun chooseField() {
        val fields = MemberSortField.entries
        Dialogs.choose(this, getString(R.string.sort_criterion), fields.map { getString(fieldLabel(it)) }, sortField.ordinal) {
            sortField = fields[it]
            updateSortLabels()
        }
    }

    private fun toggleDirection() {
        sortDirection = if (sortDirection == MemberSortDirection.ASCENDING) {
            MemberSortDirection.DESCENDING
        } else {
            MemberSortDirection.ASCENDING
        }
        updateSortLabels()
    }

    private fun updateSortLabels() {
        fieldButton.text = getString(fieldLabel(sortField))
        fieldButton.contentDescription = getString(R.string.sort_criterion) + ": " + fieldButton.text
        directionButton.text = getString(
            if (sortDirection == MemberSortDirection.ASCENDING) R.string.ascending else R.string.descending,
        )
        directionButton.contentDescription = getString(R.string.sort_direction) + ": " + directionButton.text
    }

    private fun fieldLabel(field: MemberSortField) = when (field) {
        MemberSortField.JOIN_DATE -> R.string.sort_join_date
        MemberSortField.WEEKLY_MERIT -> R.string.sort_weekly_merit
        MemberSortField.TOTAL_MERIT -> R.string.sort_total_merit
    }

    private fun applySort() {
        members = MemberOrderSorter.sort(members, latestMembers, sortField, sortDirection).toMutableList()
        adapter.notifyDataSetChanged()
        persist()
    }

    private fun confirmReset() {
        Dialogs.confirm(this, R.string.reset_member_order, R.string.reset_member_order_warning, R.string.reset) {
            work.run(task = { preferences.clear() }) {
                Feedback.show(this, R.string.member_order_reset)
                load()
            }
        }
    }

    private fun move(from: Int, to: Int) {
        if (from !in members.indices || to !in members.indices || from == to) return
        members.add(to, members.removeAt(from))
        adapter.notifyItemMoved(from, to)
        adapter.notifyItemRangeChanged(minOf(from, to), kotlin.math.abs(from - to) + 1, POSITION_PAYLOAD)
    }

    private fun persist() {
        val order = members.map(MemberStatus::uid)
        work.run(task = { check(preferences.write(order)) }) { result ->
            if (result.isFailure) Feedback.show(this, R.string.save_failed)
        }
    }

    private inner class OrderAdapter : RecyclerView.Adapter<OrderHolder>() {
        override fun getItemCount() = members.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = OrderHolder()
        override fun onBindViewHolder(holder: OrderHolder, position: Int) = holder.bind(members[position], position)
    }

    private inner class OrderHolder : RecyclerView.ViewHolder(LinearLayout(this@MemberOrderActivity)) {
        private val position = text(null, TextRole.LABEL) {
            gravity = Gravity.CENTER
            minWidth = dp(32)
        }
        private val name = text(null, TextRole.SUBTITLE) {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val detail = text(null, TextRole.CAPTION)
        private val handle = icon(R.drawable.ic_drag_handle).apply {
            scaleType = ImageView.ScaleType.CENTER
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun install() {
            handle.setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    touchHelper.startDrag(this)
                }
                false
            }
        }

        init {
            (itemView as LinearLayout).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(60)
                setPadding(dp(4), dp(6), 0, dp(6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(getColor(R.color.surface))
                    cornerRadius = dpf(12f)
                }
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
                addView(position, lp(WRAP, WRAP, end = 8))
                addView(column {
                    addView(name, lp())
                    addView(detail, lp())
                }, lp(width = 0, weight = 1f))
                addView(handle, lp(48, 48))
            }
            install()
        }

        fun bind(member: MemberStatus, index: Int) {
            position.text = (index + 1).toString()
            name.text = member.name
            detail.text = "#${member.uid}"
            itemView.contentDescription = getString(R.string.member_order_position, index + 1, members.size, member.name)
            handle.contentDescription = getString(R.string.drag_member, member.name)
            ViewCompat.removeAccessibilityAction(itemView, R.id.action_move_up)
            ViewCompat.removeAccessibilityAction(itemView, R.id.action_move_down)
            if (index > 0) {
                ViewCompat.replaceAccessibilityAction(
                    itemView,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.action_move_up, getString(R.string.move_up)),
                    getString(R.string.move_up),
                ) { _, _ -> move(bindingAdapterPosition, bindingAdapterPosition - 1).also { persist() }; true }
            }
            if (index < members.lastIndex) {
                ViewCompat.replaceAccessibilityAction(
                    itemView,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat(R.id.action_move_down, getString(R.string.move_down)),
                    getString(R.string.move_down),
                ) { _, _ -> move(bindingAdapterPosition, bindingAdapterPosition + 1).also { persist() }; true }
            }
        }
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun isLongPressDragEnabled() = true

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
            if (viewHolder is OrderHolder) super.getMovementFlags(recyclerView, viewHolder) else 0

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (target !is OrderHolder) return false
            move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            recyclerView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                viewHolder?.itemView?.background?.setTint(getColor(R.color.accent_surface))
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.background?.setTint(getColor(R.color.surface))
            persist()
        }
    }

    private companion object {
        val POSITION_PAYLOAD = Any()
    }
}
