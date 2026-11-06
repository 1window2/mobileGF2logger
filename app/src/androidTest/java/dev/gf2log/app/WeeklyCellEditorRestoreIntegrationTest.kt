package dev.gf2log.app

import android.content.Context
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gf2log.app.management.PlatoonProfileRegistry
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.PlatoonStorageScope
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonProfileData
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The weekly cell editor reopens with what was typed after its screen is recreated. */
@RunWith(AndroidJUnit4::class)
class WeeklyCellEditorRestoreIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var storageId: String

    @Before
    fun setUp() {
        clear()
        storageId = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(301_001u, "Cell editor Platoon", 0u, 0u),
        ).storageId
        check(PlatoonProfileRegistry(context).setActive(storageId))
        PlatoonRepository(context, PlatoonStorageScope(storageId)).ingest(
            Instant.now(),
            listOf(GuildMember(505_505u, "Edited", 60u, 0u, 0u, 0u, 0u, 0u)),
            "cell-editor.csv",
        )
    }

    @After
    fun tearDown() = clear()

    @Test
    fun aTypedMeritSurvivesRecreationOfTheEditor() {
        ActivityScenario.launch(WeeklyReportActivity::class.java).use { scenario ->
            waitFor { scenario.read { it.renderedMembers().isNotEmpty() } }
            val original = scenario.read { it }
            scenario.onActivity { activity ->
                val member = activity.renderedMembers().first()
                activity.startEditing(member)
                activity.onCellTapped(member, member.days.first())
                requireNotNull(activity.cellEditor).findViewById<EditText>(R.id.weekly_cell_merit).setText("42")
            }

            scenario.recreate()

            waitFor { scenario.read { it !== original && it.cellEditor?.isShowing == true } }
            scenario.onActivity { activity ->
                assertNotSame(original, activity)
                val merit = requireNotNull(activity.cellEditor).findViewById<EditText>(R.id.weekly_cell_merit)
                assertEquals("42", merit.text.toString())
            }
        }
    }

    private fun WeeklyReportActivity.renderedMembers(): List<WeeklyReportBuilder.MemberRow> {
        val model = WeeklyReportActivity::class.java.getDeclaredField("model").apply { isAccessible = true }.get(this)
            ?: return emptyList()
        val report = model.javaClass.getDeclaredMethod("getReport").invoke(model) as WeeklyReportBuilder.Report
        return report.members
    }

    /** Edit mode as the toolbar's confirmed Edit action starts it. */
    private fun WeeklyReportActivity.startEditing(member: WeeklyReportBuilder.MemberRow) {
        val edits = WeeklyReportActivity::class.java.getDeclaredField("edits").apply { isAccessible = true }
            .get(this) as WeeklyEditSession
        val model = WeeklyReportActivity::class.java.getDeclaredField("model").apply { isAccessible = true }.get(this)!!
        val report = model.javaClass.getDeclaredMethod("getReport").invoke(model) as WeeklyReportBuilder.Report
        check(member in report.members)
        edits.start(report.periodStart)
    }

    private fun <T> ActivityScenario<WeeklyReportActivity>.read(block: (WeeklyReportActivity) -> T): T {
        var value: T? = null
        onActivity { value = block(it) }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "Condition not reached" }
            Thread.sleep(50)
        }
    }

    private fun clear() {
        context.databaseList()
            .filter { it.matches(Regex("platoon-[0-9a-f]{32}\\.db")) }
            .forEach { name ->
                PlatoonRepository.withExclusiveDatabase(PlatoonStorageScope.fromDatabaseName(name)) {
                    context.deleteDatabase(name)
                }
            }
        context.getSharedPreferences("platoon_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "platoons").deleteRecursively()
    }
}
