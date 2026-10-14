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

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import org.meshtastic.core.model.ContactKey
import org.meshtastic.core.model.DataPacket
import org.meshtastic.core.model.MessageStatus
import org.meshtastic.core.model.NodeAddress
import org.meshtastic.core.model.media.ChunkingEngine
import org.meshtastic.core.model.media.NackPacket
import org.meshtastic.core.model.media.PayloadAnalyzer
import org.meshtastic.core.model.media.SentTransmissionCache
import org.meshtastic.core.repository.MessagingController
import org.meshtastic.core.repository.NodeRepository
import org.meshtastic.core.repository.RadioConfigRepository
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.PortNum
import kotlin.random.Random

/**
 * Use case for splitting and transmitting multi-chunk binary media (Codec 2 audio, 1-bit monochrome images) over the
 * Meshtastic mesh via `PortNum.PRIVATE_APP`.
 */
interface SendMediaUseCase {
    suspend operator fun invoke(
        payload: ByteArray,
        payloadType: Byte,
        isLivePtt: Boolean = false,
        contactKey: String = "0${NodeAddress.ID_BROADCAST}",
        onProgress: (sentChunks: Int, totalChunks: Int) -> Unit = { _, _ -> },
        modemPreset: ModemPreset? = null,
    ): Int

    suspend fun handleNackRetry(nack: NackPacket, fromNodeNum: Int, modemPreset: ModemPreset? = null): Boolean
}

class SendMediaUseCaseImpl(
    private val messagingController: MessagingController,
    private val nodeRepository: NodeRepository,
    private val radioConfigRepository: RadioConfigRepository? = null,
) : SendMediaUseCase {

    companion object {
        const val DEFAULT_FALLBACK_DELAY_MS = 1000L

        private var nextMessageIdCounter = Random.nextInt(0, 254)
        private val messageIdLock = Any()

        /**
         * Rotating message id in 1..254. Purely random ids can repeat within a few messages, and receivers key their
         * reassembly (and completed-session tombstones) on `sender:messageId`, so a repeat could be mistaken for a late
         * retransmission of the previous message. Rotation guarantees 254 distinct ids before any reuse.
         */
        internal fun nextMessageId(): Int = synchronized(messageIdLock) {
            nextMessageIdCounter = (nextMessageIdCounter + 1) % 254
            nextMessageIdCounter + 1
        }
    }

    private suspend fun resolveEffectiveModemPreset(providedPreset: ModemPreset?): ModemPreset? {
        if (providedPreset != null) return providedPreset
        val repo = radioConfigRepository ?: return null
        return try {
            withTimeoutOrNull(200L) { repo.localConfigFlow.firstOrNull() }?.lora?.modem_preset
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            null
        }
    }

    override suspend operator fun invoke(
        payload: ByteArray,
        payloadType: Byte,
        isLivePtt: Boolean,
        contactKey: String,
        onProgress: (sentChunks: Int, totalChunks: Int) -> Unit,
        modemPreset: ModemPreset?,
    ): Int {
        val parsedKey = ContactKey(contactKey)
        val channel = parsedKey.channelOrNull ?: 0
        val dest = parsedKey.addressString

        val ourNode = nodeRepository.ourNodeInfo.value
        val fromId = ourNode?.user?.id ?: NodeAddress.ID_LOCAL

        val messageId = nextMessageId()
        val msgIdByte = messageId.toByte()
        val chunks =
            ChunkingEngine.split(
                payload = payload,
                payloadType = payloadType,
                isLivePtt = isLivePtt,
                messageId = messageId,
            )

        SentTransmissionCache.store(messageId = msgIdByte, chunks = chunks, destination = dest, channel = channel)

        val effectivePreset = resolveEffectiveModemPreset(modemPreset)

        for ((index, chunkBytes) in chunks.withIndex()) {
            val packet =
                DataPacket(
                    to = dest,
                    bytes = chunkBytes.toByteString(),
                    dataType = PortNum.PRIVATE_APP.value,
                    channel = channel,
                )
                    .apply {
                        from = fromId
                        id = Random.nextInt(1, Int.MAX_VALUE)
                        wantAck = false // Media streams use unacknowledged flood routing
                        status = MessageStatus.QUEUED
                    }

            messagingController.sendMessage(packet)
            onProgress(index + 1, chunks.size)

            if (index < chunks.size - 1) {
                val delayMs = PayloadAnalyzer.calculateInterChunkDelayMs(chunkBytes.size, effectivePreset)
                delay(delayMs)
            }
        }

        return messageId
    }

    override suspend fun handleNackRetry(nack: NackPacket, fromNodeNum: Int, modemPreset: ModemPreset?): Boolean {
        val transmission = SentTransmissionCache.get(nack.messageId) ?: return false
        if (transmission.hasRetried) return false
        SentTransmissionCache.markRetried(nack.messageId)

        val ourNode = nodeRepository.ourNodeInfo.value
        val fromId = ourNode?.user?.id ?: NodeAddress.numToDefaultId(fromNodeNum)
        val effectivePreset = resolveEffectiveModemPreset(modemPreset)

        val retransmittedChunks = mutableListOf<ByteArray>()
        for ((index, chunkBytes) in transmission.chunks.withIndex()) {
            if ((nack.missingMask and (1 shl index)) != 0) {
                retransmittedChunks.add(chunkBytes)
            }
        }

        for ((index, chunkBytes) in retransmittedChunks.withIndex()) {
            val packet =
                DataPacket(
                    to = transmission.destination,
                    bytes = chunkBytes.toByteString(),
                    dataType = PortNum.PRIVATE_APP.value,
                    channel = transmission.channel,
                )
                    .apply {
                        from = fromId
                        id = Random.nextInt(1, Int.MAX_VALUE)
                        wantAck = false
                        status = MessageStatus.QUEUED
                    }
            messagingController.sendMessage(packet)
            if (index < retransmittedChunks.size - 1) {
                val delayMs = PayloadAnalyzer.calculateInterChunkDelayMs(chunkBytes.size, effectivePreset)
                delay(delayMs)
            }
        }
        return true
    }
}
