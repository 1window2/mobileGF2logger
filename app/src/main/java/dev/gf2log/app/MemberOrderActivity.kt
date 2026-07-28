package dev.gf2log.app

import android.animation.LayoutTransition
import android.content.ClipData
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Bundle
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import dev.gf2log.app.management.MemberOrderSorter
import dev.gf2log.app.management.MemberSortDirection
import dev.gf2log.app.management.MemberSortField
import dev.gf2log.app.management.MemberStatus
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.SnapshotMember
import dev.gf2log.app.settings.MemberOrderPreferences

class MemberOrderActivity : LocalizedActivity() {
    private lateinit var listContainer: LinearLayout
    private lateinit var scrollView: ScrollView
    private lateinit var preferences: MemberOrderPreferences
    private lateinit var latestMembers: Map<Long, SnapshotMember>
    private var members = mutableListOf<MemberStatus>()
    private var defaultMembers = emptyList<MemberStatus>()
    private var draggedUid: Long? = null
    private var dragCommitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = MemberOrderPreferences(this)
        val repository = PlatoonRepository(this)
        defaultMembers = MemberOrderSorter.sort(
            members = repository.listMemberStatuses(activeOnly = true),
            latestMembers = emptyMap(),
            field = MemberSortField.JOIN_DATE,
            direction = MemberSortDirection.ASCENDING,
        )
        latestMembers = repository.listSnapshots(limit = 1)
            .firstOrNull()
            ?.members
            .orEmpty()
            .associateBy(SnapshotMember::uid)
        members = initialOrder(defaultMembers).toMutableList()
        setContentView(buildContentView())
    }

    private fun initialOrder(active: List<MemberStatus>): List<MemberStatus> =
        if (preferences.read().isNotEmpty()) {
            preferences.apply(active, MemberStatus::uid)
        } else {
            active
        }

    private fun buildContentView(): ScrollView {
        val sortField = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemberOrderActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.sort_join_date),
                    getString(R.string.sort_weekly_merit),
                    getString(R.string.sort_total_merit),
                ),
            )
        }
        val sortDirection = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MemberOrderActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.ascending),
                    getString(R.string.descending),
                ),
            )
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(TextView(context).apply {
                text = getString(R.string.member_order)
                textSize = 26f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWidth())
            addView(TextView(context).apply {
                text = getString(R.string.member_order_description)
                setPadding(0, dp(6), 0, dp(10))
            }, matchWidth())
            addView(sortControlRow(R.string.sort_criterion, sortField), matchWidth())
            addView(sortControlRow(R.string.sort_direction, sortDirection), matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.apply_sort)
                setOnClickListener {
                    members = MemberOrderSorter.sort(
                        members = members,
                        latestMembers = latestMembers,
                        field = MemberSortField.entries[sortField.selectedItemPosition],
                        direction = MemberSortDirection.entries[sortDirection.selectedItemPosition],
                    ).toMutableList()
                    saveAndRender()
                }
            }, matchWidth())
            addView(Button(context).apply {
                text = getString(R.string.reset_member_order)
                setOnClickListener {
                    preferences.clear()
                    members = defaultMembers.toMutableList()
                    render()
                }
            }, matchWidth())
            listContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutTransition = LayoutTransition().apply {
                    setDuration(DRAG_TRANSITION_MILLIS)
                }
                setOnDragListener(::handleListDrag)
            }
            addView(listContainer, matchWidth())
        }
        scrollView = ScrollView(this).apply { addView(body, matchWidth()) }
        render()
        return scrollView
    }

    private fun sortControlRow(label: Int, spinner: Spinner) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = getString(label)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, wrap(), 1f))
        addView(spinner, LinearLayout.LayoutParams(0, wrap(), 2f))
    }

    private fun render() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        members.forEachIndexed { index, member ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(2), 0, dp(2))
                tag = member.uid
                addView(TextView(context).apply {
                    text = "${index + 1}. ${member.name}\n#${member.uid}"
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                }, LinearLayout.LayoutParams(0, wrap(), 1f))
            }
            row.addView(TextView(this).apply {
                text = "\u2630"
                textSize = 25f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(90, 94, 101))
                contentDescription = getString(R.string.drag_member, member.name)
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startDragAndDrop(
                        ClipData.newPlainText("member_uid", member.uid.toString()),
                        View.DragShadowBuilder(row),
                        member.uid,
                        0,
                    )
                    true
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
            listContainer.addView(row, matchWidth())
        }
        draggedUid?.let(::highlightDraggedRow)
    }

    private fun handleListDrag(view: View, event: DragEvent): Boolean {
        if (event.localState !is Long) return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                draggedUid = event.localState as Long
                dragCommitted = false
                highlightDraggedRow(draggedUid ?: return false)
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                autoScroll(event.y)
                previewDraggedMember(event.localState as Long, event.y)
            }
            DragEvent.ACTION_DROP -> {
                dragCommitted = true
                preferences.write(members.map(MemberStatus::uid))
                draggedUid = null
                render()
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                if (!dragCommitted) {
                    preferences.write(members.map(MemberStatus::uid))
                }
                draggedUid = null
                render()
            }
        }
        return true
    }

    private fun autoScroll(y: Float) {
        val location = IntArray(2)
        listContainer.getLocationOnScreen(location)
        val screenY = location[1] + y
        val scrollLocation = IntArray(2)
        scrollView.getLocationOnScreen(scrollLocation)
        val top = scrollLocation[1]
        val bottom = top + scrollView.height
        when {
            screenY < top + dp(72) -> scrollView.scrollBy(0, -dp(24))
            screenY > bottom - dp(72) -> scrollView.scrollBy(0, dp(24))
        }
    }

    private fun previewDraggedMember(uid: Long, y: Float) {
        val from = members.indexOfFirst { it.uid == uid }
        if (from < 0) return
        var target = members.size
        for (index in 0 until listContainer.childCount) {
            val child = listContainer.getChildAt(index)
            if (y < child.top + child.height / 2f) {
                target = index
                break
            }
        }
        if (target > from) target -= 1
        target = target.coerceIn(0, members.lastIndex)
        if (target == from) return

        val member = members.removeAt(from)
        members.add(target, member)
        val row = listContainer.getChildAt(from)
        listContainer.removeViewAt(from)
        listContainer.addView(row, target)
        refreshVisiblePositions()
        listContainer.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        highlightDraggedRow(uid)
    }

    private fun refreshVisiblePositions() {
        members.forEachIndexed { index, member ->
            val row = listContainer.getChildAt(index) as? LinearLayout ?: return@forEachIndexed
            val label = row.getChildAt(0) as? TextView ?: return@forEachIndexed
            label.text = "${index + 1}. ${member.name}\n#${member.uid}"
        }
    }

    private fun highlightDraggedRow(uid: Long) {
        for (index in 0 until listContainer.childCount) {
            val row = listContainer.getChildAt(index)
            val selected = row.tag == uid
            row.elevation = if (selected) dp(5).toFloat() else 0f
            row.background = if (selected) {
                GradientDrawable().apply {
                    setColor(DRAG_SURFACE)
                    setStroke(dp(2), DRAG_BORDER)
                    cornerRadius = dp(7).toFloat()
                }
            } else {
                null
            }
        }
    }

    private fun saveAndRender() {
        preferences.write(members.map(MemberStatus::uid))
        render()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val DRAG_TRANSITION_MILLIS = 120L
        val DRAG_BORDER = Color.rgb(232, 132, 32)
        val DRAG_SURFACE = Color.argb(48, 232, 132, 32)
    }
}
