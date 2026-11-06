package dev.gf2log.app.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordWebhookPolicyTest {
    @Test
    fun acceptsOnlyExactHttpsDiscordIncomingWebhookEndpoints() {
        val token = "a".repeat(64)
        val accepted = DiscordWebhookPolicy.requireValid(
            "https://discord.com/api/webhooks/12345678901234567/" + token,
        )
        assertEquals("discord.com", accepted.host)

        listOf(
            "http://discord.com/api/webhooks/12345678901234567/" + token,
            "https://discord.com.evil.example/api/webhooks/12345678901234567/" + token,
            "https://discord.com:444/api/webhooks/12345678901234567/" + token,
            "https://discord.com/api/webhooks/12345678901234567/" + token + "?wait=true",
            "https://discord.com/api/users/12345678901234567/" + token,
            "https://discord.com/api/webhooks/%3112345678901234567/" + token,
            "https://discord.com/api/webhooks/12345678901234567/%61" + token,
        ).forEach { value ->
            assertTrue(runCatching { DiscordWebhookPolicy.requireValid(value) }.isFailure)
        }
    }

    @Test
    fun unparsableInputNeverQuotesTheTokenInTheFailure() {
        val token = "A".repeat(36)
        val url = "https://discord.com/api/webhooks/123456789012345678/$token"
        listOf("$url x", "<$url>", "$url|extra", "https://discord.com/api/webhooks/123456789012345678/A|$token").forEach { value ->
            val error = runCatching { DiscordWebhookPolicy.requireValid(value) }.exceptionOrNull()
            assertTrue(value, error is IllegalArgumentException)
            assertEquals("Invalid Discord webhook URL", error!!.message)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            generateSequence(error, Throwable::cause).forEach { link ->
                assertFalse(link.message.orEmpty().contains(token))
                assertFalse(link.toString().contains(token))
            }
        }
    }

    @Test
    fun officialAliasHostsAreStoredAsDiscordCom() {
        val path = "/api/webhooks/12345678901234567/" + "b".repeat(40)
        listOf("ptb.discord.com", "canary.discord.com", "discordapp.com", "PTB.Discord.com").forEach { host ->
            assertEquals(host, "https://discord.com$path", DiscordWebhookPolicy.requireValid("https://$host$path").toASCIIString())
        }
        assertEquals(
            "https://discord.com:443$path",
            DiscordWebhookPolicy.requireValid("https://canary.discord.com:443$path").toASCIIString(),
        )
        listOf(
            "https://evil.ptb.discord.com$path",
            "https://discord.com.evil.net$path",
            "https://ptb.discord.com:8443$path",
            "https://user@ptb.discord.com$path",
            "http://ptb.discord.com$path",
            "https://discordapp.com$path?wait=true",
            "https://canary.discord.com/api/webhooks/%3112345678901234567/" + "b".repeat(40),
            "https://xdiscordapp.com$path",
        ).forEach { value ->
            assertTrue(value, runCatching { DiscordWebhookPolicy.requireValid(value) }.isFailure)
        }
    }

    @Test
    fun extractsOnlyCsvBodyFromValidatedHistoryEnvelope() {
        val content = """
            payloadType=21917
            capturedAt=2026-08-11T00:00:00Z

            uid,name
            1,Alice
        """.trimIndent()

        assertEquals("uid,name\n1,Alice", OriginalCsvPayload.extract(content))
    }

    @Test
    fun malformedEnvelopeIsRejected() {
        assertTrue(runCatching { OriginalCsvPayload.extract("uid,name\n1,Alice") }.isFailure)
    }
}
