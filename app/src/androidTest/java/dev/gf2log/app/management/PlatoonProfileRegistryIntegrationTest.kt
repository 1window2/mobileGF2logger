package dev.gf2log.app.management

import androidx.test.core.app.ApplicationProvider
import dev.gf2log.app.ActivePlatoonScopeBinding
import dev.gf2log.app.SupportedGamePackages
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.app.settings.ClientServerRegionPreferences
import dev.gf2log.app.settings.MemberOrderPreferences
import dev.gf2log.app.settings.WeeklyCutlinePreferences
import dev.gf2log.app.settings.WeeklyCutlines
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.GuildMember
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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
        PlatoonDatabase(context, PlatoonSchema.DATABASE_NAME).use { database ->
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
        assertNull(registry.active())
        assertThrows(IllegalArgumentException::class.java) { registry.activeScope() }
    }

    @Test
    fun existingUnscopedDatabaseIsNeverRegisteredAsAProfile() {
        val legacyDatabase = context.getDatabasePath(PlatoonSchema.DATABASE_NAME)
        PlatoonRepository(
            context,
            PlatoonStorageScope(PlatoonProfileIdentity.LEGACY_STORAGE_ID),
        ).ingest(
            Instant.parse("2026-08-24T00:00:00Z"),
            listOf(member(1u, "Legacy member")),
            "legacy.csv",
        )

        val registry = PlatoonProfileRegistry(context)

        assertTrue(registry.ensureInitialized().isEmpty())
        assertNull(registry.active())
        assertTrue(legacyDatabase.isFile)
    }

    @Test
    fun staleLegacySelectorMetadataIsRetiredInsteadOfRecreated() {
        context.getSharedPreferences("platoon_profiles", android.content.Context.MODE_PRIVATE)
            .edit()
            .putStringSet("profile_ids", setOf(PlatoonProfileIdentity.LEGACY_STORAGE_ID))
            .putString("active_profile", PlatoonProfileIdentity.LEGACY_STORAGE_ID)
            .putString("profile.legacy.client", PlatoonClient.LEGACY.name)
            .putString("profile.legacy.region", GameServerRegion.MANUAL.storedValue)
            .putLong("profile.legacy.platoon_id", 0L)
            .putString("profile.legacy.name", "Existing platoon data")
            .putLong("profile.legacy.last_seen", 0L)
            .putBoolean("profile.legacy.legacy", true)
            .commit()

        val registry = PlatoonProfileRegistry(context)

        assertTrue(registry.list().isEmpty())
        assertNull(registry.active())
        val stored = context.getSharedPreferences(
            "platoon_profiles",
            android.content.Context.MODE_PRIVATE,
        )
        assertTrue(stored.getStringSet("profile_ids", emptySet()).orEmpty().isEmpty())
        assertNull(stored.getString("active_profile", null))
    }

    @Test
    fun upgradedProfileDoesNotInterpretRetiredEmblemListsAsBannerIds() {
        val storageId = "0123456789abcdef0123456789abcdef"
        val prefix = "profile.$storageId."
        val preferences = context.getSharedPreferences(
            "platoon_profiles",
            android.content.Context.MODE_PRIVATE,
        )
        preferences.edit()
            .putStringSet("profile_ids", setOf(storageId))
            .putString("active_profile", storageId)
            .putString(prefix + "client", PlatoonClient.HAOPLAY.name)
            .putString(prefix + "region", GameServerRegion.HAOPLAY_KOREA.storedValue)
            .putLong(prefix + "platoon_id", 101817L)
            .putString(prefix + "name", "Upgraded Owls")
            .putString(prefix + "emblem_primary", "3,2,10")
            .putString(prefix + "emblem_secondary", "20,11,14")
            .putLong(prefix + "last_seen", 1_000L)
            .putBoolean(prefix + "legacy", false)
            .commit()

        val registry = PlatoonProfileRegistry(context)
        val upgraded = requireNotNull(registry.find(storageId))

        assertEquals(0L, upgraded.bannerFrameId)
        assertEquals(0L, upgraded.bannerMarkId)
        val observed = registry.updateObserved(
            storageId,
            PlatoonProfileData(101817u, "Upgraded Owls", 2u, 2u),
            Instant.parse("2026-08-26T00:00:00Z"),
        )
        assertEquals(2L, observed.bannerFrameId)
        assertEquals(2L, observed.bannerMarkId)
        assertFalse(preferences.contains(prefix + "emblem_primary"))
        assertFalse(preferences.contains(prefix + "emblem_secondary"))
    }

    @Test
    fun partialOrOutOfRangeBannerDataDoesNotEraseTheLastVerifiedCombination() {
        val registry = PlatoonProfileRegistry(context)
        val profile = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(101817u, "Owls", 2u, 3u),
        )

        val partial = registry.updateObserved(
            profile.storageId,
            PlatoonProfileData(101817u, "Owls", 7u, 0u),
        )

        assertEquals(2L, partial.bannerFrameId)
        assertEquals(3L, partial.bannerMarkId)
    }

    @Test
    fun publisherAndRegionKeepEqualPlatoonIdsInDifferentScopes() {
        val registry = PlatoonProfileRegistry(context)
        val data = PlatoonProfileData(101817u, "Owls", 1u, 2u)

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
    fun declaredCsvDestinationIsIsolatedAndLaterMatchingCaptureReusesItsScope() {
        val registry = PlatoonProfileRegistry(context)
        val declared = registry.createDeclared(
            PlatoonClient.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            101817L,
            "Declared Owls",
            Instant.parse("2026-08-25T00:00:00Z"),
        )

        assertTrue(registry.setActive(declared.storageId))
        assertEquals(declared.storageId, registry.activeScope().storageId)
        assertThrows(IllegalArgumentException::class.java) {
            registry.createDeclared(
                PlatoonClient.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                101817L,
                "Duplicate",
            )
        }

        val captured = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(101817u, "Captured Owls", 2u, 3u),
            Instant.parse("2026-08-25T01:00:00Z"),
        )

        assertEquals(declared.storageId, captured.storageId)
        assertEquals("Captured Owls", captured.platoonName)
        assertEquals(2L, captured.bannerFrameId)
        assertEquals(3L, captured.bannerMarkId)
    }

    @Test
    fun clientRegionRequiresARealSelectionAndBindingsDetectProfileChanges() {
        val regions = ClientServerRegionPreferences(context)
        assertEquals(null, regions.configured(SupportedGamePackages.HAOPLAY))
        regions.set(SupportedGamePackages.HAOPLAY, GameServerRegion.HAOPLAY_JAPAN)
        assertEquals(
            GameServerRegion.HAOPLAY_JAPAN,
            regions.configured(SupportedGamePackages.HAOPLAY),
        )

        val registry = PlatoonProfileRegistry(context)
        val first = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_JAPAN,
            PlatoonProfileData(1u, "First", 0u, 0u),
        )
        val second = registry.upsertDetected(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_GLOBAL,
            PlatoonProfileData(2u, "Second", 0u, 0u),
        )
        assertTrue(registry.setActive(first.storageId))
        val binding = ActivePlatoonScopeBinding(context)
        assertTrue(binding.isCurrent(context))
        registry.updateObserved(
            first.storageId,
            PlatoonProfileData(1u, "First renamed", 3u, 1u),
        )
        assertFalse(binding.isCurrent(context))
        val refreshedBinding = ActivePlatoonScopeBinding(context)
        assertTrue(refreshedBinding.isCurrent(context))
        assertTrue(registry.setActive(second.storageId))
        assertFalse(refreshedBinding.isCurrent(context))
    }

    @Test
    fun equalMemberUidsRemainIsolatedAcrossProfileDatabases() {
        val registry = PlatoonProfileRegistry(context)
        val identity = PlatoonProfileData(77u, "First", 0u, 0u)
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
    fun switchingProfilesKeepsMembersReportsHistorySettingsAndFilesIndependent() {
        val registry = PlatoonProfileRegistry(context)
        val first = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(101817u, "Owls", 1u, 2u),
        )
        val second = registry.upsertDetected(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_GLOBAL,
            PlatoonProfileData(101817u, "Ravens", 3u, 4u),
        )
        val firstScope = PlatoonStorageScope(first.storageId)
        val secondScope = PlatoonStorageScope(second.storageId)
        val firstRepository = PlatoonRepository(context, firstScope)
        val secondRepository = PlatoonRepository(context, secondScope)
        val observedAt = Instant.parse("2026-08-24T12:00:00Z")
        val periodStart = LocalDate.of(2026, 8, 23)

        firstRepository.ingest(observedAt, listOf(member(9u, "HaoPlay member")), "same.csv")
        secondRepository.ingest(observedAt, listOf(member(9u, "Darkwinter member")), "same.csv")
        MemberOrderPreferences(context, first.storageId).write(listOf(9L, 10L))
        MemberOrderPreferences(context, second.storageId).write(listOf(10L, 9L))
        WeeklyCutlinePreferences(context, first.storageId).write(WeeklyCutlines(dailyMerit = 90L))
        WeeklyCutlinePreferences(context, second.storageId).write(WeeklyCutlines(dailyMerit = 150L))
        val firstEvidence = File(firstScope.retainedCsvDirectory(context), "same.csv").apply {
            parentFile?.mkdirs()
            writeText("first")
        }
        val secondEvidence = File(secondScope.retainedCsvDirectory(context), "same.csv").apply {
            parentFile?.mkdirs()
            writeText("second")
        }

        assertEquals(
            "HaoPlay member",
            firstRepository.buildWeeklyReport(periodStart, ZoneOffset.UTC, observedAt.plusSeconds(1))
                .members.single().name,
        )
        assertEquals(
            "Darkwinter member",
            secondRepository.buildWeeklyReport(periodStart, ZoneOffset.UTC, observedAt.plusSeconds(1))
                .members.single().name,
        )
        assertTrue(firstRepository.listWeeklyReportHistory(periodStart).isNotEmpty())
        assertTrue(secondRepository.listWeeklyReportHistory(periodStart).isNotEmpty())
        assertEquals(listOf(9L, 10L), MemberOrderPreferences(context, first.storageId).read())
        assertEquals(listOf(10L, 9L), MemberOrderPreferences(context, second.storageId).read())
        assertEquals(90L, WeeklyCutlinePreferences(context, first.storageId).read().dailyMerit)
        assertEquals(150L, WeeklyCutlinePreferences(context, second.storageId).read().dailyMerit)
        assertNotEquals(firstEvidence.canonicalPath, secondEvidence.canonicalPath)
        assertEquals("first", firstEvidence.readText())
        assertEquals("second", secondEvidence.readText())

        assertTrue(registry.setActive(first.storageId))
        assertEquals("HaoPlay member", PlatoonRepository(context).listMemberStatuses().single().name)
        assertEquals(
            GameServerRegion.HAOPLAY_KOREA,
            ClientServerRegionPreferences(context).configured(SupportedGamePackages.HAOPLAY),
        )
        assertTrue(registry.setActive(second.storageId))
        assertEquals("Darkwinter member", PlatoonRepository(context).listMemberStatuses().single().name)
        assertEquals(
            GameServerRegion.DARKWINTER_GLOBAL,
            ClientServerRegionPreferences(context).configured(SupportedGamePackages.DARKWINTER),
        )
    }

    @Test
    fun changingServerRegionKeepsTheSameIsolatedDataScope() {
        val registry = PlatoonProfileRegistry(context)
        val profile = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(101817u, "Owls", 0u, 0u),
        )
        val scope = PlatoonStorageScope(profile.storageId)
        val repository = PlatoonRepository(context, scope)
        repository.ingest(
            Instant.parse("2026-08-24T12:00:00Z"),
            listOf(member(9u, "Preserved member")),
            "preserved.csv",
        )
        MemberOrderPreferences(context, profile.storageId).write(listOf(9L))
        assertTrue(registry.setActive(profile.storageId))

        val updated = PlatoonProfileAdministration(context).changeServerRegion(
            profile.storageId,
            GameServerRegion.HAOPLAY_JAPAN,
        )

        assertEquals(profile.storageId, updated.storageId)
        assertEquals(GameServerRegion.HAOPLAY_JAPAN, updated.serverRegion)
        assertEquals(
            "Preserved member",
            PlatoonRepository(context, scope).listMemberStatuses().single().name,
        )
        assertEquals(listOf(9L), MemberOrderPreferences(context, profile.storageId).read())
        assertEquals(
            GameServerRegion.HAOPLAY_JAPAN,
            ClientServerRegionPreferences(context).configured(SupportedGamePackages.HAOPLAY),
        )
    }

    @Test
    fun restoreCannotRegisterOneFullIdentityUnderTwoStorageScopes() {
        val registry = PlatoonProfileRegistry(context)
        val existing = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(101817u, "Owls", 0u, 0u),
        )
        val duplicate = existing.copy(storageId = PlatoonProfileIdentity.randomStorageId())

        assertThrows(IllegalArgumentException::class.java) {
            registry.requireRestoreCapacity(duplicate)
        }
        assertEquals(listOf(existing.storageId), registry.list().map(PlatoonProfile::storageId))
    }

    @Test
    fun confirmedDeletionRemovesOnlyTheSelectedProfileAndItsScopedPreferences() {
        val registry = PlatoonProfileRegistry(context)
        val deleted = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_KOREA,
            PlatoonProfileData(1u, "Delete me", 0u, 0u),
        )
        val retained = registry.upsertDetected(
            SupportedGamePackages.DARKWINTER,
            GameServerRegion.DARKWINTER_GLOBAL,
            PlatoonProfileData(2u, "Keep me", 0u, 0u),
        )
        val deletedScope = PlatoonStorageScope(deleted.storageId)
        val retainedScope = PlatoonStorageScope(retained.storageId)
        PlatoonRepository(context, deletedScope).ingest(
            Instant.parse("2026-08-24T12:00:00Z"),
            listOf(member(1u, "Deleted member")),
            "delete.csv",
        )
        PlatoonRepository(context, retainedScope).ingest(
            Instant.parse("2026-08-24T12:00:00Z"),
            listOf(member(2u, "Retained member")),
            "keep.csv",
        )
        MemberOrderPreferences(context, deleted.storageId).write(listOf(1L))
        WeeklyCutlinePreferences(context, deleted.storageId).write(
            WeeklyCutlines(dailyMerit = 90L),
        )
        assertTrue(registry.setActive(deleted.storageId))

        assertTrue(PlatoonProfileAdministration(context).deleteProfile(deleted.storageId))

        assertEquals(null, registry.find(deleted.storageId))
        assertEquals(retained.storageId, registry.activeScope().storageId)
        assertFalse(context.getDatabasePath(deletedScope.databaseName).exists())
        assertFalse(deletedScope.rootDirectory(context).exists())
        assertTrue(context.getDatabasePath(retainedScope.databaseName).exists())
        assertEquals(
            "Retained member",
            PlatoonRepository(context, retainedScope).listMemberStatuses().single().name,
        )
        assertTrue(MemberOrderPreferences(context, deleted.storageId).read().isEmpty())
        assertEquals(null, WeeklyCutlinePreferences(context, deleted.storageId).read().dailyMerit)
    }

    @Test
    fun registryRejectsUnboundedNewProfilesAndCanRemoveInactiveMetadata() {
        val registry = PlatoonProfileRegistry(context)
        val profiles = (1..PlatoonProfileRegistry.MAX_PROFILES).map { id ->
            registry.upsertDetected(
                SupportedGamePackages.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                PlatoonProfileData(id.toUInt(), "Platoon $id", 0u, 0u),
            )
        }

        val overflow = runCatching {
            registry.upsertDetected(
                SupportedGamePackages.HAOPLAY,
                GameServerRegion.HAOPLAY_KOREA,
                PlatoonProfileData(999u, "Overflow", 0u, 0u),
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
            PlatoonProfileData(999u, "Recovered capacity", 0u, 0u),
        )
        assertEquals(999L, recovered.platoonId)
    }

    @Test
    fun scopedCompleteBackupRecreatesItsProfileOnAFreshRegistry() {
        val registry = PlatoonProfileRegistry(context)
        val profile = registry.upsertDetected(
            SupportedGamePackages.HAOPLAY,
            GameServerRegion.HAOPLAY_JAPAN,
            PlatoonProfileData(101817u, "Owls", 0u, 0u),
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
        context.getSharedPreferences("user_settings", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, "platoons").deleteRecursively()

        PlatoonBackupManager.restoreSelected(
            context,
            ByteArrayInputStream(archive),
            complete = true,
        )

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
            PlatoonProfileData(101817u, "Original name", 0u, 0u),
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
        listOf(
            "platoon_member_order",
            "platoon_weekly_cutlines",
            "platoon_timezones",
            "platoon_profile_deletions",
        ).forEach { name ->
            context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
                .edit().clear().commit()
        }
        File(context.filesDir, PlatoonRepository.RETAINED_CSV_DIRECTORY).deleteRecursively()
        File(context.filesDir, "platoons").deleteRecursively()
    }
}
