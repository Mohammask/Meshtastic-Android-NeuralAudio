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

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LivePttSessionManagerTest {

    private fun TestScope.collectEvents(manager: LivePttSessionManager): MutableList<PttSessionEvent> {
        val received = mutableListOf<PttSessionEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { manager.events.collect { received.add(it) } }
        return received
    }

    private fun LivePttSessionManager.peerFloorReq(id: String, name: String, num: Int, now: Long = 1000L) =
        handleIncomingPayload(
            senderId = id,
            senderName = name,
            payload = LivePttSessionManager.createFloorRequestPacket(num),
            currentTimeMs = now,
            senderNodeNum = num,
        )

    @Test
    fun testOptInPttWorkflow() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)

        assertFalse(manager.state.value.isInSession)

        val joinPacket = manager.createControlPacket(LivePttSessionManager.OPCODE_JOIN, "!node_a", "Alice")
        manager.handleIncomingPayload("!node_a", "Alice", joinPacket, 1000L)

        // Opt-in rule: never enter a session automatically.
        assertFalse(manager.state.value.isInSession)
        val invitation = manager.state.value.pendingInvitation
        assertNotNull(invitation)
        assertEquals("!node_a", invitation.hostNodeId)
        assertEquals(1, receivedEvents.size)
        assertTrue(receivedEvents[0] is PttSessionEvent.InvitationReceived)

        // Live audio outside a session evaporates: no playback, no "store" event, no presence side effects.
        val liveAudioPacket = manager.createAudioPacket(byteArrayOf(0x01, 0x02, 0x03), isLivePtt = true)
        manager.handleIncomingPayload("!node_a", "Alice", liveAudioPacket, 1050L)
        assertEquals(1, receivedEvents.size)
        assertFalse(manager.state.value.isPlayingAudio)
        assertTrue(manager.state.value.participants.isEmpty())

        val bJoinPacket = manager.joinSession("!node_a", "Alice", "!node_b", "Bob")
        assertEquals(LivePttSessionManager.OPCODE_JOIN, bJoinPacket[0])
        assertTrue(manager.state.value.isInSession)
        assertEquals("!node_a", manager.state.value.activeSessionHostId)
        assertNull(manager.state.value.pendingInvitation)

        manager.handleIncomingPayload("!node_a", "Alice", liveAudioPacket, 1100L)
        assertTrue(receivedEvents.last() is PttSessionEvent.AutoPlayAudio)
        assertTrue(manager.state.value.isPlayingAudio)

        val leavePacket = manager.leaveSession("!node_b", "Bob")
        assertEquals(LivePttSessionManager.OPCODE_LEAVE, leavePacket[0])
        assertFalse(manager.state.value.isInSession)
        assertTrue(receivedEvents.last() is PttSessionEvent.UserLeft)
    }

    @Test
    fun testFloorReservationAndRelease() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)

        manager.startHosting("!node_a", "Alice", 100)

        val reqPacket = manager.requestFloor(100, "!node_a")
        assertEquals(3, reqPacket.size)
        assertEquals(LivePttSessionManager.OPCODE_FLOOR_REQ, reqPacket[0])
        assertEquals(100, LivePttSessionManager.parseNodeNum(reqPacket))
        assertTrue(manager.state.value.isTransmitting)
        assertEquals("!node_a", manager.state.value.floorHolderNodeId)

        val relPacket = manager.releaseFloor(100)
        assertEquals(3, relPacket.size)
        assertEquals(LivePttSessionManager.OPCODE_FLOOR_REL, relPacket[0])
        assertFalse(manager.state.value.isTransmitting)

        manager.peerFloorReq("!node_b", "Bob", 200, now = 2000L)
        assertTrue(manager.state.value.isReceiving)
        assertEquals("Bob", manager.state.value.activeSpeakerName)
        assertEquals("!node_b", manager.state.value.floorHolderNodeId)
        assertTrue(receivedEvents.last() is PttSessionEvent.FloorRequested)

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createFloorReleasePacket(200),
            2100L,
            senderNodeNum = 200,
        )
        assertFalse(manager.state.value.isReceiving)
        assertNull(manager.state.value.floorHolderNodeId)
        assertTrue(receivedEvents.last() is PttSessionEvent.FloorReleased)
    }

    @Test
    fun testFloorPacketsOutsideSessionNeverLockTheMic() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.peerFloorReq("!node_b", "Bob", 200)
        assertFalse(manager.state.value.isReceiving)
        assertNull(manager.state.value.floorHolderNodeId)
    }

    @Test
    fun testSimultaneousKeyingCollisionDeterministicTieBreaker() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)

        manager.startHosting("!node_100", "Node100", 100)
        manager.requestFloor(100, "!node_100")
        assertTrue(manager.state.value.isTransmitting)

        // Local (100) < remote (200): local wins and keeps the floor.
        manager.peerFloorReq("!node_200", "Node200", 200, now = 3000L)
        assertTrue(manager.state.value.isTransmitting)
        assertFalse(manager.state.value.floorRejected)
        assertFalse(manager.state.value.isReceiving)

        manager.releaseFloor(100)

        // Local (300) > remote (200): local loses, aborts and becomes a listener.
        manager.requestFloor(300, "!node_300")
        manager.peerFloorReq("!node_200", "Node200", 200, now = 3100L)
        assertFalse(manager.state.value.isTransmitting)
        assertTrue(manager.state.value.floorRejected)
        assertTrue(manager.state.value.isReceiving)
        assertEquals("!node_200", manager.state.value.floorHolderNodeId)
        assertTrue(receivedEvents.last() is PttSessionEvent.FloorCollisionLost)
    }

    @Test
    fun testHeartbeatAndPresenceEviction() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)

        manager.startHosting("!node_a", "Alice", 100)
        val joinPacket = manager.createControlPacket(LivePttSessionManager.OPCODE_JOIN, "!node_b", "Bob")
        manager.handleIncomingPayload("!node_b", "Bob", joinPacket, 1000L)

        assertEquals(2, manager.state.value.participants.size)
        assertTrue(manager.state.value.isBothActive)
        assertEquals(1, manager.state.value.activePeersCount)

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createHeartbeatPacket(200),
            11_000L,
            senderNodeNum = 200,
        )
        assertEquals(2, manager.state.value.participants.size)

        // 26 s of silence is NOT enough to evict an active peer (more than three heartbeats must be missed)...
        manager.pruneExpiredParticipants(currentTimeMs = 37_000L)
        assertEquals(1, manager.state.value.activePeersCount)

        // ...but 50 s of silence is: a crashed peer must not keep the room populated forever.
        manager.pruneExpiredParticipants(currentTimeMs = 60_000L)
        assertEquals(1, manager.state.value.participants.size)
        assertEquals(0, manager.state.value.activePeersCount)
        assertFalse(manager.state.value.isBothActive)
        assertTrue(receivedEvents.last() is PttSessionEvent.UserLeft)
    }

    @Test
    fun testFloorLeaseExpiresWithoutAnyCarrier() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope, clock = { testScheduler.currentTime })
        manager.startHosting("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_b", "Bob", 200, now = testScheduler.currentTime)
        assertTrue(manager.state.value.isReceiving)

        advanceTimeBy(LivePttSessionManager.FLOOR_LEASE_MS - 500L)
        assertTrue(manager.state.value.isReceiving)

        advanceTimeBy(1_000L)
        assertFalse(manager.state.value.isReceiving)
        assertNull(manager.state.value.floorHolderNodeId)
    }

    @Test
    fun testKeepAliveRenewsFloorLeaseAcrossTheEncodeGap() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope, clock = { testScheduler.currentTime })
        manager.startHosting("!node_a", "Alice", 100)
        val receivedEvents = collectEvents(manager)

        manager.peerFloorReq("!node_b", "Bob", 200, now = testScheduler.currentTime)
        advanceTimeBy(5_000L)
        manager.peerFloorReq(
            "!node_b",
            "Bob",
            200,
            now = testScheduler.currentTime,
        ) // keepalive renews the lease, no duplicate FloorRequested
        advanceTimeBy(5_000L) // 10 s after the first REQ, 5 s after the keepalive: still locked
        assertTrue(manager.state.value.isReceiving)
        assertEquals(1, receivedEvents.filterIsInstance<PttSessionEvent.FloorRequested>().size)

        advanceTimeBy(2_100L) // 7.1 s after the last keepalive: lease lapsed
        assertFalse(manager.state.value.isReceiving)
    }

    @Test
    fun testLiveChunksRenewLeaseAndReassemblyGraceHoldsTheFloor() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.startHosting("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_b", "Bob", 200)

        advanceTimeBy(2_000L)
        manager.onRfCarrierActivity(senderId = "!node_b")

        // Lease would have lapsed at 7 s without the chunk; it was renewed at 2 s.
        advanceTimeBy(6_000L)
        assertTrue(manager.state.value.isReceiving)

        // Lease lapses at 9 s but a reassembly is still in flight: one grace period is granted.
        advanceTimeBy(2_000L)
        assertTrue(manager.state.value.isReceiving)

        // Grace (8 s) also exhausted: the stalled reassembly is abandoned and the floor released.
        advanceTimeBy(LivePttSessionManager.PENDING_REASSEMBLY_GRACE_MS + 100L)
        assertFalse(manager.state.value.isReceiving)
        assertNull(manager.state.value.floorHolderNodeId)
    }

    @Test
    fun testFloorReleaseOvertakingLastChunksDoesNotUnlockUntilReassemblySettles() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.startHosting("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_b", "Bob", 200)
        manager.onRfCarrierActivity(senderId = "!node_b")

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createFloorReleasePacket(200),
            2000L,
            senderNodeNum = 200,
        )
        // FLOOR_REL arrived, but chunks are still being reassembled: the floor must stay locked.
        assertTrue(manager.state.value.isReceiving)

        manager.onInboundMediaSettled("!node_b")
        assertFalse(manager.state.value.isReceiving)
        assertNull(manager.state.value.floorHolderNodeId)
    }

    @Test
    fun testFloorReleaseFromNonHolderIsIgnored() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.startHosting("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_b", "Bob", 200)

        manager.handleIncomingPayload(
            "!node_c",
            "Carol",
            LivePttSessionManager.createFloorReleasePacket(300),
            2000L,
            senderNodeNum = 300,
        )
        assertTrue(manager.state.value.isReceiving)
        assertEquals("!node_b", manager.state.value.floorHolderNodeId)
    }

    @Test
    fun testHardwareBufferDrainLockAndPlaybackAck() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)
        manager.startHosting("!node_a", "Alice", 100)

        manager.peerFloorReq("!node_b", "Bob", 200)
        assertTrue(manager.state.value.isReceiving)

        val audioPacket = manager.createAudioPacket(byteArrayOf(0x10, 0x20), isLivePtt = true)
        manager.handleIncomingPayload("!node_b", "Bob", audioPacket, 1100L, senderNodeNum = 200)
        assertTrue(manager.state.value.isPlayingAudio)
        assertTrue(manager.isAudioPlaying)

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createFloorReleasePacket(200),
            1200L,
            senderNodeNum = 200,
        )
        // Speaker still draining: lock must hold.
        assertTrue(manager.state.value.isReceiving)
        assertTrue(receivedEvents.filterIsInstance<PttSessionEvent.PlaybackDrained>().isEmpty())

        manager.onAudioPlaybackCompleted()

        assertFalse(manager.state.value.isPlayingAudio)
        assertFalse(manager.state.value.isReceiving)
        assertNull(manager.state.value.floorHolderNodeId)
        val drained = receivedEvents.filterIsInstance<PttSessionEvent.PlaybackDrained>().single()
        assertEquals("!node_b", drained.senderId)
        assertEquals(200, drained.senderNodeNum)
    }

    @Test
    fun testBackToBackMessagesKeepTheSpeakerLockedUntilBothDrain() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.startHosting("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_b", "Bob", 200)
        val audioPacket = manager.createAudioPacket(byteArrayOf(0x10, 0x20), isLivePtt = true)
        manager.handleIncomingPayload("!node_b", "Bob", audioPacket, 1100L, senderNodeNum = 200)
        manager.handleIncomingPayload("!node_b", "Bob", audioPacket, 1200L, senderNodeNum = 200)
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createFloorReleasePacket(200),
            1300L,
            senderNodeNum = 200,
        )

        manager.onAudioPlaybackCompleted()
        assertTrue(manager.state.value.isPlayingAudio)
        assertTrue(manager.state.value.isReceiving)

        manager.onAudioPlaybackCompleted()
        assertFalse(manager.state.value.isPlayingAudio)
        assertFalse(manager.state.value.isReceiving)
    }

    @Test
    fun testLeaveAndReenterClearsEveryStaleLock() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.enterSession("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_b", "Bob", 200)
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            manager.createAudioPacket(byteArrayOf(0x10, 0x20), isLivePtt = true),
            1100L,
            senderNodeNum = 200,
        )
        assertTrue(manager.state.value.isPlayingAudio)

        manager.leaveSession("!node_a", "Alice")
        assertFalse(manager.state.value.isPlayingAudio)
        assertFalse(manager.state.value.isReceiving)

        manager.enterSession("!node_a", "Alice", 100)
        assertFalse(manager.state.value.isPlayingAudio)
        assertFalse(manager.state.value.isReceiving)
        assertFalse(manager.isAudioPlaying)
        assertEquals(0, manager.state.value.activePeersCount)
    }

    @Test
    fun testSenderStaysLockedUntilPeerPlaybackAckOrTimeout() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.enterSession("!node_a", "Alice", 100)
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttHerePacket(200),
            1000L,
            senderNodeNum = 200,
        )
        assertEquals(1, manager.state.value.activePeersCount)

        manager.requestFloor(100, "!node_a")
        manager.beginPeerPlaybackWait(timeoutMs = 30_000L)
        manager.releaseFloor(100)
        assertTrue(manager.state.value.isAwaitingPeerPlayback)

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttDonePacket(200),
            9000L,
            senderNodeNum = 200,
        )
        assertFalse(manager.state.value.isAwaitingPeerPlayback)

        // Lost ack: the wait ends on its own timeout instead of locking the sender forever.
        manager.requestFloor(100, "!node_a")
        manager.beginPeerPlaybackWait(timeoutMs = 30_000L)
        assertTrue(manager.state.value.isAwaitingPeerPlayback)
        advanceTimeBy(30_100L)
        assertFalse(manager.state.value.isAwaitingPeerPlayback)
    }

    @Test
    fun testAckThatArrivesBeforeTheWaitBeginsIsHonoured() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.enterSession("!node_a", "Alice", 100)
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttHerePacket(200),
            1000L,
            senderNodeNum = 200,
        )
        manager.requestFloor(100, "!node_a")
        // Very short message: the peer finished playing before the sender even finished its own drain delay.
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttDonePacket(200),
            2000L,
            senderNodeNum = 200,
        )
        manager.beginPeerPlaybackWait(timeoutMs = 30_000L)
        assertFalse(manager.state.value.isAwaitingPeerPlayback)
    }

    @Test
    fun testPeerTakingTheFloorEndsTheSendersWait() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.enterSession("!node_a", "Alice", 100)
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttHerePacket(200),
            1000L,
            senderNodeNum = 200,
        )
        manager.requestFloor(100, "!node_a")
        manager.releaseFloor(100)
        manager.beginPeerPlaybackWait(timeoutMs = 30_000L)
        assertTrue(manager.state.value.isAwaitingPeerPlayback)

        manager.peerFloorReq("!node_b", "Bob", 200, now = 5000L)
        assertFalse(manager.state.value.isAwaitingPeerPlayback)
        assertTrue(manager.state.value.isReceiving)
    }

    @Test
    fun testPttPresenceHandshakeAndWatchdog() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)

        val joinPacket = manager.enterSession("!node_a", "Alice", 100)
        assertEquals(2, joinPacket.size)
        assertEquals(LivePttSessionManager.OPCODE_PTT_JOIN, joinPacket[0])
        assertEquals(100, joinPacket[1].toInt() and 0xFF)
        assertTrue(manager.state.value.isInSession)
        assertEquals(0, manager.state.value.activePeersCount)

        val peerJoinPacket = LivePttSessionManager.createPttJoinPacket(200)
        manager.handleIncomingPayload("!node_b", "Bob", peerJoinPacket, 1000L, senderNodeNum = 200)
        assertEquals(1, manager.state.value.activePeersCount)
        assertTrue(manager.state.value.activePeers.contains("!node_b"))

        val replyEvent = receivedEvents.filterIsInstance<PttSessionEvent.ReplyPttHere>().firstOrNull()
        assertNotNull(replyEvent)
        assertEquals("!node_b", replyEvent.peerId)
        assertEquals(200, replyEvent.peerNodeNum)

        val initialReplyCount = receivedEvents.filterIsInstance<PttSessionEvent.ReplyPttHere>().size
        manager.handleIncomingPayload("!node_b", "Bob", peerJoinPacket, 2000L, senderNodeNum = 200)
        assertEquals(initialReplyCount, receivedEvents.filterIsInstance<PttSessionEvent.ReplyPttHere>().size)

        manager.handleIncomingPayload(
            "!node_c",
            "Charlie",
            LivePttSessionManager.createPttHerePacket(300),
            2500L,
            senderNodeNum = 300,
        )
        assertEquals(2, manager.state.value.activePeersCount)

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttLeavePacket(200),
            3000L,
            senderNodeNum = 200,
        )
        assertEquals(1, manager.state.value.activePeersCount)
        assertFalse(manager.state.value.activePeers.contains("!node_b"))

        // Charlie silent for 25.5 s: still present (heartbeats are every 10 s, expiry is 45 s).
        manager.pruneExpiredParticipants(currentTimeMs = 28_000L)
        assertEquals(1, manager.state.value.activePeersCount)

        manager.handleIncomingPayload(
            "!node_c",
            "Charlie",
            LivePttSessionManager.createPttLeavePacket(300),
            29_000L,
            senderNodeNum = 300,
        )
        assertEquals(0, manager.state.value.activePeersCount)

        val leavePacket = manager.leaveLiveSession(myNodeNum = 100)
        assertEquals(2, leavePacket.size)
        assertEquals(LivePttSessionManager.OPCODE_PTT_LEAVE, leavePacket[0])
        assertFalse(manager.state.value.isInSession)
    }

    @Test
    fun testHeartbeatFromUnknownPeerRepairsAHandshakeWhoseJoinWasLost() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        val receivedEvents = collectEvents(manager)
        manager.enterSession("!node_a", "Alice", 100)

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createHeartbeatPacket(200),
            5000L,
            senderNodeNum = 200,
        )

        // The peer is counted as present (previously a heartbeat only added a participant and the count stayed 0)...
        assertEquals(1, manager.state.value.activePeersCount)
        // ...and we answer so that the peer also learns about us.
        val reply = receivedEvents.filterIsInstance<PttSessionEvent.ReplyPttHere>().single()
        assertEquals("!node_b", reply.peerId)
        assertEquals(200, reply.peerNodeNum)

        // A known peer's next heartbeat does not trigger another reply.
        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createHeartbeatPacket(200),
            15_000L,
            senderNodeNum = 200,
        )
        assertEquals(1, receivedEvents.filterIsInstance<PttSessionEvent.ReplyPttHere>().size)
    }

    @Test
    fun testPresenceLoopRebroadcastsJoinWhileAloneAndHeartbeatsOnceThePeerIsKnown() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.enterSession("!node_a", "Alice", 100)
        assertEquals(LivePttSessionManager.OPCODE_PTT_JOIN, manager.nextPresencePacket(100)[0])
        assertEquals(LivePttSessionManager.PRESENCE_RETRY_INTERVAL_MS, manager.nextPresenceDelayMs())

        manager.handleIncomingPayload(
            "!node_b",
            "Bob",
            LivePttSessionManager.createPttHerePacket(200),
            1000L,
            senderNodeNum = 200,
        )
        assertEquals(LivePttSessionManager.OPCODE_HEARTBEAT, manager.nextPresencePacket(100)[0])
        assertEquals(LivePttSessionManager.PRESENCE_HEARTBEAT_INTERVAL_MS, manager.nextPresenceDelayMs())
    }

    @Test
    fun testOwnPacketsEchoedByTheMeshAreIgnored() = runTest {
        val manager = LivePttSessionManager(coroutineScope = backgroundScope)
        manager.enterSession("!node_a", "Alice", 100)
        manager.peerFloorReq("!node_a", "Alice", 100)
        assertFalse(manager.state.value.isReceiving)
        assertEquals(0, manager.state.value.activePeersCount)
    }
}
