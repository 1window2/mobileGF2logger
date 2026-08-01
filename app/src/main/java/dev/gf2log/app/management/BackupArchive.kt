package dev.gf2log.app.management

import dev.gf2log.app.StrictProperties
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object BackupArchive {
    fun write(output: OutputStream, database: File, settings: ByteArray?) {
        require(database.isFile) { "No Platoon database exists" }
        require(database.length() <= MAX_DATABASE_BYTES) { "Platoon database is too large" }
        require(settings == null || settings.size <= MAX_SETTINGS_BYTES) {
            "Backup settings are too large"
        }
        val formatVersion = if (settings == null) {
            BackupFormatPolicy.PLATOON_ONLY_VERSION
        } else {
            BackupFormatPolicy.COMPLETE_VERSION
        }
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            Properties().apply {
                setProperty(KEY_FORMAT_VERSION, formatVersion.toString())
                setProperty(KEY_DATABASE_SHA256, database.sha256())
                if (settings != null) {
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
                ),
            ) { "Unsupported backup version" }
            val expectedManifestKeys = if (
                formatVersion == BackupFormatPolicy.PLATOON_ONLY_VERSION
            ) {
                LEGACY_MANIFEST_KEYS
            } else {
                COMPLETE_MANIFEST_KEYS
            }
            require(metadata.stringPropertyNames() == expectedManifestKeys) {
                "Backup manifest is incomplete or contains unknown fields"
            }
            require(
                metadata.getProperty(KEY_DATABASE_SHA256)
                    .equals(stagedDatabase.sha256(), ignoreCase = true),
            ) { "Backup database checksum does not match" }

            if (formatVersion == BackupFormatPolicy.COMPLETE_VERSION) {
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
            return StagedArchive(formatVersion, settingsBytes)
        } catch (error: Exception) {
            stagedDatabase.delete()
            throw error
        }
    }

    data class StagedArchive(
        val formatVersion: Int,
        val settings: ByteArray?,
    )

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

    private const val MANIFEST_ENTRY = "manifest.properties"
    private const val DATABASE_ENTRY = "platoon.db"
    private const val SETTINGS_ENTRY = "settings.properties"
    private const val KEY_FORMAT_VERSION = "formatVersion"
    private const val KEY_DATABASE_SHA256 = "databaseSha256"
    private const val KEY_APPLICATION_ID = "applicationId"
    private const val KEY_BACKUP_SCOPE = "backupScope"
    private const val KEY_SETTINGS_SHA256 = "settingsSha256"
    private const val APPLICATION_ID = "dev.gf2log"
    private const val COMPLETE_SCOPE = "complete"
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
}
