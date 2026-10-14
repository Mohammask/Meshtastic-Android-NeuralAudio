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

import org.meshtastic.proto.Config.LoRaConfig.ModemPreset

/**
 * Multiplatform Codec 2 (700 bps) Engine bridging to native David Rowe libcodec2 C library for authentic, high-quality
 * voice communications over LoRa.
 *
 * Acoustic & Bitstream Specifications:
 * - Sampling Rate: 8000 Hz, 16-bit Mono PCM
 * - Frame Duration: 40 ms (320 samples per frame)
 * - Target Bitrate: 700 bps (28 bits per 40ms frame = 3.5 bytes/frame)
 * - Two 40ms frames are bit-packed continuously into a 56-bit block (7 bytes).
 * - Maximum Recording Duration: 30.0 seconds (750 frames = 2,625 bytes total).
 */
object Codec2Engine {

    const val SAMPLE_RATE = 8000
    const val SAMPLES_PER_FRAME = 320 // 40ms at 8kHz
    const val BITS_PER_FRAME = 28
    const val MAX_RECORDING_SECONDS = 30
    const val MAX_SAMPLES = SAMPLE_RATE * MAX_RECORDING_SECONDS // 240,000 samples
    const val MAX_COMPRESSED_BYTES = (MAX_RECORDING_SECONDS * 700) / 8 // 2,625 bytes

    const val MODE_1400 = 3
    const val MODE_700C = 8

    /** Current preferred mode for encoding (default MODE_1400 for high consonant articulation) */
    var preferredMode: Int = MODE_1400

    /** Native bridge (e.g. Codec2Jni) for official David Rowe C libcodec2 execution */
    var nativeBridge: Codec2NativeBridge? = null

    /** Returns true if the native libcodec2 C bridge is registered and available */
    fun isAvailable(): Boolean = nativeBridge?.isAvailable() == true

    /**
     * Encodes 16-bit PCM audio samples into an authentic Codec 2 bitstream using native libcodec2. Enforces the
     * 30-second recording cap, applies voice pre-processing (DC block, AGC, soft-knee limiter), and runs the LoRa
     * Time-on-Air gate validation.
     *
     * @param pcmSamples 16-bit PCM samples at 8000 Hz.
     * @param modemPreset Current modem preset for Time-on-Air validation.
     * @param mode Codec 2 mode (MODE_1400 for clear phonemes, MODE_700C for low bitrate).
     * @return Compressed byte array.
     * @throws IllegalArgumentException if audio exceeds 30 seconds.
     * @throws TxDutyCycleExceededException if airtime exceeds 20.0s.
     * @throws IllegalStateException if native libcodec2 is unavailable.
     */
    fun encode(pcmSamples: ShortArray, modemPreset: ModemPreset? = null, mode: Int = preferredMode): ByteArray {
        require(pcmSamples.size <= MAX_SAMPLES) {
            "Audio recording of ${pcmSamples.size / SAMPLE_RATE}s exceeds maximum limit of ${MAX_RECORDING_SECONDS}s"
        }
        if (pcmSamples.isEmpty()) return ByteArray(0)

        val bridge =
            nativeBridge?.takeIf { it.isAvailable() }
                ?: error("Codec2 native C library (libcodec2) is unavailable. Real speech encoding requires libcodec2.")

        // Ensure input is zero-padded to an exact multiple of SAMPLES_PER_FRAME (320 samples / 40ms)
        val remainder = pcmSamples.size % SAMPLES_PER_FRAME
        val paddedSamples =
            if (remainder != 0) {
                val needed = SAMPLES_PER_FRAME - remainder
                val padded = ShortArray(pcmSamples.size + needed)
                pcmSamples.copyInto(padded, 0, 0, pcmSamples.size)
                padded
            } else {
                pcmSamples
            }

        // Apply voice optimization: DC-blocking, smooth AGC boost, and soft-knee limiter for Codec 2
        val cleanSamples = AudioDspUtils.processVoiceForCodec2(paddedSamples)

        val compressedBytes = bridge.encode(cleanSamples, mode) ?: error("Codec2 native encoding failed.")

        // LoRa Time Gate check
        if (modemPreset != null) {
            PayloadAnalyzer.validateTransmissionTime(compressedBytes.size, modemPreset)
        }

        return compressedBytes
    }

    /**
     * Decodes a Codec 2 bitstream back into 16-bit PCM samples at 8000 Hz using native libcodec2.
     *
     * @param compressedBytes Continuous packed Codec 2 bitstream.
     * @return Decoded 8000 Hz 16-bit PCM samples with DC blocking applied.
     * @throws IllegalStateException if native libcodec2 is unavailable.
     */
    fun decode(compressedBytes: ByteArray): ShortArray {
        if (compressedBytes.isEmpty()) return ShortArray(0)

        val bridge =
            nativeBridge?.takeIf { it.isAvailable() }
                ?: error("Codec2 native C library (libcodec2) is unavailable. Real speech decoding requires libcodec2.")

        val outputPcm = bridge.decode(compressedBytes) ?: error("Codec2 native decoding failed.")

        return AudioDspUtils.applyDcBlock(outputPcm)
    }
}

/** Native bridge interface enabling Codec2Engine to invoke native C libcodec2. */
interface Codec2NativeBridge {
    fun isAvailable(): Boolean

    fun encode(pcmSamples: ShortArray): ByteArray?

    fun encode(pcmSamples: ShortArray, mode: Int): ByteArray? = encode(pcmSamples)

    fun decode(compressedBytes: ByteArray): ShortArray?
}
