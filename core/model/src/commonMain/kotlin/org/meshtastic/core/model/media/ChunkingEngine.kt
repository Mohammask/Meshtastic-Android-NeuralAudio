/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.model.media

import kotlin.math.ceil
import kotlin.random.Random

/**
 * Parsed representation of the 3-byte transport header. Wire layout: [Msg_ID (1 Byte) | Total_Chunks (1 Byte) |
 * Chunk_Index (1 Byte)] + Payload (max 205 bytes)
 */
data class MediaChunkHeader(
    val messageId: Byte,
    val totalChunks: Int,
    val chunkIndex: Int,
    val payloadType: Byte = ChunkingEngine.TYPE_AUDIO_CODEC2,
    val isLivePtt: Boolean = false,
)

/** Parsed representation of a 3-byte Selective NACK packet. Wire layout: [Msg_ID (1 Byte) | Missing_Mask (2 Bytes)] */
data class NackPacket(
    val messageId: Byte,
    val missingMask: Int, // 16-bit bitmask where set bits indicate missing chunk indices
)

/** Represents a single decoded media chunk. */
data class MediaChunk(val header: MediaChunkHeader, val data: ByteArray, val rawBytes: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaChunk) return false
        return header == other.header && rawBytes.contentEquals(other.rawBytes)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + rawBytes.contentHashCode()
}

/**
 * High-efficiency LoRa framing engine using a 3-byte overhead per chunk: `[Msg_ID (1 Byte) | Total_Chunks (1 Byte) |
 * Chunk_Index (1 Byte)]` + Payload (max 205 bytes). Supports Selective NACK packets: `[Msg_ID (1 Byte) | Missing_Mask
 * (2 Bytes)]`.
 */
object ChunkingEngine {

    const val HEADER_SIZE = 3
    const val DEFAULT_MAX_CHUNK_DATA_SIZE = 205 // Max 205 bytes + 3-byte header = 208 bytes <= 237B MTU
    const val MAX_CHUNKS = 16 // 16-bit mask supports indices 0..15

    /**
     * Bit 7 of the chunk-index byte (header byte 2). Chunk indices only use bits 0..3, so the high bit is free. When
     * set, EVERY chunk of the transmission (including NACK retransmissions and chunks that arrive after the first one
     * was lost) self-identifies as ephemeral Live PTT audio. Receivers therefore never have to guess liveness from
     * session state or from the content of chunk 0, which is what used to let stray live chunks leak into chat history.
     */
    const val LIVE_PTT_INDEX_FLAG = 0x80
    private const val CHUNK_INDEX_MASK = 0x7F

    // Payload Types (embedded in Byte 1 high nibble)
    const val TYPE_AUDIO_CODEC2: Byte = 0x10
    const val TYPE_IMAGE_1BIT: Byte = 0x20
    const val TYPE_PTT_CONTROL: Byte = 0x30
    const val TYPE_PC_COMMAND: Byte = 0x40
    const val TYPE_AUDIO_WAVTOKENIZER: Byte = 0x50

    /**
     * Splits a compressed [payload] into a list of wire-ready byte arrays, each prepended with the 3-byte header.
     *
     * @param payload Complete compressed byte payload.
     * @param payloadType The media type identifier.
     * @param isLivePtt Whether this audio packet belongs to an active synchronous Walkie-Talkie session.
     * @param maxChunkDataSize Maximum data payload per chunk (default 205 bytes).
     * @param messageId Unique message ID (0..255).
     * @return List of wire chunks ready to send over `PortNum.PRIVATE_APP`.
     */
    fun split(
        payload: ByteArray,
        payloadType: Byte = TYPE_AUDIO_CODEC2,
        isLivePtt: Boolean = false,
        maxChunkDataSize: Int = DEFAULT_MAX_CHUNK_DATA_SIZE,
        messageId: Int = Random.nextInt(1, 255),
    ): List<ByteArray> {
        val msgIdByte = (messageId and 0xFF).toByte()
        val liveBit = if (isLivePtt) LIVE_PTT_INDEX_FLAG else 0
        if (payload.isEmpty()) {
            val chunk = ByteArray(HEADER_SIZE)
            chunk[0] = msgIdByte
            chunk[1] = encodeTotalChunksByte(1, payloadType)
            chunk[2] = liveBit.toByte()
            return listOf(chunk)
        }

        val effectiveMaxChunkSize =
            if (payloadType == TYPE_AUDIO_WAVTOKENIZER) {
                ((maxChunkDataSize / 3) * 3).coerceAtLeast(3)
            } else {
                maxChunkDataSize
            }

        val totalChunks =
            ceil(payload.size.toDouble() / effectiveMaxChunkSize.toDouble()).toInt().coerceIn(1, MAX_CHUNKS)
        val chunks = ArrayList<ByteArray>(totalChunks)
        val totalChunksByte = encodeTotalChunksByte(totalChunks, payloadType)

        for (index in 0 until totalChunks) {
            val offset = index * effectiveMaxChunkSize
            val length = (payload.size - offset).coerceAtMost(effectiveMaxChunkSize)

            val chunk = ByteArray(HEADER_SIZE + length)
            chunk[0] = msgIdByte
            chunk[1] = totalChunksByte
            chunk[2] = (index or liveBit).toByte()
            payload.copyInto(chunk, destinationOffset = HEADER_SIZE, startIndex = offset, endIndex = offset + length)
            chunks.add(chunk)
        }

        return chunks
    }

    private fun encodeTotalChunksByte(totalChunks: Int, payloadType: Byte): Byte {
        val typeNibble = payloadType.toInt() and 0xF0
        val chunksNibble = if (totalChunks == 16) 0 else (totalChunks and 0x0F)
        return (typeNibble or chunksNibble).toByte()
    }

    /**
     * Parses a raw incoming wire chunk.
     *
     * @param rawBytes Raw payload bytes from `PortNum.PRIVATE_APP`.
     * @return Decoded [MediaChunk], or null if invalid or if this is a NACK packet.
     */
    fun parse(rawBytes: ByteArray): MediaChunk? {
        if (rawBytes.size < HEADER_SIZE) return null
        if (isNackPacket(rawBytes)) return null

        val messageId = rawBytes[0]
        val rawByte1 = rawBytes[1].toInt() and 0xFF
        val payloadType = (rawByte1 and 0xF0).toByte().takeIf { it != 0.toByte() } ?: TYPE_AUDIO_CODEC2
        val chunksNibble = rawByte1 and 0x0F
        val totalChunks =
            if (chunksNibble == 0 && (rawByte1 and 0xF0 != 0)) {
                16
            } else if (chunksNibble != 0) {
                chunksNibble
            } else {
                rawByte1.coerceIn(1, MAX_CHUNKS)
            }
        val rawIndexByte = rawBytes[2].toInt() and 0xFF
        val isLivePtt = (rawIndexByte and LIVE_PTT_INDEX_FLAG) != 0
        val chunkIndex = rawIndexByte and CHUNK_INDEX_MASK

        if (totalChunks <= 0 || chunkIndex >= totalChunks) {
            return null
        }

        val data = rawBytes.copyOfRange(HEADER_SIZE, rawBytes.size)
        val header =
            MediaChunkHeader(
                messageId = messageId,
                totalChunks = totalChunks,
                chunkIndex = chunkIndex,
                payloadType = payloadType,
                isLivePtt = isLivePtt,
            )

        return MediaChunk(header = header, data = data, rawBytes = rawBytes)
    }

    /** Checks if [rawBytes] represents a 3-byte Selective NACK packet. */
    fun isNackPacket(rawBytes: ByteArray): Boolean {
        if (rawBytes.size != 3) return false
        val missingMask = ((rawBytes[1].toInt() and 0xFF) shl 8) or (rawBytes[2].toInt() and 0xFF)
        return missingMask != 0
    }

    /** Parses a 3-byte Selective NACK packet. */
    fun parseNack(rawBytes: ByteArray): NackPacket? {
        if (rawBytes.size != 3) return null
        val msgId = rawBytes[0]
        val missingMask = ((rawBytes[1].toInt() and 0xFF) shl 8) or (rawBytes[2].toInt() and 0xFF)
        if (missingMask == 0) return null
        return NackPacket(msgId, missingMask)
    }

    /** Creates a 3-byte Selective NACK packet: `[Msg_ID (1B) | Missing_Mask (2B)]`. */
    fun createNack(messageId: Byte, missingMask: Int): ByteArray {
        val packet = ByteArray(3)
        packet[0] = messageId
        packet[1] = ((missingMask shr 8) and 0xFF).toByte()
        packet[2] = (missingMask and 0xFF).toByte()
        return packet
    }
}
