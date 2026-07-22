package dev.gf2log.protocol

import dev.gf2log.protocol.internal.ProtoReader
import dev.gf2log.protocol.model.Attachment
import dev.gf2log.protocol.model.AttachmentsData
import dev.gf2log.protocol.model.CommonKey
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.Doll
import dev.gf2log.protocol.model.Formation
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GameData
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.Weapon
import dev.gf2log.protocol.model.WeaponsData

object Gfl2PayloadDecoder {
    const val TYPE_WEAPONS = 11021
    const val TYPE_ATTACHMENTS = 11061
    const val TYPE_COMMON_KEYS = 11138
    const val TYPE_GUILD_MEMBERS = 21917
    const val TYPE_FORMATIONS = 23201

    val supportedTypes: Set<Int> = setOf(
        TYPE_WEAPONS,
        TYPE_ATTACHMENTS,
        TYPE_COMMON_KEYS,
        TYPE_GUILD_MEMBERS,
        TYPE_FORMATIONS,
    )

    @Throws(ProtocolException::class)
    fun decode(type: Int, bytes: ByteArray): GameData? = when (type) {
        TYPE_WEAPONS -> decodeWeapons(ProtoReader(bytes))
        TYPE_ATTACHMENTS -> decodeAttachments(ProtoReader(bytes))
        TYPE_COMMON_KEYS -> decodeCommonKeys(ProtoReader(bytes))
        TYPE_GUILD_MEMBERS -> decodeGuildMembers(ProtoReader(bytes))
        TYPE_FORMATIONS -> decodeFormationsResponse(ProtoReader(bytes))
        else -> null
    }

    private fun decodeWeapons(reader: ProtoReader): WeaponsData {
        val weapons = mutableListOf<Weapon>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) weapons += decodeWeapon(reader.readMessage(field)) else reader.skip(field)
        }
        return WeaponsData(weapons)
    }

    private fun decodeWeapon(reader: ProtoReader): Weapon {
        var id = 0u
        var level = 0u
        var rank = 0u
        var uid = 0uL
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                2 -> id = reader.readUInt(field).toUInt()
                6 -> level = reader.readUInt(field).toUInt()
                8 -> rank = reader.readUInt(field).toUInt()
                11 -> uid = reader.readUInt(field)
                else -> reader.skip(field)
            }
        }
        return Weapon(id, level, rank, uid)
    }

    private fun decodeAttachments(reader: ProtoReader): AttachmentsData {
        val attachments = mutableListOf<Attachment>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) {
                attachments += decodeAttachment(reader.readMessage(field))
            } else {
                reader.skip(field)
            }
        }
        return AttachmentsData(attachments)
    }

    private fun decodeAttachment(reader: ProtoReader): Attachment {
        var uid = 0uL
        var partId = 0u
        var isLocked = false
        var weaponUid = 0uL
        var effectId: UInt? = null
        val calibrationBoosts = mutableListOf<UInt>()
        var attributes = 0uL

        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> uid = reader.readUInt(field)
                2 -> partId = reader.readUInt(field).toUInt()
                3 -> isLocked = reader.readBoolean(field)
                4 -> weaponUid = reader.readUInt(field)
                14 -> effectId = decodeEffect(reader.readMessage(field))
                18 -> calibrationBoosts += decodeCalibration(reader.readMessage(field))
                20 -> attributes = reader.readUInt(field)
                else -> reader.skip(field)
            }
        }

        return Attachment(
            uid = uid,
            partId = partId,
            isLocked = isLocked,
            weaponUid = weaponUid,
            effectId = effectId,
            calibrationBoosts = calibrationBoosts,
            attributes = attributes,
        )
    }

    private fun decodeEffect(reader: ProtoReader): UInt? {
        var id: UInt? = null
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) id = reader.readUInt(field).toUInt() else reader.skip(field)
        }
        return id
    }

    private fun decodeCalibration(reader: ProtoReader): UInt {
        var boost = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 4) boost = reader.readUInt(field).toUInt() else reader.skip(field)
        }
        return boost
    }

    private fun decodeCommonKeys(reader: ProtoReader): CommonKeysData {
        val keys = mutableListOf<CommonKey>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) keys += decodeCommonKey(reader.readMessage(field)) else reader.skip(field)
        }
        return CommonKeysData(keys)
    }

    private fun decodeCommonKey(reader: ProtoReader): CommonKey {
        var uid = 0uL
        var keyId = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> uid = reader.readUInt(field)
                2 -> keyId = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }
        return CommonKey(uid, keyId)
    }

    private fun decodeGuildMembers(reader: ProtoReader): GuildMembersData {
        val members = mutableListOf<GuildMember>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) {
                members += decodeGuildMember(reader.readMessage(field))
            } else {
                reader.skip(field)
            }
        }
        return GuildMembersData(members)
    }

    private fun decodeGuildMember(reader: ProtoReader): GuildMember {
        var name = ""
        var level = 0u
        var weeklyMerit = 0u
        var totalMerit = 0u
        var highScore = 0u
        var totalScore = 0u
        var uid = 0u
        var lastLogin = 0u

        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> {
                    val player = decodePlayer(reader.readMessage(field))
                    name = player.first
                    level = player.second
                }
                3 -> weeklyMerit = reader.readUInt(field).toUInt()
                4 -> totalMerit = reader.readUInt(field).toUInt()
                5 -> highScore = reader.readUInt(field).toUInt()
                6 -> totalScore = reader.readUInt(field).toUInt()
                7 -> uid = reader.readUInt(field).toUInt()
                8 -> lastLogin = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }

        return GuildMember(uid, name, level, weeklyMerit, totalMerit, highScore, totalScore, lastLogin)
    }

    private fun decodePlayer(reader: ProtoReader): Pair<String, UInt> {
        var result = "" to 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) result = decodePlayerInfo(reader.readMessage(field)) else reader.skip(field)
        }
        return result
    }

    private fun decodePlayerInfo(reader: ProtoReader): Pair<String, UInt> {
        var name = ""
        var level = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                2 -> name = reader.readString(field)
                3 -> level = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }
        return name to level
    }

    private fun decodeFormationsResponse(reader: ProtoReader): FormationsData {
        val formations = mutableListOf<Formation>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) {
                formations += decodeFormationsContainer(reader.readMessage(field))
            } else {
                reader.skip(field)
            }
        }
        return FormationsData(formations)
    }

    private fun decodeFormationsContainer(reader: ProtoReader): List<Formation> {
        val formations = mutableListOf<Formation>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) formations += decodeFormation(reader.readMessage(field)) else reader.skip(field)
        }
        return formations
    }

    private fun decodeFormation(reader: ProtoReader): Formation {
        var name = ""
        val dolls = mutableListOf<Doll>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> name = reader.readString(field)
                2 -> dolls += decodeDoll(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        return Formation(name, dolls)
    }

    private fun decodeDoll(reader: ProtoReader): Doll {
        var dollId = 0u
        var weaponUid = 0uL
        val attachmentUids = mutableListOf<ULong>()
        val fixedKeyIds = mutableListOf<UInt>()
        val expansionKeyIds = mutableListOf<UInt>()
        val commonKeyUids = mutableListOf<ULong>()

        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> dollId = reader.readUInt(field).toUInt()
                2 -> weaponUid = reader.readUInt(field)
                3 -> attachmentUids += reader.readRepeatedUInt(field)
                4 -> fixedKeyIds += reader.readRepeatedUInt(field).map { it.toUInt() }
                5 -> expansionKeyIds += reader.readRepeatedUInt(field).map { it.toUInt() }
                6 -> commonKeyUids += reader.readRepeatedUInt(field)
                else -> reader.skip(field)
            }
        }

        return Doll(dollId, weaponUid, attachmentUids, fixedKeyIds, expansionKeyIds, commonKeyUids)
    }
}
