package dev.gf2log.app.management

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupArchiveTest {
    @Test
    fun `round trips a realistic complete archive without changing payload bytes`() {
        val database = temporaryFile("platoon.db", realisticDatabaseBytes())
        val settings = realisticSettingsBytes()
        val archive = ByteArrayOutputStream().also { BackupArchive.write(it, database, settings) }
        val staged = temporaryPath("restored.db")

        val result = BackupArchive.stage(ByteArrayInputStream(archive.toByteArray()), staged)

        assertEquals(BackupFormatPolicy.COMPLETE_VERSION, result.formatVersion)
        assertArrayEquals(database.readBytes(), staged.readBytes())
        assertArrayEquals(settings, result.settings)
    }

    @Test
    fun `round trips a legacy Platoon-only archive`() {
        val database = temporaryFile("platoon.db", realisticDatabaseBytes())
        val archive = ByteArrayOutputStream().also { BackupArchive.write(it, database, null) }
        val staged = temporaryPath("restored.db")

        val result = BackupArchive.stage(ByteArrayInputStream(archive.toByteArray()), staged)

        assertEquals(BackupFormatPolicy.PLATOON_ONLY_VERSION, result.formatVersion)
        assertArrayEquals(database.readBytes(), staged.readBytes())
        assertEquals(null, result.settings)
    }

    @Test
    fun `rejects corrupt and incomplete lookalike files without leaving staged data`() {
        val database = realisticDatabaseBytes()
        val settings = realisticSettingsBytes()
        val cases = listOf(
            "plain text with the right extension" to "not a zip".toByteArray(),
            "missing manifest" to archiveOf("platoon.db" to database),
            "missing database" to archiveOf(
                "manifest.properties" to completeManifest(database, settings),
                "settings.properties" to settings,
            ),
            "missing settings" to archiveOf(
                "manifest.properties" to completeManifest(database, settings),
                "platoon.db" to database,
            ),
            "unexpected entry" to archiveOf(
                "manifest.properties" to completeManifest(database, settings),
                "platoon.db" to database,
                "settings.properties" to settings,
                "preview.csv" to "plausible export".toByteArray(),
            ),
            "nested path" to archiveOf(
                "manifest.properties" to completeManifest(database, settings),
                "data/platoon.db" to database,
                "settings.properties" to settings,
            ),
        )

        cases.forEach { (label, archive) ->
            val staged = temporaryPath(label.filter(Char::isLetterOrDigit) + ".db")
            assertThrows(label, Exception::class.java) {
                BackupArchive.stage(ByteArrayInputStream(archive), staged)
            }
            assertFalse("$label left staged data", staged.exists())
        }
    }

    @Test
    fun `rejects convincing wrong-app scope version and manifest lookalikes`() {
        val database = realisticDatabaseBytes()
        val settings = realisticSettingsBytes()
        val valid = completeProperties(database, settings)
        val variants = listOf(
            valid.copyWith("applicationId", "dev.gf2log.preview"),
            valid.copyWith("backupScope", "platoon-and-settings"),
            valid.copyWith("formatVersion", "210"),
            valid.copyWith("displayName", "mobileGF2logger"),
            valid.without("settingsSha256"),
        )

        variants.forEachIndexed { index, properties ->
            assertRejected(
                archiveOf(
                    "manifest.properties" to properties.toBytes(),
                    "platoon.db" to database,
                    "settings.properties" to settings,
                ),
                "manifest-$index",
            )
        }
    }

    @Test
    fun `rejects tampered database and settings even when contents remain plausible`() {
        val database = realisticDatabaseBytes()
        val settings = realisticSettingsBytes()
        val manifest = completeManifest(database, settings)

        assertRejected(
            archiveOf(
                "manifest.properties" to manifest,
                "platoon.db" to database + "extra member row".toByteArray(),
                "settings.properties" to settings,
            ),
            "tampered-database",
        )
        assertRejected(
            archiveOf(
                "manifest.properties" to manifest,
                "platoon.db" to database,
                "settings.properties" to settings.replaceText("language=ko", "language=en"),
            ),
            "tampered-settings",
        )
    }

    @Test
    fun `rejects duplicate entries and directories`() {
        val database = realisticDatabaseBytes()
        val settings = realisticSettingsBytes()
        val manifest = completeManifest(database, settings)

        assertRejected(
            archiveOf(
                "manifest.properties" to manifest,
                "platoon.db" to database,
                "platoon.xb" to database,
                "settings.properties" to settings,
            ).replaceBytes("platoon.xb", "platoon.db"),
            "duplicate-database",
        )
        assertRejected(
            archiveOf(
                "manifest.properties" to manifest,
                "platoon.db" to database,
                "settings.properties" to settings,
                "attachments/" to null,
            ),
            "directory-entry",
        )
    }

    @Test
    fun `rejects duplicate logical fields inside the manifest`() {
        val database = realisticDatabaseBytes()
        val settings = realisticSettingsBytes()
        val duplicateManifest = completeManifest(database, settings) +
            "\nformatVersion=${BackupFormatPolicy.COMPLETE_VERSION}\n".toByteArray()

        assertRejected(
            archiveOf(
                "manifest.properties" to duplicateManifest,
                "platoon.db" to database,
                "settings.properties" to settings,
            ),
            "duplicate-manifest-field",
        )
    }

    @Test
    fun `rejects oversized settings before accepting their checksum`() {
        val database = realisticDatabaseBytes()
        val oversizedSettings = ByteArray(256 * 1024 + 1) { 'x'.code.toByte() }

        assertRejected(
            archiveOf(
                "manifest.properties" to completeManifest(database, oversizedSettings),
                "platoon.db" to database,
                "settings.properties" to oversizedSettings,
            ),
            "oversized-settings",
        )
    }

    private fun assertRejected(archive: ByteArray, name: String) {
        val staged = temporaryPath("$name.db")
        assertThrows(Exception::class.java) {
            BackupArchive.stage(ByteArrayInputStream(archive), staged)
        }
        assertFalse(staged.exists())
    }

    private fun completeManifest(database: ByteArray, settings: ByteArray): ByteArray =
        completeProperties(database, settings).toBytes()

    private fun completeProperties(database: ByteArray, settings: ByteArray) = Properties().apply {
        setProperty("formatVersion", BackupFormatPolicy.COMPLETE_VERSION.toString())
        setProperty("databaseSha256", database.sha256())
        setProperty("applicationId", "dev.gf2log")
        setProperty("backupScope", "complete")
        setProperty("settingsSha256", settings.sha256())
    }

    private fun realisticDatabaseBytes(): ByteArray = buildString {
        append("SQLite format 3\u0000")
        append("members:21001,Alpha Lead,active,notes=rotation owner\n")
        append("members:21002,Beta Two,withdrawn,notes=returning member\n")
        append("tenures:21001,2026-05-11,open;21002,2026-02-02,2026-07-25\n")
        append("weekly_notes:2026-07-12,operation review;2026-07-19,leave approved\n")
        append("weekly_overrides:21002,2026-07-20,merit=90,attended=true\n")
    }.toByteArray()

    private fun realisticSettingsBytes(): ByteArray = """
        schemaVersion=1
        language=ko
        detailedNotifications=false
        targetPackage=com.haoplay.game.and.exilium
        memberOrder=21001,21002
        cutline.dailyMerit=90
        cutline.dailyGunsmokeScore=10000
        cutline.dailyGunsmokeAttempts=3
        cutline.weeklyMerit=630
        cutline.weeklyGunsmokeScore=70000
        cutline.weeklyGunsmokeAttempts=21
        cutline.weeklyLoginDays=7
        cutline.weeklyPatrolDays=5
        payloadHistory.21917=true
        payloadHistory.21935=true
        payloadHistory.21960=true
    """.trimIndent().toByteArray()

    private fun archiveOf(vararg entries: Pair<String, ByteArray?>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    if (bytes != null) zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun Properties.copyWith(key: String, value: String): Properties =
        Properties().also { copy ->
            copy.putAll(this)
            copy.setProperty(key, value)
        }

    private fun Properties.without(key: String): Properties = Properties().also { copy ->
        copy.putAll(this)
        copy.remove(key)
    }

    private fun Properties.toBytes(): ByteArray = ByteArrayOutputStream().also { output ->
        store(output, null)
    }.toByteArray()

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

    private fun ByteArray.replaceText(old: String, new: String): ByteArray =
        toString(Charsets.UTF_8).replace(old, new).toByteArray()

    private fun ByteArray.replaceBytes(old: String, new: String): ByteArray {
        val source = old.toByteArray()
        val replacement = new.toByteArray()
        require(source.size == replacement.size)
        val result = copyOf()
        var offset = 0
        while (offset <= result.size - source.size) {
            if (result.copyOfRange(offset, offset + source.size).contentEquals(source)) {
                replacement.copyInto(result, offset)
                offset += source.size
            } else {
                offset += 1
            }
        }
        return result
    }

    private fun temporaryFile(name: String, bytes: ByteArray): File =
        temporaryPath(name).apply { writeBytes(bytes) }

    private fun temporaryPath(name: String): File = File.createTempFile("gf2-", "-$name").apply {
        delete()
        deleteOnExit()
    }
}
