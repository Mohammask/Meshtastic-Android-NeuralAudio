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

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.meshtastic.core.model.DataPacket

/** Result of a completely received and concatenated multi-chunk media transmission. */
data class CompletedMediaPayload(
    val messageId: Int,
    val senderId: String,
    val payloadType: Byte,
    val isLivePtt: Boolean,
    val completePayload: ByteArray,
    val channel: Int = 0,
    val sampleDataPacket: DataPacket? = null,
    val hasConcealedLoss: Boolean = false,
    val missingChunks: Set<Int> = emptySet(),
    val totalChunks: Int = 1,
    val chunkDataMap: Map<Int, ByteArray> = emptyMap(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompletedMediaPayload) return false
        return messageId == other.messageId &&
            senderId == other.senderId &&
            payloadType == other.payloadType &&
            isLivePtt == other.isLivePtt &&
            completePayload.contentEquals(other.completePayload) &&
            channel == other.channel &&
            hasConcealedLoss == other.hasConcealedLoss &&
            missingChunks == other.missingChunks &&
            totalChunks == other.totalChunks
    }

    override fun hashCode(): Int {
        var result = messageId
        result = 31 * result + senderId.hashCode()
        result = 31 * result + payloadType.hashCode()
        result = 31 * result + isLivePtt.hashCode()
        result = 31 * result + completePayload.contentHashCode()
        result = 31 * result + channel
        result = 31 * result + hasConcealedLoss.hashCode()
        result = 31 * result + missingChunks.hashCode()
        result = 31 * result + totalChunks
        return result
    }
}

/** Outcome of ingesting a chunk in [ReassemblyBuffer]. */
sealed interface ReassemblyResult {
    /** All chunks received or packet loss concealed: ready for consumption. */
    data class Complete(val completedPayload: CompletedMediaPayload) : ReassemblyResult

    /** Chunks missing at end of transmission or timeout: send 3-byte Selective NACK back to sender. */
    data class SendNack(val senderId: String, val messageId: Byte, val nackPacket: ByteArray, val channel: Int = 0) :
        ReassemblyResult
}

/**
 * LoRa-optimized Reassembly Buffer implementing Bitmask tracking, Dynamic Multi-Round Selective NACK (up to 3 rounds),
 * and Packet Loss Concealment (PLC) with zero-amplitude silence synthesis.
 */
class ReassemblyBuffer(
    private val maxAgeMs: Long = DEFAULT_EXPIRATION_MS,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxNackRounds: Int = DEFAULT_MAX_NACK_ROUNDS,
) {

    companion object {
        const val DEFAULT_EXPIRATION_MS = 60_000L // 60 seconds purge
        const val DEFAULT_TIMEOUT_MS = 1800L // Scaled NACK evaluation timeout (maxOf(interChunkDelay * 2, 1800L))
        const val DEFAULT_MAX_NACK_ROUNDS = 3 // Bounded up to 3 rounds per session

        /**
         * How long a finalized (complete or concealed) session id is remembered. Late NACK retransmissions and mesh
         * duplicates for an already-finalized message are discarded instead of spawning a phantom half-empty session
         * that would be concealed, played back, or (for non-live media) persisted as a corrupted chat bubble.
         */
        const val COMPLETED_TOMBSTONE_MS = 30_000L

        private val _inboundProgress = MutableStateFlow<InboundMediaProgress?>(null)
        val inboundProgress: StateFlow<InboundMediaProgress?> = _inboundProgress.asStateFlow()

        fun clearProgress() {
            _inboundProgress.value = null
        }
    }

    val inboundProgress: StateFlow<InboundMediaProgress?>
        get() = Companion.inboundProgress

    private data class BufferSession(
        val messageId: Byte,
        val totalChunks: Int,
        val payloadType: Byte,
        var isLivePtt: Boolean,
        var receivedMask: Int = 0,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        var lastActivityTimeMs: Long,
        var nackRoundsSent: Int = 0,
        var lastNackTimeMs: Long = 0L,
        var maxChunkSize: Int = 205,
        val senderId: String,
        val channel: Int = 0,
        var sampleDataPacket: DataPacket? = null,
    )

    private val sessions = mutableMapOf<String, BufferSession>()
    private val completedTombstones = mutableMapOf<String, Long>()
    private val lock = Any()

    private fun markFinalizedLocked(sessionKey: String, currentTimeMs: Long) {
        completedTombstones[sessionKey] = currentTimeMs
    }

    private fun isTombstonedLocked(sessionKey: String, currentTimeMs: Long): Boolean {
        val finalizedAt = completedTombstones[sessionKey] ?: return false
        if (currentTimeMs - finalizedAt > COMPLETED_TOMBSTONE_MS) {
            completedTombstones.remove(sessionKey)
            return false
        }
        return true
    }

    private fun updateProgressInternal() {
        val active = sessions.values.lastOrNull()
        if (active != null) {
            val progress =
                InboundMediaProgress(
                    senderId = active.senderId,
                    messageId = active.messageId.toInt() and 0xFF,
                    payloadType = active.payloadType,
                    receivedChunks = active.receivedMask.countOneBits(),
                    totalChunks = active.totalChunks,
                    isLivePtt = active.isLivePtt,
                    channel = active.channel,
                )
            _inboundProgress.value = progress
        } else {
            _inboundProgress.value = null
        }
    }

    /**
     * Ingests an incoming raw chunk from [senderId].
     *
     * @return [ReassemblyResult.Complete] if finished, [ReassemblyResult.SendNack] if missing chunks need retry, or
     *   null if pending.
     */
    fun ingestChunk(
        senderId: String,
        rawChunkBytes: ByteArray,
        currentTimeMs: Long,
        channel: Int = 0,
        sampleDataPacket: DataPacket? = null,
    ): ReassemblyResult? {
        val chunk = ChunkingEngine.parse(rawChunkBytes) ?: return null
        val header = chunk.header

        synchronized(lock) {
            purgeExpiredInternal(currentTimeMs)

            val sessionKey = "$senderId:${header.messageId}"
            if (isTombstonedLocked(sessionKey, currentTimeMs)) {
                // Late retransmission / duplicate of a message that was already delivered or concealed.
                return null
            }

            // Liveness is carried by the chunk header itself (LIVE_PTT_INDEX_FLAG). The legacy chunk-0 content sniff is
            // kept only for senders that predate the header flag. It must NEVER be derived from the local session
            // state: that used to classify ordinary voice notes received while the PTT screen was open as live audio
            // (swallowed, never stored) and, conversely, let live chunks received outside a session be stored.
            val isChunkLivePtt =
                header.isLivePtt ||
                    (
                        header.chunkIndex == 0 &&
                            chunk.data.size >= 2 &&
                            chunk.data[0] == LivePttSessionManager.OPCODE_AUDIO_FRAME &&
                            (chunk.data[1].toInt() and LivePttSessionManager.FLAG_IS_LIVE_PTT.toInt()) != 0
                        )

            // Special case: Single chunk payload (TotalChunks == 1)
            if (header.totalChunks == 1) {
                markFinalizedLocked(sessionKey, currentTimeMs)
                updateProgressInternal()
                val isWavTokenizer = header.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
                val finalData =
                    if (isWavTokenizer && chunk.data.size % 3 != 0) {
                        val pad = 3 - (chunk.data.size % 3)
                        val aligned = ByteArray(chunk.data.size + pad)
                        chunk.data.copyInto(aligned, 0, 0, chunk.data.size)
                        aligned
                    } else {
                        chunk.data
                    }
                return ReassemblyResult.Complete(
                    CompletedMediaPayload(
                        messageId = header.messageId.toInt() and 0xFF,
                        senderId = senderId,
                        payloadType = header.payloadType,
                        isLivePtt = isChunkLivePtt,
                        completePayload = finalData,
                        channel = channel,
                        sampleDataPacket = sampleDataPacket,
                        hasConcealedLoss = false,
                        missingChunks = emptySet(),
                        totalChunks = 1,
                        chunkDataMap = mapOf(0 to finalData),
                    ),
                )
            }

            val session =
                sessions.getOrPut(sessionKey) {
                    BufferSession(
                        messageId = header.messageId,
                        totalChunks = header.totalChunks,
                        payloadType = header.payloadType,
                        isLivePtt = isChunkLivePtt,
                        lastActivityTimeMs = currentTimeMs,
                        senderId = senderId,
                        channel = channel,
                        sampleDataPacket = sampleDataPacket,
                    )
                }

            if (isChunkLivePtt) {
                session.isLivePtt = true
            }

            if (sampleDataPacket != null && session.sampleDataPacket == null) {
                session.sampleDataPacket = sampleDataPacket
            }

            session.lastActivityTimeMs = currentTimeMs
            session.receivedMask = session.receivedMask or (1 shl header.chunkIndex)
            session.chunks[header.chunkIndex] = chunk.data
            session.maxChunkSize = maxOf(session.maxChunkSize, chunk.data.size)

            val expectedMask = (1 shl session.totalChunks) - 1

            // 1. All chunks received!
            if (session.receivedMask == expectedMask) {
                markFinalizedLocked(sessionKey, currentTimeMs)
                return finalizeComplete(session, sessionKey)
            }

            // 2. End-of-transmission trigger: Last chunk received, but missing bits detected
            if (header.chunkIndex == session.totalChunks - 1) {
                if (session.nackRoundsSent < maxNackRounds && session.nackRoundsSent == 0) {
                    session.nackRoundsSent = 1
                    session.lastActivityTimeMs = currentTimeMs
                    session.lastNackTimeMs = currentTimeMs
                    updateProgressInternal()
                    val missingMask = expectedMask and session.receivedMask.inv()
                    val nack = ChunkingEngine.createNack(session.messageId, missingMask)
                    return ReassemblyResult.SendNack(senderId, session.messageId, nack, session.channel)
                }
            }

            updateProgressInternal()
            return null
        }
    }

    /** Backward-compatible helper that returns [CompletedMediaPayload] when complete. */
    fun addChunk(
        senderId: String,
        rawChunkBytes: ByteArray,
        currentTimeMs: Long,
        channel: Int = 0,
        sampleDataPacket: DataPacket? = null,
    ): CompletedMediaPayload? {
        val result = ingestChunk(senderId, rawChunkBytes, currentTimeMs, channel, sampleDataPacket)
        return (result as? ReassemblyResult.Complete)?.completedPayload
    }

    /**
     * Checks active sessions for timeouts (~400ms tick interval). Dispatches dynamic Selective NACK with recalculated
     * bitmasks up to [maxNackRounds] rounds. If retries expire and chunks remain missing, never discards the session:
     * performs Packet Loss Concealment (PLC) with zero-amplitude silence synthesis so audio is salvaged.
     */
    fun checkTimeouts(currentTimeMs: Long): List<ReassemblyResult> {
        val results = mutableListOf<ReassemblyResult>()
        synchronized(lock) {
            val iterator = sessions.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val session = entry.value
                val elapsed = currentTimeMs - session.lastActivityTimeMs
                val sessionTimeout =
                    if (timeoutMs != DEFAULT_TIMEOUT_MS) {
                        timeoutMs
                    } else {
                        val interChunkDelay = PayloadAnalyzer.calculateInterChunkDelayMs(session.maxChunkSize, null)
                        maxOf(interChunkDelay * 2L, 1800L)
                    }

                if (elapsed >= sessionTimeout) {
                    val expectedMask = (1 shl session.totalChunks) - 1
                    if (session.receivedMask == expectedMask) {
                        iterator.remove()
                        markFinalizedLocked(entry.key, currentTimeMs)
                        results.add(finalizeCompleteNoLock(session))
                    } else if (session.nackRoundsSent < maxNackRounds) {
                        // Dynamically update missing bitmask and issue fresh NACK for only the missing chunks
                        session.nackRoundsSent++
                        session.lastActivityTimeMs = currentTimeMs
                        session.lastNackTimeMs = currentTimeMs
                        val missingMask = expectedMask and session.receivedMask.inv()
                        val nack = ChunkingEngine.createNack(session.messageId, missingMask)
                        results.add(
                            ReassemblyResult.SendNack(session.senderId, session.messageId, nack, session.channel),
                        )
                    } else {
                        // Multi-round retries expired: NEVER discard the session!
                        // Salvage all received chunks, synthesize zero-amplitude silence for missing chunks,
                        // and finalize with concealment so chat bubble appears.
                        iterator.remove()
                        markFinalizedLocked(entry.key, currentTimeMs)
                        results.add(finalizeWithConcealmentNoLock(session))
                    }
                }
            }
            updateProgressInternal()
        }
        return results
    }

    /** Purges abandoned partial reassemblies that have exceeded [maxAgeMs]. */
    fun purgeExpired(currentTimeMs: Long) {
        synchronized(lock) { purgeExpiredInternal(currentTimeMs) }
    }

    private fun purgeExpiredInternal(currentTimeMs: Long) {
        val iterator = sessions.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value.lastActivityTimeMs > maxAgeMs) {
                iterator.remove()
                changed = true
            }
        }
        completedTombstones.entries.removeAll { currentTimeMs - it.value > COMPLETED_TOMBSTONE_MS }
        if (changed) {
            updateProgressInternal()
        }
    }

    private fun finalizeComplete(session: BufferSession, sessionKey: String): ReassemblyResult.Complete {
        sessions.remove(sessionKey)
        updateProgressInternal()
        return finalizeCompleteNoLock(session)
    }

    private fun finalizeCompleteNoLock(session: BufferSession): ReassemblyResult.Complete {
        val isWavTokenizer = session.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
        val fallbackChunk =
            if (isWavTokenizer) {
                ByteArray(((session.maxChunkSize / 3) * 3).coerceAtLeast(3))
            } else {
                ByteArray(session.maxChunkSize)
            }

        var totalSize = 0
        for (i in 0 until session.totalChunks) {
            val piece = session.chunks[i] ?: fallbackChunk
            totalSize += piece.size
        }

        val fullPayload = ByteArray(totalSize)
        var destOffset = 0
        for (i in 0 until session.totalChunks) {
            val piece = session.chunks[i] ?: fallbackChunk
            piece.copyInto(fullPayload, destinationOffset = destOffset)
            destOffset += piece.size
        }

        return ReassemblyResult.Complete(
            CompletedMediaPayload(
                messageId = session.messageId.toInt() and 0xFF,
                senderId = session.senderId,
                payloadType = session.payloadType,
                isLivePtt = session.isLivePtt,
                completePayload = fullPayload,
                channel = session.channel,
                sampleDataPacket = session.sampleDataPacket,
                hasConcealedLoss = false,
                missingChunks = emptySet(),
                totalChunks = session.totalChunks,
                chunkDataMap = session.chunks.toMap(),
            ),
        )
    }

    private fun finalizeWithConcealment(session: BufferSession, sessionKey: String): ReassemblyResult.Complete {
        sessions.remove(sessionKey)
        updateProgressInternal()
        return finalizeWithConcealmentNoLock(session)
    }

    private fun finalizeWithConcealmentNoLock(session: BufferSession): ReassemblyResult.Complete {
        val isWavTokenizer = session.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
        val chunkSize = session.maxChunkSize.coerceAtLeast(1)
        val dummyFrame =
            if (isWavTokenizer) {
                // Strict 12-bit alignment: 2 tokens packed per 3 bytes (24 bits).
                // All dummy frames must be a multiple of 3 bytes, where 0x00 is SILENT_TOKEN (0).
                val alignedSize = ((session.maxChunkSize / 3) * 3).coerceAtLeast(3)
                ByteArray(alignedSize)
            } else {
                ByteArray(chunkSize) // Silent frames (zeros) in Codec 2
            }

        var totalSize = 0
        for (i in 0 until session.totalChunks) {
            val piece = session.chunks[i] ?: dummyFrame
            totalSize += piece.size
        }

        val fullPayload = ByteArray(totalSize)
        var destOffset = 0
        for (i in 0 until session.totalChunks) {
            val piece = session.chunks[i] ?: dummyFrame
            piece.copyInto(fullPayload, destinationOffset = destOffset)
            destOffset += piece.size
        }

        val alignedPayload =
            if (isWavTokenizer && fullPayload.size % 3 != 0) {
                val pad = 3 - (fullPayload.size % 3)
                val aligned = ByteArray(fullPayload.size + pad)
                fullPayload.copyInto(aligned, 0, 0, fullPayload.size)
                aligned
            } else {
                fullPayload
            }

        val finalPayload =
            if (session.isLivePtt && (session.receivedMask and 1) == 0) {
                byteArrayOf(LivePttSessionManager.OPCODE_AUDIO_FRAME, LivePttSessionManager.FLAG_IS_LIVE_PTT) +
                    alignedPayload
            } else {
                alignedPayload
            }

        val missingChunks = (0 until session.totalChunks).filter { (session.receivedMask and (1 shl it)) == 0 }.toSet()

        return ReassemblyResult.Complete(
            CompletedMediaPayload(
                messageId = session.messageId.toInt() and 0xFF,
                senderId = session.senderId,
                payloadType = session.payloadType,
                isLivePtt = session.isLivePtt,
                completePayload = finalPayload,
                channel = session.channel,
                sampleDataPacket = session.sampleDataPacket,
                hasConcealedLoss = true,
                missingChunks = missingChunks,
                totalChunks = session.totalChunks,
                chunkDataMap = session.chunks.toMap(),
            ),
        )
    }

    /** Number of currently active (incomplete) reassembly sessions in the buffer. */
    val activeSessionCount: Int
        get() = synchronized(lock) { sessions.size }
}
