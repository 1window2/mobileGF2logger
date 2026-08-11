package dev.gf2log.app.discord

import java.net.URI

/** Strictly limits stored credentials to Discord's incoming-webhook execution endpoint. */
object DiscordWebhookPolicy {
    fun requireValid(value: String): URI {
        val normalized = value.trim()
        require(normalized.length in 1..MAX_URL_CHARS) { "Invalid Discord webhook URL" }
        val uri = URI(normalized)
        require(uri.scheme.equals("https", ignoreCase = true)) { "Discord webhook must use HTTPS" }
        require(uri.host.equals("discord.com", ignoreCase = true)) {
            "Discord webhook host must be discord.com"
        }
        require(uri.port == -1 || uri.port == 443) { "Discord webhook must use the HTTPS port" }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) {
            "Discord webhook URL cannot contain credentials, fragments, or query parameters"
        }
        require(uri.path.matches(WEBHOOK_PATH)) { "Invalid Discord incoming-webhook path" }
        return uri
    }

    private const val MAX_URL_CHARS = 512
    private val WEBHOOK_PATH = Regex(
        "^/api(?:/v\\d{1,2})?/webhooks/\\d{17,20}/[A-Za-z0-9._-]{32,200}$",
    )
}
