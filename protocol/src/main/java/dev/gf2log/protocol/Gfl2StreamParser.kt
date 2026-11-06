package dev.gf2log.protocol

import dev.gf2log.protocol.internal.ByteAccumulator
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GameData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParseEvent
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.PlatoonActivityData
import dev.gf2log.protocol.model.PlatoonProfileData
import dev.gf2log.protocol.model.PlatoonUpdatesData
import dev.gf2log.protocol.model.PublicSkillItemsData
import dev.gf2log.protocol.model.WeaponModsData
import dev.gf2log.protocol.model.WeaponsData

class Gfl2StreamParser(
    maximumBufferedBytes: Int = DEFAULT_MAXIMUM_BUFFERED_BYTES,
    private val maximumPendingPayloadBytes: Int = DEFAULT_MAXIMUM_PENDING_PAYLOAD_BYTES,
    private val maximumPendingContinuations: Int = DEFAULT_MAXIMUM_PENDING_CONTINUATIONS,
    private val maximumPayloadElements: Int = Gfl2PayloadDecoder.MAX_DECODED_ELEMENTS,
) {
    init {
        require(maximumPendingPayloadBytes > 0) { "Pending payload byte limit must be positive" }
        require(maximumPendingContinuations > 0) { "Pending continuation limit must be positive" }
        require(maximumPayloadElements > 0) { "Payload element limit must be positive" }
    }

    private val buffer = ByteAccumulator(maximumBufferedBytes)
    private var pendingPayload: PendingPayload? = null
    private var quarantinedPayload: QuarantinedPayload? = null

    fun accept(bytes: ByteArray): List<ParseEvent> {
        val events = mutableListOf<ParseEvent>()
        try {
            buffer.append(bytes)
        } catch (error: ProtocolException) {
            reset()
            return listOf(ParseEvent.Warning(error.message ?: "Stream buffer overflow"))
        }

        while (buffer.available >= OUTER_HEADER_SIZE) {
            // A 16-bit body length can never exceed one bounded outer message.
            val messageSize = buffer.peekUInt16Le(3) + OUTER_HEADER_SIZE
            if (buffer.available < messageSize) break

            // Parsed in place: decoding copies only recognized payload bodies.
            parseMessage(buffer.peekUInt24Le(0), buffer.storage, buffer.readOffset, messageSize, events)
            buffer.discard(messageSize)
        }

        return events
    }

    fun finish(): List<ParseEvent> {
        val events = mutableListOf<ParseEvent>()
        emitPending(events)
        quarantinedPayload = null
        buffer.clear()
        return events
    }

    fun reset() {
        buffer.clear()
        pendingPayload = null
        quarantinedPayload = null
    }

    private fun parseMessage(
        messageId: Int,
        bytes: ByteArray,
        start: Int,
        size: Int,
        events: MutableList<ParseEvent>,
    ) {
        val end = start + size
        var offset = start + OUTER_HEADER_SIZE
        while (offset < end) {
            if (end - offset < PAYLOAD_HEADER_SIZE) {
                events += ParseEvent.Warning(
                    "Message $messageId ends with an incomplete payload header",
                )
                return
            }

            val type = readUInt16Le(bytes, offset)
            val payloadSize = readUInt16Le(bytes, offset + 2) + PAYLOAD_HEADER_SIZE
            val supported = Gfl2PayloadDecoder.isSupported(type)
            if (payloadSize > end - offset) {
                events += ParseEvent.Warning(
                    "Message $messageId payload $type declares $payloadSize bytes with ${end - offset} remaining",
                )
                // The truncated frame is necessarily the last one in this message.
                if (supported) discardDataset(type, messageId, isEndOfMessage = true, events)
                return
            }

            val isEndOfMessage = offset + payloadSize >= end
            if (!supported) {
                emitPending(events)
                quarantinedPayload = null
                events += ParseEvent.UnknownPayload(
                    messageId = messageId,
                    payloadType = type,
                    payloadBytes = payloadSize - PAYLOAD_HEADER_SIZE,
                    isEndOfMessage = isEndOfMessage,
                )
            } else if (!consumeQuarantinedPayload(type, messageId, isEndOfMessage)) {
                val payload = bytes.copyOfRange(offset + PAYLOAD_HEADER_SIZE, offset + payloadSize)
                try {
                    val decoded = requireNotNull(
                        Gfl2PayloadDecoder.decodeCounted(type, payload, maximumPayloadElements),
                    )
                    acceptDecodedPayload(messageId, type, isEndOfMessage, payload.size, decoded, events)
                } catch (error: ProtocolException) {
                    events += ParseEvent.Warning(
                        "Unable to decode payload $type in message $messageId: ${error.message}",
                    )
                    discardDataset(type, messageId, isEndOfMessage, events)
                }
            }

            offset += payloadSize
        }
    }

    private fun acceptDecodedPayload(
        messageId: Int,
        type: Int,
        isEndOfMessage: Boolean,
        payloadBytes: Int,
        decoded: Gfl2PayloadDecoder.Decoded,
        events: MutableList<ParseEvent>,
    ) {
        val data = decoded.data
        val pending = pendingPayload
        if (pending != null) {
            val continued = pending.type == type && continues(pending.previousMessageId, messageId)
            if (continued && isMergeable(pending.parts.first(), data)) {
                val accumulatedBytes = pending.accumulatedPayloadBytes + payloadBytes.toLong()
                val continuationCount = pending.continuationCount + 1
                val accumulatedElements = pending.accumulatedElements + decoded.elements
                if (
                    accumulatedBytes > maximumPendingPayloadBytes ||
                    continuationCount > maximumPendingContinuations ||
                    accumulatedElements > maximumPayloadElements
                ) {
                    pendingPayload = null
                    quarantinedPayload = if (isTerminal(messageId, isEndOfMessage)) {
                        null
                    } else {
                        QuarantinedPayload(type, messageId)
                    }
                    events += ParseEvent.Warning(
                        "Discarded payload $type continuation sequence exceeding parser limits",
                    )
                    return
                }
                pending.parts += data
                pending.previousMessageId = messageId
                pending.isEndOfMessage = isEndOfMessage
                pending.accumulatedPayloadBytes = accumulatedBytes
                pending.continuationCount = continuationCount
                pending.accumulatedElements = accumulatedElements
                if (isTerminal(messageId, isEndOfMessage)) emitPending(events)
                return
            }
            // A different dataset, or a continuation of a single-object payload such as
            // 21905, keeps the earlier payload intact as its own event.
            emitPending(events)
        }

        if (isTerminal(messageId, isEndOfMessage)) {
            events += ParseEvent.Payload(ParsedPayload(messageId, type, true, data))
        } else {
            if (payloadBytes > maximumPendingPayloadBytes) {
                quarantinedPayload = QuarantinedPayload(type, messageId)
                events += ParseEvent.Warning(
                    "Discarded payload $type continuation sequence exceeding parser limits",
                )
                return
            }
            pendingPayload = PendingPayload(
                type = type,
                previousMessageId = messageId,
                isEndOfMessage = isEndOfMessage,
                parts = mutableListOf(data),
                accumulatedPayloadBytes = payloadBytes.toLong(),
                continuationCount = 1,
                accumulatedElements = decoded.elements,
            )
        }
    }

    /**
     * Drops the logical dataset a malformed frame belongs to. Merging its neighbours across the
     * hole would publish an incomplete roster as if it were complete, so a continued pending
     * payload is discarded and the dataset's remaining fragments are quarantined. A payload that
     * cannot be merged, such as 21905, owns each frame and keeps its earlier, valid frames.
     */
    private fun discardDataset(
        type: Int,
        messageId: Int,
        isEndOfMessage: Boolean,
        events: MutableList<ParseEvent>,
    ) {
        val mergeable = type != Gfl2PayloadDecoder.TYPE_PLATOON_PROFILE
        val pending = pendingPayload
        if (
            mergeable && pending != null && pending.type == type &&
            continues(pending.previousMessageId, messageId)
        ) {
            pendingPayload = null
        } else {
            emitPending(events)
        }
        if (!mergeable) return
        quarantinedPayload = if (isTerminal(messageId, isEndOfMessage)) {
            null
        } else {
            QuarantinedPayload(type, messageId)
        }
    }

    private fun consumeQuarantinedPayload(
        type: Int,
        messageId: Int,
        isEndOfMessage: Boolean,
    ): Boolean {
        val quarantined = quarantinedPayload ?: return false
        if (quarantined.type == type && continues(quarantined.previousMessageId, messageId)) {
            quarantined.previousMessageId = messageId
            if (isTerminal(messageId, isEndOfMessage)) quarantinedPayload = null
            return true
        }
        quarantinedPayload = null
        return false
    }

    private fun emitPending(events: MutableList<ParseEvent>) {
        val pending = pendingPayload ?: return
        events += ParseEvent.Payload(
            ParsedPayload(
                messageId = pending.previousMessageId,
                payloadType = pending.type,
                isEndOfMessage = pending.isEndOfMessage,
                data = concatenate(pending.parts),
            ),
        )
        pendingPayload = null
    }

    /** False for payloads that are not lists and therefore cannot be concatenated. */
    private fun isMergeable(first: GameData, second: GameData): Boolean =
        first !is PlatoonProfileData && first.javaClass == second.javaClass

    /**
     * Concatenates a dataset's frames once, when it is emitted, so a long continuation sequence
     * copies each element a single time instead of once per continuation.
     */
    private fun concatenate(parts: List<GameData>): GameData {
        val first = parts.first()
        if (parts.size == 1) return first
        return when (first) {
            is WeaponsData -> WeaponsData(parts.flatMap { (it as WeaponsData).weapons })
            is WeaponModsData -> WeaponModsData(parts.flatMap { (it as WeaponModsData).mods })
            is PublicSkillItemsData ->
                PublicSkillItemsData(parts.flatMap { (it as PublicSkillItemsData).items })
            is GuildMembersData -> GuildMembersData(parts.flatMap { (it as GuildMembersData).members })
            is PlatoonActivityData -> PlatoonActivityData(
                summaries = parts.flatMap { (it as PlatoonActivityData).summaries },
                entries = parts.flatMap { (it as PlatoonActivityData).entries },
            )
            is PlatoonUpdatesData ->
                PlatoonUpdatesData(parts.flatMap { (it as PlatoonUpdatesData).entries })
            is FormationsData -> FormationsData(parts.flatMap { (it as FormationsData).formations })
            is PlatoonProfileData -> error("A Platoon profile is never merged")
        }
    }

    /** The reference continuation rule: after message id 0, or within the same message id. */
    private fun continues(previousMessageId: Int, messageId: Int): Boolean =
        previousMessageId == 0 || previousMessageId == messageId

    /** A frame with a non-zero message id that ends its message completes a logical payload. */
    private fun isTerminal(messageId: Int, isEndOfMessage: Boolean): Boolean =
        messageId != 0 && isEndOfMessage

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val OUTER_HEADER_SIZE = 5
        const val PAYLOAD_HEADER_SIZE = 4
        const val DEFAULT_MAXIMUM_BUFFERED_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAXIMUM_PENDING_PAYLOAD_BYTES = 2 * 1024 * 1024
        const val DEFAULT_MAXIMUM_PENDING_CONTINUATIONS = 64
    }

    private class PendingPayload(
        val type: Int,
        var previousMessageId: Int,
        var isEndOfMessage: Boolean,
        val parts: MutableList<GameData>,
        var accumulatedPayloadBytes: Long,
        var continuationCount: Int,
        var accumulatedElements: Int,
    )

    private data class QuarantinedPayload(
        val type: Int,
        var previousMessageId: Int,
    )
}
