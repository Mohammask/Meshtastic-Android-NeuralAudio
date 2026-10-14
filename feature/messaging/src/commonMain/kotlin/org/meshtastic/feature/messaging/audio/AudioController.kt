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
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset

/** Multiplatform interface for audio capture, Codec 2 compression, 20s ToA gate enforcement, and audio playback. */
interface AudioController {
    val isRecording: StateFlow<Boolean>
    val isPlaying: StateFlow<Boolean>
    val recordingDurationSeconds: StateFlow<Float>

    /** Starts capturing PCM 16-bit Mono audio at 8kHz. Enforces the 30-second recording cap. */
    fun startRecording(onMaxDurationReached: () -> Unit = {})

    /** Stops recording and returns the raw captured 16-bit Mono PCM samples at 8kHz. */
    fun stopRecordingPcm(): ShortArray?

    /**
     * Stops recording, encodes captured audio using Codec 2 at 700bps, and runs the 20-second LoRa Time-on-Air safety
     * gate.
     *
     * @return Compressed Codec 2 byte array, or null if cancelled / no samples captured.
     * @throws org.meshtastic.core.model.media.TxDutyCycleExceededException if ToA exceeds 20.0s.
     */
    fun stopRecording(modemPreset: ModemPreset? = null): ByteArray?

    /** Cancels the active recording session and discards captured audio. */
    fun cancelRecording()

    /** Plays raw 16-bit PCM audio samples at 8kHz on the hardware speaker. */
    fun play(pcmSamples: ShortArray, onComplete: () -> Unit = {})

    /** Decompresses Codec 2 byte stream and plays it on the speaker. */
    fun playCodec2(codec2Bytes: ByteArray, onComplete: () -> Unit = {})

    /** Stops any currently active playback. */
    fun stopPlayback()

    companion object {
        const val SAMPLE_RATE_24K = 24000
        const val SAMPLE_RATE_8K = 8000

        @kotlin.concurrent.Volatile var activeSampleRate: Int = SAMPLE_RATE_24K
    }
}
