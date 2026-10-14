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
package org.meshtastic.feature.messaging

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.koin.core.annotation.KoinViewModel
import org.meshtastic.core.common.util.currentLocaleCode
import org.meshtastic.core.common.util.ioDispatcher
import org.meshtastic.core.common.util.nowMillis
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.ContactSettings
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.Message
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.Node
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.media.ActionTargetMode
import org.meshtastic.core.model.media.AudioDspUtils
import org.meshtastic.core.model.media.ChunkingEngine
import org.meshtastic.core.model.media.Codec2Engine
import org.meshtastic.core.model.media.InboundMediaProgress
import org.meshtastic.core.model.media.LivePttSessionManager
import org.meshtastic.core.model.media.MediaStorage
import org.meshtastic.core.model.media.PayloadAnalyzer
import org.meshtastic.core.model.media.PcAiStreamState
import org.meshtastic.core.model.media.PcCommandSessionManager
import org.meshtastic.core.model.media.PcmAudioUtils
import org.meshtastic.core.model.media.PttSessionEvent
import org.meshtastic.core.model.media.PttSessionState
import org.meshtastic.core.model.media.ReassemblyBuffer
import org.meshtastic.core.model.media.RleCompressor
import org.meshtastic.core.model.media.WavTokenizerEngine
import org.meshtastic.core.repository.ActiveConversationTracker
import org.meshtastic.core.repository.ConnectionStateProvider
import org.meshtastic.core.repository.CustomEmojiPrefs
import org.meshtastic.core.repository.HomoglyphPrefs
import org.meshtastic.core.repository.MeshNotificationManager
import org.meshtastic.core.repository.MessagingController
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.PacketHandler
import org.meshtastic.core.repository.PacketRepository
import org.meshtastic.core.repository.PersistedPacketId
import org.meshtastic.core.repository.QuickChatActionRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.core.repository.UiPrefs
import org.meshtastic.core.repository.usecase.SendMediaUseCaseImpl
import org.meshtastic.core.repository.usecase.SendMessageUseCase
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.UiText
import org.meshtastic.core.resources.translation_failed
import org.meshtastic.core.resources.translation_model_download_failed
import org.meshtastic.core.resources.translation_not_required
import org.meshtastic.core.ui.util.SnackbarManager
import org.meshtastic.core.ui.viewmodel.errorEventFlow
import org.meshtastic.core.ui.viewmodel.safeLaunch
import org.meshtastic.core.ui.viewmodel.stateInWhileSubscribed
import org.meshtastic.feature.messaging.audio.AudioController
import org.meshtastic.feature.messaging.audio.AudioPlayer
import org.meshtastic.feature.messaging.component.processImageFileTo1Bit
import org.meshtastic.feature.messaging.translation.DownloadResult
import org.meshtastic.feature.messaging.translation.MessageTranslationService
import org.meshtastic.feature.messaging.translation.TranslationResult
import org.meshtastic.proto.ChannelSet
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.ToRadio
import kotlin.random.Random

/**
 * Test seam for resolving [UiText] to a plain string. Unit tests replace [resolve] so ViewModel snackbar paths don't
 * depend on the compose-resources runtime (same pattern as NodeDetailUiTextResolver).
 */
internal object MessagingUiTextResolver {
    var resolve: suspend (UiText) -> String = { it.resolve() }
}

/** State of the translation-model download dialog on the message screen. */
sealed interface TranslationDialogState {
    data object Hidden : TranslationDialogState

    /** Ask the user to confirm downloading the missing [languageTags] models (~[estimatedSizeMb] MB). */
    data class DownloadPrompt(val message: Message, val languageTags: List<String>, val estimatedSizeMb: Int) :
        TranslationDialogState

    data class Downloading(val message: Message) : TranslationDialogState
}

@Suppress("LongParameterList", "TooManyFunctions")
@KoinViewModel
class MessageViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val nodeRepository: NodeRepository,
    private val radioConfigRepository: RadioConfigRepository,
    quickChatActionRepository: QuickChatActionRepository,
    private val connectionStateProvider: ConnectionStateProvider,
    private val messagingController: MessagingController,
    private val packetRepository: PacketRepository,
    private val uiPrefs: UiPrefs,
    private val customEmojiPrefs: CustomEmojiPrefs,
    private val homoglyphEncodingPrefs: HomoglyphPrefs,
    private val meshNotificationManager: MeshNotificationManager,
    private val activeConversationTracker: ActiveConversationTracker,
    private val sendMessageUseCase: SendMessageUseCase,
    private val messageTranslationService: MessageTranslationService,
    private val snackbarManager: SnackbarManager,
    private val packetHandler: PacketHandler? = null,
) : ViewModel() {
    private val contactKeyForPagedMessages: MutableStateFlow<String?> = MutableStateFlow(null)

    val pcCommandSessionManager: PcCommandSessionManager = PcCommandSessionManager.default

    val pcAiStreamState: StateFlow<PcAiStreamState> =
        contactKeyForPagedMessages
            .filterNotNull()
            .flatMapLatest { key -> pcCommandSessionManager.getStreamState(key) }
            .stateInWhileSubscribed(initialValue = PcAiStreamState())

    fun getPcAiStreamState(contactKey: String): StateFlow<PcAiStreamState> =
        pcCommandSessionManager.getStreamState(contactKey)

    val pttSessionState: StateFlow<PttSessionState> = LivePttSessionManager.default.state

    val inboundMediaProgress: StateFlow<InboundMediaProgress?> = ReassemblyBuffer.inboundProgress

    private val _outboundVoiceProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val outboundVoiceProgress: StateFlow<Pair<Int, Int>?> = _outboundVoiceProgress.asStateFlow()

    private var pttHeartbeatJob: Job? = null
    private var outboundPttJob: Job? = null
    private var floorKeepAliveJob: Job? = null

    /**
     * True only for the ViewModel that opened the PTT screen. Several ViewModels can be alive at once and all of them
     * see the process-wide session events; without this gate each would play the same audio and send duplicate acks.
     */
    @Volatile private var ownsPttSession = false

    /** Live audio is played strictly one message at a time; a second message never cuts the first one off. */
    private val pttPlaybackQueue = Channel<PttSessionEvent.AutoPlayAudio>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            for (audio in pttPlaybackQueue) {
                playQueuedPttAudio(audio)
            }
        }
        viewModelScope.launch {
            LivePttSessionManager.default.events.collect { event ->
                when (event) {
                    is PttSessionEvent.AutoPlayAudio -> if (ownsPttSession) pttPlaybackQueue.trySend(event)

                    is PttSessionEvent.FloorCollisionLost -> {
                        // Immediately abort transmission, cancel outbound jobs, and clear outbound queues
                        stopFloorKeepAlive()
                        outboundPttJob?.cancel()
                        outboundPttJob = null
                        _outboundVoiceProgress.value = null
                        _isSendingVoiceNote.value = false
                        _isProcessingVoiceNote.value = false
                    }

                    is PttSessionEvent.ReplyPttHere -> {
                        if (ownsPttSession && LivePttSessionManager.default.state.value.isInSession) {
                            viewModelScope.launch(Dispatchers.Default) {
                                val jitterMs = Random.nextLong(100L, 251L)
                                delay(jitterMs)
                                if (!isActive || !LivePttSessionManager.default.state.value.isInSession) return@launch
                                val packet = LivePttSessionManager.createPttHerePacket(localNodeNum())
                                sendDirectPttPacket(
                                    payload = packet,
                                    contactKey = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}",
                                    customDestNum = if (event.peerNodeNum != 0) event.peerNodeNum else null,
                                )
                            }
                        }
                    }

                    is PttSessionEvent.PlaybackDrained -> {
                        if (ownsPttSession && event.senderNodeNum != 0) {
                            // Tell the speaker its audio has fully played out. Sent twice: LoRa is lossy and an
                            // unacknowledged unicast is the only thing keeping the speaker locked until we are done.
                            viewModelScope.launch(Dispatchers.Default) {
                                val ack = LivePttSessionManager.createPttDonePacket(localNodeNum())
                                val key = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}"
                                sendDirectPttPacket(ack, key, customDestNum = event.senderNodeNum)
                                delay(PTT_DONE_REPEAT_DELAY_MS)
                                sendDirectPttPacket(ack, key, customDestNum = event.senderNodeNum)
                            }
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    /**
     * Decodes and plays one live message, then reports completion EXACTLY once. Completion is what releases the
     * receiving lock, so it is reported from `finally` and also covers failures, interruption and a stuck player.
     */
    private suspend fun playQueuedPttAudio(event: PttSessionEvent.AutoPlayAudio) {
        val isWavTokenizer = event.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
        // Longest plausible playback of this payload (slowest codec) plus decode headroom.
        val watchdogMs = (event.audioBytes.size * 1000L) / 80L + PTT_PLAYBACK_WATCHDOG_MARGIN_MS
        try {
            withTimeoutOrNull(watchdogMs) {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { AudioPlayer.stop() }
                    AudioPlayer.playRawAudio(
                        audioBytes = event.audioBytes,
                        isWavTokenizer = isWavTokenizer,
                        onComplete = { if (continuation.isActive) continuation.resume(Unit) {} },
                    )
                }
            } ?: AudioPlayer.stop()
        } finally {
            LivePttSessionManager.default.onAudioPlaybackCompleted()
        }
    }

    private fun localNodeNum(): Int =
        nodeRepository.ourNodeInfo.value?.num ?: NodeAddress.idToNum(nodeRepository.myId.value.orEmpty()) ?: 0

    /**
     * Presence loop. While the room looks empty it re-broadcasts JOIN every few seconds (so one lost JOIN/HERE can no
     * longer leave both nodes "alone" forever); once peers are known it sends a heartbeat every 10 s. Heartbeats from a
     * peer we do not know yet make us answer with HERE, so the handshake repairs itself from either side.
     */
    private fun startPttHeartbeat(contactKey: String) {
        pttHeartbeatJob?.cancel()
        pttHeartbeatJob =
            viewModelScope.launch(Dispatchers.Default) {
                while (isActive && LivePttSessionManager.default.state.value.isInSession) {
                    delay(LivePttSessionManager.default.nextPresenceDelayMs())
                    if (!isActive || !LivePttSessionManager.default.state.value.isInSession) break
                    // Never add presence traffic while we are the one on air.
                    if (LivePttSessionManager.default.state.value.isTransmitting) continue
                    sendDirectPttPacket(LivePttSessionManager.default.nextPresencePacket(localNodeNum()), contactKey)
                }
            }
    }

    private fun startFloorKeepAlive(contactKey: String) {
        floorKeepAliveJob?.cancel()
        floorKeepAliveJob =
            viewModelScope.launch(Dispatchers.Default) {
                val startedAt = nowMillis
                while (isActive && LivePttSessionManager.default.state.value.isTransmitting) {
                    delay(LivePttSessionManager.FLOOR_KEEPALIVE_INTERVAL_MS)
                    if (!isActive || !LivePttSessionManager.default.state.value.isTransmitting) break
                    if (nowMillis - startedAt > PTT_KEEPALIVE_HARD_CAP_MS) break
                    // Once chunks are on air they renew the peers' lease themselves; extra packets would only delay
                    // them in the radio's TX queue.
                    if (_isSendingVoiceNote.value) continue
                    sendDirectPttPacket(LivePttSessionManager.createFloorRequestPacket(localNodeNum()), contactKey)
                }
            }
    }

    private fun stopFloorKeepAlive() {
        floorKeepAliveJob?.cancel()
        floorKeepAliveJob = null
    }

    fun sendDirectPttPacket(
        payload: ByteArray,
        contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}",
        customDestNum: Int? = null,
    ) {
        val myNode = nodeRepository.ourNodeInfo.value
        val myNodeNum = myNode?.num ?: 0
        val parsedKey = ContactKey(contactKey)
        val channel = parsedKey.channelOrNull ?: 0
        val dest = parsedKey.addressString
        val destNum = customDestNum ?: NodeAddress.idToNum(dest) ?: NodeAddress.NODENUM_BROADCAST
        val directPacket =
            MeshPacket(
                from = myNodeNum,
                to = destNum,
                id = Random.nextInt(1, Int.MAX_VALUE),
                want_ack = false,
                channel = channel,
                decoded = org.meshtastic.proto.Data(portnum = PortNum.PRIVATE_APP, payload = payload.toByteString()),
            )
        packetHandler?.sendToRadio(ToRadio(packet = directPacket))
    }

    fun enterLivePttSession(contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}") {
        val myNode = nodeRepository.ourNodeInfo.value
        val myNodeNum = myNode?.num ?: 0
        val myNodeId = myNode?.user?.id ?: nodeRepository.myId.value ?: "!local"
        val rawLong = myNode?.user?.long_name
        val rawShort = myNode?.user?.short_name
        val myNodeName: String =
            if (!rawLong.isNullOrEmpty()) {
                rawLong
            } else if (!rawShort.isNullOrEmpty()) {
                rawShort
            } else {
                "Me"
            }
        val joinPacket = LivePttSessionManager.default.enterSession(myNodeId, myNodeName, localNodeNum())
        ownsPttSession = true
        startPttHeartbeat(contactKey)
        sendDirectPttPacket(joinPacket, contactKey)
    }

    fun requestPttFloor(contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}") {
        val myNode = nodeRepository.ourNodeInfo.value
        val myNodeId = myNode?.user?.id ?: nodeRepository.myId.value ?: "!local"
        val packetBytes = LivePttSessionManager.default.requestFloor(localNodeNum(), myNodeId)

        sendDirectPttPacket(packetBytes, contactKey)
        // The lease on the peers' side only lasts a few seconds. Keep renewing it for the whole hold, the encode gap
        // after release, and until the last chunk is on air, i.e. until the floor is explicitly released.
        startFloorKeepAlive(contactKey)
    }

    /**
     * Releases the floor after a tap, a cancelled recording or an empty capture. While a recorded message is being
     * encoded or transmitted the release belongs to that send ([finishLiveTransmission]) and is ignored here, so a late
     * UI callback can never unlock the channel in the middle of our own transmission.
     */
    fun releasePttFloor(contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}") {
        if (_isSendingVoiceNote.value || _isProcessingVoiceNote.value) return
        broadcastFloorRelease(contactKey)
    }

    private fun broadcastFloorRelease(contactKey: String) {
        stopFloorKeepAlive()
        val packetBytes = LivePttSessionManager.default.releaseFloor(localNodeNum())
        sendDirectPttPacket(packetBytes, contactKey)
        // LoRa is lossy and FLOOR_REL is unacknowledged: repeat once, but never over the top of a new transmission.
        viewModelScope.launch(Dispatchers.Default) {
            delay(PTT_RELEASE_REPEAT_DELAY_MS)
            if (!LivePttSessionManager.default.state.value.isTransmitting) {
                sendDirectPttPacket(packetBytes, contactKey)
            }
        }
    }

    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title.asStateFlow()

    private val _draftMessage = MutableStateFlow<String?>(null)

    /** Persisted draft for the conversation on screen. Null until [loadDraft] has read it back. */
    val draftMessage: StateFlow<String?> = _draftMessage.asStateFlow()

    private val _isRecordingVoiceNote = MutableStateFlow(false)
    val isRecordingVoiceNote: StateFlow<Boolean> = _isRecordingVoiceNote.asStateFlow()

    fun setRecordingVoiceNote(isRecording: Boolean) {
        _isRecordingVoiceNote.value = isRecording
    }

    private val _isSendingVoiceNote = MutableStateFlow(false)
    val isSendingVoiceNote: StateFlow<Boolean> = _isSendingVoiceNote.asStateFlow()

    private val _isProcessingVoiceNote = MutableStateFlow(false)
    val isProcessingVoiceNote: StateFlow<Boolean> = _isProcessingVoiceNote.asStateFlow()

    val outboundMediaProgress: StateFlow<Pair<Int, Int>?> = outboundVoiceProgress

    val isReceivingVoiceNote: StateFlow<Boolean> =
        combine(inboundMediaProgress, pttSessionState) { inProgress, pttState ->
            inProgress != null || pttState.isReceiving || pttState.isPlayingAudio || pttState.isAwaitingPeerPlayback
        }
            .stateInWhileSubscribed(initialValue = false)

    val isRadioBusy: StateFlow<Boolean> =
        combine(
            _isRecordingVoiceNote,
            _isSendingVoiceNote,
            _isProcessingVoiceNote,
            _outboundVoiceProgress,
            isReceivingVoiceNote,
        ) { recording, sending, processing, outProgress, receiving ->
            recording || sending || processing || outProgress != null || receiving
        }
            .stateInWhileSubscribed(initialValue = false)

    private val sendErrorEvents = errorEventFlow()

    private var pendingDraftPersistence: Job? = null

    private var draftContactKey: String? = null

    /**
     * Reads the stored draft for [contactKey] once per conversation.
     *
     * [SavedStateHandle] wins over the database when it holds something, because it can carry keystrokes newer than the
     * last debounced write — the database copy is what makes the draft visible to the conversation list and survive the
     * screen being popped.
     */
    fun loadDraft(contactKey: String) {
        if (contactKeyForPagedMessages.value != contactKey) {
            contactKeyForPagedMessages.value = contactKey
        }
        if (draftContactKey == contactKey) return
        draftContactKey = contactKey
        _draftMessage.value = null
        // The coroutine itself stays on the view-model dispatcher and only the database read hops to IO, so the
        // publish below is ordered against everything else the view model does rather than racing it.
        safeLaunch(tag = "loadDraft") {
            val restored = savedStateHandle.get<String>(draftKey(contactKey))
            val loaded =
                restored?.takeIf { it.isNotEmpty() }
                    ?: withContext(ioDispatcher) { packetRepository.getDraft(contactKey) }
            // Two loads can be in flight after a fast switch between conversations; only the one still current may
            // publish, or one conversation's unsent text surfaces in another.
            if (draftContactKey == contactKey) _draftMessage.value = loaded
        }
    }

    /**
     * Updates the in-memory draft immediately; the durable writes are debounced by [DRAFT_PERSISTENCE_DELAY_MS], so
     * rapid edits cancel the prior pending flush and only the trailing value persists. On process death within that
     * window the last ≤[DRAFT_PERSISTENCE_DELAY_MS] of typing is not durably saved — a conscious trade-off to avoid
     * per-keystroke jank.
     *
     * No-ops while [draftMessage] is still null, so the composer's initial empty value cannot erase a stored draft
     * before it has been read back.
     */
    fun setDraftMessage(text: String) {
        if (_draftMessage.value == null) return
        _draftMessage.value = text
        val contactKey = draftContactKey ?: return
        pendingDraftPersistence?.cancel()
        pendingDraftPersistence =
            viewModelScope.launch {
                delay(DRAFT_PERSISTENCE_DELAY_MS)
                savedStateHandle[draftKey(contactKey)] = text
                withContext(ioDispatcher) { packetRepository.setDraft(contactKey, text) }
            }
    }

    fun clearDraftMessage() {
        _draftMessage.value = ""
        pendingDraftPersistence?.cancel()
        pendingDraftPersistence = null
        draftContactKey?.let { contactKey ->
            savedStateHandle[draftKey(contactKey)] = ""
            safeLaunch(context = ioDispatcher, tag = "clearDraft") { packetRepository.setDraft(contactKey, "") }
        }
    }

    val ourNodeInfo = nodeRepository.ourNodeInfo

    val connectionState = connectionStateProvider.connectionState

    val nodeList: StateFlow<List<Node>> = nodeRepository.getNodes().stateInWhileSubscribed(initialValue = emptyList())

    val channels = radioConfigRepository.channelSetFlow.stateInWhileSubscribed(ChannelSet())

    val showQuickChat = uiPrefs.showQuickChat

    val showFullMessageTimestamps = uiPrefs.showFullMessageTimestamps

    val audioCodec = uiPrefs.audioCodec

    fun setAudioCodec(codecName: String) {
        uiPrefs.setAudioCodec(codecName)
    }

    private val _showFiltered = MutableStateFlow(false)
    val showFiltered: StateFlow<Boolean> = _showFiltered.asStateFlow()

    val quickChatActions = quickChatActionRepository.getAllActions().stateInWhileSubscribed(initialValue = emptyList())

    val contactSettings: StateFlow<Map<String, ContactSettings>> =
        packetRepository.getContactSettings().stateInWhileSubscribed(initialValue = emptyMap())

    private val pagedMessagesForContactKey: Flow<PagingData<Message>> =
        combine(contactKeyForPagedMessages.filterNotNull(), _showFiltered, contactSettings) {
                contactKey,
                showFiltered,
                settings,
            ->
            // If filtering is disabled for this contact, always include filtered messages
            val filteringDisabled = settings[contactKey]?.filteringDisabled ?: false
            val includeFiltered = showFiltered || filteringDisabled
            contactKey to includeFiltered
        }
            .flatMapLatest { (contactKey, includeFiltered) ->
                packetRepository.getMessagesFromPaged(contactKey, includeFiltered, ::getNode)
            }
            .cachedIn(viewModelScope)

    val frequentEmojis: List<String>
        get() =
            customEmojiPrefs.customEmojiFrequency.value
                ?.split(",")
                ?.mapNotNull { entry ->
                    val parts = entry.split("=", limit = 2)
                    val count = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size == 2 && parts[0].isNotEmpty() && count != null) parts[0] to count else null
                }
                ?.sortedByDescending { it.second }
                ?.map { it.first }
                ?.take(6) ?: listOf("👍", "👎", "😂", "🔥", "❤️", "😮")

    val homoglyphEncodingEnabled = homoglyphEncodingPrefs.homoglyphEncodingEnabled

    val firstUnreadMessageUuid: StateFlow<Long?> =
        contactKeyForPagedMessages
            .filterNotNull()
            .flatMapLatest { packetRepository.getFirstUnreadMessageUuid(it) }
            .stateInWhileSubscribed(null)

    val hasUnreadMessages: StateFlow<Boolean?> =
        contactKeyForPagedMessages
            .filterNotNull()
            .flatMapLatest { packetRepository.hasUnreadMessages(it) }
            .stateInWhileSubscribed(null)

    val unreadCount: StateFlow<Int> =
        contactKeyForPagedMessages
            .filterNotNull()
            .flatMapLatest { packetRepository.getUnreadCountFlow(it) }
            .stateInWhileSubscribed(0)

    val filteredCount: StateFlow<Int> =
        contactKeyForPagedMessages
            .filterNotNull()
            .flatMapLatest { packetRepository.getFilteredCountFlow(it) }
            .stateInWhileSubscribed(0)

    // region ── Search ──

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    private val _searchResultIndex = MutableStateFlow(0)
    val searchResultIndex: StateFlow<Int> = _searchResultIndex.asStateFlow()

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<Message>> =
        combine(_searchQuery, contactKeyForPagedMessages) { query, contactKey -> query to contactKey }
            .debounce(SEARCH_DEBOUNCE_MS)
            .flatMapLatest { (query, contactKey) ->
                if (query.length < MIN_SEARCH_LENGTH) {
                    flowOf(emptyList())
                } else {
                    packetRepository.searchMessages(query, contactKey, ::getNode)
                }
            }
            .stateInWhileSubscribed(emptyList())

    /** The currently focused search result message (for scroll-to-match). */
    val currentSearchResult: StateFlow<Message?> =
        combine(searchResults, _searchResultIndex) { results, index -> results.getOrNull(index) }
            .stateInWhileSubscribed(null)

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        _searchResultIndex.value = 0
    }

    fun navigateToNextResult() {
        val max = searchResults.value.size
        if (max == 0) return
        _searchResultIndex.update { (it + 1) % max }
    }

    fun navigateToPreviousResult() {
        val max = searchResults.value.size
        if (max == 0) return
        _searchResultIndex.update { if (it == 0) max - 1 else it - 1 }
    }

    fun toggleSearch() {
        _isSearchActive.value = !_isSearchActive.value
        if (!_isSearchActive.value) {
            _searchQuery.value = ""
            _searchResultIndex.value = 0
        }
    }

    fun closeSearch() {
        _isSearchActive.value = false
        _searchQuery.value = ""
        _searchResultIndex.value = 0
    }

    // endregion

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sendErrorEvents.collect { snackbarManager.showSnackbar(MessagingUiTextResolver.resolve(it)) }
        }
        val contactKey = savedStateHandle.get<String>("contactKey")
        if (contactKey != null) {
            contactKeyForPagedMessages.value = contactKey
        }
    }

    fun setContactKey(contactKey: String) {
        if (contactKeyForPagedMessages.value != contactKey) {
            contactKeyForPagedMessages.value = contactKey
        }
    }

    fun setTitle(title: String) {
        _title.value = title
    }

    fun getMessagesFromPaged(contactKey: String): Flow<PagingData<Message>> {
        if (contactKeyForPagedMessages.value != contactKey) {
            contactKeyForPagedMessages.value = contactKey
        }
        return pagedMessagesForContactKey
    }

    /**
     * Returns a non-paged reactive [Flow] of messages for a conversation. Used by desktop targets that don't use
     * paging-compose.
     *
     * @param contactKey The unique contact key identifying the conversation.
     * @param limit Optional maximum number of messages to return (null = all).
     */
    fun getMessagesFlow(contactKey: String, limit: Int? = null): Flow<List<Message>> {
        if (contactKeyForPagedMessages.value != contactKey) {
            contactKeyForPagedMessages.value = contactKey
        }
        return flow { emitAll(packetRepository.getMessagesFrom(contactKey, limit = limit, getNode = ::getNode)) }
    }

    fun toggleShowQuickChat() {
        uiPrefs.setShowQuickChat(!uiPrefs.showQuickChat.value)
    }

    fun toggleShowFiltered() {
        _showFiltered.update { !it }
    }

    fun setContactFilteringDisabled(contactKey: String, disabled: Boolean) {
        safeLaunch(context = ioDispatcher, tag = "setContactFilteringDisabled") {
            packetRepository.setContactFilteringDisabled(contactKey, disabled)
        }
    }

    fun getNode(userId: String?) = nodeRepository.getNode(userId ?: NodeAddress.ID_BROADCAST)

    fun getUser(userId: String?) = nodeRepository.getUser(userId ?: NodeAddress.ID_BROADCAST)

    /**
     * Sends a message to a contact or channel.
     *
     * If the message is a direct message (no channel specified), this function will:
     * - If the device firmware version is older than 2.7.12, it will mark the destination node as a favorite to prevent
     *   it from being removed from the on-device node database.
     * - If the device firmware version is 2.7.12 or newer, it will send a shared contact to the destination node.
     *
     * @param str The message content.
     * @param contactKey The unique contact key, which is a combination of channel (optional) and node ID. Defaults to
     *   broadcasting on channel 0.
     * @param replyId The ID of the message this is a reply to, if any.
     */
    fun sendMessage(str: String, contactKey: String = "0${NodeAddress.ID_BROADCAST}", replyId: Int? = null) {
        safeLaunch(errorEvents = sendErrorEvents, tag = "sendMessage") {
            sendMessageUseCase.invoke(str, contactKey, replyId)
        }
    }

    fun sendReaction(emoji: String, replyId: Int, contactKey: String) =
        safeLaunch(tag = "sendReaction") { messagingController.sendReaction(emoji, replyId, contactKey) }

    private val watchdogJobs = mutableMapOf<String, Job>()

    fun sendVoiceNote(
        durationSeconds: Int,
        audioBytes: ByteArray? = null,
        contactKey: String = "0${NodeAddress.ID_BROADCAST}",
        isLivePtt: Boolean = false,
        sampleRate: Int? = null,
    ) {
        if (_isSendingVoiceNote.value || _isProcessingVoiceNote.value) {
            return
        }
        if (isLivePtt && (audioBytes == null || audioBytes.isEmpty())) {
            // Nothing was captured (released before the mic was ready, or capture failed): do not encode silence and
            // transmit it, just give the floor back.
            releasePttFloor(contactKey)
            return
        }

        _isRecordingVoiceNote.value = false
        _isProcessingVoiceNote.value = true
        safeLaunch(errorEvents = sendErrorEvents, tag = "sendVoiceNote") {
            // Liveness is decided by the caller only. It used to also be inferred from "the PTT session is open",
            // which turned an ordinary voice note sent from chat into an ephemeral, never-stored one.
            val isLive = isLivePtt
            var chunksSent = false
            val playbackMs = actualPlaybackMs(durationSeconds, audioBytes, sampleRate)
            var interChunkDelayMs =
                PayloadAnalyzer.calculateInterChunkDelayMs(ChunkingEngine.DEFAULT_MAX_CHUNK_DATA_SIZE, null)
            try {
                if (isLive) {
                    outboundPttJob = coroutineContext[Job]
                }
                val selectedCodec = uiPrefs.audioCodec.value
                val isWavTokenizer = selectedCodec.startsWith("WavTokenizer")
                val isCodec2700 = selectedCodec == UiPrefs.CODEC_CODEC2_700 || selectedCodec == UiPrefs.CODEC_CODEC2
                val codec2Mode = if (isCodec2700) Codec2Engine.MODE_700C else Codec2Engine.MODE_1400
                Codec2Engine.preferredMode = codec2Mode

                val incomingSampleRate = sampleRate ?: AudioController.activeSampleRate
                val actualDurationSeconds = durationSeconds.coerceAtLeast(1)

                // Encode captured PCM audio off the main thread
                val pair: Pair<ByteArray, Byte> =
                    withContext(Dispatchers.Default) {
                        val rawSamples =
                            if (audioBytes != null && audioBytes.isNotEmpty()) {
                                PcmAudioUtils.pcmBytesToShorts(audioBytes)
                            } else {
                                ShortArray(actualDurationSeconds * incomingSampleRate)
                            }
                        // Dynamic codec routing based on user's preference
                        if (isWavTokenizer) {
                            Pair(WavTokenizerEngine.encode(rawSamples), ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER)
                        } else {
                            // Codec 2 strictly requires 8000 Hz narrowband audio.
                            // If audio was captured at 24000 Hz, downsample to 8000 Hz before encoding.
                            val is24k =
                                incomingSampleRate == AudioController.SAMPLE_RATE_24K ||
                                    (durationSeconds > 0 && (rawSamples.size / durationSeconds) > 12000)
                            val c2Samples =
                                if (is24k) {
                                    AudioDspUtils.resample24kTo8k(rawSamples)
                                } else {
                                    rawSamples
                                }
                            Pair(Codec2Engine.encode(c2Samples, mode = codec2Mode), ChunkingEngine.TYPE_AUDIO_CODEC2)
                        }
                    }
                val payloadBytes = pair.first
                val payloadType = pair.second

                // Live PTT audio is ephemeral: it must not be written to disk either.
                val localPath =
                    if (isLive) {
                        ""
                    } else {
                        MediaStorage.saveAudioPayload(payloadBytes, if (isWavTokenizer) "wt" else "c2")
                    }

                // Transition from local encoding/saving to mesh transmission (unbroken handover)
                _isSendingVoiceNote.value = true
                _isProcessingVoiceNote.value = false

                var persistedId: PersistedPacketId? = null
                if (!isLive) {
                    val pathSuffix = if (localPath.isNotBlank()) " | $localPath" else ""
                    val minutes = actualDurationSeconds / 60
                    val seconds = actualDurationSeconds % 60
                    val durationStr = "$minutes:${seconds.toString().padStart(2, '0')}"
                    val localRepresentation = "[Voice Note: $durationStr$pathSuffix]"

                    val parsedKey = ContactKey(contactKey)
                    val channel = parsedKey.channelOrNull ?: 0
                    val dest = parsedKey.addressString
                    val ourNode = nodeRepository.ourNodeInfo.value
                    val fromId = ourNode?.user?.id ?: NodeAddress.ID_LOCAL

                    val (uniquePacketId, uniqueReceivedTime) =
                        synchronized(voicePacketLock) {
                            val id = ++nextVoicePacketId
                            val time = maxOf(nowMillis, lastVoicePacketTime + 1)
                            lastVoicePacketTime = time
                            id to time
                        }

                    val localPacket =
                        DataPacket(to = dest, channel = channel, text = localRepresentation).apply {
                            from = fromId
                            id = uniquePacketId
                            status = MessageStatus.QUEUED
                        }

                    persistedId =
                        packetRepository.savePacket(
                            myNodeNum = ourNode?.num ?: 0,
                            contactKey = contactKey,
                            packet = localPacket,
                            receivedTime = uniqueReceivedTime,
                        )
                }

                val payloadToSend =
                    if (isLive) {
                        LivePttSessionManager.default.createAudioPacket(payloadBytes, isLivePtt = true)
                    } else {
                        payloadBytes
                    }

                val sendMediaUseCase =
                    SendMediaUseCaseImpl(
                        messagingController = messagingController,
                        nodeRepository = nodeRepository,
                        radioConfigRepository = radioConfigRepository,
                    )
                withContext(Dispatchers.Default) {
                    sendMediaUseCase(
                        payload = payloadToSend,
                        payloadType = payloadType,
                        isLivePtt = isLive,
                        contactKey = contactKey,
                        onProgress = { sent, total -> _outboundVoiceProgress.value = Pair(sent, total) },
                    )
                }

                if (isLive) {
                    chunksSent = true
                    val lora = radioConfigRepository.localConfigFlow.firstOrNull()?.lora
                    val preset = lora?.modem_preset
                    val lastChunkSize =
                        (payloadToSend.size % ChunkingEngine.DEFAULT_MAX_CHUNK_DATA_SIZE).let {
                            if (it == 0) ChunkingEngine.DEFAULT_MAX_CHUNK_DATA_SIZE else it
                        } + ChunkingEngine.HEADER_SIZE
                    val toaDelayMs = PayloadAnalyzer.calculateInterChunkDelayMs(lastChunkSize, preset)
                    interChunkDelayMs =
                        PayloadAnalyzer.calculateInterChunkDelayMs(
                            ChunkingEngine.DEFAULT_MAX_CHUNK_DATA_SIZE + ChunkingEngine.HEADER_SIZE,
                            preset,
                        )
                    // The last chunk was only handed to the radio queue; keep the floor until it has had time to
                    // clear the transceiver (time on air plus CSMA / queue headroom).
                    delay(toaDelayMs + PTT_TX_DRAIN_MARGIN_MS)
                    return@safeLaunch
                }

                persistedId?.let { id -> packetRepository.updateMessageStatus(id, MessageStatus.DELIVERED) }
            } finally {
                // The floor is ALWAYS handed back (success, failure or cancellation), and the sender then stays locked
                // until the peers confirm the audio finished playing, so a mid-playback re-key cannot happen.
                if (isLive) {
                    finishLiveTransmission(contactKey, chunksSent, playbackMs, interChunkDelayMs)
                }
                outboundPttJob = null
                _outboundVoiceProgress.value = null
                _isSendingVoiceNote.value = false
                _isProcessingVoiceNote.value = false
                _isRecordingVoiceNote.value = false
            }
        }
    }

    fun sendImageAttachment(
        mediaPath: String,
        caption: String? = null,
        rawImageBytes: ByteArray? = null,
        contactKey: String = "0${NodeAddress.ID_BROADCAST}",
    ) {
        safeLaunch(errorEvents = sendErrorEvents, tag = "sendImageAttachment") {
            val payload =
                withContext(Dispatchers.Default) {
                    rawImageBytes
                        ?: processImageFileTo1Bit(mediaPath)
                        ?: RleCompressor.compress(mediaPath.encodeToByteArray())
                }
            val sendMediaUseCase =
                SendMediaUseCaseImpl(
                    messagingController = messagingController,
                    nodeRepository = nodeRepository,
                    radioConfigRepository = radioConfigRepository,
                )
            withContext(Dispatchers.Default) {
                sendMediaUseCase(
                    payload = payload,
                    payloadType = ChunkingEngine.TYPE_IMAGE_1BIT,
                    isLivePtt = false,
                    contactKey = contactKey,
                )
            }
            val captionSuffix = if (!caption.isNullOrBlank()) " $caption" else ""
            sendMessageUseCase.invoke("[1-bit Image: $mediaPath]$captionSuffix", contactKey, null)
        }
    }

    fun toggleLivePtt(
        enable: Boolean,
        contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}",
    ) {
        safeLaunch(errorEvents = sendErrorEvents, tag = "toggleLivePtt") {
            val myNode = nodeRepository.ourNodeInfo.value
            val myNodeId: String = myNode?.user?.id ?: nodeRepository.myId.value ?: "!local"
            val rawLong = myNode?.user?.long_name
            val rawShort = myNode?.user?.short_name
            val myNodeName: String =
                if (!rawLong.isNullOrEmpty()) {
                    rawLong
                } else if (!rawShort.isNullOrEmpty()) {
                    rawShort
                } else {
                    "Me"
                }

            val myNodeNum = myNode?.num ?: 0

            val controlPacket =
                if (enable) {
                    startPttHeartbeat(contactKey)
                    LivePttSessionManager.default.startHosting(myNodeId, myNodeName, myNodeNum)
                } else {
                    pttHeartbeatJob?.cancel()
                    pttHeartbeatJob = null
                    LivePttSessionManager.default.leaveSession(myNodeId, myNodeName)
                }

            val sendMediaUseCase =
                SendMediaUseCaseImpl(
                    messagingController = messagingController,
                    nodeRepository = nodeRepository,
                    radioConfigRepository = radioConfigRepository,
                )
            withContext(Dispatchers.Default) {
                sendMediaUseCase(
                    payload = controlPacket,
                    payloadType = ChunkingEngine.TYPE_PTT_CONTROL,
                    isLivePtt = enable,
                    contactKey = contactKey,
                )
            }
        }
    }

    fun enterLivePttRoom(contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}") {
        toggleLivePtt(enable = true, contactKey = contactKey)
    }

    fun joinLivePttSession(
        hostId: String,
        hostName: String,
        contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}",
    ) {
        safeLaunch(errorEvents = sendErrorEvents, tag = "joinLivePttSession") {
            val myNode = nodeRepository.ourNodeInfo.value
            val myNodeId: String = myNode?.user?.id ?: nodeRepository.myId.value ?: "!local"
            val myNodeNum = myNode?.num ?: 0
            val rawLong = myNode?.user?.long_name
            val rawShort = myNode?.user?.short_name
            val myNodeName: String =
                if (!rawLong.isNullOrEmpty()) {
                    rawLong
                } else if (!rawShort.isNullOrEmpty()) {
                    rawShort
                } else {
                    "Me"
                }

            startPttHeartbeat(contactKey)
            val joinPacket =
                LivePttSessionManager.default.joinSession(hostId, hostName, myNodeId, myNodeName, myNodeNum)
            val sendMediaUseCase =
                SendMediaUseCaseImpl(
                    messagingController = messagingController,
                    nodeRepository = nodeRepository,
                    radioConfigRepository = radioConfigRepository,
                )
            withContext(Dispatchers.Default) {
                sendMediaUseCase(
                    payload = joinPacket,
                    payloadType = ChunkingEngine.TYPE_PTT_CONTROL,
                    isLivePtt = true,
                    contactKey = contactKey,
                )
            }
        }
    }

    fun dismissPttInvitation() {
        LivePttSessionManager.default.dismissInvitation()
    }

    /** Playback duration of the captured audio, derived from the real sample count when available. */
    private fun actualPlaybackMs(durationSeconds: Int, audioBytes: ByteArray?, sampleRate: Int?): Long {
        val rate = sampleRate ?: AudioController.activeSampleRate
        return if (audioBytes != null && audioBytes.isNotEmpty() && rate > 0) {
            (audioBytes.size / 2L) * 1000L / rate
        } else {
            durationSeconds.coerceAtLeast(1) * 1000L
        }
    }

    /**
     * Ends a live transmission: starts the wait for the peers' playback-drained acks (only if chunks actually went on
     * air), then broadcasts the floor release. If the floor was already lost (collision) nothing is broadcast.
     */
    private fun finishLiveTransmission(
        contactKey: String,
        chunksSent: Boolean,
        playbackMs: Long,
        interChunkDelayMs: Long,
    ) {
        if (!LivePttSessionManager.default.state.value.isTransmitting) {
            stopFloorKeepAlive()
            return
        }
        if (chunksSent) {
            val timeoutMs =
                (playbackMs + PTT_PEER_DECODE_MARGIN_MS + 3L * interChunkDelayMs).coerceAtMost(PTT_PEER_WAIT_CAP_MS)
            LivePttSessionManager.default.beginPeerPlaybackWait(timeoutMs)
        }
        broadcastFloorRelease(contactKey)
    }

    fun leaveLivePttSession(contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}") {
        // The screen reports exit twice (button + dispose). Only the first call is a real leave.
        if (!LivePttSessionManager.default.state.value.isInSession) {
            pttHeartbeatJob?.cancel()
            pttHeartbeatJob = null
            ownsPttSession = false
            return
        }
        ownsPttSession = false
        stopFloorKeepAlive()
        outboundPttJob?.cancel()
        outboundPttJob = null
        AudioPlayer.stop()
        pttHeartbeatJob?.cancel()
        pttHeartbeatJob = null
        val myNode = nodeRepository.ourNodeInfo.value
        val myNodeId: String = myNode?.user?.id ?: nodeRepository.myId.value ?: "!local"
        val myNodeNum = myNode?.num ?: 0
        val rawLong = myNode?.user?.long_name
        val rawShort = myNode?.user?.short_name
        val myNodeName: String =
            if (!rawLong.isNullOrEmpty()) {
                rawLong
            } else if (!rawShort.isNullOrEmpty()) {
                rawShort
            } else {
                "Me"
            }

        val leavePacket = LivePttSessionManager.default.leaveLiveSession(myNodeNum, myNodeId, myNodeName)
        sendDirectPttPacket(leavePacket, contactKey)
    }

    fun sendPcCommand(
        command: String,
        targetMode: ActionTargetMode = ActionTargetMode.WSL,
        imagePayload: ByteArray? = null,
        contactKey: String = "0${NodeAddress.ID_BROADCAST}",
    ) {
        safeLaunch(errorEvents = sendErrorEvents, tag = "sendPcCommand") {
            watchdogJobs[contactKey]?.cancel()

            val payload =
                pcCommandSessionManager.createOutboundPayload(
                    targetMode = targetMode,
                    commandText = command,
                    imagePayload = imagePayload,
                    contactKey = contactKey,
                )
            val sendMediaUseCase =
                SendMediaUseCaseImpl(
                    messagingController = messagingController,
                    nodeRepository = nodeRepository,
                    radioConfigRepository = radioConfigRepository,
                )
            withContext(Dispatchers.Default) {
                sendMediaUseCase(
                    payload = payload,
                    payloadType = ChunkingEngine.TYPE_PC_COMMAND,
                    isLivePtt = false,
                    contactKey = contactKey,
                )
            }

            val displayTag = "${targetMode.iconEmoji} [${targetMode.title}]"
            sendMessageUseCase.invoke("$displayTag $command", contactKey, null)

            // 20-Second Watchdog Coroutine tied to viewModelScope
            watchdogJobs[contactKey] =
                viewModelScope.launch {
                    delay(PcCommandSessionManager.WATCHDOG_TIMEOUT_MS)
                    val didTimeout = pcCommandSessionManager.triggerWatchdogTimeout(contactKey)
                    if (didTimeout) {
                        val timeoutWarning = "[System Warning]: Timeout: Target did not respond as a Command Center."
                        sendMessageUseCase.invoke(timeoutWarning, contactKey, null)
                    }
                }
        }
    }

    fun cancelPcCommandSession(
        contactKey: String = contactKeyForPagedMessages.value ?: "0${NodeAddress.ID_BROADCAST}",
    ) {
        watchdogJobs[contactKey]?.cancel()
        watchdogJobs.remove(contactKey)
        pcCommandSessionManager.cancelSession(contactKey)
        safeLaunch(tag = "cancelPcCommandSession") {
            sendMessageUseCase.invoke("[System]: Cancelled by user.", contactKey, null)
        }
    }

    fun deleteMessages(uuidList: List<Long>) =
        safeLaunch(context = ioDispatcher, tag = "deleteMessages") { packetRepository.deleteMessages(uuidList) }

    // region ── Translation ──

    /** Whether on-device translation into the current locale is possible (always false on F-Droid/desktop). */
    val translationAvailable: StateFlow<Boolean> =
        flow { emit(messageTranslationService.isLanguageAvailable(currentLocaleCode())) }
            .stateInWhileSubscribed(initialValue = false)

    private val _translationDialogState = MutableStateFlow<TranslationDialogState>(TranslationDialogState.Hidden)
    val translationDialogState: StateFlow<TranslationDialogState> = _translationDialogState.asStateFlow()

    /** Translates [message] into the device language, or just re-shows a previously persisted translation. */
    // Translation methods use plain safeLaunch (like sendMessage): every call inside is a main-safe suspend
    // function (the repository hops to IO internally), and tests stay on the deterministic test scheduler.
    fun translateMessage(message: Message) = safeLaunch(tag = "translateMessage") {
        if (message.translatedText != null) {
            packetRepository.setShowTranslated(message.uuid, true)
            return@safeLaunch
        }
        when (val result = messageTranslationService.translate(message.text, currentLocaleCode())) {
            is TranslationResult.Success ->
                packetRepository.setMessageTranslation(message.uuid, result.translatedText)

            is TranslationResult.NotRequired ->
                snackbarManager.showSnackbar(
                    MessagingUiTextResolver.resolve(UiText.Resource(Res.string.translation_not_required)),
                )

            is TranslationResult.ModelDownloadRequired ->
                _translationDialogState.value =
                    TranslationDialogState.DownloadPrompt(message, result.languageTags, result.estimatedSizeMb)

            is TranslationResult.Unavailable ->
                snackbarManager.showSnackbar(
                    MessagingUiTextResolver.resolve(UiText.Resource(Res.string.translation_failed)),
                )
        }
    }

    fun toggleShowTranslated(message: Message) = safeLaunch(tag = "toggleShowTranslated") {
        packetRepository.setShowTranslated(message.uuid, !message.showTranslated)
    }

    fun confirmTranslationModelDownload() {
        val prompt = _translationDialogState.value as? TranslationDialogState.DownloadPrompt ?: return
        safeLaunch(tag = "downloadTranslationModel") {
            _translationDialogState.value = TranslationDialogState.Downloading(prompt.message)
            val result = messageTranslationService.downloadLanguageModels(prompt.languageTags)
            _translationDialogState.value = TranslationDialogState.Hidden
            when (result) {
                is DownloadResult.Success -> translateMessage(prompt.message)

                is DownloadResult.Failed ->
                    snackbarManager.showSnackbar(
                        MessagingUiTextResolver.resolve(UiText.Resource(Res.string.translation_model_download_failed)),
                    )
            }
        }
    }

    fun dismissTranslationDialog() {
        _translationDialogState.value = TranslationDialogState.Hidden
    }

    // endregion

    /**
     * Marks [contactKey] as the conversation on screen so an arriving message for it is not also announced as a
     * notification. Paired with [onConversationHidden] on pause — backgrounding the app hides the screen, which is what
     * makes a single signal enough.
     */
    fun onConversationVisible(contactKey: String) = activeConversationTracker.setActive(contactKey)

    fun onConversationHidden(contactKey: String) = activeConversationTracker.clearActive(contactKey)

    fun clearUnreadCount(contact: String, messageUuid: Long, lastReadTimestamp: Long) =
        safeLaunch(context = ioDispatcher, tag = "clearUnreadCount") {
            val existingTimestamp = contactSettings.value[contact]?.lastReadMessageTimestamp ?: Long.MIN_VALUE
            if (lastReadTimestamp <= existingTimestamp) {
                return@safeLaunch
            }
            packetRepository.clearUnreadCount(contact, lastReadTimestamp)
            packetRepository.updateLastReadMessage(contact, messageUuid, lastReadTimestamp)
            val unreadCount = packetRepository.getUnreadCount(contact)
            // The count is read before this suspends, so it can be stale by the time the cancel lands. Re-checking
            // that the conversation is still on screen closes the window: while it is, an arriving message posts no
            // notification at all (ActiveConversationTracker suppresses it), so there is nothing this can erase. Once
            // the user has left, the notification is theirs to see and must survive.
            // Cancelling must go through the domain manager: the conversation is posted under a notification *tag*,
            // so an untagged cancel by id never matches it. This also rebuilds the group summary.
            val stillOnScreen = activeConversationTracker.activeContactKey.value == contact
            if (unreadCount == 0 && stillOnScreen) meshNotificationManager.cancelMessageNotification(contact)
        }

    companion object {
        private const val SEARCH_DEBOUNCE_MS = 300L
        private const val MIN_SEARCH_LENGTH = 2
        private const val DRAFT_PERSISTENCE_DELAY_MS = 300L
        private const val KEY_DRAFT_MESSAGE_PREFIX = "draftMessage:"

        private var nextVoicePacketId = kotlin.random.Random.nextInt(1, 10_000_000)
        private var lastVoicePacketTime = 0L
        private val voicePacketLock = Any()

        /** Saved-state drafts are keyed per conversation; one shared entry would leak text between them. */
        private fun draftKey(contactKey: String) = KEY_DRAFT_MESSAGE_PREFIX + contactKey
    }
}

/** Delay before the second FLOOR_REL / PTT_DONE copy (LoRa is lossy and both are unacknowledged). */
private const val PTT_RELEASE_REPEAT_DELAY_MS = 600L
private const val PTT_DONE_REPEAT_DELAY_MS = 800L

/** Extra headroom after the last chunk's time on air before the floor is released (CSMA backoff, TX queue). */
private const val PTT_TX_DRAIN_MARGIN_MS = 700L

/** Receiver-side decode + HAL start-up headroom used for the sender's fallback wait if no ack ever arrives. */
private const val PTT_PEER_DECODE_MARGIN_MS = 5_000L
private const val PTT_PEER_WAIT_CAP_MS = 75_000L
private const val PTT_PLAYBACK_WATCHDOG_MARGIN_MS = 20_000L
private const val PTT_KEEPALIVE_HARD_CAP_MS = 120_000L
