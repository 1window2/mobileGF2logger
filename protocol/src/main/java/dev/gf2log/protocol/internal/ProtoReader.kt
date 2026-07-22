package dev.gf2log.protocol.internal

import dev.gf2log.protocol.ProtocolException

internal data class ProtoField(
    val number: Int,
    val wireType: Int,
)

internal class ProtoReader(
    private val bytes: ByteArray,
) {
    private var position = 0

    val exhausted: Boolean
        get() = position == bytes.size

    fun nextField(): ProtoField? {
        if (exhausted) return null
        val tag = readVarint()
        val number = (tag shr 3).toInt()
        val wireType = (tag and 0x07u).toInt()
        if (number <= 0) throw ProtocolException("Invalid protobuf field number: $number")
        return ProtoField(number, wireType)
    }

    fun readUInt(field: ProtoField): ULong {
        requireWireType(field, 0)
        return readVarint()
    }

    fun readBoolean(field: ProtoField): Boolean = readUInt(field) != 0uL

    fun readString(field: ProtoField): String = readBytes(field).toString(Charsets.UTF_8)

    fun readMessage(field: ProtoField): ProtoReader = ProtoReader(readBytes(field))

    fun readRepeatedUInt(field: ProtoField): List<ULong> = when (field.wireType) {
        0 -> listOf(readVarint())
        2 -> {
            val packed = ProtoReader(readLengthDelimited())
            buildList {
                while (!packed.exhausted) add(packed.readVarint())
            }
        }
        else -> throw ProtocolException(
            "Field ${field.number} has unsupported repeated-integer wire type ${field.wireType}",
        )
    }

    fun skip(field: ProtoField) {
        when (field.wireType) {
            0 -> readVarint()
            1 -> advance(8)
            2 -> advance(readVarintLength())
            5 -> advance(4)
            else -> throw ProtocolException("Unsupported protobuf wire type ${field.wireType}")
        }
    }

    private fun readBytes(field: ProtoField): ByteArray {
        requireWireType(field, 2)
        return readLengthDelimited()
    }

    private fun readLengthDelimited(): ByteArray {
        val length = readVarintLength()
        val result = bytes.copyOfRange(position, position + length)
        position += length
        return result
    }

    private fun readVarintLength(): Int {
        val length = readVarint()
        if (length > Int.MAX_VALUE.toULong()) {
            throw ProtocolException("Protobuf length is too large: $length")
        }
        val result = length.toInt()
        if (result > bytes.size - position) {
            throw ProtocolException(
                "Protobuf field declares $result bytes with only ${bytes.size - position} remaining",
            )
        }
        return result
    }

    private fun readVarint(): ULong {
        var result = 0uL
        for (index in 0 until 10) {
            if (position >= bytes.size) throw ProtocolException("Truncated protobuf varint")
            val value = bytes[position++].toInt() and 0xFF
            if (index == 9 && value > 1) throw ProtocolException("Protobuf varint overflows 64 bits")
            result = result or ((value and 0x7F).toULong() shl (index * 7))
            if ((value and 0x80) == 0) return result
        }
        throw ProtocolException("Invalid protobuf varint")
    }

    private fun advance(count: Int) {
        if (count > bytes.size - position) throw ProtocolException("Truncated protobuf field")
        position += count
    }

    private fun requireWireType(field: ProtoField, expected: Int) {
        if (field.wireType != expected) {
            throw ProtocolException(
                "Field ${field.number} has wire type ${field.wireType}; expected $expected",
            )
        }
    }
}
