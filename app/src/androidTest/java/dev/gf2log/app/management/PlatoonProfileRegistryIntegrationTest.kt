package dev.gf2log.app.management

import androidx.test.core.app.ApplicationProvider
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.GuildMember
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlatoonProfileRegistryIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() = clearState()

    @After
    fun tearDown() = clearState()

    @Test
    fun emptyDatabaseIsNotRegisteredAsLegacyData() {
        PlatoonDatabase(context).use { database ->
            database.recordWeeklyReportHistory(
                periodStartEpochDay = 0L,
                recordedAt = Instant.EPOCH,
                fingerprint = "0".repeat(64),
                payload = byteArrayOf(1),
                clearActiveOnChange = false,
            )
        }

        val registry = PlatoonProfileRegistry(context)

        assertTrue(registry.ensureInitialized().isEmpty())
        assertEquals(PlatoonProfileIdentity.LEGACY_STORAGE_ID, registry.activeScope().storageId)
    }

    @Test
    fun existingDatabaseWithManagementDataIsRegisteredWithoutMovingIt() {
        val legacyDatabase = context.getDatabasePath(PlatoonSchema.DATABASE_NAME)
        PlatoonRepository(context).ingest(
            Instant.parse("2026-08-24T00:00:00Z"),
            listOf(member(1u, "Legacy member")),
            "legacy.csv",
        )

        val registry = PlatoonProfileRegistry(context)
        val profile = registry.ensureInitialized().single()

        assertTrue(profile.legacy)
        assertEquals(PlatoonSchema.DATABASE_NAME, registry.activeScope().databaseName)
        assertTrue(legacyDatabase.isFile)
    }

    @Test
    fun publisherAndRegionKeepEqualPlatoonIdsInDifferentScopes() {
        val registry = PlatoonProfileRegistry(context)
        val data = PlatoonProfileData(101817u, "Owls", listOf(1u), listOf(2u))

        val haoPlay = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            data,
            Instant.parse("2026-08-24T00:00:00Z"),
        )
        val darkwinter = registry.upsertDetected(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_GLOBAL,
            data,
            Instant.parse("2026-08-24T00:01:00Z"),
        )

        assertNotEquals(haoPlay.storageId, darkwinter.storageId)
        assertNotEquals(
            PlatoonStorageScope(haoPlay.storageId).databaseName,
            PlatoonStorageScope(darkwinter.storageId).databaseName,
        )
        assertEquals(2, registry.list().size)
        assertTrue(registry.setActive(darkwinter.storageId))
        assertEquals(darkwinter.storageId, registry.activeScope().storageId)
        assertFalse(registry.setActive("0".repeat(32)))
    }

    @Test
    fun equalMemberUidsRemainIsolatedAcrossProfileDatabases() {
        val registry = PlatoonProfileRegistry(context)
        val identity = PlatoonProfileData(77u, "First", emptyList(), emptyList())
        val first = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            identity,
        )
        val second = registry.upsertDetected(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_GLOBAL,
            identity.copy(platoonName = "Second"),
        )
        val firstRepository = PlatoonRepository(context, PlatoonStorageScope(first.storageId))
        val secondRepository = PlatoonRepository(context, PlatoonStorageScope(second.storageId))

        firstRepository.ingest(
            Instant.parse("2026-08-24T01:00:00Z"),
            listOf(member(9u, "HaoPlay member")),
            "first.csv",
        )
        secondRepository.ingest(
            Instant.parse("2026-08-24T01:00:00Z"),
            listOf(member(9u, "Darkwinter member")),
            "second.csv",
        )

        assertEquals("HaoPlay member", firstRepository.listMemberStatuses().single().name)
        assertEquals("Darkwinter member", secondRepository.listMemberStatuses().single().name)
    }

    @Test
    fun registryRejectsUnboundedNewProfilesAndCanRemoveInactiveMetadata() {
        val registry = PlatoonProfileRegistry(context)
        val profiles = (1..PlatoonProfileRegistry.MAX_PROFILES).map { id ->
            registry.upsertDetected(
                SupportedGamePackages.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                PlatoonProfileData(id.toUInt(), "Platoon $id", emptyList(), emptyList()),
            )
        }

        val overflow = runCatching {
            registry.upsertDetected(
                SupportedGamePackages.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                PlatoonProfileData(999u, "Overflow", emptyList(), emptyList()),
            )
        }

        assertTrue(overflow.isFailure)
        assertTrue(registry.removeIfInactive(profiles.last().storageId))
        assertEquals(PlatoonProfileRegistry.MAX_PROFILES - 1, registry.list().size)
        assertFalse(registry.removeIfInactive(profiles.first().storageId))

        val retained = File(
            PlatoonStorageScope(profiles.first().storageId).rootDirectory(context),
            "retained-proof.txt",
        ).apply {
            parentFile?.mkdirs()
            writeText("preserve")
        }
        assertTrue(registry.forget(profiles.first().storageId))
        assertTrue(retained.isFile)
        assertFalse(registry.list().any { it.storageId == profiles.first().storageId })
        val recovered = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(999u, "Recovered capacity", emptyList(), emptyList()),
        )
        assertEquals(999L, recovered.platoonId)
    }

    @Test
    fun scopedCompleteBackupRecreatesItsProfileOnAFreshRegistry() {
        val registry = PlatoonProfileRegistry(context)
        val profile = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_JAPAN,
            PlatoonProfileData(101817u, "Owls", emptyList(), emptyList()),
        )
        registry.setActive(profile.storageId)
        PlatoonRepository(context, PlatoonStorageScope(profile.storageId)).ingest(
            Instant.parse("2026-08-24T02:00:00Z"),
            listOf(member(10u, "Restored member")),
            "restore.csv",
        )
        val archive = ByteArrayOutputStream().also { output ->
            PlatoonBackupManager(context).exportFull(output)
        }.toByteArray()

        PlatoonRepository.withExclusiveDatabase(PlatoonStorageScope(profile.storageId)) {
            context.deleteDatabase(PlatoonStorageScope(profile.storageId).databaseName)
        }
        context.getSharedPreferences("platoon_profiles", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "platoons").deleteRecursively()

        PlatoonBackupManager(context).restoreFull(ByteArrayInputStream(archive))

        val restoredRegistry = PlatoonProfileRegistry(context)
        assertEquals(profile.storageId, restoredRegistry.activeScope().storageId)
        assertEquals(
            GameServerRegion.HAOPLAY_JAPAN,
            ClientServerRegionPreferences(context).get(SupportedGamePackages.HAOPLAY),
        )
        assertEquals(
            "Restored member",
            PlatoonRepository(context, restoredRegistry.activeScope())
                .listMemberStatuses()
                .single()
                .name,
        )
    }

    @Test
    fun rejectedScopedBackupDoesNotAlterExistingProfileMetadata() {
        val registry = PlatoonProfileRegistry(context)
        val existing = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(101817u, "Original name", emptyList(), emptyList()),
        )
        assertTrue(registry.setActive(existing.storageId))
        val invalidDatabase = File(context.cacheDir, "invalid-profile-restore.db").apply {
            writeText("not a SQLite database")
        }
        val archive = ByteArrayOutputStream().also { output ->
            BackupArchive.write(
                output,
                invalidDatabase,
                settings = null,
                profile = existing.copy(platoonName = "Untrusted replacement"),
            )
        }.toByteArray()

        val failure = runCatching {
            PlatoonBackupManager(context).restore(ByteArrayInputStream(archive))
        }

        assertTrue(failure.isFailure)
        assertEquals("Original name", registry.find(existing.storageId)?.platoonName)
        invalidDatabase.delete()
    }

    private fun member(uid: UInt, name: String) = GuildMember(
        uid = uid,
        name = name,
        level = 1u,
        weeklyMerit = 0u,
        totalMerit = 0u,
        highScore = 0u,
        totalScore = 0u,
        lastLogin = 0u,
    )

    private fun clearState() {
        context.databaseList()
            .filter { it == PlatoonSchema.DATABASE_NAME || it.matches(Regex("platoon-[0-9a-f]{32}\\.db")) }
            .forEach { databaseName ->
                val scope = PlatoonStorageScope.fromDatabaseName(databaseName)
                PlatoonRepository.withExclusiveDatabase(scope) {
                    context.deleteDatabase(databaseName)
                }
            }
        context.getSharedPreferences("platoon_profiles", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(
            ClientServerRegionPreferences.PREFERENCES,
            android.content.Context.MODE_PRIVATE,
        ).edit().clear().commit()
        File(context.filesDir, PlatoonRepository.RETAINED_CSV_DIRECTORY).deleteRecursively()
        File(context.filesDir, "platoons").deleteRecursively()
    }
}
