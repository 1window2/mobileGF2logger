package dev.gf2log.app

import android.view.View
import android.widget.AdapterView

class SimpleItemSelectedListener(
    private val selected: () -> Unit,
) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        selected()
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
