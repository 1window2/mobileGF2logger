package dev.gf2log.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Hosts one pre-built view (a header, footer, or state message) inside a ConcatAdapter. The view
 * is never recycled, so focused inputs inside it survive list updates.
 */
internal class SingleViewAdapter(view: View? = null) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var view: View? = view
        set(value) {
            if (field === value) return
            val hadView = field != null
            field = value
            when {
                hadView && value != null -> notifyItemChanged(0)
                hadView -> notifyItemRemoved(0)
                value != null -> notifyItemInserted(0)
            }
        }

    override fun getItemCount(): Int = if (view == null) 0 else 1

    override fun getItemViewType(position: Int): Int = System.identityHashCode(this)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        object : RecyclerView.ViewHolder(
            FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            },
        ) {}.apply { setIsRecyclable(false) }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val container = holder.itemView as FrameLayout
        val content = view ?: return
        if (content.parent === container) return
        (content.parent as? ViewGroup)?.removeView(content)
        container.removeAllViews()
        container.addView(content, FrameLayout.LayoutParams(MATCH, WRAP))
    }
}
