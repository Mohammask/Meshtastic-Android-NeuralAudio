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
package org.meshtastic.feature.messaging.audio

import kotlinx.coroutines.flow.StateFlow

/** Cross-platform Audio Player interface for decoding and playing voice notes. */
expect object AudioPlayer {
    val isPlaying: StateFlow<Boolean>
    val activeMediaPath: StateFlow<String?>

    /**
     * Reads binary payload from [mediaPath], decodes it according to its OpCode / header, and streams PCM audio samples
     * to the hardware speaker.
     */
    fun play(mediaPath: String?, onComplete: () -> Unit = {})

    /**
     * Decodes in-memory [audioBytes] according to [isWavTokenizer] and streams PCM audio samples directly to the
     * hardware speaker with hardware buffer drain lock.
     */
    fun playRawAudio(
        audioBytes: ByteArray,
        isWavTokenizer: Boolean,
        onPlaybackStarting: () -> Unit = {},
        onComplete: () -> Unit = {},
    )

    /** Stops current playback. */
    fun stop()
}
