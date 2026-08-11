package dev.gf2log.app.discord

import org.junit.Assert.assertEquals
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
        ).forEach { value ->
            assertTrue(runCatching { DiscordWebhookPolicy.requireValid(value) }.isFailure)
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
