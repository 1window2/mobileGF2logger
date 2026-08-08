package dev.gf2log.protocol

import dev.gf2log.protocol.model.AttachmentsData
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import dev.gf2log.protocol.model.WeaponsData

object ParsedPayloadTextFormatter {
    fun format(payload: ParsedPayload, capturedAt: String): String = buildString {
        appendLine("capturedAt=$capturedAt")
        appendLine("messageId=${payload.messageId}")
        appendLine("payloadType=${payload.payloadType}")
        appendLine()

        when (val data = payload.data) {
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
                appendLine("id,level,rank,uid")
                data.weapons.forEach { appendLine("${it.id},${it.level},${it.rank},${it.uid}") }
            }
            is AttachmentsData -> {
                appendLine("uid,partId,isLocked,weaponUid,effectId,calibrationBoosts,attributes")
                data.attachments.forEach {
                    appendLine(
                        listOf(
                            it.uid,
                            it.partId,
                            it.isLocked,
                            it.weaponUid,
                            it.effectId ?: "",
                            it.calibrationBoosts.joinToString("|"),
                            it.attributes,
                        ).joinToString(","),
                    )
                }
            }
            is CommonKeysData -> {
                appendLine("uid,keyId")
                data.keys.forEach { appendLine("${it.uid},${it.keyId}") }
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
    }.trimEnd()

}
