package dev.gf2log.app

import java.security.GeneralSecurityException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiscordWebhookSaveTest {
    private val valid = "https://discord.com/api/webhooks/12345678901234567/" + "a".repeat(64)

    @Test
    fun aRejectedAddressIsReportedAsInvalidWithoutTouchingTheStore() {
        var stored = false
        val message = discordWebhookSaveFailure(valid.replace("https", "http")) { stored = true }

        assertEquals(R.string.discord_webhook_invalid, message)
        assertFalse(stored)
    }

    @Test
    fun aKeystoreOrStorageFailureIsNotBlamedOnTheAddress() {
        assertEquals(
            R.string.discord_webhook_store_failed,
            discordWebhookSaveFailure(valid) { throw GeneralSecurityException("cipher unavailable") },
        )
        assertEquals(
            R.string.discord_webhook_store_failed,
            discordWebhookSaveFailure(valid) { check(false) { "Unable to store the encrypted Discord webhook" } },
        )
        // Once the address passed, even a Keystore IllegalArgumentException is a storage failure.
        assertEquals(
            R.string.discord_webhook_store_failed,
            discordWebhookSaveFailure(valid) { throw IllegalArgumentException("bad key spec") },
        )
    }

    @Test
    fun theStoreReceivesTheCanonicalAddress() {
        var stored: String? = null
        val message = discordWebhookSaveFailure("  https://ptb.discord.com/api/webhooks/12345678901234567/${"a".repeat(64)} ") {
            stored = it
        }

        assertNull(message)
        assertEquals(valid, stored)
    }
}
