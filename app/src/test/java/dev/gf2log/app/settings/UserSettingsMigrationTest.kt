package dev.gf2log.app.settings

import android.content.SharedPreferences
import dev.gf2log.protocol.PayloadCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UserSettingsMigrationTest {
    @Test
    fun `a migration that cannot reach the disk still serves the migrated settings`() {
        val preferences = InMemoryPreferences(commitSucceeds = false)
        var notices = 0
        var legacyReads = 0
        val legacy = {
            legacyReads += 1
            settings(language = "ko")
        }

        val first = UserSettingsPreferences.migratedLocked(preferences, legacy) { notices += 1 }
        val second = UserSettingsPreferences.migratedLocked(preferences, legacy) { notices += 1 }

        assertSame(preferences, first)
        assertSame(preferences, second)
        assertEquals("ko", preferences.getString("language", null))
        assertEquals("ko", second.getString("language", null))
        // The in-memory copy is current, so this process migrates once; the next process retries.
        assertEquals(1, notices)
        assertEquals(1, legacyReads)
    }

    @Test
    fun `a persisted migration reports nothing`() {
        val preferences = InMemoryPreferences(commitSucceeds = true)
        var notices = 0

        UserSettingsPreferences.migratedLocked(preferences, { settings(language = "en") }) { notices += 1 }

        assertEquals(0, notices)
        assertEquals(4, preferences.getInt("schema_version", 0))
    }

    private fun settings(language: String) = AppBackupSettings(
        language = language,
        themeMode = "system",
        gameServerRegion = GameServerRegion.MANUAL.storedValue,
        gameTimeZoneId = "UTC",
        onboardingCompleted = false,
        detailedNotifications = true,
        targetPackage = dev.gf2log.app.TargetPackagePreferences.DEFAULT_TARGET_PACKAGE,
        payloadHistory = PayloadCatalog.categories.associate { it.payloadType to it.isRequired },
        memberOrder = emptyList(),
        weeklyCutlines = WeeklyCutlines(),
    )

    /** SharedPreferences whose commit updates memory first and can then fail, like a full disk. */
    private class InMemoryPreferences(private val commitSucceeds: Boolean) : SharedPreferences {
        private val values = HashMap<String, Any?>()

        override fun getAll(): Map<String, *> = HashMap(values)
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = key in values
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean {
                apply()
                return commitSucceeds
            }

            override fun apply() {
                if (clear) values.clear()
                removed.forEach(values::remove)
                values.putAll(pending)
            }
        }
    }
}
