package dev.gf2log.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonProfileData
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A draft typed for one Platoon survives recreation bound to that Platoon, with its save-or-discard
 * prompt, after another Platoon became active; it is never restored into, or saved to, the new one.
 */
@RunWith(AndroidJUnit4::class)
class ScopeChangeDraftIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val registry = PlatoonProfileRegistry(context)
    private lateinit var first: String
    private lateinit var second: String

    @Before
    fun setUp() {
        clear()
        first = profile(201_001u, "Draft Platoon")
        second = profile(201_002u, "Other Platoon")
        check(registry.setActive(first))
    }

    @After
    fun tearDown() = clear()

    @Test
    fun weeklyCutlineDraftStaysWithItsPlatoonAcrossRecreation() {
        WeeklyCutlinePreferences(context, first).write(WeeklyCutlines(dailyMerit = 100))
        ActivityScenario.launch(WeeklySettingsActivity::class.java).use { scenario ->
            scenario.onActivity { it.findViewById<EditText>(R.id.cutline_daily_merit).setText("250") }
            switchPlatoonWhileShown(scenario)
            scenario.onActivity { assertNotNull(it.draftPromptDialog()) }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals(first, activity.boundStorageId())
                assertEquals("250", activity.findViewById<EditText>(R.id.cutline_daily_merit).text.toString())
                (activity.draftPromptDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            waitFor { WeeklyCutlinePreferences(context, first).read().dailyMerit == 250L }
            assertNull(WeeklyCutlinePreferences(context, second).read().dailyMerit)
        }
    }

    @Test
    fun memberNoteDraftStaysWithItsPlatoonAcrossRecreation() {
        PlatoonRepository(context, PlatoonStorageScope(first)).ingest(AT, listOf(member()), "first.csv")
        val other = PlatoonRepository(context, PlatoonStorageScope(second))
        other.ingest(AT, listOf(member()), "second.csv")
        assertTrue(other.updateMember(UID, "", "other note"))
        val intent = Intent(context, MemberDetailActivity::class.java).putExtra(MemberDetailActivity.EXTRA_UID, UID)
        ActivityScenario.launch<MemberDetailActivity>(intent).use { scenario ->
            waitFor { scenario.read { it.findViewById<EditText>(R.id.member_note_input).hint != null && it.loaded() } }
            scenario.onActivity { it.findViewById<EditText>(R.id.member_note_input).setText("draft note") }
            switchPlatoonWhileShown(scenario)
            scenario.onActivity { assertNotNull(it.draftPromptDialog()) }

            scenario.recreate()

            // The recreated screen loads the member of its own Platoon again, then asks.
            waitFor { scenario.read { it.draftPromptDialog() != null } }
            scenario.onActivity { activity ->
                assertEquals(first, activity.boundStorageId())
                assertEquals("draft note", activity.findViewById<EditText>(R.id.member_note_input).text.toString())
                (activity.draftPromptDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            waitFor { note(first) == "draft note" }
            assertEquals("other note", note(second))
        }
    }

    @Test
    fun anOpenWithdrawnMemberDraftKeepsItsPlatoonUntilItCloses() {
        ActivityScenario.launch(PlatoonActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.addWithdrawnMember()
                activity.withdrawnDialog!!.findViewById<EditText>(R.id.dialog_uid_input).setText("123456")
            }
            val original = scenario.read { it }
            switchPlatoonWhileShown(scenario)

            // Not recreated under the open dialog, which still adds to the Platoon it was typed for.
            scenario.onActivity { activity ->
                assertSame(original, activity)
                assertEquals(first, activity.boundStorageId())
                assertTrue(activity.withdrawnDialog!!.isShowing)
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals(first, activity.boundStorageId())
                val dialog = requireNotNull(activity.withdrawnDialog)
                assertEquals("123456", dialog.findViewById<EditText>(R.id.dialog_uid_input).text.toString())
                dialog.dismiss()
            }
            // Closing it follows the newly selected Platoon, with no draft carried over.
            waitFor { scenario.read { it !== original && it.boundStorageId() == second && it.withdrawnDialog == null } }
            assertFalse(PlatoonRepository(context, PlatoonStorageScope(second)).listMemberStatuses().any { it.uid == 123_456L })
        }
    }

    private fun <A : BaseActivity> switchPlatoonWhileShown(scenario: ActivityScenario<A>) {
        check(registry.setActive(second))
        scenario.moveToState(Lifecycle.State.STARTED)
        scenario.moveToState(Lifecycle.State.RESUMED)
    }

    private fun <A : Activity, T> ActivityScenario<A>.read(block: (A) -> T): T {
        var value: T? = null
        onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun MemberDetailActivity.loaded(): Boolean =
        MemberDetailActivity::class.java.getDeclaredField("model").apply { isAccessible = true }.get(this) != null

    private fun note(storageId: String): String =
        PlatoonRepository(context, PlatoonStorageScope(storageId)).listMemberStatuses().single { it.uid == UID }.note

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "Condition not reached" }
            Thread.sleep(50)
        }
    }

    private fun profile(id: UInt, name: String): String = registry.upsertDetected(
        SupportedGamePackages.HAOPLAY,
        GameServerRegion.HAOPLAY_KOREA,
        PlatoonProfileData(id, name, 0u, 0u),
    ).storageId

    private fun member() = GuildMember(
        uid = UID.toUInt(),
        name = "Drafted",
        level = 60u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private fun clear() {
        context.databaseList()
            .filter { it.matches(Regex("platoon-[0-9a-f]{32}\\.db")) }
            .forEach { name ->
                PlatoonRepository.withExclusiveDatabase(PlatoonStorageScope.fromDatabaseName(name)) {
                    context.deleteDatabase(name)
                }
            }
        listOf("platoon_profiles", "platoon_weekly_cutlines").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        java.io.File(context.filesDir, "platoons").deleteRecursively()
    }

    private companion object {
        const val UID = 404_404L
        val AT: Instant = Instant.parse("2026-08-03T12:00:00Z")
    }
}
