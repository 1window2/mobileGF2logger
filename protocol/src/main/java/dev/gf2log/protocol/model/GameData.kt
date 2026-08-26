package dev.gf2log.protocol.model

sealed interface GameData

data class WeaponsData(val weapons: List<Weapon>) : GameData

data class Weapon(
    val id: UInt,
    val stcId: UInt,
    val level: UInt,
    val exp: UInt,
    val gunId: UInt,
    val breakTimes: UInt,
    val rawFlags: UInt,
    val weaponMods: List<WeaponModBinding>,
)

data class WeaponModBinding(
    val id: UInt,
    val gunId: UInt,
)

data class WeaponModsData(val mods: List<WeaponMod>) : GameData

data class WeaponMod(
    val id: UInt,
    val stcId: UInt,
    val lockedFlags: ULong,
    val modSuitPowerId: UInt,
    val level: UInt,
    val exp: UInt,
    val suitFlags: ULong,
)

data class PublicSkillItemsData(val items: List<PublicSkillItem>) : GameData

data class PublicSkillItem(
    val id: ULong,
    val stcId: UInt,
    val gunId: UInt,
    val lockedFlags: ULong,
    val isNew: Boolean,
)

data class GuildMembersData(val members: List<GuildMember>) : GameData

/** Stable identity carried ahead of roster and activity data on a platoon flow. */
data class PlatoonProfileData(
    val platoonId: UInt,
    val platoonName: String,
    val bannerFrameId: UInt,
    val bannerMarkId: UInt,
    val level: UInt = 0u,
    val exp: UInt = 0u,
    val announcement: String = "",
    val declaration: String = "",
    val joinPolicyFlag: Boolean = false,
    val questId: UInt = 0u,
    val seasonId: UInt = 0u,
) : GameData

data class GuildMember(
    val uid: UInt,
    val name: String,
    val level: UInt,
    val weeklyMerit: UInt,
    val totalMerit: UInt,
    val highScore: UInt,
    val totalScore: UInt,
    val lastLogin: UInt,
)

data class PlatoonActivityData(
    val summaries: List<PlatoonActivitySummary>,
    val entries: List<PlatoonActivityEntry>,
) : GameData

data class PlatoonActivitySummary(
    val id: ULong,
    val actionId: UInt,
    val occurredAt: UInt,
    val count: UInt,
)

data class PlatoonActivityEntry(
    val kind: UInt,
    val occurredAt: UInt,
    val actionId: UInt,
    val memberName: String,
)

data class PlatoonUpdatesData(
    val entries: List<PlatoonUpdateEntry>,
) : GameData

data class PlatoonUpdateEntry(
    val kind: UInt,
    val members: List<PlatoonUpdateMember>,
    val occurredAt: UInt,
)

data class PlatoonUpdateMember(
    val role: UInt,
    val uid: UInt,
    val name: String,
)

data class FormationsData(val formations: List<Formation>) : GameData

data class Formation(
    val name: String,
    val dolls: List<Doll>,
)

data class Doll(
    val dollId: UInt,
    val weaponUid: ULong,
    val attachmentUids: List<ULong>,
    val fixedKeyIds: List<UInt>,
    val expansionKeyIds: List<UInt>,
    val commonKeyUids: List<ULong>,
)
