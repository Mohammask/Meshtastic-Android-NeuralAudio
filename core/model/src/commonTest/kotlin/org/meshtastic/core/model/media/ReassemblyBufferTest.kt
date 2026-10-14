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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReassemblyBufferTest {

    @Test
    fun testSingleChunkImmediateAssembly() {
        val buffer = ReassemblyBuffer()
        val originalData = byteArrayOf(1, 2, 3, 4, 5)
        val chunks =
            ChunkingEngine.split(payload = originalData, payloadType = ChunkingEngine.TYPE_PTT_CONTROL, messageId = 42)
        assertEquals(1, chunks.size)

        val completed = buffer.addChunk(senderId = "!node1", rawChunkBytes = chunks[0], currentTimeMs = 1000L)

        assertNotNull(completed)
        assertEquals(42, completed.messageId)
        assertEquals("!node1", completed.senderId)
        assertEquals(ChunkingEngine.TYPE_PTT_CONTROL, completed.payloadType)
        assertTrue(originalData.contentEquals(completed.completePayload))
    }

    @Test
    fun testMultiChunkInOrderReassembly() {
        val buffer = ReassemblyBuffer()
        val originalData = ByteArray(450) { it.toByte() } // 3 chunks (205 + 205 + 40)
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                isLivePtt = true,
                maxChunkDataSize = 205,
                messageId = 99,
            )
        assertEquals(3, chunks.size)

        // Feed chunk 0
        var completed = buffer.addChunk("!node1", chunks[0], 1000L)
        assertNull(completed, "Should not complete on chunk 0")

        // Feed chunk 1
        completed = buffer.addChunk("!node1", chunks[1], 1050L)
        assertNull(completed, "Should not complete on chunk 1")

        // Feed chunk 2
        completed = buffer.addChunk("!node1", chunks[2], 1100L)
        assertNotNull(completed, "Should complete on chunk 2")

        assertEquals(99, completed.messageId)
        assertEquals(ChunkingEngine.TYPE_AUDIO_CODEC2, completed.payloadType)
        assertEquals(450, completed.completePayload.size)
        assertTrue(originalData.contentEquals(completed.completePayload))
    }

    @Test
    fun testMultiChunkOutOfOrderReassembly() {
        val buffer = ReassemblyBuffer()
        val originalData = ByteArray(500) { (it * 3).toByte() }
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_IMAGE_1BIT,
                maxChunkDataSize = 205,
                messageId = 77,
            )
        assertEquals(3, chunks.size)

        // Feed in reverse order: Chunk 2, then Chunk 0, then Chunk 1
        // Note: Chunk 2 triggers NACK check since it's the last chunk (index 2 of 3)
        val result2 = buffer.ingestChunk("!nodeX", chunks[2], 1000L)
        assertIs<ReassemblyResult.SendNack>(result2)
        val nack = ChunkingEngine.parseNack(result2.nackPacket)
        assertNotNull(nack)
        // Bit 0 and Bit 1 are missing, so missingMask should be 0b011 = 3
        assertEquals(3, nack.missingMask)

        val result0 = buffer.ingestChunk("!nodeX", chunks[0], 1050L)
        assertNull(result0)

        val result1 = buffer.ingestChunk("!nodeX", chunks[1], 1100L)
        assertIs<ReassemblyResult.Complete>(result1, "Should complete when last missing chunk arrives")

        val completed = result1.completedPayload
        assertEquals(77, completed.messageId)
        assertEquals(ChunkingEngine.TYPE_IMAGE_1BIT, completed.payloadType)
        assertEquals(500, completed.completePayload.size)
        assertTrue(
            originalData.contentEquals(completed.completePayload),
            "Out-of-order reassembly must preserve exact bytes",
        )
    }

    @Test
    fun testSelectiveNackAndSingleRetryFlow() {
        val buffer = ReassemblyBuffer()
        val originalData = ByteArray(400) { (it + 1).toByte() } // 2 chunks of 200B
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 55,
            )
        assertEquals(2, chunks.size)

        // Simulate chunk 0 lost in transit, only chunk 1 arrives (the last chunk)
        val nackResult = buffer.ingestChunk("!nodeTx", chunks[1], 1000L)
        assertIs<ReassemblyResult.SendNack>(nackResult)
        assertEquals(55.toByte(), nackResult.messageId)

        val nack = ChunkingEngine.parseNack(nackResult.nackPacket)
        assertNotNull(nack)
        // Chunk 0 is missing, so missingMask has bit 0 set (1)
        assertEquals(1, nack.missingMask)

        // Now transmitter resends chunk 0
        val retryResult = buffer.ingestChunk("!nodeTx", chunks[0], 1100L)
        assertIs<ReassemblyResult.Complete>(retryResult)
        assertEquals(400, retryResult.completedPayload.completePayload.size)
        assertTrue(originalData.contentEquals(retryResult.completedPayload.completePayload))
    }

    @Test
    fun testPacketLossConcealmentWhenRetryFails() {
        val buffer = ReassemblyBuffer(timeoutMs = 500L, maxNackRounds = 1)
        val originalData = ByteArray(410) { (it + 5).toByte() } // 2 chunks (205 + 205)
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 88,
            )
        assertEquals(2, chunks.size)

        // Chunk 0 is lost, chunk 1 arrives
        val nackResult = buffer.ingestChunk("!nodeTx", chunks[1], 1000L)
        assertIs<ReassemblyResult.SendNack>(nackResult)

        // 500ms elapses and retry never arrived (timeout):
        val timeoutResults = buffer.checkTimeouts(1600L)
        assertEquals(1, timeoutResults.size)
        assertIs<ReassemblyResult.Complete>(timeoutResults[0])

        val completed = (timeoutResults[0] as ReassemblyResult.Complete).completedPayload
        assertEquals(88, completed.messageId)
        assertEquals(410, completed.completePayload.size)
        // Missing chunk 0 was concealed with silent zeros, while chunk 1 data was preserved
        val chunk1Expected = originalData.copyOfRange(205, 410)
        val chunk1Actual = completed.completePayload.copyOfRange(205, 410)
        assertTrue(
            chunk1Expected.contentEquals(chunk1Actual),
            "Received chunk audio must be preserved during concealment",
        )
    }

    @Test
    fun testBufferSlidingExpirationPurge() {
        val buffer = ReassemblyBuffer(maxAgeMs = 5000L)
        val chunks =
            ChunkingEngine.split(
                payload = ByteArray(400),
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 15,
            )

        // Feed only chunk 0 at t = 1000ms
        buffer.addChunk("!nodeA", chunks[0], currentTimeMs = 1000L)
        assertEquals(1, buffer.activeSessionCount)

        // At t = 3000ms (within 5000ms), still retained
        buffer.purgeExpired(currentTimeMs = 3000L)
        assertEquals(1, buffer.activeSessionCount)

        // At t = 7000ms (exceeded 5000ms from 1000ms), purged
        buffer.purgeExpired(currentTimeMs = 7000L)
        assertEquals(0, buffer.activeSessionCount)
    }

    @Test
    fun testLostFinalChunkTriggersNackViaWatchdog() {
        val buffer = ReassemblyBuffer(timeoutMs = 500L)
        val originalData = ByteArray(500) { (it + 10).toByte() } // 3 chunks (205 + 205 + 90)
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 61,
            )
        assertEquals(3, chunks.size)

        // Feed chunk 0 and chunk 1; chunk 2 (final chunk) is lost over RF
        val res0 = buffer.ingestChunk("!nodeTx", chunks[0], currentTimeMs = 1000L, channel = 1)
        assertNull(res0)
        val res1 = buffer.ingestChunk("!nodeTx", chunks[1], currentTimeMs = 1050L, channel = 1)
        assertNull(res1)

        // At t = 1400L (< 500ms elapsed since last chunk at 1050L): no timeout yet
        val timeoutBefore = buffer.checkTimeouts(currentTimeMs = 1400L)
        assertTrue(timeoutBefore.isEmpty())

        // At t = 1600L (550ms elapsed since chunk 1 at 1050L): watchdog detects lost final chunk
        val timeoutResults = buffer.checkTimeouts(currentTimeMs = 1600L)
        assertEquals(1, timeoutResults.size)
        assertIs<ReassemblyResult.SendNack>(timeoutResults[0])

        val nackRes = timeoutResults[0] as ReassemblyResult.SendNack
        assertEquals(61.toByte(), nackRes.messageId)
        assertEquals(1, nackRes.channel)
        val nack = ChunkingEngine.parseNack(nackRes.nackPacket)
        assertNotNull(nack)
        // Bit 2 is missing, so missingMask should be 0b100 = 4
        assertEquals(4, nack.missingMask)

        // Transmitter now retries chunk 2
        val retryResult = buffer.ingestChunk("!nodeTx", chunks[2], currentTimeMs = 1700L, channel = 1)
        assertIs<ReassemblyResult.Complete>(retryResult)
        val completed = retryResult.completedPayload
        assertEquals(61, completed.messageId)
        assertEquals(1, completed.channel)
        assertEquals(500, completed.completePayload.size)
        assertTrue(originalData.contentEquals(completed.completePayload))
    }

    @Test
    fun testLostFinalChunkConcealmentWhenRetryFails() {
        val buffer = ReassemblyBuffer(timeoutMs = 500L, maxNackRounds = 1)
        val originalData = ByteArray(500) { (it + 10).toByte() } // 3 chunks (205 + 205 + 90)
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 62,
            )
        assertEquals(3, chunks.size)

        // Feed chunk 0 and chunk 1; chunk 2 is lost
        buffer.ingestChunk("!nodeTx", chunks[0], currentTimeMs = 1000L, channel = 2)
        buffer.ingestChunk("!nodeTx", chunks[1], currentTimeMs = 1050L, channel = 2)

        // Watchdog fires NACK at t = 1600L
        val nackList = buffer.checkTimeouts(currentTimeMs = 1600L)
        assertEquals(1, nackList.size)
        assertIs<ReassemblyResult.SendNack>(nackList[0])

        // Retry chunk is ALSO lost in transit.
        // After another 500ms (t = 2200L, which is 600ms >= 500ms after NACK at 1600L):
        val plcList = buffer.checkTimeouts(currentTimeMs = 2200L)
        assertEquals(1, plcList.size)
        assertIs<ReassemblyResult.Complete>(plcList[0])

        val completed = (plcList[0] as ReassemblyResult.Complete).completedPayload
        assertEquals(62, completed.messageId)
        assertEquals(2, completed.channel)
        // Chunks 0 and 1 are preserved
        assertTrue(originalData.copyOfRange(0, 410).contentEquals(completed.completePayload.copyOfRange(0, 410)))
    }

    @Test
    fun testInboundProgressTracksReceivedChunksAndClearsOnCompletion() {
        val buffer = ReassemblyBuffer()
        val originalData = ByteArray(600) { it.toByte() }
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 101,
            )
        assertEquals(3, chunks.size)

        assertNull(buffer.inboundProgress.value, "Progress should initially be null")

        // Chunk 0 arrives
        buffer.ingestChunk("!sender1", chunks[0], currentTimeMs = 1000L, channel = 0)
        val progress1 = buffer.inboundProgress.value
        assertNotNull(progress1)
        assertEquals("!sender1", progress1.senderId)
        assertEquals(101, progress1.messageId)
        assertEquals(1, progress1.receivedChunks)
        assertEquals(3, progress1.totalChunks)
        assertTrue(progress1.isAudio)

        // Chunk 1 arrives
        buffer.ingestChunk("!sender1", chunks[1], currentTimeMs = 1100L, channel = 0)
        val progress2 = buffer.inboundProgress.value
        assertNotNull(progress2)
        assertEquals(2, progress2.receivedChunks)
        assertEquals(3, progress2.totalChunks)

        // Chunk 2 (final chunk) arrives -> completes
        val complete = buffer.ingestChunk("!sender1", chunks[2], currentTimeMs = 1200L, channel = 0)
        assertIs<ReassemblyResult.Complete>(complete)
        assertNull(buffer.inboundProgress.value, "Progress should reset to null once complete")
    }

    @Test
    fun testWavTokenizerConcealmentPreservesStrict12BitAlignment() {
        val buffer = ReassemblyBuffer(timeoutMs = 500L, maxNackRounds = 1)
        // 600 bytes = 200 pairs of 12-bit tokens (exactly 400 tokens)
        val originalData = ByteArray(600) { (it % 256).toByte() }
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER,
                maxChunkDataSize = 205,
                messageId = 73,
            )
        // For WavTokenizer, maxChunkDataSize is aligned to a multiple of 3 (204 bytes)
        // 600 / 204 = 2.94 -> 3 chunks (204 + 204 + 192)
        assertEquals(3, chunks.size)
        for (chunk in chunks) {
            val parsed = ChunkingEngine.parse(chunk)
            assertNotNull(parsed)
            // Header is 3 bytes; data size must be a multiple of 3 to preserve 12-bit token alignment
            assertEquals(0, parsed.data.size % 3, "Each WavTokenizer chunk data size must be multiple of 3")
        }

        // Chunk 0 arrives, chunk 1 is lost, chunk 2 arrives
        buffer.ingestChunk("!wavNode", chunks[0], currentTimeMs = 1000L)
        val nackRes = buffer.ingestChunk("!wavNode", chunks[2], currentTimeMs = 1100L)
        assertIs<ReassemblyResult.SendNack>(nackRes)

        // Retry also lost: timeout triggers concealment
        val plcResults = buffer.checkTimeouts(currentTimeMs = 1700L)
        assertEquals(1, plcResults.size)
        assertIs<ReassemblyResult.Complete>(plcResults[0])

        val completed = (plcResults[0] as ReassemblyResult.Complete).completedPayload
        assertEquals(ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER, completed.payloadType)
        // The completed payload must preserve 12-bit (multiple of 3) alignment
        assertEquals(
            0,
            completed.completePayload.size % 3,
            "Reassembled WavTokenizer payload must strictly preserve 12-bit alignment",
        )

        // Ensure unpack12Bit successfully unpacks without token offset corruption
        val unpackedTokens = WavTokenizerEngine.unpack12Bit(completed.completePayload)
        assertEquals((completed.completePayload.size * 2) / 3, unpackedTokens.size)
        // All tokens must be valid 12-bit values (0..4095)
        for (token in unpackedTokens) {
            assertTrue(token in 0 until WavTokenizerEngine.CODEBOOK_SIZE)
        }
    }

    @Test
    fun testDynamicMultiRoundSelectiveNackBitmaskRecalculation() {
        val buffer = ReassemblyBuffer(timeoutMs = 400L, maxNackRounds = 3)
        val originalData = ByteArray(800) { (it + 1).toByte() } // 4 chunks (200 bytes each)
        val chunks =
            ChunkingEngine.split(
                payload = originalData,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                maxChunkDataSize = 205,
                messageId = 111,
            )
        assertEquals(4, chunks.size)

        // Chunk 0 arrives at t = 1000ms, chunk 1 arrives at t = 1050ms
        // Chunks 2 and 3 drop over the air
        buffer.ingestChunk("!nodeTx", chunks[0], currentTimeMs = 1000L)
        buffer.ingestChunk("!nodeTx", chunks[1], currentTimeMs = 1050L)

        // Round 1 NACK: At t = 1500ms (450ms > 400ms timeout)
        val round1 = buffer.checkTimeouts(currentTimeMs = 1500L)
        assertEquals(1, round1.size)
        assertIs<ReassemblyResult.SendNack>(round1[0])
        val nack1 = ChunkingEngine.parseNack((round1[0] as ReassemblyResult.SendNack).nackPacket)
        assertNotNull(nack1)
        // Chunks 2 and 3 are missing -> missingMask = 0b1100 = 12
        assertEquals(12, nack1.missingMask)

        // Sender retransmits chunks 2 and 3. Chunk 2 arrives at t = 1600ms, but chunk 3 drops again!
        val retryChunk2Result = buffer.ingestChunk("!nodeTx", chunks[2], currentTimeMs = 1600L)
        assertNull(retryChunk2Result, "Should not complete because chunk 3 is still missing")

        // Round 2 NACK: At t = 2050ms (450ms > 400ms after chunk 2 arrived)
        val round2 = buffer.checkTimeouts(currentTimeMs = 2050L)
        assertEquals(1, round2.size)
        assertIs<ReassemblyResult.SendNack>(round2[0])
        val nack2 = ChunkingEngine.parseNack((round2[0] as ReassemblyResult.SendNack).nackPacket)
        assertNotNull(nack2)
        // Dynamically recalculated! Chunk 2 was cleared, so missingMask is SOLELY chunk 3 (0b1000 = 8)
        assertEquals(8, nack2.missingMask)

        // Round 3 NACK: Sender retransmitted, but chunk 3 drops a 3rd time!
        // At t = 2500ms (450ms > 400ms after round 2 NACK)
        val round3 = buffer.checkTimeouts(currentTimeMs = 2500L)
        assertEquals(1, round3.size)
        assertIs<ReassemblyResult.SendNack>(round3[0])
        val nack3 = ChunkingEngine.parseNack((round3[0] as ReassemblyResult.SendNack).nackPacket)
        assertNotNull(nack3)
        assertEquals(8, nack3.missingMask)

        // Retries expire! At t = 2950ms (450ms > 400ms after round 3 NACK)
        // NEVER discard session! Finalizes with concealment and salvages all received chunks
        val expiredRound = buffer.checkTimeouts(currentTimeMs = 2950L)
        assertEquals(1, expiredRound.size)
        assertIs<ReassemblyResult.Complete>(expiredRound[0])
        val completed = (expiredRound[0] as ReassemblyResult.Complete).completedPayload
        assertEquals(111, completed.messageId)
        assertTrue(completed.hasConcealedLoss)
        assertEquals(1, completed.missingChunks.size)
        assertTrue(completed.missingChunks.contains(3))
        assertEquals(4, completed.totalChunks)
        assertEquals(3, completed.chunkDataMap.size)
        // Chunks 0, 1, 2 data was salvaged and preserved
        assertTrue(completed.chunkDataMap.containsKey(0))
        assertTrue(completed.chunkDataMap.containsKey(1))
        assertTrue(completed.chunkDataMap.containsKey(2))
    }
}
