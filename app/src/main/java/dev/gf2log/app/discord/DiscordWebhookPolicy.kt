package dev.gf2log.app.discord

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Strictly limits stored credentials to Discord's incoming-webhook execution endpoint. */
object DiscordWebhookPolicy {
    fun requireValid(value: String): URI {
        val normalized = value.trim()
        require(normalized.length in 1..MAX_URL_CHARS) { INVALID_URL }
        val uri = canonicalHost(parse(normalized))
        require(uri.scheme.equals("https", ignoreCase = true)) { "Discord webhook must use HTTPS" }
        require(uri.host.equals(CANONICAL_HOST, ignoreCase = true)) {
            "Discord webhook host must be discord.com"
        }
        require(uri.port == -1 || uri.port == 443) { "Discord webhook must use the HTTPS port" }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "Discord webhook URL cannot contain credentials, fragments, or query parameters"
        }
        // The raw path is what is sent, so percent-encoded spellings cannot pass as canonical.
        require(uri.rawPath.orEmpty().matches(WEBHOOK_PATH)) { "Invalid Discord incoming-webhook path" }
        return uri
    }

    /**
     * URISyntaxException quotes its whole input, token included, and failures are logged; the
     * replacement carries neither the input nor a cause.
     */
    private fun parse(value: String): URI = try {
        URI(value)
    } catch (_: URISyntaxException) {
        throw IllegalArgumentException(INVALID_URL)
    }

    /**
     * The PTB, Canary, and legacy hosts copy the same webhook under another name. Only the host
     * is replaced, so the stored and sent host is always discord.com and every other component
     * still meets the checks above unchanged. A URL with credentials keeps its host and fails.
     */
    private fun canonicalHost(uri: URI): URI {
        val host = uri.host?.lowercase(Locale.ROOT) ?: return uri
        if (host !in ALIAS_HOSTS || uri.rawUserInfo != null) return uri
        return parse(buildString {
            append(uri.scheme).append("://").append(CANONICAL_HOST)
            if (uri.port != -1) append(':').append(uri.port)
            append(uri.rawPath.orEmpty())
            uri.rawQuery?.let { append('?').append(it) }
            uri.rawFragment?.let { append('#').append(it) }
        })
    }

    private const val INVALID_URL = "Invalid Discord webhook URL"
    private const val CANONICAL_HOST = "discord.com"
    private val ALIAS_HOSTS = setOf("ptb.discord.com", "canary.discord.com", "discordapp.com")
    private const val MAX_URL_CHARS = 512
    private val WEBHOOK_PATH = Regex(
        "^/api(?:/v\\d{1,2})?/webhooks/\\d{17,20}/[A-Za-z0-9._-]{32,200}$",
    )
}
