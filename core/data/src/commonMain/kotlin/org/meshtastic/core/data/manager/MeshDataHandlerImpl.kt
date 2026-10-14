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
package org.meshtastic.core.data.manager

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.koin.core.annotation.Single
import org.meshtastic.core.common.di.ServiceScope
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.common.util.nowSeconds
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MeshBeaconOffer
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.Reaction
import org.meshtastic.core.model.destination
import org.meshtastic.core.model.geofence.activeWaypointPackets
import org.meshtastic.core.model.isBroadcast
import org.meshtastic.core.model.isFromLocal
import org.meshtastic.core.model.isModifiableBy
import org.meshtastic.core.model.media.AudioDspUtils
import org.meshtastic.core.model.media.ChunkingEngine
import org.meshtastic.core.model.media.Codec2Engine
import org.meshtastic.core.model.media.CompletedMediaPayload
import org.meshtastic.core.model.media.InboundMediaProgress
import org.meshtastic.core.model.media.LivePttSessionManager
import org.meshtastic.core.model.media.MediaStorage
import org.meshtastic.core.model.media.PayloadAnalyzer
import org.meshtastic.core.model.media.PcCommandSessionManager
import org.meshtastic.core.model.media.PcStreamStatus
import org.meshtastic.core.model.media.ReassemblyBuffer
import org.meshtastic.core.model.media.ReassemblyResult
import org.meshtastic.core.model.media.SentTransmissionCache
import org.meshtastic.core.model.media.WavTokenizerEngine
import org.meshtastic.core.model.source
import org.meshtastic.core.model.textMentionsNode
import org.meshtastic.core.model.util.MeshDataMapper
import org.meshtastic.core.model.util.decodeOrNull
import org.meshtastic.core.model.util.isAlreadyJoined
import org.meshtastic.core.model.util.isValidCodePoint
import org.meshtastic.core.model.util.snrOrNull
import org.meshtastic.core.model.util.toOneLiner
import org.meshtastic.core.repository.ActiveConversationTracker
import org.meshtastic.core.repository.AdminPacketHandler
import org.meshtastic.core.repository.DataPair
import org.meshtastic.core.repository.DiscoveryPacketCollectorRegistry
import org.meshtastic.core.repository.MeshBeaconRepository
import org.meshtastic.core.repository.MeshDataHandler
import org.meshtastic.core.repository.MeshNotificationManager
import org.meshtastic.core.repository.MessageAnnouncement
import org.meshtastic.core.repository.MessageFilter
import org.meshtastic.core.repository.NeighborInfoHandler
import org.meshtastic.core.repository.NodeManager
import org.meshtastic.core.repository.Notification
import org.meshtastic.core.repository.NotificationManager
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PlatformAnalytics
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.RadioInterfaceService
import org.meshtastic.core.repository.RadioSessionContext
import org.meshtastic.core.repository.ServiceStateWriter
import org.meshtastic.core.repository.StoreForwardPacketHandler
import org.meshtastic.core.repository.TelemetryPacketHandler
import org.meshtastic.core.repository.TracerouteHandler
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.critical_alert
import org.meshtastic.core.resources.error_duty_cycle
import org.meshtastic.core.resources.getStringSuspend
import org.meshtastic.core.resources.mesh_beacon_notification_body
import org.meshtastic.core.resources.mesh_beacon_notification_title
import org.meshtastic.core.resources.unknown_username
import org.meshtastic.core.resources.waypoint_received
import org.meshtastic.proto.MeshBeacon
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Paxcount
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Routing
import org.meshtastic.proto.StatusMessage
import org.meshtastic.proto.User
import org.meshtastic.proto.Waypoint

/**
 * Implementation of [MeshDataHandler] that decodes and routes incoming mesh data packets.
 *
 * This class handles the complexity of:
 * 1. Mapping raw [MeshPacket] objects to domain-friendly [DataPacket] objects.
 * 2. Routing packets to specialized handlers (e.g., Traceroute, NeighborInfo, Telemetry, Admin, SFPP).
 * 3. Managing message history and persistence.
 * 4. Triggering notifications for various packet types (Text, Waypoints).
 */
@Suppress("LongParameterList", "TooManyFunctions", "CyclomaticComplexMethod")
@Single
class MeshDataHandlerImpl(
    private val nodeManager: NodeManager,
    private val packetHandler: PacketHandler,
    private val serviceStateWriter: ServiceStateWriter,
    private val packetRepository: Lazy<PacketRepository>,
    private val notificationManager: NotificationManager,
    private val serviceNotifications: MeshNotificationManager,
    private val analytics: PlatformAnalytics,
    private val dataMapper: MeshDataMapper,
    private val tracerouteHandler: TracerouteHandler,
    private val neighborInfoHandler: NeighborInfoHandler,
    private val radioConfigRepository: RadioConfigRepository,
    private val messageFilter: MessageFilter,
    private val storeForwardHandler: StoreForwardPacketHandler,
    private val telemetryHandler: TelemetryPacketHandler,
    private val adminPacketHandler: AdminPacketHandler,
    private val collectorRegistry: DiscoveryPacketCollectorRegistry,
    private val geofenceMonitor: GeofenceMonitor,
    private val meshBeaconRepository: MeshBeaconRepository,
    private val radioInterfaceService: RadioInterfaceService,
    private val activeConversationTracker: ActiveConversationTracker,
    private val scope: ServiceScope,
) : MeshDataHandler {

    private val reassemblyBuffer = ReassemblyBuffer()

    override val inboundMediaProgress: StateFlow<InboundMediaProgress?>
        get() = reassemblyBuffer.inboundProgress

    @Volatile private var lastRadioSessionContext: RadioSessionContext? = null
    private var watchdogJob: Job? = null

    internal fun startReassemblyWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob =
            scope.launch {
                while (isActive) {
                    delay(150L)
                    processReassemblyTimeouts()
                    if (reassemblyBuffer.activeSessionCount == 0) {
                        break
                    }
                }
            }
    }

    internal fun stopReassemblyWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    internal suspend fun processReassemblyTimeouts(currentTimeMs: Long = nowMillis) {
        val results = reassemblyBuffer.checkTimeouts(currentTimeMs)
        if (results.isEmpty()) return

        val myNode = nodeManager.myNodeNum.value ?: 0
        for (result in results) {
            when (result) {
                is ReassemblyResult.SendNack -> {
                    val destNum = NodeAddress.idToNum(result.senderId) ?: NodeAddress.NODENUM_BROADCAST
                    val nackMeshPacket =
                        MeshPacket(
                            from = myNode,
                            to = destNum,
                            id = kotlin.random.Random.nextInt(1, Int.MAX_VALUE),
                            want_ack = false,
                            channel = result.channel,
                            decoded =
                            org.meshtastic.proto.Data(
                                portnum = PortNum.PRIVATE_APP,
                                payload = result.nackPacket.toByteString(),
                            ),
                        )
                    packetHandler.sendToRadio(nackMeshPacket)
                }

                is ReassemblyResult.Complete -> {
                    val dataPacket =
                        result.completedPayload.sampleDataPacket
                            ?: DataPacket(
                                to = NodeAddress.ID_BROADCAST,
                                bytes = ByteString.EMPTY,
                                dataType = PortNum.PRIVATE_APP.value,
                                channel = result.completedPayload.channel,
                            )
                                .apply {
                                    from = result.completedPayload.senderId
                                    id = result.completedPayload.messageId and 0xFF
                                }
                    handleCompletedMediaPayload(
                        completed = result.completedPayload,
                        dataPacket = dataPacket,
                        myNodeNum = myNode,
                        session = lastRadioSessionContext,
                    )
                }
            }
        }
    }

    override fun handleReceivedData(
        packet: MeshPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
        logUuid: String?,
        logInsertJob: Job?,
    ) {
        lastRadioSessionContext = session
        val dataPacket = dataMapper.toDataPacket(packet) ?: return
        val fromUs = myNodeNum == packet.from
        dataPacket.status = MessageStatus.RECEIVED

        handleDataPacket(packet, dataPacket, myNodeNum, fromUs, session, logUuid, logInsertJob)

        analytics.track("num_data_receive", DataPair("num_data_receive", 1))

        // Forward to discovery scan collector if active
        collectorRegistry.collector?.let { collector ->
            if (collector.isActive) {
                radioInterfaceService.launchSessionWork(scope, session) {
                    collector.onPacketReceived(packet, dataPacket)
                }
            }
        }
    }

    private fun handleDataPacket(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        fromUs: Boolean,
        session: RadioSessionContext,
        logUuid: String?,
        logInsertJob: Job?,
    ) {
        val decoded = packet.decoded ?: return
        when (decoded.portnum) {
            PortNum.TEXT_MESSAGE_APP -> handleTextMessage(packet, dataPacket, myNodeNum, session)
            PortNum.NODE_STATUS_APP -> handleNodeStatus(packet, dataPacket, myNodeNum, session)
            PortNum.ALERT_APP -> rememberDataPacket(dataPacket, myNodeNum, session = session)
            PortNum.WAYPOINT_APP -> handleWaypoint(packet, dataPacket, myNodeNum, session)
            PortNum.POSITION_APP -> handlePosition(packet, dataPacket, myNodeNum, session)
            PortNum.NODEINFO_APP -> if (!fromUs) handleNodeInfo(packet, session)
            PortNum.TELEMETRY_APP -> telemetryHandler.handleTelemetry(packet, dataPacket, myNodeNum, session)
            else -> handleSpecializedDataPacket(packet, dataPacket, myNodeNum, session, logUuid, logInsertJob)
        }
    }

    private fun handleSpecializedDataPacket(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
        logUuid: String?,
        logInsertJob: Job?,
    ) {
        val decoded = packet.decoded ?: return
        when (decoded.portnum) {
            PortNum.TRACEROUTE_APP -> {
                tracerouteHandler.handleTraceroute(packet, logUuid, logInsertJob, session)
            }

            PortNum.ROUTING_APP -> {
                handleRouting(packet, dataPacket, session)
            }

            PortNum.PAXCOUNTER_APP -> {
                handlePaxCounter(packet, session)
            }

            PortNum.STORE_FORWARD_APP -> {
                storeForwardHandler.handleStoreAndForward(packet, dataPacket, myNodeNum, session)
            }

            PortNum.STORE_FORWARD_PLUSPLUS_APP -> {
                storeForwardHandler.handleStoreForwardPlusPlus(packet, session)
            }

            PortNum.ADMIN_APP -> {
                adminPacketHandler.handleAdminMessage(packet, myNodeNum, session)
            }

            PortNum.NEIGHBORINFO_APP -> {
                neighborInfoHandler.handleNeighborInfo(packet)
            }

            PortNum.ATAK_PLUGIN,
            PortNum.ATAK_PLUGIN_V2,
            -> {}

            PortNum.PRIVATE_APP -> {
                handlePrivateAppPacket(packet, dataPacket, myNodeNum, session)
            }

            PortNum.RANGE_TEST_APP,
            PortNum.DETECTION_SENSOR_APP,
            -> {
                handleRangeTest(dataPacket, myNodeNum, session)
            }

            PortNum.MESH_BEACON_APP -> {
                handleMeshBeacon(packet, myNodeNum, session)
            }

            else -> {}
        }
    }

    private fun handlePrivateAppPacket(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
    ) {
        val payload = packet.decoded?.payload?.toByteArray() ?: return
        val senderId = dataPacket.from ?: NodeAddress.numToDefaultId(packet.from)

        radioInterfaceService.launchSessionWork(scope, session) {
            // 1. PTT control micro-packets are claimed BEFORE NACK parsing. A 3-byte floor packet
            // `[0x11|0x12|0x03, nodeNum hi, nodeNum lo]` is byte-for-byte shaped like a 3-byte Selective NACK
            // `[msgId, mask hi, mask lo]`; checking NACKs first made a FLOOR_REL look like a NACK for message id 0x12
            // and could trigger spurious chunk retransmissions (extra RF traffic during half-duplex).
            if (tryHandlePttControl(payload, senderId, packet.from)) return@launchSessionWork

            // 2. Check if this is a 3-byte Selective NACK packet
            if (ChunkingEngine.isNackPacket(payload)) {
                val nack = ChunkingEngine.parseNack(payload)
                if (nack != null && nack.missingMask != 0) {
                    val transmission = SentTransmissionCache.get(nack.messageId)
                    if (transmission != null && !transmission.hasRetried) {
                        SentTransmissionCache.markRetried(nack.messageId)
                        val destNum = NodeAddress.idToNum(transmission.destination) ?: NodeAddress.NODENUM_BROADCAST
                        val lora = radioConfigRepository.localConfigFlow.firstOrNull()?.lora
                        val preset = lora?.modem_preset
                        val retransmitted =
                            transmission.chunks.filterIndexed { index, _ -> (nack.missingMask and (1 shl index)) != 0 }
                        for ((index, chunkBytes) in retransmitted.withIndex()) {
                            val retryPacket =
                                MeshPacket(
                                    from = myNodeNum,
                                    to = destNum,
                                    id = kotlin.random.Random.nextInt(1, Int.MAX_VALUE),
                                    want_ack = false,
                                    channel = transmission.channel,
                                    decoded =
                                    org.meshtastic.proto.Data(
                                        portnum = PortNum.PRIVATE_APP,
                                        payload = chunkBytes.toByteString(),
                                    ),
                                )
                            packetHandler.sendToRadio(retryPacket)
                            if (index < retransmitted.size - 1) {
                                val delayMs = PayloadAnalyzer.calculateInterChunkDelayMs(chunkBytes.size, preset)
                                delay(delayMs)
                            }
                        }
                        return@launchSessionWork
                    }
                }
            }

            // 3. Live PTT audio chunks (identified by the header flag carried in EVERY chunk) are ephemeral.
            val parsedChunk = ChunkingEngine.parse(payload)
            val isMediaChunk = parsedChunk?.let { it.data.isNotEmpty() } == true
            val isLiveChunk = isMediaChunk && parsedChunk?.header?.isLivePtt == true
            if (isLiveChunk) {
                val pttManager = LivePttSessionManager.default
                if (!pttManager.state.value.isInSession) {
                    // Not in the room: the frame evaporates. It is not buffered, not NACKed, and never stored.
                    return@launchSessionWork
                }
                // Only genuine live chunks renew the floor lease. Ordinary voice notes (no live flag) used to refresh
                // it too, which produced phantom "receiving" locks on the PTT screen.
                val preset =
                    withTimeoutOrNull(PRESET_LOOKUP_TIMEOUT_MS) { radioConfigRepository.localConfigFlow.firstOrNull() }
                        ?.lora
                        ?.modem_preset
                val interChunkGapMs = PayloadAnalyzer.calculateInterChunkDelayMs(payload.size, preset)
                val leaseMs = maxOf(LivePttSessionManager.FLOOR_LEASE_MS, interChunkGapMs * 3L + 1_000L)
                pttManager.onRfCarrierActivity(senderId, nowMillis, leaseMs)
            }

            // 4. Ingest data chunk using bitmask tracking & NACK logic
            val result =
                reassemblyBuffer.ingestChunk(
                    senderId = senderId,
                    rawChunkBytes = payload,
                    currentTimeMs = nowMillis,
                    channel = dataPacket.channel,
                    sampleDataPacket = dataPacket,
                )
            startReassemblyWatchdog()

            if (result == null) return@launchSessionWork

            when (result) {
                is ReassemblyResult.SendNack -> {
                    // Send 3-byte Selective NACK packet back to transmitter
                    val destNum = NodeAddress.idToNum(senderId) ?: packet.from
                    val nackMeshPacket =
                        MeshPacket(
                            from = myNodeNum,
                            to = destNum,
                            id = kotlin.random.Random.nextInt(1, Int.MAX_VALUE),
                            want_ack = false,
                            channel = result.channel,
                            decoded =
                            org.meshtastic.proto.Data(
                                portnum = PortNum.PRIVATE_APP,
                                payload = result.nackPacket.toByteString(),
                            ),
                        )
                    packetHandler.sendToRadio(nackMeshPacket)
                }

                is ReassemblyResult.Complete -> {
                    handleCompletedMediaPayload(result.completedPayload, dataPacket, myNodeNum, session)
                }
            }
        }
    }

    /**
     * Routes a raw (non-chunked) PTT micro-packet to [LivePttSessionManager]. Returns true if the payload was claimed.
     *
     * Each opcode has one exact wire size, so a media chunk or NACK can never be mistaken for control traffic. The
     * 3-byte floor/heartbeat packets embed the sender's low 16 node-number bits; a mismatch means the bytes are really
     * a NACK whose message id happens to equal the opcode. A zero node number (sender did not know its own number yet)
     * is accepted: `[op, 0, 0]` can never be a NACK because NACK masks are non-zero.
     */
    private fun tryHandlePttControl(payload: ByteArray, senderId: String, fromNum: Int): Boolean {
        if (payload.isEmpty()) return false
        val opcode = payload[0]
        val expectedSize =
            when (opcode) {
                LivePttSessionManager.OPCODE_FLOOR_REQ,
                LivePttSessionManager.OPCODE_FLOOR_REL,
                LivePttSessionManager.OPCODE_HEARTBEAT,
                -> 3

                LivePttSessionManager.OPCODE_PTT_JOIN,
                LivePttSessionManager.OPCODE_PTT_HERE,
                LivePttSessionManager.OPCODE_PTT_LEAVE,
                LivePttSessionManager.OPCODE_PTT_DONE,
                -> 2

                else -> return false
            }
        if (payload.size != expectedSize) return false
        if (expectedSize == 3) {
            val claimed = LivePttSessionManager.parseNodeNum(payload)
            if (claimed != 0 && claimed != (fromNum and 0xFFFF)) return false
        }
        LivePttSessionManager.default.handleIncomingPayload(
            senderId = senderId,
            senderName = displayNameFor(senderId),
            payload = payload,
            currentTimeMs = nowMillis,
            senderNodeNum = fromNum,
        )
        return true
    }

    private fun displayNameFor(nodeId: String): String {
        val user = nodeManager.getNodeById(nodeId)?.user
        return user?.long_name?.takeIf { it.isNotBlank() } ?: user?.short_name?.takeIf { it.isNotBlank() } ?: nodeId
    }

    private fun handleCompletedMediaPayload(
        completed: CompletedMediaPayload,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext?,
    ) {
        val contactKey = dataPacket.contactKey(myNodeNum)
        val senderId = dataPacket.from ?: NodeAddress.numToDefaultId(myNodeNum)

        if (completed.payloadType == ChunkingEngine.TYPE_PC_COMMAND) {
            PcCommandSessionManager.default.handleInboundStreamPacket(
                completed.completePayload,
                contactKey = contactKey,
            )
            val streamState = PcCommandSessionManager.default.getStreamState(contactKey).value
            val textContent =
                when {
                    streamState.errorMessage != null -> "[PC-CMD Error]: ${streamState.errorMessage}"

                    streamState.lastScreenshotBytes != null ->
                        "[1-bit Screenshot: ${streamState.lastScreenshotBytes?.size ?: 0} bytes]"

                    streamState.thinkingThoughts.isNotBlank() && streamState.responseText.isNotBlank() ->
                        "<think>${streamState.thinkingThoughts}</think>\n${streamState.responseText}"

                    streamState.responseText.isNotBlank() -> streamState.responseText

                    else -> "[PC-CMD]: Processing..."
                }
            val textPacket =
                dataPacket.copy(
                    bytes = textContent.encodeToByteArray().toByteString(),
                    id = completed.messageId.toInt() and 0xFF,
                    dataType = PortNum.TEXT_MESSAGE_APP.value,
                )
            rememberDataPacket(textPacket, myNodeNum, updateNotification = true, session = session)
            return
        }

        val isLiveAudio =
            completed.isLivePtt ||
                (
                    completed.completePayload.size >= 2 &&
                        completed.completePayload[0] == LivePttSessionManager.OPCODE_AUDIO_FRAME &&
                        (completed.completePayload[1].toInt() and LivePttSessionManager.FLAG_IS_LIVE_PTT.toInt()) != 0
                    )

        if (completed.payloadType == ChunkingEngine.TYPE_PTT_CONTROL || isLiveAudio) {
            // Use the id the reassembly buffer keyed on, so carrier activity, playback and settle all agree.
            val liveSenderId = completed.senderId
            LivePttSessionManager.default.handleIncomingPayload(
                senderId = liveSenderId,
                senderName = displayNameFor(liveSenderId),
                payload = completed.completePayload,
                currentTimeMs = nowMillis,
                payloadType = completed.payloadType,
                senderNodeNum = NodeAddress.idToNum(liveSenderId) ?: 0,
            )
            // The reassembly is settled: the floor may now be released once playback drains.
            if (isLiveAudio) LivePttSessionManager.default.onInboundMediaSettled(liveSenderId)
            // Live PTT and control micro-packets are strictly real-time and ephemeral; never persist to database or
            // disk
            return
        }

        if (completed.payloadType == ChunkingEngine.TYPE_IMAGE_1BIT) {
            val currentState = PcCommandSessionManager.default.getStreamState(contactKey).value
            if (currentState.isAwaitingScreenshot) {
                val screenshotContainer = ByteArray(2 + completed.completePayload.size)
                screenshotContainer[0] = PcStreamStatus.SCREENSHOT_DATA.id
                screenshotContainer[1] = 0x00
                completed.completePayload.copyInto(screenshotContainer, destinationOffset = 2)
                PcCommandSessionManager.default.handleInboundStreamPacket(screenshotContainer, contactKey = contactKey)
            }
        }

        val audioBytesToStore = completed.completePayload

        val audioPath =
            if (
                completed.payloadType == ChunkingEngine.TYPE_AUDIO_CODEC2 ||
                completed.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
            ) {
                if (completed.hasConcealedLoss) {
                    val sRate =
                        if (completed.payloadType == ChunkingEngine.TYPE_AUDIO_CODEC2) {
                            Codec2Engine.SAMPLE_RATE
                        } else {
                            WavTokenizerEngine.SAMPLE_RATE_24K
                        }
                    val pcm = AudioDspUtils.salvageAndSynthesizeConcealedAudio(completed, sRate)
                    val wavBytes = AudioDspUtils.createWavFile(pcm, sRate)
                    MediaStorage.saveAudioPayload(wavBytes, "wav")
                } else {
                    val ext = if (completed.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER) "wt" else "c2"
                    MediaStorage.saveAudioPayload(audioBytesToStore, ext)
                }
            } else {
                null
            }

        val textRepresentation =
            when (completed.payloadType) {
                ChunkingEngine.TYPE_IMAGE_1BIT -> "[1-bit Image: ${completed.messageId}]"

                ChunkingEngine.TYPE_AUDIO_CODEC2,
                ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER,
                -> {
                    val pathSuffix = if (!audioPath.isNullOrBlank()) " | $audioPath" else ""
                    val effectiveAudioSize = audioBytesToStore.size
                    val durationSec =
                        if (completed.hasConcealedLoss && completed.totalChunks > 0) {
                            if (completed.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER) {
                                val refChunkSize = completed.chunkDataMap.values.firstOrNull()?.size ?: 204
                                val totalBytes = completed.totalChunks * refChunkSize
                                ((totalBytes * 2L) / (3L * WavTokenizerEngine.TOKENS_PER_SECOND))
                                    .toInt()
                                    .coerceAtLeast(1)
                            } else {
                                ((completed.totalChunks * 180L * 8L) / 700L).toInt().coerceAtLeast(1)
                            }
                        } else if (completed.payloadType == ChunkingEngine.TYPE_AUDIO_CODEC2) {
                            (effectiveAudioSize * 8 / 700).coerceAtLeast(1)
                        } else {
                            // WavTokenizer: 75 tokens/s, 12 bits/token -> 2 tokens = 3 bytes -> 112.5 bytes/second
                            ((effectiveAudioSize * 2L) / (3L * WavTokenizerEngine.TOKENS_PER_SECOND))
                                .toInt()
                                .coerceAtLeast(1)
                        }
                    val minutes = durationSec / 60
                    val seconds = durationSec % 60
                    val durationStr = "$minutes:${seconds.toString().padStart(2, '0')}"
                    val label = "Voice Note"
                    "[$label: $durationStr$pathSuffix]"
                }

                else -> null
            }

        val assembledDataPacket =
            dataPacket.copy(
                bytes = (textRepresentation ?: "").encodeToByteArray().toByteString(),
                id = completed.messageId.toInt() and 0xFF,
                dataType = PortNum.TEXT_MESSAGE_APP.value,
            )

        rememberDataPacket(assembledDataPacket, myNodeNum, updateNotification = true, session = session)
    }

    private fun handleRangeTest(dataPacket: DataPacket, myNodeNum: Int, session: RadioSessionContext) {
        val u = dataPacket.copy(dataType = PortNum.TEXT_MESSAGE_APP.value)
        rememberDataPacket(u, myNodeNum, session = session)
    }

    /**
     * A Mesh Beacon is an advisory, zero-hop invitation from another mesh — not a contact in the local NodeDB and not a
     * message. We stash the offer in [MeshBeaconRepository] for the Discovery surface to present, and fire a single
     * low-priority notification when the invitation is first seen (not on every periodic re-broadcast). Only beacons
     * carrying a join offer (a channel) are actionable; message-only beacons are ignored.
     */
    @Suppress("ReturnCount")
    private fun handleMeshBeacon(packet: MeshPacket, myNodeNum: Int, session: RadioSessionContext) {
        // Ignore our own beacons (spec FR-001) — once broadcast is enabled a node that also listens would self-notify.
        if (packet.from == myNodeNum) return
        val payload = packet.decoded?.payload ?: return
        val beacon = MeshBeacon.ADAPTER.decodeOrNull(payload, Logger)
        // Only actionable beacons (carrying a channel offer) that we haven't already seen warrant a notification.
        if (beacon?.offer_channel == null) return
        val offer =
            MeshBeaconOffer(
                fromNodeNum = packet.from,
                beacon = beacon,
                // [MeshBeaconOffer.snr] is not nullable, so absent narrows to 0f. See [snrOrNull].
                snr = packet.snrOrNull() ?: 0f,
                rssi = packet.rx_rssi,
            )
        val isNew = meshBeaconRepository.add(offer)
        if (isNew) {
            radioInterfaceService.launchSessionWork(scope, session) {
                // design#140 behavior 10: still stored for the invitations list, but a mesh the radio is already on
                // does not warrant a notification.
                val channelSet = radioConfigRepository.channelSetFlow.first()
                if (beacon.isAlreadyJoined(channelSet.lora_config, channelSet.settings)) return@launchSessionWork
                notificationManager.dispatch(
                    Notification(
                        title = getStringSuspend(Res.string.mesh_beacon_notification_title),
                        message = offer.message.ifBlank { getStringSuspend(Res.string.mesh_beacon_notification_body) },
                        category = Notification.Category.MeshBeacon,
                        // Literal URI avoids a core:navigation module dep (see NodeManagerImpl).
                        deepLinkUri = "meshtastic://meshtastic/discovery",
                    ),
                )
            }
        }
    }

    private fun handlePaxCounter(packet: MeshPacket, session: RadioSessionContext) {
        val payload = packet.decoded?.payload ?: return
        val p = Paxcount.ADAPTER.decodeOrNull(payload, Logger) ?: return
        nodeManager.handleReceivedPaxcounter(packet.from, p, session)
    }

    private fun handlePosition(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
    ) {
        val payload = packet.decoded?.payload ?: return
        val p = Position.ADAPTER.decodeOrNull(payload, Logger) ?: return
        Logger.d { "Position from ${packet.from}: ${Position.ADAPTER.toOneLiner(p)}" }
        nodeManager.handleReceivedPosition(packet.from, myNodeNum, p, dataPacket.time, session)
        geofenceMonitor.onPositionReceived(packet.from, myNodeNum, p, session)
    }

    private fun handleWaypoint(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
    ) {
        val payload = packet.decoded?.payload ?: return
        val u = Waypoint.ADAPTER.decode(payload)
        // A locked waypoint may only be created/updated by its owner; drop it if the sender isn't allowed to modify it.
        if (!u.isModifiableBy(packet.from)) return
        // icon is an arbitrary int on the wire and a waypoint with expire == 0 is retained indefinitely, so
        // normalise an unrenderable code point here at the trust boundary rather than relying on every consumer to
        // guard (0 means "use the default pushpin").
        if (!u.icon.isValidCodePoint()) {
            Logger.w { "Clearing an out-of-range waypoint icon code point (${u.icon})" }
            dataPacket.bytes = Waypoint.ADAPTER.encode(u.copy(icon = 0)).toByteString()
        }
        val updateNotification = u.expire > nowSeconds.toInt()
        radioInterfaceService.launchSessionWork(scope, session) {
            // Persisted-owner enforcement: a stored, locked waypoint may only be modified by the node it is locked to.
            // The inbound check above only validates the incoming payload, so without this a non-owner could hijack a
            // stored locked waypoint by replaying its id with locked_to = 0 (unlock) or their own num (takeover). The
            // read and the write share this one lease so the check sees a committed snapshot (see [persistDataPacket]).
            if (!storedWaypointModifiableBy(u.id, packet.from)) return@launchSessionWork
            persistDataPacket(dataPacket, myNodeNum, updateNotification)
        }
    }

    /**
     * Whether an inbound waypoint update from [from] may modify the currently-stored waypoint with [waypointId]. A new
     * waypoint (nothing stored) or an unlocked stored waypoint is always modifiable; a locked stored waypoint is
     * modifiable only by the node it is locked to — this deliberately rejects inbound unlock (`locked_to = 0`) attempts
     * from anyone else.
     *
     * [PacketRepository.getWaypoints] is a row-per-transmission firehose, so it is collapsed via
     * [activeWaypointPackets] (newest-per-id, expired dropped) — the same normalisation the map UI and geofence engine
     * use, so the three cannot drift. Waypoint packets are infrequent, so this one-shot read per waypoint is not hot.
     */
    private suspend fun storedWaypointModifiableBy(waypointId: Int, from: Int): Boolean {
        // firstOrNull().orEmpty(): getWaypoints() is a hot repository flow that always emits (an empty list when there
        // are none), but tolerate a flow that completes without emitting rather than throwing on the inbound path.
        val active = packetRepository.value.getWaypoints().firstOrNull().orEmpty().activeWaypointPackets(nowSeconds)
        val stored = active[waypointId]?.waypoint
        return stored == null || stored.isModifiableBy(from)
    }

    private fun handleTextMessage(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
    ) {
        val decoded = packet.decoded ?: return
        if (decoded.isReaction()) {
            rememberReaction(packet, dataPacket, session)
        } else {
            rememberDataPacket(dataPacket, myNodeNum, session = session)
        }
    }

    private fun handleNodeInfo(packet: MeshPacket, session: RadioSessionContext) {
        val payload = packet.decoded?.payload ?: return
        val u =
            User.ADAPTER.decode(payload)
                .let { if (it.is_licensed == true) it.copy(public_key = ByteString.EMPTY) else it }
                .let {
                    if (packet.via_mqtt == true && !it.long_name.endsWith(" (MQTT)")) {
                        it.copy(long_name = "${it.long_name} (MQTT)")
                    } else {
                        it
                    }
                }
        nodeManager.handleReceivedUser(packet.from, u, packet.channel, session = session)
    }

    private fun handleNodeStatus(
        packet: MeshPacket,
        dataPacket: DataPacket,
        myNodeNum: Int,
        session: RadioSessionContext,
    ) {
        val payload = packet.decoded?.payload ?: return
        val s = StatusMessage.ADAPTER.decodeOrNull(payload, Logger) ?: return
        nodeManager.handleReceivedNodeStatus(packet.from, s, session)
        rememberDataPacket(dataPacket, myNodeNum, session = session)
    }

    private fun handleRouting(packet: MeshPacket, dataPacket: DataPacket, session: RadioSessionContext) {
        val payload = packet.decoded?.payload ?: return
        val r = Routing.ADAPTER.decodeOrNull(payload, Logger) ?: return
        if (r.error_reason == Routing.Error.DUTY_CYCLE_LIMIT) {
            radioInterfaceService.launchSessionWork(scope, session) {
                serviceStateWriter.setErrorMessage(getStringSuspend(Res.string.error_duty_cycle), Severity.Warn)
            }
        }
        handleAckNak(
            packet.decoded?.request_id ?: 0,
            nodeManager.toNodeID(packet.from),
            r.error_reason?.value ?: 0,
            dataPacket.relayNode,
            session,
        )
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun handleAckNak(
        requestId: Int,
        fromId: String,
        routingError: Int,
        relayNode: Int?,
        session: RadioSessionContext,
    ) {
        radioInterfaceService.launchSessionWork(scope, session) {
            val isAck = routingError == Routing.Error.NONE.value
            val packets =
                packetRepository.value.findPacketsWithId(requestId).filter { it.status != MessageStatus.RECEIVED }
            val reactions =
                packetRepository.value.findReactionsWithId(requestId).filter { it.status != MessageStatus.RECEIVED }
            val p = packets.filter { it.to == fromId }.singleOrNull() ?: packets.singleOrNull()
            val reaction = reactions.filter { it.to == fromId }.singleOrNull() ?: reactions.singleOrNull()

            @Suppress("MaxLineLength")
            Logger.d {
                val statusInfo = "status=${p?.status ?: reaction?.status}"
                "[ackNak] req=$requestId routeErr=$routingError isAck=$isAck " +
                    "packetId=${p?.id ?: reaction?.packetId} dataId=${p?.id} $statusInfo"
            }

            val m =
                when {
                    isAck && (fromId == p?.to || fromId == reaction?.to) -> MessageStatus.RECEIVED
                    isAck -> MessageStatus.DELIVERED
                    else -> MessageStatus.ERROR
                }
            if (p != null && p.status != MessageStatus.RECEIVED) {
                val updatedPacket =
                    p.copy(status = m, relays = if (isAck) p.relays + 1 else p.relays, relayNode = relayNode)
                packetRepository.value.update(updatedPacket, routingError = routingError)
            }

            reaction?.let { r ->
                if (r.status != MessageStatus.RECEIVED) {
                    var updated = r.copy(status = m, routingError = routingError, relayNode = relayNode)
                    if (isAck) {
                        updated = updated.copy(relays = updated.relays + 1)
                    }
                    packetRepository.value.updateReaction(updated)
                }
            }
            packetHandler.completeDispatchedResponse(requestId, complete = isAck)
        }
    }

    override fun rememberDataPacket(
        dataPacket: DataPacket,
        myNodeNum: Int,
        updateNotification: Boolean,
        session: RadioSessionContext?,
    ) {
        if (dataPacket.dataType !in PERSISTED_DATA_PORT_NUMBERS) return
        val text = dataPacket.text.orEmpty()
        // Ephemeral Live PTT audio and PTT control packets must NEVER be persisted in chat database!
        if (text.startsWith("[Live PTT:") || text.startsWith("[PTT:")) return
        radioInterfaceService.launchSessionWork(scope, session) {
            persistDataPacket(dataPacket, myNodeNum, updateNotification)
        }
    }

    /**
     * Deduplicates, filters, persists, and (when appropriate) notifies for a single [dataPacket]. Runs inside a session
     * lease — callers must launch it via [RadioInterfaceService.launchSessionWork] and must have already confirmed the
     * packet's [DataPacket.dataType] is one of [PERSISTED_DATA_PORT_NUMBERS]. Split out from [rememberDataPacket] so
     * the waypoint path can gate persistence on a repository read within the same lease (see [handleWaypoint]).
     */
    private suspend fun persistDataPacket(dataPacket: DataPacket, myNodeNum: Int, updateNotification: Boolean) {
        val fromLocal = dataPacket.isFromLocal(myNodeNum)
        val contactKey = dataPacket.contactKey(myNodeNum)

        if (!fromLocal) {
            val text = dataPacket.text.orEmpty()
            val isPcStreamInternal = text.startsWith("[PC-CMD") || text.startsWith("<think>")
            if (!isPcStreamInternal) {
                PcCommandSessionManager.default.onAnyMessageReceived(contactKey)
            }
        }

        packetRepository.value.apply {
            // Check for duplicates before inserting
            val existingPackets = findPacketsWithId(dataPacket.id)
            if (existingPackets.any { it.hasSameSenderAs(dataPacket, myNodeNum) }) {
                Logger.d {
                    "Skipping duplicate packet: packetId=${dataPacket.id} from=${dataPacket.from} " +
                        "to=${dataPacket.to} contactKey=$contactKey" +
                        " (already have ${existingPackets.size} packet(s))"
                }
                return
            }

            // Check if message should be filtered
            val isFiltered = shouldFilterMessage(dataPacket, contactKey)

            insert(dataPacket, myNodeNum, contactKey, nowMillis, read = fromLocal || isFiltered, filtered = isFiltered)
            if (!isFiltered) {
                handlePacketNotification(dataPacket, contactKey, updateNotification)
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun PacketRepository.shouldFilterMessage(dataPacket: DataPacket, contactKey: String): Boolean {
        val isIgnored = nodeManager.getNodeById(dataPacket.from.orEmpty())?.isIgnored == true
        if (isIgnored) return true

        if (dataPacket.dataType != PortNum.TEXT_MESSAGE_APP.value) return false
        val isFilteringDisabled = getContactSettings(contactKey).filteringDisabled
        return messageFilter.shouldFilter(dataPacket.text.orEmpty(), isFilteringDisabled)
    }

    private suspend fun handlePacketNotification(
        dataPacket: DataPacket,
        contactKey: String,
        updateNotification: Boolean,
    ) {
        val conversationMuted = packetRepository.value.getContactSettings(contactKey).isMuted
        val nodeMuted = nodeManager.getNodeById(dataPacket.from.orEmpty())?.isMuted == true
        // A mention of our own id is a targeted ping, so it breaks through a muted channel/conversation
        // (per meshtastic/design#21). Node mute is a stronger, per-sender signal and stays authoritative:
        // a muted node cannot force a notification by spamming @-mentions.
        val mentionsMe =
            dataPacket.dataType == PortNum.TEXT_MESSAGE_APP.value &&
                textMentionsNode(dataPacket.text, nodeManager.getMyId())
        val muted = nodeMuted || (conversationMuted && !mentionsMe)
        when {
            // A critical alert is the one category that deliberately breaks through, so it is not suppressed for the
            // conversation on screen — only mute silences it.
            dataPacket.dataType == PortNum.ALERT_APP.value ->
                if (!muted) {
                    notificationManager.dispatch(
                        Notification(
                            title = getSenderName(dataPacket),
                            message = dataPacket.alert ?: getStringSuspend(Res.string.critical_alert),
                            category = Notification.Category.Alert,
                            contactKey = contactKey,
                        ),
                    )
                }

            !updateNotification || muted -> Unit

            // Reading the conversation is its own acknowledgement: notifying about a message whose bubble is already
            // on screen only buzzes the phone in the user's hand. Foreground-but-elsewhere still posts, silently, so
            // the shade and Auto/Wear keep the conversation.
            else ->
                when (activeConversationTracker.announcementFor(contactKey)) {
                    MessageAnnouncement.Suppress -> Unit
                    MessageAnnouncement.Silent -> updateNotification(contactKey, dataPacket, isSilent = true)
                    MessageAnnouncement.Alert -> updateNotification(contactKey, dataPacket, isSilent = false)
                }
        }
    }

    private suspend fun getSenderName(packet: DataPacket): String {
        if (packet.source is NodeAddress.Local) {
            val myId = nodeManager.getMyId()
            return nodeManager.getNodeById(myId)?.user?.long_name ?: getStringSuspend(Res.string.unknown_username)
        }
        return nodeManager.getNodeById(packet.from.orEmpty())?.user?.long_name
            ?: getStringSuspend(Res.string.unknown_username)
    }

    private suspend fun updateNotification(contactKey: String, dataPacket: DataPacket, isSilent: Boolean) {
        when (dataPacket.dataType) {
            PortNum.TEXT_MESSAGE_APP.value -> {
                val message = dataPacket.text!!
                val isBroadcast = dataPacket.destination is NodeAddress.Broadcast
                val channelName =
                    if (isBroadcast) {
                        radioConfigRepository.channelSetFlow.first().settings.getOrNull(dataPacket.channel)?.name
                    } else {
                        null
                    }
                serviceNotifications.updateMessageNotification(
                    contactKey,
                    getSenderName(dataPacket),
                    message,
                    isBroadcast,
                    channelName,
                    isSilent,
                )
            }

            PortNum.WAYPOINT_APP.value -> {
                val message = getStringSuspend(Res.string.waypoint_received, dataPacket.waypoint!!.name)
                notificationManager.dispatch(
                    Notification(
                        title = getSenderName(dataPacket),
                        message = message,
                        category = Notification.Category.Message,
                        contactKey = contactKey,
                        isSilent = isSilent,
                    ),
                )
            }

            else -> return
        }
    }

    @Suppress("LongMethod", "KotlinConstantConditions")
    private fun rememberReaction(packet: MeshPacket, dataPacket: DataPacket, session: RadioSessionContext) =
        radioInterfaceService.launchSessionWork(scope, session) {
            val decoded = packet.decoded ?: return@launchSessionWork
            val emoji = decoded.payload.toByteArray().decodeToString()
            val fromId = nodeManager.toNodeID(packet.from)
            val toId = nodeManager.toNodeID(packet.to)
            val myNodeNum = nodeManager.myNodeNum.value ?: 0
            val contactKey = dataPacket.contactKey(myNodeNum)

            val fromNode = nodeManager.nodeDBbyNodeNum[packet.from] ?: Node(num = packet.from)
            val fromUser = fromNode.user.copy(id = fromNode.user.id.ifEmpty { fromId })

            val reaction =
                Reaction(
                    replyId = decoded.reply_id,
                    user = fromUser,
                    emoji = emoji,
                    timestamp = nowMillis,
                    // [Reaction.snr] is not nullable, so absent narrows to 0f here. See [snrOrNull].
                    snr = packet.snrOrNull() ?: 0f,
                    rssi = packet.rx_rssi,
                    hopsAway =
                    if (packet.hop_start == 0 || packet.hop_limit > packet.hop_start) {
                        HOPS_AWAY_UNAVAILABLE
                    } else {
                        packet.hop_start - packet.hop_limit
                    },
                    packetId = packet.id,
                    status = MessageStatus.RECEIVED,
                    to = toId,
                    channel = dataPacket.channel,
                )

            // Check for duplicates before inserting
            val existingReactions = packetRepository.value.findReactionsWithId(packet.id)
            if (existingReactions.any { it.user.id == fromId }) {
                Logger.d {
                    "Skipping duplicate reaction: packetId=${packet.id} replyId=${decoded.reply_id} " +
                        "(already have ${existingReactions.size} reaction(s))"
                }
                return@launchSessionWork
            }

            packetRepository.value.insertReaction(reaction, myNodeNum)

            // A reply ID is sender-scoped, so only use a parent that is unique within this reaction's conversation.
            packetRepository.value
                .findPacketsWithId(decoded.reply_id)
                .filter { it.contactKey(myNodeNum) == contactKey }
                .singleOrNull()
                ?.let { originalPacket ->
                    // Skip notification if the original message was filtered
                    val conversationMuted = packetRepository.value.getContactSettings(contactKey).isMuted
                    val nodeMuted = nodeManager.getNodeById(fromId)?.isMuted == true
                    val muted = conversationMuted || nodeMuted
                    val announcement = activeConversationTracker.announcementFor(contactKey)

                    if (!muted && announcement != MessageAnnouncement.Suppress) {
                        val isBroadcast = originalPacket.destination is NodeAddress.Broadcast
                        val channelName =
                            if (isBroadcast) {
                                radioConfigRepository.channelSetFlow
                                    .first()
                                    .settings
                                    .getOrNull(originalPacket.channel)
                                    ?.name
                            } else {
                                null
                            }
                        serviceNotifications.updateReactionNotification(
                            contactKey,
                            getSenderName(dataPacket),
                            emoji,
                            isBroadcast,
                            channelName,
                            announcement == MessageAnnouncement.Silent,
                        )
                    }
                }
        }

    private fun DataPacket.contactKey(myNodeNum: Int): String {
        val contactId = if (isFromLocal(myNodeNum) || isBroadcast) to else from
        return "$channel$contactId"
    }

    private fun DataPacket.hasSameSenderAs(other: DataPacket, myNodeNum: Int): Boolean =
        source == other.source || (isFromLocal(myNodeNum) && other.isFromLocal(myNodeNum))

    companion object {
        private const val HOPS_AWAY_UNAVAILABLE = -1
        private const val PRESET_LOOKUP_TIMEOUT_MS = 200L
    }
}
