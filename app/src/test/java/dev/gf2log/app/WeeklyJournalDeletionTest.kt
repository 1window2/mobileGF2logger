package dev.gf2log.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WeeklyJournalDeletionTest {
    @Test
    fun `only a stored deletion reports success`() {
        assertEquals(R.string.note_deleted, WeeklyJournalSection.deletionFeedback(Result.success(true)))
    }

    @Test
    fun `a deletion that changed nothing or threw reports failure`() {
        assertEquals(R.string.note_delete_failed, WeeklyJournalSection.deletionFeedback(Result.success(false)))
        assertEquals(
            R.string.note_delete_failed,
            WeeklyJournalSection.deletionFeedback(Result.failure(IllegalStateException("disk I/O"))),
        )
    }
}
