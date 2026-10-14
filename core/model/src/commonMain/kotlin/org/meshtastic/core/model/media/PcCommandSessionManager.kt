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
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import org.meshtastic.core.model.NodeAddress

/**
 * Supported execution targets for the Contextual PC/AI Gateway. Strictly avoids Docker: WSL 2.0, PowerShell,
 * Multi-Monitor Screenshots, Gemini, Ollama.
 */
enum class ActionTargetMode(
    val id: Byte,
    val title: String,
    val iconEmoji: String,
    val allowsAudio: Boolean,
    val allowsImage: Boolean,
    val allowsTextInput: Boolean,
) {
    P2P(0x00, "P2P Chat", "💬", allowsAudio = true, allowsImage = true, allowsTextInput = true),
    CLOUD_AI_GEMINI(0x01, "Cloud AI (Gemini)", "☁️", allowsAudio = false, allowsImage = true, allowsTextInput = true),
    LOCAL_AI_OLLAMA(0x02, "Local AI (Ollama)", "🖥️", allowsAudio = false, allowsImage = true, allowsTextInput = true),
    WSL(0x03, "WSL 2.0", "⌨️", allowsAudio = false, allowsImage = false, allowsTextInput = true),
    POWERSHELL(0x04, "PowerShell", "⚡", allowsAudio = false, allowsImage = false, allowsTextInput = true),
    GET_SCREENSHOT(0x05, "Get Screenshot", "📸", allowsAudio = false, allowsImage = false, allowsTextInput = false),
    ;

    companion object {
        fun fromId(id: Byte): ActionTargetMode = entries.firstOrNull { it.id == id } ?: P2P
    }
}

/** Inbound streaming response statuses from the Remote PC Gateway. */
enum class PcStreamStatus(val id: Byte) {
    THINKING(0x01), // Model is generating "Chain of Thought"
    STREAMING_CHUNK(0x02), // Model is streaming final response
    STREAM_COMPLETE(0x03), // Execution finished
    SCREENSHOT_DATA(0x05), // Inbound 1-bit screenshot container
    ERROR(0x7F), // Remote error
    ;

    companion object {
        fun fromId(id: Byte): PcStreamStatus = entries.firstOrNull { it.id == id } ?: ERROR
    }
}

/**
 * Real-time state of the Remote PC Gateway / AI Chain of Thought, Terminal execution, or Screenshot stream. Scoped
 * strictly to a specific [contactKey] to eliminate cross-chat bleeding.
 */
data class PcAiStreamState(
    val contactKey: String = "",
    val activeTarget: ActionTargetMode = ActionTargetMode.P2P,
    // AI Chain of Thought (Cloud Gemini / Local Ollama strictly)
    val isThinking: Boolean = false,
    val thinkingThoughts: String = "",
    // CLI Terminal Execution (WSL 2.0 / PowerShell strictly)
    val isExecuting: Boolean = false,
    val executingCommand: String = "",
    // Screen Capture (Get Screenshot strictly)
    val isAwaitingScreenshot: Boolean = false,
    // Stream output (Markdown AI tokens or Terminal stdout)
    val isResponseStreaming: Boolean = false,
    val responseText: String = "",
    // Lifecycle & Watchdog
    val isComplete: Boolean = true,
    val isTimedOut: Boolean = false,
    val awaitingSinceTimestamp: Long = 0L,
    val lastScreenshotBytes: ByteArray? = null,
    val errorMessage: String? = null,
) {
    val isAwaitingResponse: Boolean
        get() = isThinking || isExecuting || isAwaitingScreenshot || isResponseStreaming

    val canManualCancel: Boolean
        get() =
            isAwaitingResponse &&
                awaitingSinceTimestamp > 0L &&
                (kotlinx.datetime.Clock.System.now().toEpochMilliseconds() - awaitingSinceTimestamp >= 10_000L)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcAiStreamState) return false
        return contactKey == other.contactKey &&
            activeTarget == other.activeTarget &&
            isThinking == other.isThinking &&
            thinkingThoughts == other.thinkingThoughts &&
            isExecuting == other.isExecuting &&
            executingCommand == other.executingCommand &&
            isAwaitingScreenshot == other.isAwaitingScreenshot &&
            isResponseStreaming == other.isResponseStreaming &&
            responseText == other.responseText &&
            isComplete == other.isComplete &&
            isTimedOut == other.isTimedOut &&
            awaitingSinceTimestamp == other.awaitingSinceTimestamp &&
            lastScreenshotBytes.contentEquals(other.lastScreenshotBytes) &&
            errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = contactKey.hashCode()
        result = 31 * result + activeTarget.hashCode()
        result = 31 * result + isThinking.hashCode()
        result = 31 * result + thinkingThoughts.hashCode()
        result = 31 * result + isExecuting.hashCode()
        result = 31 * result + executingCommand.hashCode()
        result = 31 * result + isAwaitingScreenshot.hashCode()
        result = 31 * result + isResponseStreaming.hashCode()
        result = 31 * result + responseText.hashCode()
        result = 31 * result + isComplete.hashCode()
        result = 31 * result + isTimedOut.hashCode()
        result = 31 * result + (lastScreenshotBytes?.contentHashCode() ?: 0)
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

/** Session manager for outbound PC command packaging, per-conversation state scoping, and inbound streaming updates. */
class PcCommandSessionManager {

    companion object {
        val default = PcCommandSessionManager()
        const val WATCHDOG_TIMEOUT_MS = 20_000L
    }

    private val lock = Any()
    private val sessionFlows = mutableMapOf<String, MutableStateFlow<PcAiStreamState>>()

    /** Returns an isolated, per-conversation [StateFlow] scoped strictly to [contactKey]. */
    fun getStreamState(contactKey: String): StateFlow<PcAiStreamState> = synchronized(lock) {
        sessionFlows
            .getOrPut(contactKey) { MutableStateFlow(PcAiStreamState(contactKey = contactKey)) }
            .asStateFlow()
    }

    private fun getMutableStateFlow(contactKey: String): MutableStateFlow<PcAiStreamState> = synchronized(lock) {
        sessionFlows.getOrPut(contactKey) { MutableStateFlow(PcAiStreamState(contactKey = contactKey)) }
    }

    // Default global compatibility flow (broadcast channel 0)
    val streamState: StateFlow<PcAiStreamState>
        get() = getStreamState("0${NodeAddress.ID_BROADCAST}")

    /**
     * Builds an outbound wire payload for [targetMode] scoped strictly to [contactKey]. Format: [TargetID: 1B | Flags:
     * 1B | TextLength: 2B | CommandText] + Optional Image Bytes.
     */
    fun createOutboundPayload(
        targetMode: ActionTargetMode,
        commandText: String,
        imagePayload: ByteArray? = null,
        contactKey: String = "0${NodeAddress.ID_BROADCAST}",
    ): ByteArray {
        val textBytes = commandText.encodeToByteArray()
        val textLength = textBytes.size
        val imageLength = imagePayload?.size ?: 0

        val payload = ByteArray(4 + textLength + imageLength)
        payload[0] = targetMode.id
        payload[1] = if (imagePayload != null) 0x01 else 0x00
        payload[2] = ((textLength shr 8) and 0xFF).toByte()
        payload[3] = (textLength and 0xFF).toByte()

        textBytes.copyInto(payload, destinationOffset = 4)
        imagePayload?.copyInto(payload, destinationOffset = 4 + textLength)

        val flow = getMutableStateFlow(contactKey)
        val now = Clock.System.now().toEpochMilliseconds()
        when (targetMode) {
            ActionTargetMode.GET_SCREENSHOT -> {
                flow.update {
                    it.copy(
                        contactKey = contactKey,
                        activeTarget = targetMode,
                        isAwaitingScreenshot = true,
                        isThinking = false,
                        isExecuting = false,
                        isResponseStreaming = false,
                        responseText = "",
                        isComplete = false,
                        isTimedOut = false,
                        awaitingSinceTimestamp = now,
                        errorMessage = null,
                    )
                }
            }

            ActionTargetMode.WSL,
            ActionTargetMode.POWERSHELL,
            -> {
                flow.update {
                    it.copy(
                        contactKey = contactKey,
                        activeTarget = targetMode,
                        isExecuting = true,
                        executingCommand = commandText,
                        isThinking = false,
                        isAwaitingScreenshot = false,
                        isResponseStreaming = false,
                        responseText = "",
                        isComplete = false,
                        isTimedOut = false,
                        awaitingSinceTimestamp = now,
                        errorMessage = null,
                    )
                }
            }

            ActionTargetMode.CLOUD_AI_GEMINI,
            ActionTargetMode.LOCAL_AI_OLLAMA,
            -> {
                flow.update {
                    it.copy(
                        contactKey = contactKey,
                        activeTarget = targetMode,
                        isThinking = true,
                        thinkingThoughts = "",
                        isExecuting = false,
                        isAwaitingScreenshot = false,
                        isResponseStreaming = false,
                        responseText = "",
                        isComplete = false,
                        isTimedOut = false,
                        awaitingSinceTimestamp = now,
                        errorMessage = null,
                    )
                }
            }

            ActionTargetMode.P2P -> {
                // P2P messages do not lock the remote PC gateway state
            }
        }

        return payload
    }

    /**
     * Ingests an inbound streaming packet received over `PortNum.PRIVATE_APP` scoped to [contactKey]. Format:
     * [StatusID: 1B | Flags: 1B | StreamChunkBytes].
     */
    fun handleInboundStreamPacket(rawPayload: ByteArray, contactKey: String = "0${NodeAddress.ID_BROADCAST}") {
        if (rawPayload.size < 2) return

        val status = PcStreamStatus.fromId(rawPayload[0])
        val chunkData = rawPayload.copyOfRange(2, rawPayload.size)

        val targetFlows =
            synchronized(lock) {
                val flows = mutableListOf<MutableStateFlow<PcAiStreamState>>()
                sessionFlows[contactKey]?.let { flows.add(it) }
                for ((key, flow) in sessionFlows) {
                    if (key != contactKey && flow.value.isAwaitingResponse) {
                        flows.add(flow)
                    }
                }
                if (flows.isEmpty()) {
                    flows.add(getMutableStateFlow(contactKey))
                }
                flows
            }

        for (flow in targetFlows) {
            when (status) {
                PcStreamStatus.THINKING -> {
                    val newThoughts = chunkData.decodeToString()
                    flow.update {
                        it.copy(
                            isThinking = true,
                            isExecuting = false,
                            isAwaitingScreenshot = false,
                            thinkingThoughts = it.thinkingThoughts + newThoughts,
                            isComplete = false,
                            isTimedOut = false,
                        )
                    }
                }

                PcStreamStatus.STREAMING_CHUNK -> {
                    val newChunk = chunkData.decodeToString()
                    flow.update {
                        it.copy(
                            isThinking = false,
                            isExecuting = false,
                            isAwaitingScreenshot = false,
                            isResponseStreaming = true,
                            responseText = it.responseText + newChunk,
                            isComplete = false,
                            isTimedOut = false,
                        )
                    }
                }

                PcStreamStatus.STREAM_COMPLETE -> {
                    flow.update {
                        it.copy(
                            isThinking = false,
                            isExecuting = false,
                            isAwaitingScreenshot = false,
                            isResponseStreaming = false,
                            isComplete = true,
                            isTimedOut = false,
                            awaitingSinceTimestamp = 0L,
                        )
                    }
                }

                PcStreamStatus.SCREENSHOT_DATA -> {
                    flow.update {
                        it.copy(
                            isAwaitingScreenshot = false,
                            isThinking = false,
                            isExecuting = false,
                            isResponseStreaming = false,
                            isComplete = true,
                            isTimedOut = false,
                            awaitingSinceTimestamp = 0L,
                            lastScreenshotBytes = chunkData,
                        )
                    }
                }

                PcStreamStatus.ERROR -> {
                    val errorMsg = chunkData.decodeToString()
                    flow.update {
                        it.copy(
                            isThinking = false,
                            isExecuting = false,
                            isAwaitingScreenshot = false,
                            isResponseStreaming = false,
                            isComplete = true,
                            isTimedOut = false,
                            awaitingSinceTimestamp = 0L,
                            errorMessage = errorMsg,
                        )
                    }
                }
            }
        }
    }

    /**
     * RULE 3: Any-Message Interruption. If ANY valid message arrives from [contactKey] while the app is awaiting a
     * screenshot, execution, or thinking response, immediately clear the waiting state!
     */
    fun onAnyMessageReceived(contactKey: String) {
        val targetFlows =
            synchronized(lock) {
                val flows = mutableListOf<MutableStateFlow<PcAiStreamState>>()
                sessionFlows[contactKey]?.let { flows.add(it) }
                for ((key, flow) in sessionFlows) {
                    if (key != contactKey && flow.value.isAwaitingResponse) {
                        flows.add(flow)
                    }
                }
                flows
            }

        for (flow in targetFlows) {
            flow.update { current ->
                if (current.isAwaitingResponse) {
                    current.copy(
                        isThinking = false,
                        isExecuting = false,
                        isAwaitingScreenshot = false,
                        isResponseStreaming = false,
                        isComplete = true,
                        awaitingSinceTimestamp = 0L,
                    )
                } else {
                    current
                }
            }
        }
    }

    /** RULE 1: Manual cancel requested by the user after >= 10s wait. */
    fun cancelSession(contactKey: String = "0${NodeAddress.ID_BROADCAST}") {
        val flow = getMutableStateFlow(contactKey)
        flow.update { current ->
            current.copy(
                isThinking = false,
                isExecuting = false,
                isAwaitingScreenshot = false,
                isResponseStreaming = false,
                isComplete = true,
                awaitingSinceTimestamp = 0L,
                errorMessage = "Cancelled by user.",
            )
        }
    }

    /**
     * Triggers a watchdog timeout for [contactKey].
     *
     * @return true if an active incomplete session was timed out, false if it was already complete.
     */
    fun triggerWatchdogTimeout(contactKey: String = "0${NodeAddress.ID_BROADCAST}"): Boolean {
        val flow = getMutableStateFlow(contactKey)
        var timedOut = false
        flow.update { current ->
            if (!current.isComplete) {
                timedOut = true
                current.copy(
                    isThinking = false,
                    isExecuting = false,
                    isAwaitingScreenshot = false,
                    isResponseStreaming = false,
                    isComplete = true,
                    isTimedOut = true,
                    awaitingSinceTimestamp = 0L,
                    errorMessage = "Timeout: Target did not respond as a Command Center.",
                )
            } else {
                current
            }
        }
        return timedOut
    }

    fun resetState(contactKey: String = "0${NodeAddress.ID_BROADCAST}") {
        getMutableStateFlow(contactKey).value = PcAiStreamState(contactKey = contactKey)
    }
}
