package dev.gf2log.protocol

import dev.gf2log.protocol.internal.ByteAccumulator
import dev.gf2log.protocol.model.AttachmentsData
import dev.gf2log.protocol.model.CommonKeysData
import dev.gf2log.protocol.model.FormationsData
import dev.gf2log.protocol.model.GameData
import dev.gf2log.protocol.model.GuildMembersData
import dev.gf2log.protocol.model.ParseEvent
import dev.gf2log.protocol.model.ParsedPayload
import dev.gf2log.protocol.model.WeaponsData

class Gfl2StreamParser(
    maximumBufferedBytes: Int = DEFAULT_MAXIMUM_BUFFERED_BYTES,
) {
    private val buffer = ByteAccumulator(maximumBufferedBytes)
    private var pendingPayload: PendingPayload? = null

    fun accept(bytes: ByteArray): List<ParseEvent> {
        val events = mutableListOf<ParseEvent>()
        try {
            buffer.append(bytes)
        } catch (error: ProtocolException) {
            buffer.clear()
            return listOf(ParseEvent.Warning(error.message ?: "Stream buffer overflow"))
        }

        while (buffer.available >= OUTER_HEADER_SIZE) {
            val messageId = buffer.peekUInt24Le(0)
            val messageSize = buffer.peekUInt16Le(3) + OUTER_HEADER_SIZE

            if (messageSize > MAXIMUM_MESSAGE_SIZE) {
                buffer.discard(1)
                events += ParseEvent.Warning("Discarded an invalid message header declaring $messageSize bytes")
                continue
            }
            if (buffer.available < messageSize) break

            val message = buffer.read(messageSize)
            parseMessage(messageId, message, events)
        }

        return events
    }

    fun reset() {
        buffer.clear()
        pendingPayload = null
    }

    private fun parseMessage(
        messageId: Int,
        message: ByteArray,
        events: MutableList<ParseEvent>,
    ) {
        var offset = OUTER_HEADER_SIZE
        while (offset < message.size) {
            if (message.size - offset < PAYLOAD_HEADER_SIZE) {
                events += ParseEvent.Warning(
                    "Message $messageId ends with an incomplete payload header",
                )
                return
            }

            val type = readUInt16Le(message, offset)
            val payloadSize = readUInt16Le(message, offset + 2) + PAYLOAD_HEADER_SIZE
            if (payloadSize > message.size - offset) {
                events += ParseEvent.Warning(
                    "Message $messageId payload $type declares $payloadSize bytes with ${message.size - offset} remaining",
                )
                return
            }

            val isEndOfMessage = offset + payloadSize >= message.size
            if (type in Gfl2PayloadDecoder.supportedTypes) {
                val payload = message.copyOfRange(
                    offset + PAYLOAD_HEADER_SIZE,
                    offset + payloadSize,
                )
                try {
                    val decoded = Gfl2PayloadDecoder.decode(type, payload)
                    if (decoded != null) {
                        acceptDecodedPayload(messageId, type, isEndOfMessage, decoded, events)
                    }
                } catch (error: ProtocolException) {
                    events += ParseEvent.Warning(
                        "Unable to decode payload $type in message $messageId: ${error.message}",
                    )
                }
            } else {
                emitPending(events)
            }

            offset += payloadSize
        }
    }

    private fun acceptDecodedPayload(
        messageId: Int,
        type: Int,
        isEndOfMessage: Boolean,
        data: GameData,
        events: MutableList<ParseEvent>,
    ) {
        val pending = pendingPayload
        if (pending != null) {
            if (pending.type == type &&
                (pending.previousMessageId == 0 || pending.previousMessageId == messageId)
            ) {
                pending.data = merge(pending.data, data)
                pending.previousMessageId = messageId
                pending.isEndOfMessage = isEndOfMessage
                if (messageId != 0 && isEndOfMessage) emitPending(events)
                return
            }
            emitPending(events)
        }

        if (messageId != 0 && isEndOfMessage) {
            events += ParseEvent.Payload(ParsedPayload(messageId, type, true, data))
        } else {
            pendingPayload = PendingPayload(type, messageId, isEndOfMessage, data)
        }
    }

    private fun emitPending(events: MutableList<ParseEvent>) {
        val pending = pendingPayload ?: return
        events += ParseEvent.Payload(
            ParsedPayload(
                messageId = pending.previousMessageId,
                payloadType = pending.type,
                isEndOfMessage = pending.isEndOfMessage,
                data = pending.data,
            ),
        )
        pendingPayload = null
    }

    private fun merge(first: GameData, second: GameData): GameData = when {
        first is WeaponsData && second is WeaponsData -> WeaponsData(first.weapons + second.weapons)
        first is AttachmentsData && second is AttachmentsData ->
            AttachmentsData(first.attachments + second.attachments)
        first is CommonKeysData && second is CommonKeysData -> CommonKeysData(first.keys + second.keys)
        first is GuildMembersData && second is GuildMembersData ->
            GuildMembersData(first.members + second.members)
        first is FormationsData && second is FormationsData ->
            FormationsData(first.formations + second.formations)
        else -> second
    }

    private fun readUInt16Le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val OUTER_HEADER_SIZE = 5
        const val PAYLOAD_HEADER_SIZE = 4
        const val MAXIMUM_MESSAGE_SIZE = 65_540
        const val DEFAULT_MAXIMUM_BUFFERED_BYTES = 2 * 1024 * 1024
    }

    private data class PendingPayload(
        val type: Int,
        var previousMessageId: Int,
        var isEndOfMessage: Boolean,
        var data: GameData,
    )
}
