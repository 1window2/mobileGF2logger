package dev.gf2log.protocol

import dev.gf2log.protocol.model.AttachmentsData
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParseEvent
import dev.gf2log.protocol.model.WeaponsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Gfl2StreamParserTest {
    @Test
    fun fragmentedOuterHeaderIsRetained() {
        val parser = Gfl2StreamParser()
        val message = outerMessage(
            messageId = 0x030201,
            payload(Gfl2PayloadDecoder.TYPE_WEAPONS, weaponsPayload()),
        )

        assertTrue(parser.accept(message.copyOfRange(0, 2)).isEmpty())
        val events = parser.accept(message.copyOfRange(2, message.size))

        val parsed = events.singlePayload()
        assertEquals(0x030201, parsed.value.messageId)
        val data = parsed.value.data as WeaponsData
        assertEquals(1, data.weapons.size)
        assertEquals(42u, data.weapons.single().id)
        assertEquals(70u, data.weapons.single().level)
        assertEquals(5u, data.weapons.single().rank)
        assertEquals(9_007_199_254_740_993uL, data.weapons.single().uid)
    }

    @Test
    fun coalescedMessagesAreParsedIndependently() {
        val parser = Gfl2StreamParser()
        val first = outerMessage(1, payload(Gfl2PayloadDecoder.TYPE_WEAPONS, weaponsPayload()))
        val second = outerMessage(2, payload(Gfl2PayloadDecoder.TYPE_COMMON_KEYS, commonKeysPayload()))

        val payloads = parser.accept(first + second).filterIsInstance<ParseEvent.Payload>()

        assertEquals(2, payloads.size)
        assertTrue(payloads[0].value.data is WeaponsData)
        val keys = payloads[1].value.data as CommonKeysData
        assertEquals(123uL, keys.keys.single().uid)
        assertEquals(456u, keys.keys.single().keyId)
    }

    @Test
    fun malformedPayloadDoesNotConsumeTheNextMessage() {
        val parser = Gfl2StreamParser()
        val malformedBody = littleEndian16(Gfl2PayloadDecoder.TYPE_WEAPONS) +
            littleEndian16(100) + byteArrayOf(0x01)
        val malformed = outerMessageWithBody(10, malformedBody)
        val valid = outerMessage(11, payload(Gfl2PayloadDecoder.TYPE_WEAPONS, weaponsPayload()))

        val events = parser.accept(malformed + valid)

        assertEquals(1, events.filterIsInstance<ParseEvent.Warning>().size)
        assertEquals(11, events.filterIsInstance<ParseEvent.Payload>().single().value.messageId)
    }

    @Test
    fun allKnownPayloadSchemasDecodeWithoutAProtobufRuntime() {
        val parser = Gfl2StreamParser()
        val message = outerMessage(
            99,
            payload(Gfl2PayloadDecoder.TYPE_ATTACHMENTS, attachmentsPayload()),
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload()),
            payload(Gfl2PayloadDecoder.TYPE_FORMATIONS, formationsPayload()),
        )

        val payloads = parser.accept(message).filterIsInstance<ParseEvent.Payload>()

        val attachment = (payloads[0].value.data as AttachmentsData).attachments.single()
        assertEquals(1000uL, attachment.uid)
        assertEquals(17u, attachment.partId)
        assertTrue(attachment.isLocked)
        assertEquals(8u, attachment.effectId)
        assertEquals(listOf(15u), attachment.calibrationBoosts)

        val member = (payloads[1].value.data as GuildMembersData).members.single()
        assertEquals("Commander", member.name)
        assertEquals(60u, member.level)
        assertEquals(777u, member.weeklyMerit)

        val formation = (payloads[2].value.data as FormationsData).formations.single()
        assertEquals("Alpha", formation.name)
        assertEquals(listOf(10uL, 11uL), formation.dolls.single().attachmentUids)
        assertEquals(listOf(20u, 21u), formation.dolls.single().fixedKeyIds)
    }

    @Test
    fun oversizedBufferedInputIsRejectedAndStateIsReset() {
        val parser = Gfl2StreamParser(maximumBufferedBytes = 64)

        val events = parser.accept(ByteArray(65))

        assertEquals(1, events.filterIsInstance<ParseEvent.Warning>().size)
        val recovered = parser.accept(
            outerMessage(7, payload(Gfl2PayloadDecoder.TYPE_COMMON_KEYS, commonKeysPayload())),
        )
        assertEquals(1, recovered.filterIsInstance<ParseEvent.Payload>().size)
    }

    @Test
    fun messageIdZeroContinuationIsMergedLikeTheReferenceLogger() {
        val parser = Gfl2StreamParser()
        val first = outerMessage(
            0,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("First", 1uL)),
        )
        val last = outerMessage(
            42,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("Second", 2uL)),
        )

        assertTrue(parser.accept(first).isEmpty())
        val event = parser.accept(last).singlePayload()
        val members = (event.value.data as GuildMembersData).members

        assertEquals(listOf("First", "Second"), members.map { it.name })
        assertEquals(listOf(1u, 2u), members.map { it.uid })
    }

    private fun weaponsPayload(): ByteArray {
        val weapon = uintField(2, 42uL) +
            uintField(6, 70uL) +
            uintField(8, 5uL) +
            uintField(11, 9_007_199_254_740_993uL)
        return messageField(1, weapon)
    }

    private fun commonKeysPayload(): ByteArray {
        val key = uintField(1, 123uL) + uintField(2, 456uL)
        return messageField(1, key)
    }

    private fun attachmentsPayload(): ByteArray {
        val effect = uintField(1, 8uL)
        val calibration = uintField(4, 15uL)
        val attachment = uintField(1, 1000uL) +
            uintField(2, 17uL) +
            uintField(3, 1uL) +
            uintField(4, 2000uL) +
            messageField(14, effect) +
            messageField(18, calibration) +
            uintField(20, 0x0102uL)
        return messageField(1, attachment)
    }

    private fun guildMembersPayload(name: String = "Commander", uid: ULong = 2222uL): ByteArray {
        val playerInfo = stringField(2, name) + uintField(3, 60uL)
        val player = messageField(1, playerInfo)
        val member = messageField(1, player) +
            uintField(3, 777uL) +
            uintField(4, 888uL) +
            uintField(5, 999uL) +
            uintField(6, 1111uL) +
            uintField(7, uid) +
            uintField(8, 3333uL)
        return messageField(1, member)
    }

    private fun formationsPayload(): ByteArray {
        val doll = uintField(1, 12uL) +
            uintField(2, 900uL) +
            packedUIntField(3, 10uL, 11uL) +
            packedUIntField(4, 20uL, 21uL) +
            packedUIntField(5, 30uL) +
            packedUIntField(6, 40uL, 41uL)
        val formation = stringField(1, "Alpha") + messageField(2, doll)
        val formations = messageField(1, formation)
        return messageField(1, formations)
    }

    private fun List<ParseEvent>.singlePayload(): ParseEvent.Payload =
        filterIsInstance<ParseEvent.Payload>().single()

    private fun outerMessage(messageId: Int, vararg payloads: ByteArray): ByteArray =
        outerMessageWithBody(messageId, payloads.fold(ByteArray(0)) { result, payload -> result + payload })

    private fun outerMessageWithBody(messageId: Int, body: ByteArray): ByteArray {
        require(body.size <= 0xFFFF)
        return byteArrayOf(
            messageId.toByte(),
            (messageId ushr 8).toByte(),
            (messageId ushr 16).toByte(),
        ) + littleEndian16(body.size) + body
    }

    private fun payload(type: Int, data: ByteArray): ByteArray =
        littleEndian16(type) + littleEndian16(data.size) + data

    private fun uintField(number: Int, value: ULong): ByteArray =
        varint((number shl 3).toULong()) + varint(value)

    private fun stringField(number: Int, value: String): ByteArray =
        messageField(number, value.toByteArray(Charsets.UTF_8))

    private fun messageField(number: Int, value: ByteArray): ByteArray =
        varint(((number shl 3) or 2).toULong()) + varint(value.size.toULong()) + value

    private fun packedUIntField(number: Int, vararg values: ULong): ByteArray =
        messageField(number, values.fold(ByteArray(0)) { bytes, value -> bytes + varint(value) })

    private fun varint(input: ULong): ByteArray {
        var value = input
        val bytes = mutableListOf<Byte>()
        do {
            var next = (value and 0x7FuL).toByte()
            value = value shr 7
            if (value != 0uL) next = (next.toInt() or 0x80).toByte()
            bytes += next
        } while (value != 0uL)
        return bytes.toByteArray()
    }

    private fun littleEndian16(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
    )
}
