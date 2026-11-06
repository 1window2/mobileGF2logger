package dev.gf2log.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberOrderPreferencesTest {
    private val a = 1L
    private val b = 2L
    private val c = 3L
    private val d = 4L

    @Test
    fun `saving the active members keeps a withdrawn member after its neighbour`() {
        // C has withdrawn; the leader moves D above A.
        val merged = MemberOrderMerge.keepingInactive(stored = listOf(a, b, c, d), active = listOf(d, a, b))

        assertEquals(listOf(d, a, b, c), merged)
        // A past week whose roster still lists C shows C after B, not last.
        assertEquals(listOf(d, a, b, c), MemberOrderMerge.apply(merged, listOf(a, b, c, d)) { it })
    }

    @Test
    fun `consecutive and leading withdrawn members keep their places`() {
        val merged = MemberOrderMerge.keepingInactive(stored = listOf(c, a, d, b), active = listOf(b, a))

        assertEquals(listOf(c, b, a, d), merged)
    }

    @Test
    fun `new active members and an empty stored order save as arranged`() {
        assertEquals(listOf(b, a), MemberOrderMerge.keepingInactive(stored = emptyList(), active = listOf(b, a)))
        assertEquals(listOf(c, a, b), MemberOrderMerge.keepingInactive(stored = listOf(a, b), active = listOf(c, a, b)))
    }

    @Test
    fun `a drag that ends where it started is no change`() {
        assertFalse(MemberOrderMerge.changed(listOf(a, b, c), listOf(a, b, c)))
        assertTrue(MemberOrderMerge.changed(listOf(a, b, c), listOf(b, a, c)))
    }

    @Test
    fun `without a saved order the weekly ranking is kept`() {
        val ranked = listOf(c, a, b)
        val prefs = FakeSharedPreferences()
        val order = ScopedMemberOrder(prefs, "profile")
        order.write(listOf(a, b, c))

        assertTrue(order.clear())

        assertEquals(emptyList<Long>(), order.read())
        assertEquals(ranked, MemberOrderMerge.apply(order.read(), ranked) { it })
    }

    @Test
    fun `a failed write leaves the previous order readable`() {
        val prefs = FakeSharedPreferences()
        val order = ScopedMemberOrder(prefs, "profile")
        assertTrue(order.write(listOf(a, b)))
        prefs.failingCommits = 1

        assertFalse(order.write(listOf(b, a)))

        assertEquals(listOf(a, b), order.read())
    }

    @Test
    fun `a failed first write leaves no order behind`() {
        val prefs = FakeSharedPreferences()
        val order = ScopedMemberOrder(prefs, "profile")
        prefs.failingCommits = 1

        assertFalse(order.write(listOf(b, a)))

        assertEquals(emptyList<Long>(), order.read())
        assertFalse(prefs.contains("profile"))
    }

    @Test
    fun `a failed reset keeps the custom order and reports failure`() {
        val prefs = FakeSharedPreferences()
        val order = ScopedMemberOrder(prefs, "profile")
        order.write(listOf(b, a))
        prefs.failingCommits = 1

        assertFalse(order.clear())

        assertEquals(listOf(b, a), order.read())
    }

    @Test
    fun `other profiles in the shared file are untouched by a revert`() {
        val prefs = FakeSharedPreferences()
        ScopedMemberOrder(prefs, "other").write(listOf(d))
        val order = ScopedMemberOrder(prefs, "profile")
        prefs.failingCommits = 1

        order.write(listOf(a))

        assertEquals(listOf(d), ScopedMemberOrder(prefs, "other").read())
    }
}
