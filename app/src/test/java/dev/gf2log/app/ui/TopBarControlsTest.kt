package dev.gf2log.app.ui

import dev.gf2log.app.stacksPickerFields
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopBarControlsTest {
    private val selectAll = BarAction(1, "Select all") {}
    private val export = BarAction(2, "Export selected members", enabled = false) {}

    @Test
    fun `re-applying the same buttons keeps them, whatever their state or handler`() {
        assertTrue(sameControls(listOf(selectAll, export), listOf(selectAll.copy(onClick = {}), export.copy(enabled = true))))
        assertTrue(sameControls(emptyList(), emptyList()))
    }

    @Test
    fun `a different icon, label, order, or count rebuilds the bar`() {
        assertFalse(sameControls(listOf(selectAll, export), listOf(export, selectAll)))
        assertFalse(sameControls(listOf(selectAll), listOf(selectAll.copy(icon = 3))))
        assertFalse(sameControls(listOf(selectAll), listOf(selectAll.copy(label = "Clear selection"))))
        assertFalse(sameControls(listOf(selectAll), listOf(selectAll, export)))
    }

    @Test
    fun `large text stacks the boundary date and time fields`() {
        assertFalse(stacksPickerFields(1f))
        assertFalse(stacksPickerFields(1.15f))
        assertTrue(stacksPickerFields(1.3f))
        assertTrue(stacksPickerFields(2f))
    }
}
