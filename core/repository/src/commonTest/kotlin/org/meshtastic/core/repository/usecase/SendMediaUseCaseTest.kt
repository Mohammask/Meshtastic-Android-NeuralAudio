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
package org.meshtastic.core.repository.usecase

import kotlinx.coroutines.test.runTest
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.media.ChunkingEngine
import org.meshtastic.core.model.media.NackPacket
import org.meshtastic.core.model.media.PayloadAnalyzer
import org.meshtastic.core.model.media.SentTransmissionCache
import org.meshtastic.core.repository.MessagingController
import org.meshtastic.core.testing.FakeNodeRepository
import org.meshtastic.core.testing.FakeRadioConfigRepository
import org.meshtastic.proto.Config
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.SharedContact
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SendMediaUseCaseTest {

    private class TestMessagingController : MessagingController {
        val sentPackets = mutableListOf<DataPacket>()

        override suspend fun sendMessage(packet: DataPacket) {
            sentPackets.add(packet)
        }

        override suspend fun sendReaction(emoji: String, replyId: Int, contactKey: String) {}

        override suspend fun importContact(contact: SharedContact) {}

        override suspend fun sendSharedContact(nodeNum: Int): Boolean = true
    }

    private lateinit var messagingController: TestMessagingController
    private lateinit var nodeRepository: FakeNodeRepository
    private lateinit var radioConfigRepository: FakeRadioConfigRepository
    private lateinit var sendMediaUseCase: SendMediaUseCase

    @BeforeTest
    fun setUp() {
        messagingController = TestMessagingController()
        nodeRepository = FakeNodeRepository()
        radioConfigRepository = FakeRadioConfigRepository()
        sendMediaUseCase =
            SendMediaUseCaseImpl(
                messagingController = messagingController,
                nodeRepository = nodeRepository,
                radioConfigRepository = radioConfigRepository,
            )
    }

    @Test
    fun testSendMediaSplitsAndTransmitsChunksWithProgress() = runTest {
        // 500 bytes of audio will split into 3 chunks (200 + 200 + 100)
        val payload = ByteArray(500) { it.toByte() }
        val progressUpdates = mutableListOf<Pair<Int, Int>>()

        val messageId =
            sendMediaUseCase(
                payload = payload,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                contactKey = "0${NodeAddress.ID_BROADCAST}",
                onProgress = { sent, total -> progressUpdates.add(sent to total) },
                modemPreset = ModemPreset.SHORT_FAST,
            )

        assertTrue(messageId in 1..255)
        assertEquals(3, messagingController.sentPackets.size)
        assertEquals(3, progressUpdates.size)
        assertEquals(1 to 3, progressUpdates[0])
        assertEquals(2 to 3, progressUpdates[1])
        assertEquals(3 to 3, progressUpdates[2])

        // Verify sent packets structure
        for (packet in messagingController.sentPackets) {
            assertEquals(PortNum.PRIVATE_APP.value, packet.dataType)
            assertEquals(NodeAddress.ID_BROADCAST, packet.to)
            assertFalse(packet.wantAck)
        }

        // Verify transmission cache stored the message
        val cached = SentTransmissionCache.get(messageId.toByte())
        assertNotNull(cached)
        assertEquals(3, cached.chunks.size)
        assertFalse(cached.hasRetried)
    }

    @Test
    fun testPacingDelayCalculationAdaptiveForPreset() {
        // SF11, 250kHz (LONG_FAST) for 200 bytes should be ~1.8s
        val longFastDelay = PayloadAnalyzer.calculateInterChunkDelayMs(200, ModemPreset.LONG_FAST)
        assertTrue(longFastDelay in 1500L..2500L)

        // SF7, 250kHz (SHORT_FAST) for 200 bytes should be ~213ms
        val shortFastDelay = PayloadAnalyzer.calculateInterChunkDelayMs(200, ModemPreset.SHORT_FAST)
        assertTrue(shortFastDelay in 180L..350L)

        // Null preset fallback
        assertEquals(1800L, PayloadAnalyzer.calculateInterChunkDelayMs(200, null))
        assertEquals(800L, PayloadAnalyzer.calculateInterChunkDelayMs(50, null))
    }

    @Test
    fun testHandleNackRetryRetransmitsMissingChunksOnly() = runTest {
        val payload = ByteArray(500) { it.toByte() }
        val messageId =
            sendMediaUseCase(
                payload = payload,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                contactKey = "0${NodeAddress.ID_BROADCAST}",
                modemPreset = ModemPreset.SHORT_FAST,
            )
        messagingController.sentPackets.clear()

        // Simulate missing chunk index 1 (bitmask 0b010 = 2)
        val nack = NackPacket(messageId = messageId.toByte(), missingMask = 0b010)

        val retryHandled =
            sendMediaUseCase.handleNackRetry(nack = nack, fromNodeNum = 12345, modemPreset = ModemPreset.SHORT_FAST)

        assertTrue(retryHandled)
        assertEquals(1, messagingController.sentPackets.size)

        // Second retry succeeds (round 2)
        val secondRetry =
            sendMediaUseCase.handleNackRetry(nack = nack, fromNodeNum = 12345, modemPreset = ModemPreset.SHORT_FAST)
        assertTrue(secondRetry)
        assertEquals(2, messagingController.sentPackets.size)

        // Third retry succeeds (round 3)
        val thirdRetry =
            sendMediaUseCase.handleNackRetry(nack = nack, fromNodeNum = 12345, modemPreset = ModemPreset.SHORT_FAST)
        assertTrue(thirdRetry)
        assertEquals(3, messagingController.sentPackets.size)

        // Fourth retry must be rejected (bounded up to 3 rounds)
        val fourthRetry =
            sendMediaUseCase.handleNackRetry(nack = nack, fromNodeNum = 12345, modemPreset = ModemPreset.SHORT_FAST)
        assertFalse(fourthRetry)
    }

    @Test
    fun testSendMediaResolvesPresetFromRadioConfigRepositoryWhenOmitted() = runTest {
        // Set local config to MEDIUM_FAST
        radioConfigRepository.setLocalConfig(Config(lora = Config.LoRaConfig(modem_preset = ModemPreset.MEDIUM_FAST)))

        val payload = ByteArray(250) { 1 }
        val messageId =
            sendMediaUseCase(
                payload = payload,
                payloadType = ChunkingEngine.TYPE_AUDIO_CODEC2,
                contactKey = "0${NodeAddress.ID_BROADCAST}",
                // modemPreset omitted -> resolved via radioConfigRepository
            )

        assertTrue(messageId in 1..255)
        assertEquals(2, messagingController.sentPackets.size)
    }
}
