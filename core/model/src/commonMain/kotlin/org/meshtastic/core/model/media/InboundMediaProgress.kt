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

/**
 * Observable snapshot of an in-progress inbound media transmission arriving over the mesh network.
 *
 * @property senderId Node ID of the remote transmitter (e.g. "!12345678").
 * @property messageId 8-bit wire message ID identifying the multi-chunk media packet.
 * @property payloadType Media type indicator (e.g. [ChunkingEngine.TYPE_AUDIO_CODEC2],
 *   [ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER]).
 * @property receivedChunks Number of distinct chunks successfully ingested so far.
 * @property totalChunks Total expected chunks for this transmission.
 * @property isLivePtt Whether this incoming transmission is part of an active synchronous Live PTT call.
 * @property channel Mesh radio channel index.
 */
data class InboundMediaProgress(
    val senderId: String,
    val messageId: Int,
    val payloadType: Byte,
    val receivedChunks: Int,
    val totalChunks: Int,
    val isLivePtt: Boolean = false,
    val channel: Int = 0,
) {
    /** True if this incoming transfer is an audio payload (Codec 2 or WavTokenizer). */
    val isAudio: Boolean
        get() = payloadType == ChunkingEngine.TYPE_AUDIO_CODEC2 || payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
}
