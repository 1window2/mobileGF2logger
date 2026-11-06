package dev.gf2log.app.management

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.capture.CaptureHistoryPreparation
import dev.gf2log.app.capture.CaptureNotice
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.GameTimeZonePreferences
import dev.gf2log.protocol.GuildMembersCsv
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.PlatoonProfileData
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** How a damaged database and a held CSV-import claim reach the screens and capture start. */
@RunWith(AndroidJUnit4::class)
class PlatoonAccessFailureIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var profile: PlatoonProfile
    private lateinit var scope: PlatoonStorageScope

    @Before
    fun setUp() {
        profile = PlatoonProfileRegistry(context).upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(Random.nextInt(1_000_000, 9_000_000).toUInt(), "Access Platoon", 0u, 0u),
        )
        check(PlatoonProfileRegistry(context).setActive(profile.storageId))
        scope = PlatoonStorageScope(profile.storageId)
        GameTimeZonePreferences.set(context, ZoneOffset.UTC, scope.storageId)
    }

    @After
    fun tearDown() {
        runCatching { PlatoonProfileAdministration(context).deleteProfile(profile.storageId) }
        PlatoonRepository.withExclusiveDatabase(scope) { context.deleteDatabase(scope.databaseName) }
        scope.rootDirectory(context).deleteRecursively()
    }

    @Test
    fun aQuarantinedDatabaseIsReportedAsDamagedAndTheRebuildKeepsTheDamagedCopy() {
        val retained = scope.retainedCsvDirectory(context).apply { mkdirs() }
        File(retained, "gf2log_platoonmembers_20260803_120000.csv").writeText(
            GuildMembersCsv.format(GuildMembersCsv.Snapshot(CAPTURED_AT.toString(), listOf(member(1, "One")))),
        )
        val repository = PlatoonRepository(context, scope)
        assertEquals(1, repository.reconcileRetainedCsvFiles().imported)
        PlatoonRepository.withExclusiveDatabase(scope) {
            PlatoonDatabaseDamage.quarantine(context, scope, context.getDatabasePath(scope.databaseName))
        }

        val read = runCatching { repository.listSnapshots() }.exceptionOrNull()
        assertEquals(PlatoonAccessFailure.DATABASE_DAMAGED, PlatoonAccessFailure.of(read))
        val start = runCatching {
            CaptureHistoryPreparation.run(listOf(profile), name = PlatoonProfile::platoonName, recover = { 0 }) {
                PlatoonRepository(context, scope).reconcileRetainedCsvFiles()
            }
        }.exceptionOrNull()
        assertEquals(
            CaptureNotice.DATABASE_DAMAGED to listOf("Access Platoon"),
            CaptureHistoryPreparation.stopNotice(start),
        )

        PlatoonDatabaseDamage.rebuildFromRetainedCsv(context, scope)

        assertFalse(PlatoonDatabaseDamage.isDamaged(context, scope))
        assertEquals(1, PlatoonRepository(context, scope).listSnapshots().size)
        // The damaged copy is never deleted by the rebuild.
        assertTrue(
            PlatoonDatabaseDamage.quarantinedFiles(context, scope).any { file ->
                file.isFile && !file.name.endsWith("-wal") && !file.name.endsWith("-journal")
            },
        )
    }

    @Test
    fun aHeldClaimRefusesOtherThreadsWithTheTypedException() {
        val repository = PlatoonRepository(context, scope)
        repository.ingest(CAPTURED_AT, listOf(member(1, "One")), "roster.csv")
        val archive = ByteArrayOutputStream().also { PlatoonBackupManager(context, scope).export(it) }.toByteArray()
        val applying = CountDownLatch(1)
        val release = CountDownLatch(1)
        val importer = Thread {
            CsvImportCheckpointManager(context, scope).runImport(emptySet()) {
                applying.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
        }.apply { start() }
        try {
            assertTrue(applying.await(10, TimeUnit.SECONDS))
            assertTrue(CsvImportCheckpointManager.anyBusy())
            listOf(
                runCatching { repository.listSnapshots() },
                runCatching { CsvImportCheckpointManager(context, scope).restore() },
                runCatching { CsvImportCheckpointManager(context, scope).runImport(emptySet()) {} },
                runCatching { PlatoonBackupManager.restoreSelected(context, ByteArrayInputStream(archive)) },
                runCatching { PlatoonDatabaseDamage.rebuildFromRetainedCsv(context, scope) },
            ).forEach { result ->
                val failure = result.exceptionOrNull()
                assertEquals(failure.toString(), PlatoonAccessFailure.IMPORT_IN_PROGRESS, PlatoonAccessFailure.of(failure))
            }
            val start = runCatching {
                CaptureHistoryPreparation.run(
                    listOf(scope),
                    busy = { CsvImportCheckpointManager.isBusy(it.storageId) },
                    recover = { CsvImportCheckpointManager.recoverInterruptedImports(context, it) },
                ) {}
            }.exceptionOrNull()
            assertEquals(CaptureNotice.CSV_IMPORT_IN_PROGRESS to emptyList<String>(), CaptureHistoryPreparation.stopNotice(start))
        } finally {
            release.countDown()
            importer.join(10_000)
        }
        assertFalse(CsvImportCheckpointManager.anyBusy())
        assertEquals(1, PlatoonRepository(context, scope).listSnapshots().size)
    }

    private fun member(uid: Long, name: String) = GuildMember(
        uid = uid.toUInt(),
        name = name,
        level = 60u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private companion object {
        val CAPTURED_AT: Instant = Instant.parse("2026-08-03T12:00:00Z")
    }
}
