package dev.gf2log.protocol

import dev.gf2log.protocol.model.AttachmentsData
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParsedPayload
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
                data.members.forEach { appendLine(GuildMembersCsv.row(it, capturedAt)) }
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
                                csvEscape(formation.name),
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

    private fun csvEscape(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
        return "\"${value.replace("\"", "\"\"")}\""
    }
}
