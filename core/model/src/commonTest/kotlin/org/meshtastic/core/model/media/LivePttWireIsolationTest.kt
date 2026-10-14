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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the guarantee that live PTT audio is identifiable from EVERY chunk and can never be mistaken for a voice note.
 */
class LivePttWireIsolationTest {

    private val payload = ByteArray(450) { it.toByte() } // 3 chunks

    @Test
    fun everyLiveChunkCarriesTheLiveFlagAndKeepsItsIndex() {
        val chunks =
            ChunkingEngine.split(payload, ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER, isLivePtt = true, messageId = 9)
        assertEquals(3, chunks.size)
        chunks.forEachIndexed { index, raw ->
            val parsed = assertNotNull(ChunkingEngine.parse(raw))
            assertTrue(parsed.header.isLivePtt, "chunk $index must self-identify as live")
            assertEquals(index, parsed.header.chunkIndex)
            assertEquals(3, parsed.header.totalChunks)
        }
    }

    @Test
    fun ordinaryVoiceNoteChunksAreNeverLive() {
        val chunks =
            ChunkingEngine.split(payload, ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER, isLivePtt = false, messageId = 9)
        chunks.forEach { assertFalse(assertNotNull(ChunkingEngine.parse(it)).header.isLivePtt) }
    }

    @Test
    fun liveFlagDoesNotMakeAFloorOrNackShapedPacketAMediaChunk() {
        // 3-byte NACKs / floor packets are never parsed as chunks regardless of the flag bit.
        assertNull(ChunkingEngine.parse(ChunkingEngine.createNack(0x12, 0x0003)))
        assertNull(ChunkingEngine.parse(LivePttSessionManager.createFloorReleasePacket(0x1234)))
    }

    @Test
    fun liveClassificationIgnoresTheLocalSessionState() {
        val buffer = ReassemblyBuffer()
        // The local node is NOT in a session, yet the live header flag alone classifies the transmission as live.
        val live = ChunkingEngine.split(payload, ChunkingEngine.TYPE_AUDIO_CODEC2, isLivePtt = true, messageId = 5)
        var done: ReassemblyResult? = null
        live.forEachIndexed { i, c -> done = buffer.ingestChunk("!a", c, 1000L + i) ?: done }
        val complete = assertIs<ReassemblyResult.Complete>(done)
        assertTrue(complete.completedPayload.isLivePtt)
    }

    @Test
    fun ordinaryVoiceNoteReceivedWhileInThePttRoomIsNotSwallowedAsLive() {
        val manager = LivePttSessionManager.default
        manager.enterSession("!me", "Me", 1)
        try {
            val buffer = ReassemblyBuffer()
            val note = ChunkingEngine.split(payload, ChunkingEngine.TYPE_AUDIO_CODEC2, isLivePtt = false, messageId = 6)
            var done: ReassemblyResult? = null
            note.forEachIndexed { i, c -> done = buffer.ingestChunk("!a", c, 1000L + i) ?: done }
            val complete = assertIs<ReassemblyResult.Complete>(done)
            // Previously `|| isInSession` forced this to true and the voice note was auto-played and never stored.
            assertFalse(complete.completedPayload.isLivePtt)
        } finally {
            manager.leaveSession("!me", "Me")
        }
    }

    @Test
    fun lateRetransmissionOfAFinalizedMessageIsDiscardedInsteadOfBecomingAPhantomSession() {
        val buffer = ReassemblyBuffer()
        val chunks = ChunkingEngine.split(payload, ChunkingEngine.TYPE_AUDIO_CODEC2, isLivePtt = true, messageId = 7)

        var done: ReassemblyResult? = null
        chunks.forEachIndexed { i, c -> done = buffer.ingestChunk("!a", c, 1000L + i) ?: done }
        assertIs<ReassemblyResult.Complete>(done)

        // A NACK retransmission (or mesh duplicate) of chunk 1 shows up afterwards.
        assertNull(buffer.ingestChunk("!a", chunks[1], 2000L))
        assertEquals(0, buffer.activeSessionCount)
    }

    @Test
    fun lateRetransmissionAfterConcealmentIsDiscarded() {
        val buffer = ReassemblyBuffer(timeoutMs = 100L, maxNackRounds = 1)
        val chunks = ChunkingEngine.split(payload, ChunkingEngine.TYPE_AUDIO_CODEC2, isLivePtt = true, messageId = 8)
        buffer.ingestChunk("!a", chunks[0], 1000L)
        buffer.ingestChunk("!a", chunks[2], 1010L) // last chunk with a gap -> NACK round 1

        // NACK round already spent -> the first timeout tick finalises with concealment.
        val concealed = buffer.checkTimeouts(1200L).filterIsInstance<ReassemblyResult.Complete>()
        assertEquals(1, concealed.size)
        assertTrue(concealed.single().completedPayload.hasConcealedLoss)

        // The retransmitted missing chunk arrives too late: it must not start a second half-empty session.
        assertNull(buffer.ingestChunk("!a", chunks[1], 1500L))
        assertEquals(0, buffer.activeSessionCount)
    }

    @Test
    fun rotatingMessageIdsAreNeverRepeatedWithinOneCycle() {
        // Mirrors SendMediaUseCaseImpl.nextMessageId(): 254 distinct ids in 1..254 before any reuse.
        var counter = 17
        val seen = mutableSetOf<Int>()
        repeat(254) {
            counter = (counter + 1) % 254
            seen += counter + 1
        }
        assertEquals(254, seen.size)
        assertTrue(seen.all { it in 1..254 })
    }
}
