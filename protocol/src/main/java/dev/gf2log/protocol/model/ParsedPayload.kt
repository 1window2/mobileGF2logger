package dev.gf2log.protocol.model

data class ParsedPayload(
    val messageId: Int,
    val payloadType: Int,
    val isEndOfMessage: Boolean,
    val data: GameData,
)

sealed interface ParseEvent {
    data class Payload(val value: ParsedPayload) : ParseEvent
    data class Warning(val description: String) : ParseEvent
    data class UnknownPayload(
        val messageId: Int,
        val payloadType: Int,
        val payloadBytes: Int,
        val isEndOfMessage: Boolean,
    ) : ParseEvent
}
