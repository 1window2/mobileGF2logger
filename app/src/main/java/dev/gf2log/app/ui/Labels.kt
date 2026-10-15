package dev.gf2log.app.ui

import android.content.Context
import dev.gf2log.app.R
import dev.gf2log.app.management.PlatoonProfile
import dev.gf2log.app.settings.GameServerRegion
import dev.gf2log.protocol.Gfl2PayloadDecoder
import dev.gf2log.protocol.PayloadCatalog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAccessor

/** Localized presentation labels shared by every screen; no domain policy lives here. */
internal object Labels {
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yy/MM/dd")
    val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm")
    val DATE_TIME_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yy/MM/dd HH:mm:ss")
    val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    val FILE_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

    fun format(formatter: DateTimeFormatter, instant: Instant, zone: ZoneId): String =
        formatter.format(instant.atZone(zone))

    fun format(formatter: DateTimeFormatter, value: TemporalAccessor): String = formatter.format(value)

    /** "10/12 Sun" with the weekday in the active app language. */
    fun dayHeader(context: Context, day: java.time.LocalDate): String =
        "${day.monthValue}/${day.dayOfMonth} " +
            day.dayOfWeek.getDisplayName(TextStyle.SHORT, context.resources.configuration.locales[0])

    fun region(context: Context, region: GameServerRegion): String = context.getString(
        when (region) {
            GameServerRegion.MANUAL -> R.string.server_region_manual
            GameServerRegion.DARKWINTER_GLOBAL -> R.string.server_region_darkwinter_global
            GameServerRegion.DARKWINTER_CHINA -> R.string.server_region_darkwinter_china
            GameServerRegion.HAOPLAY_GLOBAL -> R.string.server_region_haoplay_global
            GameServerRegion.HAOPLAY_JAPAN -> R.string.server_region_haoplay_japan
            GameServerRegion.HAOPLAY_KOREA -> R.string.server_region_haoplay_korea
            GameServerRegion.HAOPLAY_ASIA -> R.string.server_region_haoplay_asia
        },
    )

    /** "HaoPlay · South Korea · ID 1234" */
    fun profileIdentity(context: Context, profile: PlatoonProfile): String = context.getString(
        R.string.profile_identity,
        profile.client.displayName,
        region(context, profile.serverRegion),
        profile.platoonId,
    )

    fun payloadName(context: Context, payloadType: Int?): String = when (payloadType) {
        Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE -> context.getString(R.string.payload_name_platoon_profile)
        Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> context.getString(R.string.payload_name_platoon_members)
        Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY -> context.getString(R.string.payload_name_platoon_activity)
        Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES -> context.getString(R.string.payload_name_platoon_updates)
        Gfl2PayloadDecoder.TYPE_WEAPONS -> context.getString(R.string.payload_name_weapons)
        Gfl2PayloadDecoder.TYPE_WEAPON_MODS -> context.getString(R.string.payload_name_attachments)
        Gfl2PayloadDecoder.TYPE_PUBLIC_SKILL_ITEMS -> context.getString(R.string.payload_name_common_keys)
        Gfl2PayloadDecoder.TYPE_FORMATIONS -> context.getString(R.string.payload_name_formations)
        else -> context.getString(R.string.unknown_payload_type)
    }

    fun payloadTag(context: Context, payloadType: Int?): String = when (payloadType) {
        Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE -> context.getString(R.string.payload_tag_profile)
        Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> context.getString(R.string.payload_tag_platoon)
        Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY -> context.getString(R.string.payload_tag_activity)
        Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES -> context.getString(R.string.payload_tag_updates)
        Gfl2PayloadDecoder.TYPE_WEAPONS -> context.getString(R.string.payload_tag_weapons)
        Gfl2PayloadDecoder.TYPE_WEAPON_MODS -> context.getString(R.string.payload_tag_attachments)
        Gfl2PayloadDecoder.TYPE_PUBLIC_SKILL_ITEMS -> context.getString(R.string.payload_tag_common_keys)
        Gfl2PayloadDecoder.TYPE_FORMATIONS -> context.getString(R.string.payload_tag_formations)
        else -> PayloadCatalog.tag(payloadType)
    }

    fun payloadDescription(context: Context, payloadType: Int): String = context.getString(
        when (payloadType) {
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE -> R.string.payload_description_platoon_profile
            Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS -> R.string.payload_description_platoon_members
            Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY -> R.string.payload_description_platoon_activity
            Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES -> R.string.payload_description_platoon_updates
            Gfl2PayloadDecoder.TYPE_WEAPONS -> R.string.payload_description_weapons
            Gfl2PayloadDecoder.TYPE_WEAPON_MODS -> R.string.payload_description_attachments
            Gfl2PayloadDecoder.TYPE_PUBLIC_SKILL_ITEMS -> R.string.payload_description_common_keys
            Gfl2PayloadDecoder.TYPE_FORMATIONS -> R.string.payload_description_formations
            else -> R.string.unknown_payload_type
        },
    )
}
