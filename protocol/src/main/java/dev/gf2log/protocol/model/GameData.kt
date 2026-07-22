package dev.gf2log.protocol.model

sealed interface GameData

data class WeaponsData(val weapons: List<Weapon>) : GameData

data class Weapon(
    val id: UInt,
    val level: UInt,
    val rank: UInt,
    val uid: ULong,
)

data class AttachmentsData(val attachments: List<Attachment>) : GameData

data class Attachment(
    val uid: ULong,
    val partId: UInt,
    val isLocked: Boolean,
    val weaponUid: ULong,
    val effectId: UInt?,
    val calibrationBoosts: List<UInt>,
    val attributes: ULong,
)

data class CommonKeysData(val keys: List<CommonKey>) : GameData

data class CommonKey(
    val uid: ULong,
    val keyId: UInt,
)

data class GuildMembersData(val members: List<GuildMember>) : GameData

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
