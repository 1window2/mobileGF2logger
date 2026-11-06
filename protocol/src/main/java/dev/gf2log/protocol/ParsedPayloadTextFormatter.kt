package dev.gf2log.protocol

import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import dev.gf2log.protocol.model.PublicSkillItemsData
import dev.gf2log.protocol.model.WeaponModsData
import dev.gf2log.protocol.model.WeaponsData

object ParsedPayloadTextFormatter {
    fun format(payload: ParsedPayload, capturedAt: String): String =
        checkNotNull(format(payload, capturedAt, Int.MAX_VALUE))

    /**
     * Formats [payload], or returns null as soon as the text would exceed [maxChars], before a
     * crafted payload can allocate its whole oversized text. A UTF-8 encoding is never shorter
     * than its UTF-16 unit count, so a byte limit can be passed as [maxChars] directly.
     */
    fun format(payload: ParsedPayload, capturedAt: String, maxChars: Int): String? {
        require(maxChars >= 0) { "Character limit must not be negative" }
        // The trailing line break is trimmed after writing, so it may briefly pass the limit.
        val text = BoundedText(maxChars.toLong() + 1)
        try {
            text.write(payload, capturedAt)
        } catch (_: BoundedText.LimitExceeded) {
            return null
        }
        return text.toString().trimEnd().takeIf { it.length <= maxChars }
    }

    private fun Appendable.write(payload: ParsedPayload, capturedAt: String) {
        appendLine("capturedAt=$capturedAt")
        appendLine("messageId=${payload.messageId}")
        appendLine("payloadType=${payload.payloadType}")
        appendLine("isEndOfMessage=${payload.isEndOfMessage}")
        appendLine()

        when (val data = payload.data) {
            is PlatoonProfileData -> {
                appendLine(
                    "platoonId,platoonName,level,exp,bannerFrameId,bannerMarkId," +
                        "questId,seasonId,joinPolicyFlag,announcement,declaration",
                )
                appendLine(
                    listOf(
                        data.platoonId,
                        CsvCell.escape(data.platoonName),
                        data.level,
                        data.exp,
                        data.bannerFrameId,
                        data.bannerMarkId,
                        data.questId,
                        data.seasonId,
                        data.joinPolicyFlag,
                        CsvCell.escape(data.announcement),
                        CsvCell.escape(data.declaration),
                    ).joinToString(","),
                )
            }
            is GuildMembersData -> {
                appendLine(GuildMembersCsv.HEADER)
                data.members.forEach {
                    appendLine(GuildMembersCsv.rowForSpreadsheet(it, capturedAt))
                }
            }
            is PlatoonActivityData -> {
                appendLine("recordType,id,kind,occurredAt,actionId,count,memberName")
                data.summaries.forEach {
                    appendLine("summary,${it.id},,${it.occurredAt},${it.actionId},${it.count},")
                }
                data.entries.forEach {
                    appendLine(
                        "entry,,${it.kind},${it.occurredAt},${it.actionId},," +
                            CsvCell.escape(it.memberName),
                    )
                }
            }
            is PlatoonUpdatesData -> {
                appendLine("kind,occurredAt,memberIndex,role,uid,memberName")
                data.entries.forEach { entry ->
                    entry.members.forEachIndexed { index, member ->
                        appendLine(
                            "${entry.kind},${entry.occurredAt},$index,${member.role}," +
                                "${member.uid},${CsvCell.escape(member.name)}",
                        )
                    }
                }
            }
            is WeaponsData -> {
                appendLine("id,stcId,level,exp,gunId,breakTimes,rawFlags,weaponMods")
                data.weapons.forEach {
                    appendLine(
                        listOf(
                            it.id,
                            it.stcId,
                            it.level,
                            it.exp,
                            it.gunId,
                            it.breakTimes,
                            it.rawFlags,
                            it.weaponMods.joinToString("|") { binding ->
                                "${binding.id}:${binding.gunId}"
                            },
                        ).joinToString(","),
                    )
                }
            }
            is WeaponModsData -> {
                appendLine("id,stcId,lockedFlags,modSuitPowerId,level,exp,suitFlags")
                data.mods.forEach {
                    appendLine(
                        listOf(
                            it.id,
                            it.stcId,
                            it.lockedFlags,
                            it.modSuitPowerId,
                            it.level,
                            it.exp,
                            it.suitFlags,
                        ).joinToString(","),
                    )
                }
            }
            is PublicSkillItemsData -> {
                appendLine("id,stcId,gunId,lockedFlags,isNew")
                data.items.forEach {
                    appendLine("${it.id},${it.stcId},${it.gunId},${it.lockedFlags},${it.isNew}")
                }
            }
            is FormationsData -> {
                appendLine("formation,dollId,weaponUid,attachmentUids,fixedKeyIds,expansionKeyIds,commonKeyUids")
                data.formations.forEach { formation ->
                    formation.dolls.forEach { doll ->
                        appendLine(
                            listOf(
                                CsvCell.escape(formation.name),
                                doll.dollId,
                                doll.weaponUid,
                                doll.attachmentUids.joinToString("|"),
                                doll.fixedKeyIds.joinToString("|"),
                                doll.expansionKeyIds.joinToString("|"),
                                doll.commonKeyUids.joinToString("|"),
                            ).joinToString(","),
                        )
                    }
                }
            }
        }
    }

    /** A text buffer that refuses to grow past [maxChars]. */
    private class BoundedText(private val maxChars: Long) : Appendable {
        private val builder = StringBuilder()

        override fun append(value: CharSequence?): Appendable {
            val text = value ?: "null"
            return append(text, 0, text.length)
        }

        override fun append(value: CharSequence?, start: Int, end: Int): Appendable {
            if (builder.length.toLong() + (end - start) > maxChars) throw LimitExceeded()
            builder.append(value ?: "null", start, end)
            return this
        }

        override fun append(value: Char): Appendable {
            if (builder.length.toLong() + 1 > maxChars) throw LimitExceeded()
            builder.append(value)
            return this
        }

        override fun toString(): String = builder.toString()

        class LimitExceeded : RuntimeException(null, null, false, false)
    }
}
