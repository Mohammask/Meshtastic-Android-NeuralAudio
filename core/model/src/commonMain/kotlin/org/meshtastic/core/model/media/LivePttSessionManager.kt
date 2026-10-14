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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.NodeAddress

/** Events emitted by the [LivePttSessionManager] to drive UI banners, notifications, and audio playback. */
sealed interface PttSessionEvent {
    /** Prompt User B to opt-in: "User A started a Live PTT Session. [Tap to Join]" */
    data class InvitationReceived(val hostId: String, val hostName: String) : PttSessionEvent

    /** User joined the session. */
    data class UserJoined(val nodeId: String, val nodeName: String) : PttSessionEvent

    /** User left the session. */
    data class UserLeft(val nodeId: String, val nodeName: String) : PttSessionEvent

    /** Live audio arrived while the user is in the session -> play on speaker. Never persisted. */
    data class AutoPlayAudio(
        val senderId: String,
        val audioBytes: ByteArray,
        val payloadType: Byte = ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER,
    ) : PttSessionEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is AutoPlayAudio) return false
            return senderId == other.senderId &&
                audioBytes.contentEquals(other.audioBytes) &&
                payloadType == other.payloadType
        }

        override fun hashCode(): Int = 31 * (31 * senderId.hashCode() + audioBytes.contentHashCode()) + payloadType

        override fun toString(): String =
            "AutoPlayAudio(senderId='$senderId', bytes=${audioBytes.size}, payloadType=$payloadType)"
    }

    /** Peer requested floor reservation. */
    data class FloorRequested(val senderId: String, val senderName: String) : PttSessionEvent

    /** Peer released floor reservation (and the local node finished buffering and playing its audio). */
    data class FloorReleased(val senderId: String) : PttSessionEvent

    /** Local node lost collision tie-breaker to [winnerNodeId] and must abort transmission. */
    data class FloorCollisionLost(val winnerNodeId: String) : PttSessionEvent

    /** Peer broadcasted OPCODE_PTT_JOIN (or a heartbeat from an unknown peer); local node must reply with HERE. */
    data class ReplyPttHere(val peerId: String, val peerNodeNum: Int) : PttSessionEvent

    /**
     * The local speaker finished decoding AND playing everything [senderId] transmitted (HAL buffer drained). The UI
     * layer must ack the speaker with OPCODE_PTT_DONE so the speaker keeps its button locked until now.
     */
    data class PlaybackDrained(val senderId: String, val senderNodeNum: Int) : PttSessionEvent
}

/** Snapshot of the current PTT Walkie-Talkie session state. */
data class PttSessionState(
    val isInSession: Boolean = false,
    val activeSessionHostId: String? = null,
    val activeSessionHostName: String? = null,
    val pendingInvitation: PttInvitation? = null,
    val participants: Set<String> = emptySet(),
    val activePeers: Set<String> = emptySet(),
    val isReceiving: Boolean = false,
    val isPlayingAudio: Boolean = false,
    val activeSpeakerName: String? = null,
    val isTransmitting: Boolean = false,
    /** Local transmission finished on air; waiting for the peers' playback-drained ack (or its timeout). */
    val isAwaitingPeerPlayback: Boolean = false,
    val floorRejected: Boolean = false,
    val floorHolderNodeId: String? = null,
    val lastPeerJoinedName: String? = null,
) {
    val activePeersCount: Int
        get() = activePeers.size

    val isBothActive: Boolean
        get() = activePeers.isNotEmpty() || participants.size >= 2
}

data class PttInvitation(val hostNodeId: String, val hostName: String, val timestampMs: Long)

/**
 * Opt-In Live PTT session state machine: micro-packet floor control with a renewable floor LEASE, deterministic Node_ID
 * tie-breaking, self-healing presence, and an end-to-end "playback drained" handshake.
 *
 * Thread safety: every public mutator takes [lock]. Packets arrive on radio worker threads, playback callbacks on the
 * main thread, and timers on [coroutineScope]; all of them funnel through the same lock so the plain collections and
 * flags below are never observed half-updated.
 */
@Suppress("TooManyFunctions", "LargeClass")
class LivePttSessionManager(
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    /** Wall clock used for presence bookkeeping; injectable so tests can drive it from virtual time. */
    private val clock: () -> Long = { nowMillis },
) {

    companion object {
        val default = LivePttSessionManager()

        const val OPCODE_JOIN: Byte = 0x01
        const val OPCODE_LEAVE: Byte = 0x02
        const val OPCODE_HEARTBEAT: Byte = 0x03
        const val OPCODE_AUDIO_FRAME: Byte = 0x10
        const val OPCODE_FLOOR_REQ: Byte = 0x11
        const val OPCODE_FLOOR_REL: Byte = 0x12
        const val OPCODE_PTT_JOIN: Byte = 0x13
        const val OPCODE_PTT_HERE: Byte = 0x14
        const val OPCODE_PTT_LEAVE: Byte = 0x15

        /** Receiver -> speaker ack: "everything you sent has been played out of my speaker". 2 bytes. */
        const val OPCODE_PTT_DONE: Byte = 0x16

        const val FLAG_IS_LIVE_PTT: Byte = 0x01

        /**
         * Floor lease. Renewed by the speaker's FLOOR_REQ keepalive (every [FLOOR_KEEPALIVE_INTERVAL_MS]) and by every
         * live chunk. Must comfortably exceed the longest silent gap on air (PTT release -> encode -> first chunk).
         */
        const val FLOOR_LEASE_MS: Long = 7_000L
        const val FLOOR_INACTIVITY_TIMEOUT_MS: Long = FLOOR_LEASE_MS
        const val FLOOR_FAILSAFE_TIMEOUT_MS: Long = FLOOR_LEASE_MS
        const val FLOOR_DEADLOCK_TIMEOUT_MS: Long = FLOOR_LEASE_MS
        const val FLOOR_KEEPALIVE_INTERVAL_MS: Long = 2_000L

        /** Extra time granted once when the lease lapses while a live reassembly is still in flight (NACK rounds). */
        const val PENDING_REASSEMBLY_GRACE_MS: Long = 8_000L

        const val PARTICIPANT_EXPIRY_MS: Long = 25_000L

        /** Active peers renew presence with any packet; heartbeats arrive every [PRESENCE_HEARTBEAT_INTERVAL_MS]. */
        const val PEER_EXPIRY_MS: Long = 45_000L
        const val PRESENCE_HEARTBEAT_INTERVAL_MS: Long = 10_000L
        const val PRESENCE_RETRY_INTERVAL_MS: Long = 4_000L
        private const val HERE_REPLY_MIN_INTERVAL_MS = 5_000L
        private const val PLAYBACK_GUARD_MARGIN_MS = 15_000L

        /** Returns true if [opcode] corresponds to a lightweight PTT control micro-packet. */
        fun isPttControlOpcode(opcode: Byte): Boolean = opcode == OPCODE_JOIN ||
            opcode == OPCODE_LEAVE ||
            opcode == OPCODE_HEARTBEAT ||
            opcode == OPCODE_FLOOR_REQ ||
            opcode == OPCODE_FLOOR_REL ||
            opcode == OPCODE_PTT_JOIN ||
            opcode == OPCODE_PTT_HERE ||
            opcode == OPCODE_PTT_LEAVE ||
            opcode == OPCODE_PTT_DONE

        /** Creates a 3-byte floor request micro-packet: `[OPCODE_FLOOR_REQ (0x11) | nodeNum (2B)]`. */
        fun createFloorRequestPacket(nodeNum: Int): ByteArray =
            byteArrayOf(OPCODE_FLOOR_REQ, ((nodeNum shr 8) and 0xFF).toByte(), (nodeNum and 0xFF).toByte())

        /** Creates a 3-byte floor release micro-packet: `[OPCODE_FLOOR_REL (0x12) | nodeNum (2B)]`. */
        fun createFloorReleasePacket(nodeNum: Int): ByteArray =
            byteArrayOf(OPCODE_FLOOR_REL, ((nodeNum shr 8) and 0xFF).toByte(), (nodeNum and 0xFF).toByte())

        /** Creates a 3-byte heartbeat ping micro-packet: `[OPCODE_HEARTBEAT (0x03) | nodeNum (2B)]`. */
        fun createHeartbeatPacket(nodeNum: Int): ByteArray =
            byteArrayOf(OPCODE_HEARTBEAT, ((nodeNum shr 8) and 0xFF).toByte(), (nodeNum and 0xFF).toByte())

        /** Creates a 2-byte PTT join presence packet: `[OPCODE_PTT_JOIN (0x13) | nodeNum (1B)]`. */
        fun createPttJoinPacket(nodeNum: Int): ByteArray = byteArrayOf(OPCODE_PTT_JOIN, (nodeNum and 0xFF).toByte())

        /** Creates a 2-byte PTT presence acknowledgment packet: `[OPCODE_PTT_HERE (0x14) | nodeNum (1B)]`. */
        fun createPttHerePacket(nodeNum: Int): ByteArray = byteArrayOf(OPCODE_PTT_HERE, (nodeNum and 0xFF).toByte())

        /** Creates a 2-byte PTT leave presence packet: `[OPCODE_PTT_LEAVE (0x15) | nodeNum (1B)]`. */
        fun createPttLeavePacket(nodeNum: Int): ByteArray = byteArrayOf(OPCODE_PTT_LEAVE, (nodeNum and 0xFF).toByte())

        /** Creates a 2-byte playback-drained ack: `[OPCODE_PTT_DONE (0x16) | nodeNum (1B)]`. */
        fun createPttDonePacket(nodeNum: Int): ByteArray = byteArrayOf(OPCODE_PTT_DONE, (nodeNum and 0xFF).toByte())

        /** Parses a 2-byte node ID from byte 1 and byte 2 of a 3-byte micro-packet. */
        fun parseNodeNum(payload: ByteArray): Int {
            if (payload.size < 3) return 0
            val high = payload[1].toInt() and 0xFF
            val low = payload[2].toInt() and 0xFF
            return (high shl 8) or low
        }

        /** Opcodes that prove a peer is currently in the PTT room. */
        private fun isRoomPresenceOpcode(opcode: Byte): Boolean = opcode == OPCODE_JOIN ||
            opcode == OPCODE_HEARTBEAT ||
            opcode == OPCODE_AUDIO_FRAME ||
            opcode == OPCODE_FLOOR_REQ ||
            opcode == OPCODE_FLOOR_REL ||
            opcode == OPCODE_PTT_JOIN ||
            opcode == OPCODE_PTT_HERE ||
            opcode == OPCODE_PTT_DONE
    }

    private val _state = MutableStateFlow(PttSessionState())
    val state: StateFlow<PttSessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<PttSessionEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<PttSessionEvent> = _events.asSharedFlow()

    private val lock = Any()

    private var localNodeNum: Int = 0
    private var localNodeId: String = ""

    private val peerLastSeen = mutableMapOf<String, Long>()
    private val peerNodeNums = mutableMapOf<String, Int>()
    private val lastHereReplyTimeByPeer = mutableMapOf<String, Long>()
    private val participantLastSeen = mutableMapOf<String, Long>()
    private val participantNames = mutableMapOf<String, String>()

    // Receive-side floor lease
    private var leaseJob: Job? = null
    private var leaseGeneration = 0L
    private var peerReleased = false
    private var leaseExpired = false
    private var inboundPending = false
    private var pendingGraceUsed = false

    // Receive-side playback accounting (counter, not a boolean: messages may queue back to back)
    private var playbackCount = 0
    private var playbackGuardJob: Job? = null
    private val playedSenders = mutableSetOf<String>()

    // Send-side wait for the peers' playback-drained ack
    private var peerWaitJob: Job? = null
    private var awaitedPeers: Set<String> = emptySet()
    private val peerPlaybackAcks = mutableSetOf<String>()

    private var evictionJob: Job? = null

    /** True while at least one live message is queued, decoding or draining through the speaker HAL. */
    val isAudioPlaying: Boolean
        get() = _state.value.isPlayingAudio

    fun createFloorRequestPacket(nodeNum: Int): ByteArray = Companion.createFloorRequestPacket(nodeNum)

    fun createFloorReleasePacket(nodeNum: Int): ByteArray = Companion.createFloorReleasePacket(nodeNum)

    fun createHeartbeatPacket(nodeNum: Int): ByteArray = Companion.createHeartbeatPacket(nodeNum)

    /**
     * Next presence packet for the periodic presence loop: while the room looks empty a JOIN is re-broadcast (a single
     * lost JOIN/HERE exchange can no longer leave two nodes "alone" forever); otherwise a plain heartbeat.
     */
    fun nextPresencePacket(nodeNum: Int): ByteArray = if (_state.value.activePeers.isEmpty()) {
        createPttJoinPacket(nodeNum)
    } else {
        createHeartbeatPacket(nodeNum)
    }

    /** Delay before the next presence packet: fast retries while alone, relaxed heartbeat once peers are known. */
    fun nextPresenceDelayMs(): Long =
        if (_state.value.activePeers.isEmpty()) PRESENCE_RETRY_INTERVAL_MS else PRESENCE_HEARTBEAT_INTERVAL_MS

    fun startEvictionWatchdog() {
        synchronized(lock) { startEvictionWatchdogLocked() }
    }

    private fun startEvictionWatchdogLocked() {
        evictionJob?.cancel()
        evictionJob =
            coroutineScope.launch {
                while (isActive && _state.value.isInSession) {
                    delay(5_000L)
                    pruneExpiredParticipants(clock())
                }
            }
    }

    fun dispose() {
        synchronized(lock) {
            resetTransientLocked()
            evictionJob?.cancel()
            evictionJob = null
        }
    }

    private fun resetTransientLocked() {
        leaseGeneration++
        leaseJob?.cancel()
        leaseJob = null
        playbackGuardJob?.cancel()
        playbackGuardJob = null
        peerWaitJob?.cancel()
        peerWaitJob = null
        peerReleased = false
        leaseExpired = false
        inboundPending = false
        pendingGraceUsed = false
        playbackCount = 0
        playedSenders.clear()
        awaitedPeers = emptySet()
        peerPlaybackAcks.clear()
    }

    private fun clearPresenceLocked() {
        participantLastSeen.clear()
        participantNames.clear()
        peerLastSeen.clear()
        peerNodeNums.clear()
        lastHereReplyTimeByPeer.clear()
    }

    /** User enters the Live PTT Screen. Starts from a clean slate so stale locks never leak into the new session. */
    fun enterSession(myNodeId: String, myNodeName: String, myNodeNum: Int = 0): ByteArray = synchronized(lock) {
        resetTransientLocked()
        clearPresenceLocked()
        localNodeId = myNodeId
        localNodeNum = myNodeNum
        participantLastSeen[myNodeId] = clock()
        participantNames[myNodeId] = myNodeName
        startEvictionWatchdogLocked()

        _state.update {
            it.copy(
                isInSession = true,
                participants = setOf(myNodeId),
                activePeers = emptySet(),
                isReceiving = false,
                isPlayingAudio = false,
                activeSpeakerName = null,
                isTransmitting = false,
                isAwaitingPeerPlayback = false,
                floorRejected = false,
                floorHolderNodeId = null,
            )
        }
        createPttJoinPacket(myNodeNum)
    }

    /** User initiates hosting a Live PTT session. */
    fun startHosting(myNodeId: String, myNodeName: String, myNodeNum: Int = 0): ByteArray = synchronized(lock) {
        resetTransientLocked()
        clearPresenceLocked()
        localNodeId = myNodeId
        localNodeNum = myNodeNum
        participantLastSeen[myNodeId] = clock()
        participantNames[myNodeId] = myNodeName
        startEvictionWatchdogLocked()

        _state.update {
            it.copy(
                isInSession = true,
                activeSessionHostId = myNodeId,
                activeSessionHostName = myNodeName,
                pendingInvitation = null,
                participants = setOf(myNodeId),
                activePeers = emptySet(),
                isReceiving = false,
                isPlayingAudio = false,
                activeSpeakerName = null,
                isTransmitting = false,
                isAwaitingPeerPlayback = false,
                floorRejected = false,
                floorHolderNodeId = null,
            )
        }
        createControlPacket(OPCODE_JOIN, myNodeId, myNodeName)
    }

    /** User B taps [Tap to Join] on the UI banner. */
    fun joinSession(
        hostId: String,
        hostName: String,
        myNodeId: String,
        myNodeName: String,
        myNodeNum: Int = 0,
    ): ByteArray = synchronized(lock) {
        resetTransientLocked()
        localNodeId = myNodeId
        localNodeNum = myNodeNum
        val now = clock()
        participantLastSeen[myNodeId] = now
        participantNames[myNodeId] = myNodeName
        participantLastSeen[hostId] = now
        peerLastSeen[hostId] = now
        participantNames[hostId] = hostName

        _state.update {
            it.copy(
                isInSession = true,
                activeSessionHostId = hostId,
                activeSessionHostName = hostName,
                pendingInvitation = null,
                participants = it.participants + hostId + myNodeId,
                activePeers = it.activePeers + hostId,
                lastPeerJoinedName = hostName,
                isReceiving = false,
                isPlayingAudio = false,
                activeSpeakerName = null,
                isTransmitting = false,
                isAwaitingPeerPlayback = false,
                floorRejected = false,
                floorHolderNodeId = null,
            )
        }
        startEvictionWatchdogLocked()
        _events.tryEmit(PttSessionEvent.UserJoined(myNodeId, myNodeName))
        createControlPacket(OPCODE_JOIN, myNodeId, myNodeName)
    }

    /** User leaves the active session. Resets every lock, counter and timer. */
    fun leaveSession(myNodeId: String = "!local", myNodeName: String = "Me"): ByteArray = synchronized(lock) {
        resetTransientLocked()
        evictionJob?.cancel()
        evictionJob = null
        clearPresenceLocked()

        _state.update {
            it.copy(
                isInSession = false,
                activeSessionHostId = null,
                activeSessionHostName = null,
                pendingInvitation = null,
                participants = emptySet(),
                activePeers = emptySet(),
                isReceiving = false,
                isPlayingAudio = false,
                activeSpeakerName = null,
                isTransmitting = false,
                isAwaitingPeerPlayback = false,
                floorRejected = false,
                floorHolderNodeId = null,
            )
        }
        _events.tryEmit(PttSessionEvent.UserLeft(myNodeId, myNodeName))
        createControlPacket(OPCODE_LEAVE, myNodeId, myNodeName)
    }

    /** User leaves Live PTT screen, returning 2-byte OPCODE_PTT_LEAVE micro-packet. */
    fun leaveLiveSession(
        myNodeNum: Int = localNodeNum,
        myNodeId: String = "!local",
        myNodeName: String = "Me",
    ): ByteArray {
        val targetNodeNum = if (myNodeNum != 0) myNodeNum else localNodeNum
        leaveSession(myNodeId, myNodeName)
        return createPttLeavePacket(targetNodeNum)
    }

    /** Dismisses a pending invitation banner without joining. */
    fun dismissInvitation() {
        _state.update { it.copy(pendingInvitation = null) }
    }

    /**
     * User touched down the PTT button to reserve the floor immediately.
     *
     * @return 3-byte `[OPCODE_FLOOR_REQ (0x11) | nodeNum (2B)]` packet to broadcast unacknowledged.
     */
    fun requestFloor(myNodeNum: Int, myNodeId: String): ByteArray = synchronized(lock) {
        localNodeNum = myNodeNum
        localNodeId = myNodeId
        // We are the speaker now: no peer lease applies, and acks from earlier transmissions are stale.
        leaseGeneration++
        leaseJob?.cancel()
        leaseJob = null
        peerReleased = false
        leaseExpired = false
        inboundPending = false
        peerPlaybackAcks.clear()
        endPeerWaitLocked()
        _state.update {
            it.copy(
                isTransmitting = true,
                floorRejected = false,
                isReceiving = false,
                activeSpeakerName = null,
                floorHolderNodeId = myNodeId,
            )
        }
        createFloorRequestPacket(myNodeNum)
    }

    /**
     * User released the PTT button / the transmission fully drained to release the floor reservation.
     *
     * @return 3-byte `[OPCODE_FLOOR_REL (0x12) | nodeNum (2B)]` packet to broadcast unacknowledged.
     */
    fun releaseFloor(myNodeNum: Int): ByteArray = synchronized(lock) {
        _state.update {
            if (it.floorHolderNodeId == localNodeId) {
                it.copy(isTransmitting = false, floorHolderNodeId = null)
            } else {
                it.copy(isTransmitting = false)
            }
        }
        createFloorReleasePacket(myNodeNum)
    }

    /**
     * Keeps the local PTT button locked after the last chunk left the radio until every active peer reported that it
     * finished playing (OPCODE_PTT_DONE), or [timeoutMs] elapsed (lost ack / peer crashed). Acks that arrived while the
     * local node was still transmitting are honoured.
     */
    fun beginPeerPlaybackWait(timeoutMs: Long) {
        synchronized(lock) {
            val expected = _state.value.activePeers
            if ((expected - peerPlaybackAcks).isEmpty()) return
            awaitedPeers = expected
            _state.update { it.copy(isAwaitingPeerPlayback = true) }
            peerWaitJob?.cancel()
            peerWaitJob =
                coroutineScope.launch {
                    delay(timeoutMs)
                    synchronized(lock) { endPeerWaitLocked() }
                }
        }
    }

    private fun endPeerWaitLocked() {
        peerWaitJob?.cancel()
        peerWaitJob = null
        awaitedPeers = emptySet()
        if (_state.value.isAwaitingPeerPlayback) {
            _state.update { it.copy(isAwaitingPeerPlayback = false) }
        }
    }

    private fun maybeFinishPeerWaitLocked() {
        if (_state.value.isAwaitingPeerPlayback && (awaitedPeers - peerPlaybackAcks).isEmpty()) {
            endPeerWaitLocked()
        }
    }

    /**
     * Called exactly once per [PttSessionEvent.AutoPlayAudio] when the AudioTrack has drained its HAL hardware buffer
     * (plus the acoustic safety margin), or when playback failed / was interrupted.
     */
    fun onAudioPlaybackCompleted() {
        synchronized(lock) {
            playbackCount = (playbackCount - 1).coerceAtLeast(0)
            if (playbackCount == 0) {
                playbackGuardJob?.cancel()
                playbackGuardJob = null
                _state.update { it.copy(isPlayingAudio = false) }
                evaluateFloorReleaseLocked()
                emitPlaybackDrainedLocked()
            }
        }
    }

    /**
     * Called by the data layer when a live reassembly from [senderId] finished (complete or concealed). From this point
     * the audio is either queued for playback or lost, so the inbound-pending hold on the floor is dropped.
     */
    fun onInboundMediaSettled(senderId: String) {
        synchronized(lock) {
            val holder = _state.value.floorHolderNodeId
            if (holder == null || holder == senderId) {
                inboundPending = false
                pendingGraceUsed = false
            }
            evaluateFloorReleaseLocked()
            emitPlaybackDrainedLocked()
        }
    }

    /**
     * Handles incoming control or media packets received over the Meshtastic mesh.
     *
     * @param senderId Node ID of the sender.
     * @param senderName User-facing name of the sender.
     * @param payload Raw payload from `PortNum.PRIVATE_APP`.
     * @param currentTimeMs Current timestamp in milliseconds.
     * @param payloadType Media type indicator (e.g. [ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER]).
     * @param senderNodeNum 32-bit node number of sender if available.
     */
    fun handleIncomingPayload(
        senderId: String,
        senderName: String,
        payload: ByteArray,
        currentTimeMs: Long,
        payloadType: Byte = ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER,
        senderNodeNum: Int = 0,
    ) {
        if (payload.isEmpty()) return
        synchronized(lock) {
            val opcode = payload[0]
            // Ignore our own packets echoed back by the mesh.
            if (localNodeId.isNotEmpty() && senderId == localNodeId) return
            if (senderNodeNum != 0) peerNodeNums[senderId] = senderNodeNum

            val current = _state.value
            val wasKnownPeer = current.activePeers.contains(senderId)
            // Presence is only tracked for packets that prove the sender is in the room, and only while WE are in the
            // room: traffic observed outside a session must not seed participants for the next session.
            if (current.isInSession && isRoomPresenceOpcode(opcode)) {
                recordPeerPresenceLocked(senderId, senderName, currentTimeMs)
            }

            when (opcode) {
                OPCODE_JOIN -> handleIncomingJoinLocked(senderId, senderName, currentTimeMs)

                OPCODE_LEAVE,
                OPCODE_PTT_LEAVE,
                -> handleIncomingLeaveLocked(senderId, senderName)

                OPCODE_HEARTBEAT ->
                    if (current.isInSession && !wasKnownPeer) maybeReplyHereLocked(senderId, currentTimeMs)

                OPCODE_AUDIO_FRAME -> handleIncomingAudioLocked(senderId, payload, payloadType)

                OPCODE_FLOOR_REQ -> handleIncomingFloorReqLocked(senderId, senderName, payload, senderNodeNum)

                OPCODE_FLOOR_REL -> handleIncomingFloorRelLocked(senderId)

                OPCODE_PTT_JOIN -> if (current.isInSession) maybeReplyHereLocked(senderId, currentTimeMs)

                OPCODE_PTT_DONE -> handleIncomingPttDoneLocked(senderId)

                else -> Unit // PTT_HERE: presence already recorded above.
            }
        }
    }

    private fun handleIncomingFloorReqLocked(
        senderId: String,
        senderName: String,
        payload: ByteArray,
        senderNodeNum: Int,
    ) {
        val currentState = _state.value
        // Floor control only exists inside a session; outside one a stray REQ must not lock the chat mic button.
        if (!currentState.isInSession) return

        val peerNum =
            when {
                senderNodeNum != 0 -> senderNodeNum
                else -> NodeAddress.idToNum(senderId) ?: parseNodeNum(payload)
            }
        val localNum = if (localNodeNum != 0) localNodeNum else (NodeAddress.idToNum(localNodeId) ?: 0)

        if (currentState.isTransmitting) {
            // Simultaneous keying collision tie-breaker: lower unsigned 32-bit node ID wins the floor.
            val localWins = localNum.toUInt() < peerNum.toUInt()
            if (localWins) return
            peerReleased = false
            leaseExpired = false
            pendingGraceUsed = false
            _state.update {
                it.copy(
                    isTransmitting = false,
                    floorRejected = true,
                    isReceiving = true,
                    activeSpeakerName = senderName,
                    floorHolderNodeId = senderId,
                )
            }
            endPeerWaitLocked()
            _events.tryEmit(PttSessionEvent.FloorCollisionLost(senderId))
            armLeaseLocked(FLOOR_LEASE_MS)
            return
        }

        val holder = currentState.floorHolderNodeId
        if (currentState.isReceiving && holder != null && holder != senderId && !leaseExpired) {
            // Another peer already holds a live lease: a late/colliding REQ cannot steal it.
            return
        }

        val isNewHolder = !(currentState.isReceiving && holder == senderId)
        peerReleased = false
        leaseExpired = false
        pendingGraceUsed = false
        endPeerWaitLocked() // a peer taking the floor proves it has finished listening to us
        _state.update {
            it.copy(
                isReceiving = true,
                activeSpeakerName = senderName,
                floorHolderNodeId = senderId,
                floorRejected = false,
            )
        }
        if (isNewHolder) _events.tryEmit(PttSessionEvent.FloorRequested(senderId, senderName))
        armLeaseLocked(FLOOR_LEASE_MS)
    }

    private fun handleIncomingFloorRelLocked(senderId: String) {
        val currentState = _state.value
        if (!currentState.isInSession || currentState.floorHolderNodeId != senderId) return
        // The release may overtake the last chunks on air or arrive before reassembly/playback finished: record it and
        // let evaluateFloorReleaseLocked() free the floor only once nothing is pending.
        peerReleased = true
        evaluateFloorReleaseLocked()
    }

    private fun handleIncomingPttDoneLocked(senderId: String) {
        peerPlaybackAcks += senderId
        maybeFinishPeerWaitLocked()
    }

    private fun handleIncomingJoinLocked(senderId: String, senderName: String, currentTimeMs: Long) {
        if (_state.value.isInSession) {
            _state.update { it.copy(participants = it.participants + senderId, lastPeerJoinedName = senderName) }
            _events.tryEmit(PttSessionEvent.UserJoined(senderId, senderName))
        } else {
            val invitation = PttInvitation(hostNodeId = senderId, hostName = senderName, timestampMs = currentTimeMs)
            _state.update { it.copy(pendingInvitation = invitation) }
            _events.tryEmit(PttSessionEvent.InvitationReceived(senderId, senderName))
        }
    }

    private fun handleIncomingLeaveLocked(senderId: String, senderName: String) {
        participantLastSeen.remove(senderId)
        participantNames.remove(senderId)
        peerLastSeen.remove(senderId)
        peerNodeNums.remove(senderId)
        lastHereReplyTimeByPeer.remove(senderId)
        removePeerFromStateLocked(setOf(senderId))
        _events.tryEmit(PttSessionEvent.UserLeft(senderId, senderName))
    }

    private fun removePeerFromStateLocked(ids: Set<String>) {
        val holderLeft = _state.value.floorHolderNodeId?.let { it in ids } == true
        awaitedPeers = awaitedPeers - ids
        peerPlaybackAcks -= ids
        if (holderLeft) {
            leaseGeneration++
            leaseJob?.cancel()
            leaseJob = null
            peerReleased = false
            leaseExpired = false
            inboundPending = false
            pendingGraceUsed = false
        }
        _state.update {
            val isHost = it.activeSessionHostId?.let { host -> host in ids } == true
            it.copy(
                participants = it.participants - ids,
                activePeers = it.activePeers - ids,
                activeSessionHostId = if (isHost) null else it.activeSessionHostId,
                activeSessionHostName = if (isHost) null else it.activeSessionHostName,
                isReceiving = if (holderLeft) false else it.isReceiving,
                activeSpeakerName = if (holderLeft) null else it.activeSpeakerName,
                floorHolderNodeId = if (holderLeft) null else it.floorHolderNodeId,
            )
        }
        maybeFinishPeerWaitLocked()
    }

    private fun handleIncomingAudioLocked(senderId: String, payload: ByteArray, payloadType: Byte) {
        if (payload.size < 2) return
        val isLivePtt = (payload[1].toInt() and FLAG_IS_LIVE_PTT.toInt()) != 0
        // Live PTT audio outside a session evaporates: it is never stored, queued or replayed later.
        if (!_state.value.isInSession || !isLivePtt) return
        val audioBytes = payload.copyOfRange(2, payload.size)
        if (audioBytes.isEmpty()) return

        queuePlaybackLocked(senderId, audioBytes.size)
        val speakerName = participantNames[senderId] ?: senderId
        _state.update {
            it.copy(
                isPlayingAudio = true,
                activeSpeakerName =
                if (it.floorHolderNodeId == null || it.floorHolderNodeId == senderId) {
                    speakerName
                } else {
                    it.activeSpeakerName
                },
            )
        }
        _events.tryEmit(PttSessionEvent.AutoPlayAudio(senderId, audioBytes, payloadType))
    }

    /** Counts the playback BEFORE the event is consumed so there is no window where audio exists but is not counted. */
    private fun queuePlaybackLocked(senderId: String, audioSize: Int) {
        playbackCount++
        playedSenders += senderId
        // Safety net if no consumer ever reports completion (no subscriber, crashed player): never lock forever.
        val guardMs = (audioSize.toLong() * 1000L) / 80L + PLAYBACK_GUARD_MARGIN_MS
        playbackGuardJob?.cancel()
        playbackGuardJob =
            coroutineScope.launch {
                delay(guardMs)
                synchronized(lock) {
                    if (playbackCount > 0) {
                        playbackCount = 0
                        _state.update { it.copy(isPlayingAudio = false) }
                        evaluateFloorReleaseLocked()
                    }
                }
            }
    }

    private fun maybeReplyHereLocked(senderId: String, currentTimeMs: Long) {
        val lastReply = lastHereReplyTimeByPeer[senderId]
        if (lastReply == null || currentTimeMs - lastReply >= HERE_REPLY_MIN_INTERVAL_MS) {
            lastHereReplyTimeByPeer[senderId] = currentTimeMs
            _events.tryEmit(PttSessionEvent.ReplyPttHere(peerId = senderId, peerNodeNum = peerNodeNums[senderId] ?: 0))
        }
    }

    private fun recordPeerPresenceLocked(senderId: String, senderName: String, currentTimeMs: Long) {
        if (senderId == localNodeId) return
        peerLastSeen[senderId] = currentTimeMs
        participantLastSeen[senderId] = currentTimeMs
        if (senderName.isNotBlank()) participantNames[senderId] = senderName
        val current = _state.value
        val isNew = !current.activePeers.contains(senderId)
        if (isNew || !current.participants.contains(senderId)) {
            _state.update {
                it.copy(
                    activePeers = it.activePeers + senderId,
                    participants = it.participants + senderId,
                    lastPeerJoinedName = if (isNew) senderName else it.lastPeerJoinedName,
                )
            }
        }
    }

    /**
     * Called when a LIVE audio chunk from [senderId] arrives (never for ordinary voice notes). Renews the floor lease,
     * marks a reassembly as in flight, and locks the floor to the sender if its FLOOR_REQ was lost.
     */
    fun onRfCarrierActivity(senderId: String, currentTimeMs: Long = clock(), leaseMs: Long = FLOOR_LEASE_MS) {
        synchronized(lock) {
            val current = _state.value
            if (!current.isInSession || senderId == localNodeId) return
            recordPeerPresenceLocked(senderId, participantNames[senderId] ?: senderId, currentTimeMs)
            if (current.isTransmitting) return
            val holder = current.floorHolderNodeId
            if (holder == null || holder == senderId) {
                inboundPending = true
                pendingGraceUsed = false
                leaseExpired = false
                _state.update {
                    it.copy(
                        isReceiving = true,
                        floorHolderNodeId = senderId,
                        activeSpeakerName = participantNames[senderId] ?: senderId,
                    )
                }
                armLeaseLocked(leaseMs)
            }
        }
    }

    private fun armLeaseLocked(timeoutMs: Long) {
        val generation = ++leaseGeneration
        leaseJob?.cancel()
        leaseJob =
            coroutineScope.launch {
                delay(timeoutMs)
                onLeaseExpired(generation)
            }
    }

    private fun onLeaseExpired(generation: Long) {
        synchronized(lock) {
            if (generation != leaseGeneration || !_state.value.isReceiving) return
            if (inboundPending && !pendingGraceUsed) {
                // Chunks were seen but the reassembly has not settled (NACK rounds): grant one grace period.
                pendingGraceUsed = true
                armLeaseLocked(PENDING_REASSEMBLY_GRACE_MS)
                return
            }
            leaseExpired = true
            inboundPending = false
            evaluateFloorReleaseLocked()
        }
    }

    /**
     * Single place where the receive-side floor is freed. It is released only when the speaker is done (FLOOR_REL or
     * lease lapse) AND nothing from it is still reassembling or playing out of the speaker.
     */
    private fun evaluateFloorReleaseLocked() {
        val current = _state.value
        if (!current.isReceiving) return
        if (playbackCount > 0 || inboundPending) return
        if (!peerReleased && !leaseExpired) return

        val holder = current.floorHolderNodeId
        leaseGeneration++
        leaseJob?.cancel()
        leaseJob = null
        peerReleased = false
        leaseExpired = false
        pendingGraceUsed = false
        _state.update {
            it.copy(
                isReceiving = false,
                activeSpeakerName = null,
                floorHolderNodeId = if (it.floorHolderNodeId == holder) null else it.floorHolderNodeId,
            )
        }
        _events.tryEmit(PttSessionEvent.FloorReleased(holder ?: ""))
    }

    private fun emitPlaybackDrainedLocked() {
        if (playbackCount > 0 || playedSenders.isEmpty()) return
        val holder = _state.value.floorHolderNodeId
        val ready = playedSenders.filter { !(inboundPending && it == holder) }
        for (sender in ready) {
            playedSenders -= sender
            val num = peerNodeNums[sender] ?: NodeAddress.idToNum(sender) ?: 0
            _events.tryEmit(PttSessionEvent.PlaybackDrained(sender, num))
        }
    }

    /**
     * Prunes session participants that stopped talking. Legacy participants expire after [PARTICIPANT_EXPIRY_MS];
     * active PTT peers (JOIN/HERE/heartbeat) after [PEER_EXPIRY_MS], which is more than three missed heartbeats, so a
     * crashed peer can no longer keep the room looking populated (and the floor locked) forever.
     */
    fun pruneExpiredParticipants(currentTimeMs: Long) {
        synchronized(lock) {
            val current = _state.value
            val expired = mutableSetOf<String>()
            for ((id, lastSeen) in participantLastSeen) {
                if (id == localNodeId) continue
                val limit = if (current.activePeers.contains(id)) PEER_EXPIRY_MS else PARTICIPANT_EXPIRY_MS
                if (currentTimeMs - lastSeen > limit) expired += id
            }
            for ((id, lastSeen) in peerLastSeen) {
                if (id != localNodeId && currentTimeMs - lastSeen > PEER_EXPIRY_MS) expired += id
            }
            if (expired.isEmpty()) return

            for (id in expired) {
                participantLastSeen.remove(id)
                peerLastSeen.remove(id)
                peerNodeNums.remove(id)
                lastHereReplyTimeByPeer.remove(id)
                val name = participantNames.remove(id) ?: id
                _events.tryEmit(PttSessionEvent.UserLeft(id, name))
            }
            removePeerFromStateLocked(expired)
        }
    }

    /** Builds a binary control packet: [Opcode (1B) | NodeIdLen (1B) | NodeId | NameLen (1B) | Name]. */
    fun createControlPacket(opcode: Byte, nodeId: String, nodeName: String): ByteArray {
        val nodeIdBytes = nodeId.encodeToByteArray()
        val nameBytes = nodeName.encodeToByteArray()

        val packet = ByteArray(1 + 1 + nodeIdBytes.size + 1 + nameBytes.size)
        var offset = 0
        packet[offset++] = opcode
        packet[offset++] = nodeIdBytes.size.toByte()
        nodeIdBytes.copyInto(packet, destinationOffset = offset)
        offset += nodeIdBytes.size
        packet[offset++] = nameBytes.size.toByte()
        nameBytes.copyInto(packet, destinationOffset = offset)

        return packet
    }

    /** Wraps compressed Codec 2 or WavTokenizer audio into a wire frame with opcode and Live PTT flags. */
    fun createAudioPacket(audioBytes: ByteArray, isLivePtt: Boolean): ByteArray {
        val packet = ByteArray(2 + audioBytes.size)
        packet[0] = OPCODE_AUDIO_FRAME
        packet[1] = if (isLivePtt) FLAG_IS_LIVE_PTT else 0x00
        audioBytes.copyInto(packet, destinationOffset = 2)
        return packet
    }
}
