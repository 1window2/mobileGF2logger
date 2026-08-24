package dev.gf2log.app.management

import dev.gf2log.app.StrictProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object BackupArchive {
    fun write(
        output: OutputStream,
        database: File,
        settings: ByteArray?,
        profile: PlatoonProfile? = null,
    ) {
        require(database.isFile) { "No Platoon database exists" }
        require(database.length() <= MAX_DATABASE_BYTES) { "Platoon database is too large" }
        require(settings == null || settings.size <= MAX_SETTINGS_BYTES) {
            "Backup settings are too large"
        }
        val formatVersion = if (profile != null) {
            BackupFormatPolicy.SCOPED_VERSION
        } else if (settings == null) {
            BackupFormatPolicy.PLATOON_ONLY_VERSION
        } else {
            BackupFormatPolicy.COMPLETE_VERSION
        }
        ZipOutputStream(BufferedOutputStream(NonClosingOutputStream(output))).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            Properties().apply {
                setProperty(KEY_FORMAT_VERSION, formatVersion.toString())
                setProperty(KEY_DATABASE_SHA256, database.sha256())
                if (profile != null) {
                    setProperty(KEY_APPLICATION_ID, APPLICATION_ID)
                    setProperty(
                        KEY_BACKUP_SCOPE,
                        if (settings == null) PLATOON_SCOPE else COMPLETE_SCOPE,
                    )
                    putProfile(profile)
                    if (settings != null) setProperty(KEY_SETTINGS_SHA256, settings.sha256())
                } else if (settings != null) {
                    setProperty(KEY_APPLICATION_ID, APPLICATION_ID)
                    setProperty(KEY_BACKUP_SCOPE, COMPLETE_SCOPE)
                    setProperty(KEY_SETTINGS_SHA256, settings.sha256())
                }
            }.store(zip, "mobileGF2logger backup")
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
            database.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            if (settings != null) {
                zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
                zip.write(settings)
                zip.closeEntry()
            }
        }
    }

    fun stage(input: InputStream, stagedDatabase: File): StagedArchive {
        var manifest: Properties? = null
        var databaseSeen = false
        var settingsBytes: ByteArray? = null
        try {
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(!entry.isDirectory) { "Backup cannot contain directories" }
                    require(entry.name in ALLOWED_ENTRIES) {
                        "Unexpected backup entry: ${entry.name}"
                    }
                    when (entry.name) {
                        MANIFEST_ENTRY -> {
                            require(manifest == null) { "Duplicate backup manifest" }
                            val bytes = ByteArrayOutputStream().also { output ->
                                zip.copyBoundedTo(output, MAX_MANIFEST_BYTES)
                            }.toByteArray()
                            manifest = StrictProperties("Backup manifest").apply {
                                ByteArrayInputStream(bytes).use(::load)
                            }
                        }
                        DATABASE_ENTRY -> {
                            require(!databaseSeen) { "Duplicate database entry" }
                            databaseSeen = true
                            stagedDatabase.outputStream().use { output ->
                                zip.copyBoundedTo(output, MAX_DATABASE_BYTES)
                            }
                        }
                        SETTINGS_ENTRY -> {
                            require(settingsBytes == null) { "Duplicate settings entry" }
                            settingsBytes = ByteArrayOutputStream().also { output ->
                                zip.copyBoundedTo(output, MAX_SETTINGS_BYTES)
                            }.toByteArray()
                        }
                    }
                    zip.closeEntry()
                }
            }

            val metadata = requireNotNull(manifest) { "Backup manifest is missing" }
            require(databaseSeen && stagedDatabase.isFile) { "Backup database is missing" }
            val formatVersion = requireNotNull(
                metadata.getProperty(KEY_FORMAT_VERSION)?.toIntOrNull(),
            ) { "Backup format version is missing" }
            require(
                formatVersion in setOf(
                    BackupFormatPolicy.PLATOON_ONLY_VERSION,
                    BackupFormatPolicy.COMPLETE_VERSION,
                    BackupFormatPolicy.SCOPED_VERSION,
                ),
            ) { "Unsupported backup version" }
            val expectedManifestKeys = when (formatVersion) {
                BackupFormatPolicy.PLATOON_ONLY_VERSION -> LEGACY_MANIFEST_KEYS
                BackupFormatPolicy.COMPLETE_VERSION -> COMPLETE_MANIFEST_KEYS
                else -> SCOPED_MANIFEST_KEYS + if (
                    metadata.getProperty(KEY_BACKUP_SCOPE) == COMPLETE_SCOPE
                ) {
                    setOf(KEY_SETTINGS_SHA256)
                } else {
                    emptySet()
                }
            }
            require(metadata.stringPropertyNames() == expectedManifestKeys) {
                "Backup manifest is incomplete or contains unknown fields"
            }
            require(
                metadata.getProperty(KEY_DATABASE_SHA256)
                    .equals(stagedDatabase.sha256(), ignoreCase = true),
            ) { "Backup database checksum does not match" }

            val profile = if (formatVersion == BackupFormatPolicy.SCOPED_VERSION) {
                require(metadata.getProperty(KEY_APPLICATION_ID) == APPLICATION_ID) {
                    "Backup belongs to another application"
                }
                require(metadata.getProperty(KEY_BACKUP_SCOPE) in setOf(PLATOON_SCOPE, COMPLETE_SCOPE)) {
                    "Backup scope is invalid"
                }
                metadata.profile()
            } else {
                null
            }

            if (
                formatVersion == BackupFormatPolicy.COMPLETE_VERSION ||
                metadata.getProperty(KEY_BACKUP_SCOPE) == COMPLETE_SCOPE
            ) {
                require(metadata.getProperty(KEY_APPLICATION_ID) == APPLICATION_ID) {
                    "Backup belongs to another application"
                }
                require(metadata.getProperty(KEY_BACKUP_SCOPE) == COMPLETE_SCOPE) {
                    "Backup scope is incomplete"
                }
                val settings = requireNotNull(settingsBytes) { "Backup settings are missing" }
                require(
                    metadata.getProperty(KEY_SETTINGS_SHA256)
                        .equals(settings.sha256(), ignoreCase = true),
                ) { "Backup settings checksum does not match" }
            } else {
                require(settingsBytes == null) { "Legacy backup contains unexpected settings" }
            }
            return StagedArchive(formatVersion, settingsBytes, profile)
        } catch (error: Exception) {
            stagedDatabase.delete()
            throw error
        }
    }

    data class StagedArchive(
        val formatVersion: Int,
        val settings: ByteArray?,
        val profile: BackupPlatoonProfile? = null,
    )

    internal data class BackupPlatoonProfile(
        val storageId: String,
        val client: PlatoonClient,
        val serverRegion: dev.gf2log.app.settings.GameServerRegion,
        val platoonId: Long,
        val platoonName: String,
        val emblemPrimary: List<Long>,
        val emblemSecondary: List<Long>,
        val legacy: Boolean,
    ) {
        fun toProfile() = PlatoonProfile(
            storageId = storageId,
            client = client,
            serverRegion = serverRegion,
            platoonId = platoonId,
            platoonName = platoonName,
            emblemPrimary = emblemPrimary,
            emblemSecondary = emblemSecondary,
            lastSeenAt = java.time.Instant.now(),
            legacy = legacy,
        )
    }

    private fun Properties.putProfile(profile: PlatoonProfile) {
        setProperty(KEY_PROFILE_STORAGE_ID, profile.storageId)
        setProperty(KEY_PROFILE_CLIENT, profile.client.name)
        setProperty(KEY_PROFILE_REGION, profile.serverRegion.storedValue)
        setProperty(KEY_PROFILE_PLATOON_ID, profile.platoonId.toString())
        setProperty(KEY_PROFILE_NAME, profile.platoonName)
        setProperty(KEY_PROFILE_EMBLEM_PRIMARY, profile.emblemPrimary.joinToString(","))
        setProperty(KEY_PROFILE_EMBLEM_SECONDARY, profile.emblemSecondary.joinToString(","))
        setProperty(KEY_PROFILE_LEGACY, profile.legacy.toString())
    }

    private fun Properties.profile(): BackupPlatoonProfile {
        val client = PlatoonClient.valueOf(required(KEY_PROFILE_CLIENT))
        val region = dev.gf2log.app.settings.GameServerRegion.fromStored(required(KEY_PROFILE_REGION))
        require(region.storedValue == required(KEY_PROFILE_REGION)) { "Backup server region is invalid" }
        val id = requireNotNull(required(KEY_PROFILE_PLATOON_ID).toLongOrNull()) {
            "Backup Platoon ID is invalid"
        }
        val legacy = required(KEY_PROFILE_LEGACY).let { value ->
            require(value in setOf("true", "false")) { "Backup legacy marker is invalid" }
            value.toBooleanStrict()
        }
        val result = BackupPlatoonProfile(
            storageId = required(KEY_PROFILE_STORAGE_ID),
            client = client,
            serverRegion = region,
            platoonId = id,
            platoonName = required(KEY_PROFILE_NAME),
            emblemPrimary = longList(required(KEY_PROFILE_EMBLEM_PRIMARY)),
            emblemSecondary = longList(required(KEY_PROFILE_EMBLEM_SECONDARY)),
            legacy = legacy,
        )
        result.toProfile()
        if (!legacy) {
            require(
                result.storageId == PlatoonProfileIdentity.storageId(client, region, id),
            ) { "Backup Platoon identity does not match its storage scope" }
        }
        return result
    }

    private fun Properties.required(key: String): String =
        requireNotNull(getProperty(key)) { "Backup manifest is missing $key" }

    private fun longList(value: String): List<Long> = if (value.isBlank()) {
        emptyList()
    } else {
        value.split(',').map { item ->
            requireNotNull(item.toLongOrNull()) { "Backup emblem data is invalid" }
        }.also { require(it.size <= PlatoonProfile.MAX_EMBLEM_PARTS) }
    }

    private fun InputStream.copyBoundedTo(output: OutputStream, maximum: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximum) { "Backup entry exceeds its size limit" }
            output.write(buffer, 0, count)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .hex()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Lets ZIP cleanup release its deflater without closing the caller-owned destination. */
    private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
        override fun close() = flush()
    }

    private const val MANIFEST_ENTRY = "manifest.properties"
    private const val DATABASE_ENTRY = "platoon.db"
    private const val SETTINGS_ENTRY = "settings.properties"
    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_DATABASE_SHA256 = "databaseSha256"
    private const val KEY_APPLICATION_ID = "applicationId"
    private const val KEY_BACKUP_SCOPE = "backupScope"
    private const val KEY_SETTINGS_SHA256 = "settingsSha256"
    private const val KEY_PROFILE_STORAGE_ID = "profile.storageId"
    private const val KEY_PROFILE_CLIENT = "profile.client"
    private const val KEY_PROFILE_REGION = "profile.region"
    private const val KEY_PROFILE_PLATOON_ID = "profile.platoonId"
    private const val KEY_PROFILE_NAME = "profile.name"
    private const val KEY_PROFILE_EMBLEM_PRIMARY = "profile.emblemPrimary"
    private const val KEY_PROFILE_EMBLEM_SECONDARY = "profile.emblemSecondary"
    private const val KEY_PROFILE_LEGACY = "profile.legacy"
    private const val APPLICATION_ID = "dev.gf2log"
    private const val COMPLETE_SCOPE = "complete"
    private const val PLATOON_SCOPE = "platoon"
    private const val MAX_MANIFEST_BYTES = 64L * 1024
    private const val MAX_SETTINGS_BYTES = 256L * 1024
    private const val MAX_DATABASE_BYTES = 50L * 1024 * 1024
    private val ALLOWED_ENTRIES = setOf(MANIFEST_ENTRY, DATABASE_ENTRY, SETTINGS_ENTRY)
    private val LEGACY_MANIFEST_KEYS = setOf(KEY_FORMAT_VERSION, KEY_DATABASE_SHA256)
    private val COMPLETE_MANIFEST_KEYS = LEGACY_MANIFEST_KEYS + setOf(
        KEY_APPLICATION_ID,
        KEY_BACKUP_SCOPE,
        KEY_SETTINGS_SHA256,
    )
    private val SCOPED_MANIFEST_KEYS = LEGACY_MANIFEST_KEYS + setOf(
        KEY_APPLICATION_ID,
        KEY_BACKUP_SCOPE,
        KEY_PROFILE_STORAGE_ID,
        KEY_PROFILE_CLIENT,
        KEY_PROFILE_REGION,
        KEY_PROFILE_PLATOON_ID,
        KEY_PROFILE_NAME,
        KEY_PROFILE_EMBLEM_PRIMARY,
        KEY_PROFILE_EMBLEM_SECONDARY,
        KEY_PROFILE_LEGACY,
    )
}
