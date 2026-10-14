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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PcCommandSessionManagerTest {

    @Test
    fun testActionTargetModeLockoutRules() {
        // P2P: Audio, Image, and Text all enabled
        assertTrue(ActionTargetMode.P2P.allowsAudio)
        assertTrue(ActionTargetMode.P2P.allowsImage)
        assertTrue(ActionTargetMode.P2P.allowsTextInput)

        // Cloud AI (Gemini): Audio disabled, Image & Text enabled
        assertFalse(ActionTargetMode.CLOUD_AI_GEMINI.allowsAudio)
        assertTrue(ActionTargetMode.CLOUD_AI_GEMINI.allowsImage)
        assertTrue(ActionTargetMode.CLOUD_AI_GEMINI.allowsTextInput)

        // Local AI (Ollama): Audio disabled, Image & Text enabled
        assertFalse(ActionTargetMode.LOCAL_AI_OLLAMA.allowsAudio)
        assertTrue(ActionTargetMode.LOCAL_AI_OLLAMA.allowsImage)
        assertTrue(ActionTargetMode.LOCAL_AI_OLLAMA.allowsTextInput)

        // Terminal (WSL 2.0 & PowerShell): Audio & Image disabled, ONLY Text enabled
        assertFalse(ActionTargetMode.WSL.allowsAudio)
        assertFalse(ActionTargetMode.WSL.allowsImage)
        assertTrue(ActionTargetMode.WSL.allowsTextInput)

        assertFalse(ActionTargetMode.POWERSHELL.allowsAudio)
        assertFalse(ActionTargetMode.POWERSHELL.allowsImage)
        assertTrue(ActionTargetMode.POWERSHELL.allowsTextInput)

        // Screenshot: Audio & Image disabled, Text input temporarily disabled
        assertFalse(ActionTargetMode.GET_SCREENSHOT.allowsAudio)
        assertFalse(ActionTargetMode.GET_SCREENSHOT.allowsImage)
        assertFalse(ActionTargetMode.GET_SCREENSHOT.allowsTextInput)
    }

    @Test
    fun testCreateOutboundPayloadPackaging() {
        val manager = PcCommandSessionManager()
        val command = "ls -la"
        val payload = manager.createOutboundPayload(ActionTargetMode.WSL, command)

        assertEquals(ActionTargetMode.WSL.id, payload[0]) // Target ID
        assertEquals(0x00.toByte(), payload[1]) // Flag: No image
        val textLength = (payload[2].toInt() shl 8) or (payload[3].toInt() and 0xFF)
        assertEquals(command.length, textLength)

        val extractedCmd = payload.copyOfRange(4, 4 + textLength).decodeToString()
        assertEquals(command, extractedCmd)
    }

    @Test
    fun testCreateOutboundPayloadWithVisionImage() {
        val manager = PcCommandSessionManager()
        val command = "What is shown in this image?"
        val fakeImage = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val payload = manager.createOutboundPayload(ActionTargetMode.CLOUD_AI_GEMINI, command, fakeImage)

        assertEquals(ActionTargetMode.CLOUD_AI_GEMINI.id, payload[0])
        assertEquals(0x01.toByte(), payload[1]) // Flag: Image attached
        val textLength = (payload[2].toInt() shl 8) or (payload[3].toInt() and 0xFF)
        assertEquals(command.length, textLength)

        val imageBytes = payload.copyOfRange(4 + textLength, payload.size)
        assertTrue(fakeImage.contentEquals(imageBytes))
    }

    @Test
    fun testSeparateTerminalExecutionAndScreenshotStates() {
        val manager = PcCommandSessionManager()
        val chatKey = "chat_test_1"

        // 1. Dispatch WSL command -> must set isExecuting = true, NOT isThinking
        manager.createOutboundPayload(targetMode = ActionTargetMode.WSL, commandText = "uname -r", contactKey = chatKey)
        val wslState = manager.getStreamState(chatKey).value
        assertTrue(wslState.isExecuting)
        assertFalse(wslState.isThinking)
        assertFalse(wslState.isAwaitingScreenshot)
        assertEquals("uname -r", wslState.executingCommand)
        assertTrue(wslState.isAwaitingResponse)

        // 2. Dispatch Screenshot -> must set isAwaitingScreenshot = true, NOT isThinking or isExecuting
        manager.createOutboundPayload(
            targetMode = ActionTargetMode.GET_SCREENSHOT,
            commandText = "TAKE_SCREENSHOT",
            contactKey = chatKey,
        )
        val screenshotState = manager.getStreamState(chatKey).value
        assertTrue(screenshotState.isAwaitingScreenshot)
        assertFalse(screenshotState.isThinking)
        assertFalse(screenshotState.isExecuting)
        assertTrue(screenshotState.isAwaitingResponse)

        // 3. Dispatch AI -> must set isThinking = true, NOT isExecuting or isAwaitingScreenshot
        manager.createOutboundPayload(
            targetMode = ActionTargetMode.CLOUD_AI_GEMINI,
            commandText = "Explain quantum computing",
            contactKey = chatKey,
        )
        val aiState = manager.getStreamState(chatKey).value
        assertTrue(aiState.isThinking)
        assertFalse(aiState.isExecuting)
        assertFalse(aiState.isAwaitingScreenshot)
        assertTrue(aiState.isAwaitingResponse)
    }

    @Test
    fun testPerConversationIsolation() {
        val manager = PcCommandSessionManager()
        val chatA = "0!node_alice"
        val chatB = "0!node_bob"

        // Dispatch AI command in chatA
        manager.createOutboundPayload(
            targetMode = ActionTargetMode.CLOUD_AI_GEMINI,
            commandText = "Calculate hash",
            contactKey = chatA,
        )

        // Verify Chat A is thinking
        val stateA = manager.getStreamState(chatA).value
        assertTrue(stateA.isThinking)
        assertFalse(stateA.isComplete)

        // Verify Chat B is completely unaffected (idle)
        val stateB = manager.getStreamState(chatB).value
        assertFalse(stateB.isThinking)
        assertFalse(stateB.isExecuting)
        assertFalse(stateB.isAwaitingScreenshot)
        assertTrue(stateB.isComplete)
    }

    @Test
    fun testWatchdogTimeoutBehavior() {
        val manager = PcCommandSessionManager()
        val chatGhost = "0!ghost_node"

        // Dispatch command to a node that won't respond
        manager.createOutboundPayload(
            targetMode = ActionTargetMode.WSL,
            commandText = "cat /etc/os-release",
            contactKey = chatGhost,
        )
        assertTrue(manager.getStreamState(chatGhost).value.isAwaitingResponse)

        // Trigger the 20-second watchdog timeout
        val didTimeout = manager.triggerWatchdogTimeout(chatGhost)
        assertTrue(didTimeout)

        val timedOutState = manager.getStreamState(chatGhost).value
        assertFalse(timedOutState.isAwaitingResponse)
        assertTrue(timedOutState.isComplete)
        assertTrue(timedOutState.isTimedOut)
        assertEquals("Timeout: Target did not respond as a Command Center.", timedOutState.errorMessage)

        // Calling timeout again on completed session should return false
        assertFalse(manager.triggerWatchdogTimeout(chatGhost))
    }

    @Test
    fun testInboundStreamingTransitions() {
        val manager = PcCommandSessionManager()
        val chatKey = "chat_stream_1"
        manager.resetState(chatKey)

        // 1. Inbound THINKING chunk
        val thinkChunk =
            byteArrayOf(PcStreamStatus.THINKING.id, 0x00) + "Analyzing system metrics...".encodeToByteArray()
        manager.handleInboundStreamPacket(thinkChunk, chatKey)

        val state1 = manager.getStreamState(chatKey).value
        assertTrue(state1.isThinking)
        assertFalse(state1.isResponseStreaming)
        assertEquals("Analyzing system metrics...", state1.thinkingThoughts)

        // 2. Inbound STREAMING_CHUNK
        val streamChunk1 =
            byteArrayOf(PcStreamStatus.STREAMING_CHUNK.id, 0x00) + "CPU usage is at 12%".encodeToByteArray()
        manager.handleInboundStreamPacket(streamChunk1, chatKey)

        val state2 = manager.getStreamState(chatKey).value
        assertFalse(state2.isThinking)
        assertTrue(state2.isResponseStreaming)
        assertEquals("CPU usage is at 12%", state2.responseText)

        // 3. Inbound STREAM_COMPLETE
        val completePacket = byteArrayOf(PcStreamStatus.STREAM_COMPLETE.id, 0x00)
        manager.handleInboundStreamPacket(completePacket, chatKey)

        val state3 = manager.getStreamState(chatKey).value
        assertFalse(state3.isThinking)
        assertFalse(state3.isResponseStreaming)
        assertTrue(state3.isComplete)
    }

    @Test
    fun testScreenshotCaptureFlow() {
        val manager = PcCommandSessionManager()
        val chatKey = "chat_screen_1"
        manager.resetState(chatKey)

        // Trigger screenshot outbound
        manager.createOutboundPayload(ActionTargetMode.GET_SCREENSHOT, "TAKE_SCREENSHOT", contactKey = chatKey)
        assertTrue(manager.getStreamState(chatKey).value.isAwaitingScreenshot)
        assertFalse(manager.getStreamState(chatKey).value.isComplete)

        // Inbound 1-bit screenshot packet
        val screenshotBytes = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xFF.toByte())
        val inboundPacket = byteArrayOf(PcStreamStatus.SCREENSHOT_DATA.id, 0x00) + screenshotBytes
        manager.handleInboundStreamPacket(inboundPacket, chatKey)

        val state = manager.getStreamState(chatKey).value
        assertFalse(state.isAwaitingScreenshot)
        assertTrue(state.isComplete)
        assertNotNull(state.lastScreenshotBytes)
        assertTrue(screenshotBytes.contentEquals(state.lastScreenshotBytes))
    }
}
