package dev.gf2log.protocol

import dev.gf2log.protocol.internal.ProtoReader
import dev.gf2log.protocol.model.Doll
import dev.gf2log.protocol.model.Formation
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GameData
import dev.gf2log.protocol.model.GuildMember
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonActivityEntry
import dev.gf2log.protocol.model.PlatoonActivitySummary
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.PlatoonUpdateEntry
import dev.gf2log.protocol.model.PlatoonUpdateMember
import dev.gf2log.protocol.model.PlatoonUpdatesData
import dev.gf2log.protocol.model.PublicSkillItem
import dev.gf2log.protocol.model.PublicSkillItemsData
import dev.gf2log.protocol.model.Weapon
import dev.gf2log.protocol.model.WeaponMod
import dev.gf2log.protocol.model.WeaponModBinding
import dev.gf2log.protocol.model.WeaponModsData
import dev.gf2log.protocol.model.WeaponsData

object Gfl2PayloadDecoder {
    const val TYPE_WEAPONS = 11021
    const val TYPE_WEAPON_MODS = 11061
    const val TYPE_PUBLIC_SKILL_ITEMS = 11138
    const val TYPE_PLATOON_PROFILE = 21905
    const val TYPE_GUILD_MEMBERS = 21917
    const val TYPE_PLATOON_ACTIVITY = 21935
    const val TYPE_PLATOON_UPDATES = 21960
    const val TYPE_FORMATIONS = 23201

    val supportedTypes: Set<Int> = setOf(
        TYPE_WEAPONS,
        TYPE_WEAPON_MODS,
        TYPE_PUBLIC_SKILL_ITEMS,
        TYPE_PLATOON_PROFILE,
        TYPE_GUILD_MEMBERS,
        TYPE_PLATOON_ACTIVITY,
        TYPE_PLATOON_UPDATES,
        TYPE_FORMATIONS,
    )

    @Throws(ProtocolException::class)
    fun decode(type: Int, bytes: ByteArray): GameData? = when (type) {
        TYPE_WEAPONS -> decodeWeapons(ProtoReader(bytes))
        TYPE_WEAPON_MODS -> decodeWeaponMods(ProtoReader(bytes))
        TYPE_PUBLIC_SKILL_ITEMS -> decodePublicSkillItems(ProtoReader(bytes))
        TYPE_PLATOON_PROFILE -> decodePlatoonProfile(ProtoReader(bytes))
        TYPE_GUILD_MEMBERS -> decodeGuildMembers(ProtoReader(bytes))
        TYPE_PLATOON_ACTIVITY -> decodePlatoonActivity(ProtoReader(bytes))
        TYPE_PLATOON_UPDATES -> decodePlatoonUpdates(ProtoReader(bytes))
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
        var stcId = 0u
        var level = 0u
        var exp = 0u
        var gunId = 0u
        var breakTimes = 0u
        var rawFlags = 0u
        val weaponMods = mutableListOf<WeaponModBinding>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> id = reader.readUInt(field).toUInt()
                2 -> stcId = reader.readUInt(field).toUInt()
                3 -> level = reader.readUInt(field).toUInt()
                4 -> exp = reader.readUInt(field).toUInt()
                5 -> gunId = reader.readUInt(field).toUInt()
                6 -> breakTimes = reader.readUInt(field).toUInt()
                7 -> rawFlags = reader.readUInt(field).toUInt()
                8 -> weaponMods += decodeWeaponModBinding(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        return Weapon(id, stcId, level, exp, gunId, breakTimes, rawFlags, weaponMods)
    }

    private fun decodeWeaponModBinding(reader: ProtoReader): WeaponModBinding {
        var id = 0u
        var gunId = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> id = reader.readUInt(field).toUInt()
                2 -> gunId = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }
        return WeaponModBinding(id, gunId)
    }

    private fun decodeWeaponMods(reader: ProtoReader): WeaponModsData {
        val mods = mutableListOf<WeaponMod>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) {
                mods += decodeWeaponMod(reader.readMessage(field))
            } else {
                reader.skip(field)
            }
        }
        return WeaponModsData(mods)
    }

    private fun decodeWeaponMod(reader: ProtoReader): WeaponMod {
        var id = 0u
        var stcId = 0u
        var lockedFlags = 0uL
        var modSuitPowerId = 0u
        var level = 0u
        var exp = 0u
        var suitFlags = 0uL

        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> id = reader.readUInt(field).toUInt()
                2 -> stcId = reader.readUInt(field).toUInt()
                3 -> lockedFlags = reader.readUInt(field)
                4 -> modSuitPowerId = reader.readUInt(field).toUInt()
                5 -> level = reader.readUInt(field).toUInt()
                6 -> exp = reader.readUInt(field).toUInt()
                7 -> suitFlags = reader.readUInt(field)
                else -> reader.skip(field)
            }
        }

        return WeaponMod(
            id = id,
            stcId = stcId,
            lockedFlags = lockedFlags,
            modSuitPowerId = modSuitPowerId,
            level = level,
            exp = exp,
            suitFlags = suitFlags,
        )
    }

    private fun decodePublicSkillItems(reader: ProtoReader): PublicSkillItemsData {
        val items = mutableListOf<PublicSkillItem>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) {
                items += decodePublicSkillItem(reader.readMessage(field))
            } else {
                reader.skip(field)
            }
        }
        return PublicSkillItemsData(items)
    }

    private fun decodePublicSkillItem(reader: ProtoReader): PublicSkillItem {
        var id = 0uL
        var stcId = 0u
        var gunId = 0u
        var lockedFlags = 0uL
        var isNew = false
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> id = reader.readUInt(field)
                2 -> stcId = reader.readUInt(field).toUInt()
                3 -> gunId = reader.readUInt(field).toUInt()
                4 -> lockedFlags = reader.readUInt(field)
                5 -> isNew = reader.readBoolean(field)
                else -> reader.skip(field)
            }
        }
        return PublicSkillItem(id, stcId, gunId, lockedFlags, isNew)
    }

    /** Decodes the grounded identity, banner, progression, policy, and bounded text fields in 21905. */
    private fun decodePlatoonProfile(reader: ProtoReader): PlatoonProfileData {
        var profile = PlatoonProfileData(0u, "", 0u, 0u)
        var questId = 0u
        var seasonId = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> profile = decodePlatoonProfileBody(reader.readMessage(field))
                2 -> questId = reader.readUInt(field).toUInt()
                4 -> seasonId = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }
        return profile.copy(questId = questId, seasonId = seasonId)
    }

    private fun decodePlatoonProfileBody(reader: ProtoReader): PlatoonProfileData {
        var platoonId = 0u
        var platoonName = ""
        var level = 0u
        var exp = 0u
        var announcement = ""
        var bannerFrameId = 0u
        var bannerMarkId = 0u
        var declaration = ""
        var joinPolicyFlag = false
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> platoonId = reader.readUInt(field).toUInt()
                2 -> platoonName = reader.readString(field).take(MAX_PLATOON_NAME_CHARS)
                3 -> level = reader.readUInt(field).toUInt()
                4 -> exp = reader.readUInt(field).toUInt()
                6 -> announcement = reader.readString(field).take(MAX_PLATOON_TEXT_CHARS)
                // CS_ChangeGuildFlag and asymmetric live profiles establish that field 9 is
                // the mark/design selector while field 10 is the colored frame selector.
                9 -> bannerMarkId = reader.readUInt(field).toUInt()
                10 -> bannerFrameId = reader.readUInt(field).toUInt()
                12 -> declaration = reader.readString(field).take(MAX_PLATOON_TEXT_CHARS)
                13 -> joinPolicyFlag = reader.readBoolean(field)
                else -> reader.skip(field)
            }
        }
        return PlatoonProfileData(
            platoonId = platoonId,
            platoonName = platoonName,
            bannerFrameId = bannerFrameId,
            bannerMarkId = bannerMarkId,
            level = level,
            exp = exp,
            announcement = announcement,
            declaration = declaration,
            joinPolicyFlag = joinPolicyFlag,
        )
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

    private fun decodePlatoonActivity(reader: ProtoReader): PlatoonActivityData {
        val summaries = mutableListOf<PlatoonActivitySummary>()
        val entries = mutableListOf<PlatoonActivityEntry>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> summaries += decodePlatoonActivitySummary(reader.readMessage(field))
                2 -> entries += decodePlatoonActivityEntry(reader.readMessage(field))
                else -> reader.skip(field)
            }
        }
        return PlatoonActivityData(summaries, entries)
    }

    private fun decodePlatoonActivitySummary(reader: ProtoReader): PlatoonActivitySummary {
        var id = 0uL
        var actionId = 0u
        var occurredAt = 0u
        var count = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> id = reader.readUInt(field)
                2 -> actionId = reader.readUInt(field).toUInt()
                3 -> occurredAt = reader.readUInt(field).toUInt()
                4 -> count = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }
        return PlatoonActivitySummary(id, actionId, occurredAt, count)
    }

    private fun decodePlatoonActivityEntry(reader: ProtoReader): PlatoonActivityEntry {
        var kind = 0u
        var occurredAt = 0u
        var actionId = 0u
        var memberName = ""
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                2 -> kind = reader.readUInt(field).toUInt()
                3 -> occurredAt = reader.readUInt(field).toUInt()
                4 -> actionId = reader.readUInt(field).toUInt()
                5 -> memberName = reader.readString(field)
                else -> reader.skip(field)
            }
        }
        return PlatoonActivityEntry(kind, occurredAt, actionId, memberName)
    }

    private fun decodePlatoonUpdates(reader: ProtoReader): PlatoonUpdatesData {
        val entries = mutableListOf<PlatoonUpdateEntry>()
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            if (field.number == 1) {
                entries += decodePlatoonUpdateEntry(reader.readMessage(field))
            } else {
                reader.skip(field)
            }
        }
        return PlatoonUpdatesData(entries)
    }

    private fun decodePlatoonUpdateEntry(reader: ProtoReader): PlatoonUpdateEntry {
        var kind = 0u
        val members = mutableListOf<PlatoonUpdateMember>()
        var occurredAt = 0u
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> kind = reader.readUInt(field).toUInt()
                2 -> members += decodePlatoonUpdateMember(reader.readMessage(field))
                3 -> occurredAt = reader.readUInt(field).toUInt()
                else -> reader.skip(field)
            }
        }
        return PlatoonUpdateEntry(kind, members, occurredAt)
    }

    private fun decodePlatoonUpdateMember(reader: ProtoReader): PlatoonUpdateMember {
        var role = 0u
        var uid = 0u
        var name = ""
        while (!reader.exhausted) {
            val field = reader.nextField() ?: break
            when (field.number) {
                1 -> role = reader.readUInt(field).toUInt()
                2 -> uid = reader.readUInt(field).toUInt()
                3 -> name = reader.readString(field)
                else -> reader.skip(field)
            }
        }
        return PlatoonUpdateMember(role, uid, name)
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

    private const val MAX_PLATOON_NAME_CHARS = 128
    private const val MAX_PLATOON_TEXT_CHARS = 4_096
}
