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

import android.util.Log
import org.meshtastic.core.model.media.Codec2Engine
import org.meshtastic.core.model.media.Codec2NativeBridge

/**
 * JNI wrapper for the official David Rowe libcodec2 library compiled natively for Android.
 *
 * Supports speech modes:
 * - MODE_3200 = 0 (3200 bps, 160 samples per frame, 8 bytes per frame)
 * - MODE_2400 = 1 (2400 bps, 160 samples per frame, 6 bytes per frame)
 * - MODE_1600 = 2 (1600 bps, 320 samples per frame, 8 bytes per frame)
 * - MODE_1400 = 3 (1400 bps, 320 samples per frame, 7 bytes per frame)
 * - MODE_1300 = 4 (1300 bps, 320 samples per frame, 6.5 bytes per frame)
 * - MODE_1200 = 5 (1200 bps, 320 samples per frame, 6 bytes per frame)
 * - MODE_700 = 6 (700 bps, 320 samples per frame, 28 bits per frame)
 * - MODE_700B = 7 (700 bps, 320 samples per frame, 28 bits per frame)
 * - MODE_700C = 8 (700 bps, 320 samples per frame, 28 bits per frame)
 *
 * For Meshtastic voice communications, [DEFAULT_MODE] is [MODE_700C]. Frames are continuously bit-packed with zero
 * padding bits (2 frames = 56 bits = exactly 7 bytes).
 */
object Codec2Jni {

    private const val TAG = "Codec2Jni"

    const val MODE_3200 = 0
    const val MODE_2400 = 1
    const val MODE_1600 = 2
    const val MODE_1400 = 3
    const val MODE_1300 = 4
    const val MODE_1200 = 5
    const val MODE_700 = 6
    const val MODE_700B = 7
    const val MODE_700C = 8

    const val MAGIC_BYTE: Byte = 0xC2.toByte()

    /** Standard speech mode for LoRa voice transmissions: MODE_1400 provides crisp phonemes & intelligibility */
    const val DEFAULT_MODE = MODE_1400

    var activeMode: Int = DEFAULT_MODE

    private val isNativeLoaded: Boolean

    init {
        isNativeLoaded =
            try {
                System.loadLibrary("codec2")
                Log.i(TAG, "Successfully loaded libcodec2 native library.")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load libcodec2 native library: ${t.message}", t)
                false
            }

        // Hook native C libcodec2 into Codec2Engine so all codebase invocations use native C
        if (isNativeLoaded) {
            Codec2Engine.nativeBridge =
                object : Codec2NativeBridge {
                    override fun isAvailable(): Boolean = isNativeLoaded

                    override fun encode(pcmSamples: ShortArray): ByteArray? =
                        Codec2Jni.encodeWithHeader(pcmSamples, activeMode)

                    override fun encode(pcmSamples: ShortArray, mode: Int): ByteArray? =
                        Codec2Jni.encodeWithHeader(pcmSamples, mode)

                    override fun decode(compressedBytes: ByteArray): ShortArray? =
                        Codec2Jni.decodeWithHeader(compressedBytes)
                }
            Log.i(
                TAG,
                "Hooked native Codec2Jni bridge into Codec2Engine (default MODE_1400 with MODE_700C auto-detection).",
            )
        }
    }

    fun isAvailable(): Boolean = isNativeLoaded

    /** Explicit initializer to guarantee native library load and bridge registration. */
    fun initialize() {
        Log.d(TAG, "Codec2Jni initialized. Native available: $isNativeLoaded")
    }

    /** Encodes 8 kHz 16-bit PCM ShortArray prepending the self-describing 2-byte header [0xC2, mode]. */
    fun encodeWithHeader(pcmSamples: ShortArray, mode: Int = activeMode): ByteArray? {
        val raw = encode(pcmSamples, mode) ?: return null
        val header = byteArrayOf(MAGIC_BYTE, mode.toByte())
        val out = ByteArray(header.size + raw.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(raw, 0, out, header.size, raw.size)
        return out
    }

    /** Decodes bitstream, automatically detecting the 2-byte header [0xC2, mode] if present. */
    fun decodeWithHeader(bits: ByteArray): ShortArray? {
        if (!isNativeLoaded || bits.isEmpty()) return null
        if (bits.size >= 3 && bits[0] == MAGIC_BYTE) {
            val mode = bits[1].toInt() and 0xFF
            val raw = bits.copyOfRange(2, bits.size)
            return decode(raw, mode)
        }
        return decode(bits, activeMode)
    }

    /**
     * High-level helper to encode 8 kHz 16-bit PCM ShortArray into continuous bit-packed Codec 2 byte stream.
     * Automatically handles framing, padding, continuous 28-bit packing (no padding bits), and state lifecycle.
     */
    fun encode(pcmSamples: ShortArray, mode: Int = DEFAULT_MODE): ByteArray? {
        if (!isNativeLoaded || pcmSamples.isEmpty()) return null

        val handle = nativeCreate(mode)
        if (handle == 0L) return null
        try {
            val samplesPerFrame = nativeGetSamplesPerFrame(handle)
            if (samplesPerFrame <= 0) return null

            // Pad samples to multiple of samplesPerFrame if necessary
            val remainder = pcmSamples.size % samplesPerFrame
            val paddedSamples =
                if (remainder != 0) {
                    val needed = samplesPerFrame - remainder
                    val padded = ShortArray(pcmSamples.size + needed)
                    pcmSamples.copyInto(padded, 0, 0, pcmSamples.size)
                    padded
                } else {
                    pcmSamples
                }

            return nativeEncodeAll(handle, paddedSamples)
        } finally {
            nativeDestroy(handle)
        }
    }

    /**
     * High-level helper to decode continuous bit-packed Codec 2 bitstream into 8 kHz 16-bit PCM ShortArray.
     * Automatically handles continuous 28-bit unpacking, framing, and state lifecycle.
     */
    fun decode(bits: ByteArray, mode: Int = DEFAULT_MODE): ShortArray? {
        if (!isNativeLoaded || bits.isEmpty()) return null
        val handle = nativeCreate(mode)
        if (handle == 0L) return null
        try {
            return nativeDecodeAll(handle, bits)
        } finally {
            nativeDestroy(handle)
        }
    }

    @JvmStatic external fun nativeCreate(mode: Int): Long

    @JvmStatic external fun nativeDestroy(handle: Long)

    @JvmStatic external fun nativeGetSamplesPerFrame(handle: Long): Int

    @JvmStatic external fun nativeGetBitsPerFrame(handle: Long): Int

    @JvmStatic external fun nativeGetBytesPerFrame(handle: Long): Int

    @JvmStatic
    external fun nativeEncodeFrame(
        handle: Long,
        speechIn: ShortArray,
        inOffset: Int,
        bitsOut: ByteArray,
        outOffset: Int,
    ): Int

    @JvmStatic
    external fun nativeDecodeFrame(
        handle: Long,
        bitsIn: ByteArray,
        inOffset: Int,
        speechOut: ShortArray,
        outOffset: Int,
    ): Int

    @JvmStatic external fun nativeEncodeAll(handle: Long, speechIn: ShortArray): ByteArray?

    @JvmStatic external fun nativeDecodeAll(handle: Long, bitsIn: ByteArray): ShortArray?
}
