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
}
