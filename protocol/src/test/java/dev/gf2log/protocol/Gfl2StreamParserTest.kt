package dev.gf2log.protocol

import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParseEvent
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import dev.gf2log.protocol.model.PublicSkillItemsData
import dev.gf2log.protocol.model.WeaponsData
import dev.gf2log.protocol.model.WeaponModsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalUnsignedTypes::class)
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
        assertEquals(7001u, data.weapons.single().stcId)
        assertEquals(70u, data.weapons.single().level)
        assertEquals(125u, data.weapons.single().exp)
        assertEquals(17u, data.weapons.single().gunId)
        assertEquals(5u, data.weapons.single().breakTimes)
        assertEquals(3u, data.weapons.single().rawFlags)
        assertEquals(91u, data.weapons.single().weaponMods.single().id)
    }

    @Test
    fun coalescedMessagesAreParsedIndependently() {
        val parser = Gfl2StreamParser()
        val first = outerMessage(1, payload(Gfl2PayloadDecoder.TYPE_WEAPONS, weaponsPayload()))
        val second = outerMessage(
            2,
            payload(Gfl2PayloadDecoder.TYPE_PUBLIC_SKILL_ITEMS, publicSkillItemsPayload()),
        )

        val payloads = parser.accept(first + second).filterIsInstance<ParseEvent.Payload>()

        assertEquals(2, payloads.size)
        assertTrue(payloads[0].value.data is WeaponsData)
        val items = payloads[1].value.data as PublicSkillItemsData
        assertEquals(123uL, items.items.single().id)
        assertEquals(456u, items.items.single().stcId)
        assertEquals(12u, items.items.single().gunId)
        assertEquals(3uL, items.items.single().lockedFlags)
        assertTrue(items.items.single().isNew)
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
            payload(Gfl2PayloadDecoder.TYPE_WEAPON_MODS, weaponModsPayload()),
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload()),
            payload(Gfl2PayloadDecoder.TYPE_FORMATIONS, formationsPayload()),
        )

        val payloads = parser.accept(message).filterIsInstance<ParseEvent.Payload>()

        val mod = (payloads[0].value.data as WeaponModsData).mods.single()
        assertEquals(1000u, mod.id)
        assertEquals(17u, mod.stcId)
        assertEquals(3uL, mod.lockedFlags)
        assertEquals(8u, mod.modSuitPowerId)
        assertEquals(15u, mod.level)
        assertEquals(29u, mod.exp)
        assertEquals(0x0102uL, mod.suitFlags)

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
    fun platoonActivityPreservesTimestampsActionsAndNames() {
        val parser = Gfl2StreamParser()
        val message = outerMessage(
            101,
            payload(Gfl2PayloadDecoder.TYPE_PLATOON_ACTIVITY, platoonActivityPayload()),
        )

        val data = parser.accept(message).singlePayload().value.data as PlatoonActivityData

        assertEquals(1, data.summaries.size)
        assertEquals(9_007_199_254_740_993uL, data.summaries.single().id)
        assertEquals(802001u, data.summaries.single().actionId)
        assertEquals(1_700_100_000u, data.summaries.single().occurredAt)
        assertEquals(1u, data.summaries.single().count)
        assertEquals(2u, data.entries.single().kind)
        assertEquals("Test Member", data.entries.single().memberName)
    }

    @Test
    fun platoonUpdatesPreserveKindsExactUidsAndTimestamps() {
        val parser = Gfl2StreamParser()
        val message = outerMessage(
            102,
            payload(Gfl2PayloadDecoder.TYPE_PLATOON_UPDATES, platoonUpdatesPayload()),
        )

        val entry = (parser.accept(message).singlePayload().value.data as PlatoonUpdatesData)
            .entries
            .single()

        assertEquals(5u, entry.kind)
        assertEquals(1_700_200_000u, entry.occurredAt)
        assertEquals(listOf(1_111_111u, 2_222_222u), entry.members.map { it.uid })
        assertEquals(listOf("Leader", "Removed Member"), entry.members.map { it.name })
        assertEquals(listOf(1u, 1u), entry.members.map { it.role })
    }

    @Test
    fun capturedPlatoonProfilePreservesStableIdentityAndBannerIds() {
        val bytes = hex(
            "0aa30208b99a061205486f726e79181920e4de20325d72656a6563742073616e6974792c" +
                "20656d627261636520686f726e792e0a0a446f6e277420666f7267657420746f20646f20" +
                "796f75722047756e736d6f6b65206869747320616674657220636c616e6b696e67207468" +
                "6520646f6c6c73380e4801500358c2e9a3c40662910141696d696e6720666f7220546f70" +
                "20352520647572696e672047756e736d6f6b6520666f7220616c6c207265776172647321" +
                "20546f70203130252069732066696e6520746f6f2e0a0a52656372756974696e67206d65" +
                "6d626572732077686f2077696c6c20686974207477696365207065722064617920647572" +
                "696e672047756e736d6f6b652046726f6e746c696e652e68017a0302010a820106140e0b" +
                "110f1288010110914e2814",
        )

        val profile = Gfl2PayloadDecoder.decode(
            Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE,
            bytes,
        ) as PlatoonProfileData

        assertEquals(101_689u, profile.platoonId)
        assertEquals("Horny", profile.platoonName)
        assertEquals(25u, profile.level)
        assertTrue(profile.announcement.startsWith("reject sanity"))
        assertTrue(profile.declaration.startsWith("Aiming for Top 5%"))
        assertTrue(profile.joinPolicyFlag)
        // This live profile visibly uses the purple pentagonal frame (3) and first mark (1).
        assertEquals(3u, profile.bannerFrameId)
        assertEquals(1u, profile.bannerMarkId)
    }

    @Test
    fun oversizedBufferedInputIsRejectedAndStateIsReset() {
        val parser = Gfl2StreamParser(maximumBufferedBytes = 64)

        val events = parser.accept(ByteArray(65))

        assertEquals(1, events.filterIsInstance<ParseEvent.Warning>().size)
        val recovered = parser.accept(
            outerMessage(
                7,
                payload(Gfl2PayloadDecoder.TYPE_PUBLIC_SKILL_ITEMS, publicSkillItemsPayload()),
            ),
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

    @Test
    fun pendingContinuationIsEmittedWhenFlowFinishes() {
        val parser = Gfl2StreamParser()
        val first = outerMessage(
            0,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("Only", 7uL)),
        )

        assertTrue(parser.accept(first).isEmpty())
        val event = parser.finish().singlePayload()

        assertEquals(0, event.value.messageId)
        assertEquals(listOf("Only"), (event.value.data as GuildMembersData).members.map { it.name })
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun bufferOverflowAlsoClearsPendingContinuation() {
        val parser = Gfl2StreamParser(maximumBufferedBytes = 256)
        val first = outerMessage(
            0,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("Stale", 8uL)),
        )

        assertTrue(parser.accept(first).isEmpty())
        assertEquals(1, parser.accept(ByteArray(257)).filterIsInstance<ParseEvent.Warning>().size)
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun continuationCountOverflowQuarantinesTheTailAndParserRecovers() {
        val parser = Gfl2StreamParser(maximumPendingContinuations = 2)
        val continuation = outerMessage(
            0,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("Part", 8uL)),
        )

        assertTrue(parser.accept(continuation).isEmpty())
        assertTrue(parser.accept(continuation).isEmpty())
        val overflow = parser.accept(continuation)

        assertEquals(1, overflow.filterIsInstance<ParseEvent.Warning>().size)
        assertTrue(parser.accept(continuation).isEmpty())
        assertTrue(
            parser.accept(
                outerMessage(
                    42,
                    payload(
                        Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                        guildMembersPayload("Truncated tail", 10uL),
                    ),
                ),
            ).isEmpty(),
        )
        val recovered = parser.accept(
            outerMessage(
                7,
                payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("Fresh", 9uL)),
            ),
        ).singlePayload()
        assertEquals("Fresh", (recovered.value.data as GuildMembersData).members.single().name)
    }

    @Test
    fun continuationByteOverflowQuarantinesTheTail() {
        val body = guildMembersPayload("Part", 8uL)
        val parser = Gfl2StreamParser(maximumPendingPayloadBytes = body.size * 2 - 1)
        val continuation = outerMessage(
            0,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, body),
        )

        assertTrue(parser.accept(continuation).isEmpty())
        assertEquals(1, parser.accept(continuation).filterIsInstance<ParseEvent.Warning>().size)
        assertTrue(parser.accept(continuation).isEmpty())
        assertTrue(
            parser.accept(
                outerMessage(
                    42,
                    payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, body),
                ),
            ).isEmpty(),
        )
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun oversizedFirstContinuationIsQuarantinedBeforePendingStateIsCreated() {
        val body = guildMembersPayload("Oversized first", 8uL)
        val parser = Gfl2StreamParser(maximumPendingPayloadBytes = body.size - 1)

        val warning = parser.accept(
            outerMessage(
                0,
                payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, body),
            ),
        )

        assertEquals(1, warning.filterIsInstance<ParseEvent.Warning>().size)
        assertTrue(
            parser.accept(
                outerMessage(
                    42,
                    payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, body),
                ),
            ).isEmpty(),
        )
        assertTrue(parser.finish().isEmpty())
    }

    @Test
    fun malformedTerminalFrameClearsQuarantineBeforeDecoding() {
        val parser = Gfl2StreamParser(maximumPendingContinuations = 1)
        val continuation = outerMessage(
            0,
            payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, guildMembersPayload("Part", 8uL)),
        )

        assertTrue(parser.accept(continuation).isEmpty())
        assertEquals(1, parser.accept(continuation).filterIsInstance<ParseEvent.Warning>().size)
        assertTrue(
            parser.accept(
                outerMessage(
                    42,
                    payload(Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS, byteArrayOf(0x0A, 0x7F)),
                ),
            ).isEmpty(),
        )

        val recovered = parser.accept(
            outerMessage(
                43,
                payload(
                    Gfl2PayloadDecoder.TYPE_GUILD_MEMBERS,
                    guildMembersPayload("Recovered", 9uL),
                ),
            ),
        ).singlePayload()
        assertEquals("Recovered", (recovered.value.data as GuildMembersData).members.single().name)
    }

    @Test
    fun unknownPayloadsRemainObservableWithoutRetainingTheirBytes() {
        val parser = Gfl2StreamParser()
        val events = parser.accept(outerMessage(51, payload(24567, byteArrayOf(1, 2, 3))))

        assertEquals(
            ParseEvent.UnknownPayload(
                messageId = 51,
                payloadType = 24567,
                payloadBytes = 3,
                isEndOfMessage = true,
            ),
            events.single(),
        )
    }

    private fun weaponsPayload(): ByteArray {
        val weaponMod = uintField(1, 91uL) + uintField(2, 17uL)
        val weapon = uintField(1, 42uL) +
            uintField(2, 7001uL) +
            uintField(3, 70uL) +
            uintField(4, 125uL) +
            uintField(5, 17uL) +
            uintField(6, 5uL) +
            uintField(7, 3uL) +
            messageField(8, weaponMod)
        return messageField(1, weapon)
    }

    private fun publicSkillItemsPayload(): ByteArray {
        val key = uintField(1, 123uL) +
            uintField(2, 456uL) +
            uintField(3, 12uL) +
            uintField(4, 3uL) +
            uintField(5, 1uL)
        return messageField(1, key)
    }

    private fun weaponModsPayload(): ByteArray {
        val attachment = uintField(1, 1000uL) +
            uintField(2, 17uL) +
            uintField(3, 3uL) +
            uintField(4, 8uL) +
            uintField(5, 15uL) +
            uintField(6, 29uL) +
            uintField(7, 0x0102uL)
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

    private fun platoonActivityPayload(): ByteArray {
        val summary = uintField(1, 9_007_199_254_740_993uL) +
            uintField(2, 802001uL) +
            uintField(3, 1_700_100_000uL) +
            uintField(4, 1uL)
        val entry = uintField(2, 2uL) +
            uintField(3, 1_700_100_000uL) +
            uintField(4, 802001uL) +
            stringField(5, "Test Member")
        return messageField(1, summary) + messageField(2, entry)
    }

    private fun platoonUpdatesPayload(): ByteArray {
        fun member(uid: ULong, name: String): ByteArray =
            uintField(1, 1uL) + uintField(2, uid) + stringField(3, name)

        val entry = uintField(1, 5uL) +
            messageField(2, member(1_111_111uL, "Leader")) +
            messageField(2, member(2_222_222uL, "Removed Member")) +
            uintField(3, 1_700_200_000uL)
        return messageField(1, entry)
    }

    private fun List<ParseEvent>.singlePayload(): ParseEvent.Payload =
        filterIsInstance<ParseEvent.Payload>().single()

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }
        .toByteArray()

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
