package dev.gf2log.app.settings

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Known publisher/region reset schedules, expressed without device-local assumptions. */
internal enum class GameServerRegion(
    val storedValue: String,
    val serverZone: ZoneId?,
) {
    MANUAL("manual", null),
    DARKWINTER_GLOBAL("darkwinter_global", ZoneOffset.ofHours(-4)),
    DARKWINTER_CHINA("darkwinter_china", ZoneOffset.ofHours(8)),
    HAOPLAY_GLOBAL("haoplay_global", ZoneOffset.UTC),
    HAOPLAY_JAPAN("haoplay_japan", ZoneOffset.ofHours(9)),
    HAOPLAY_KOREA("haoplay_korea", ZoneOffset.ofHours(9)),
    HAOPLAY_ASIA("haoplay_asia", ZoneOffset.ofHours(8)),
    ;

    fun nextReset(after: Instant = Instant.now()): Instant {
        val zone = requireNotNull(serverZone)
        val now = after.atZone(zone)
        val today = ZonedDateTime.of(now.toLocalDate(), RESET_TIME, zone)
        return (if (today.toInstant().isAfter(after)) today else today.plusDays(1)).toInstant()
    }

    companion object {
        val RESET_TIME: LocalTime = LocalTime.of(5, 0)

        fun fromStored(value: String?): GameServerRegion = entries
            .firstOrNull { it.storedValue == value }
            ?: MANUAL
    }
}
