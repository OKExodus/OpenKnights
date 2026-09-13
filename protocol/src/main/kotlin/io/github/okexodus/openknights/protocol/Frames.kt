package io.github.okexodus.openknights.protocol

import java.io.ByteArrayOutputStream

/**
 * The game's TCP framing: a little-endian u16 length that includes the 4-byte header, a u16 opcode, then the payload.
 * A length of 0xFFFF marks a continuation chunk of 0xFFFE wire bytes (0xFFFA payload bytes); the message ends with the
 * next normal frame, and the opcode of the latest continuation chunk is the message's opcode.
 */
class Frame(val opcode: Int, val payload: ByteArray, val chunks: Int = 1) {
    override fun toString(): String = "Frame(opcode=$opcode, ${payload.size} bytes, chunks=$chunks)"
}

object Frames {
    const val MAX_WIRE_SIZE = 0xFFFE
    const val CONTINUATION_PAYLOAD = 0xFFFA

    fun encode(opcode: Int, payload: ByteArray): ByteArray {
        require(opcode in 0..0xFFFF) { "Opcode must fit uint16" }
        val out = ByteArrayOutputStream(payload.size + 4 + 4 * (payload.size / CONTINUATION_PAYLOAD))
        var offset = 0
        while (payload.size - offset > CONTINUATION_PAYLOAD) {
            header(out, 0xFFFF, opcode)
            out.write(payload, offset, CONTINUATION_PAYLOAD)
            offset += CONTINUATION_PAYLOAD
        }
        val remaining = payload.size - offset
        header(out, remaining + 4, opcode)
        out.write(payload, offset, remaining)
        return out.toByteArray()
    }

    private fun header(out: ByteArrayOutputStream, length: Int, opcode: Int) {
        out.write(length and 0xFF); out.write(length ushr 8)
        out.write(opcode and 0xFF); out.write(opcode ushr 8)
    }
}

/** Incremental decoder; `feed` returns the complete messages. Mirrors the reference's decoder, limits included. */
class FrameDecoder(private val maxMessageSize: Int = 32 * 1024 * 1024) {
    private var buffer = ByteArray(0)
    private var partial = ByteArrayOutputStream()
    private var partialOpcode: Int? = null
    private var chunks = 0

    fun feed(data: ByteArray): List<Frame> {
        buffer += data
        val frames = ArrayList<Frame>()
        var offset = 0
        while (buffer.size - offset >= 4) {
            val length = u16(buffer, offset)
            val opcode = u16(buffer, offset + 2)
            if (length < 4) throw ProtocolException("Invalid wire length $length")
            val wireSize = if (length == 0xFFFF) Frames.MAX_WIRE_SIZE else length
            if (buffer.size - offset < wireSize) break
            val start = offset + 4
            val end = offset + wireSize
            offset += wireSize
            if (partial.size() + (end - start) > maxMessageSize) throw ProtocolException("Logical message exceeds configured limit")
            if (length == 0xFFFF) {
                partialOpcode = opcode
                partial.write(buffer, start, end - start)
                chunks++
            } else if (partialOpcode != null) {
                partial.write(buffer, start, end - start)
                frames.add(Frame(partialOpcode!!, partial.toByteArray(), chunks + 1))
                partial = ByteArrayOutputStream()
                partialOpcode = null
                chunks = 0
            } else {
                frames.add(Frame(opcode, buffer.copyOfRange(start, end)))
            }
        }
        buffer = buffer.copyOfRange(offset, buffer.size)
        return frames
    }

    /** The stream ended: anything left over is a truncated frame or an unfinished continuation. */
    fun finish() {
        if (buffer.isNotEmpty() || partialOpcode != null) throw ProtocolException("Truncated frame or unfinished continuation")
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
}

/** A malformed or unexpected wire value (the reference raises ValueError in the same places). */
class ProtocolException(message: String) : IllegalArgumentException(message)
