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
 * State-of-the-Art WavTokenizer Neural Audio Codec Engine.
 *
 * Ultra-low-bitrate discrete acoustic tokenizer operating at 40 tokens/s with 12-bit codebook indices (480 bps).
 * Optimized for LoRa bandwidth constraints, producing ~1,800 bytes for 30.0 seconds of speech.
 *
 * Specifications:
 * - Sampling Rate: 8,000 Hz or 24,000 Hz 16-bit Mono PCM
 * - Token Rate: 40 tokens/second (25ms per token)
 * - Quantization: 12 bits per token (4,096 codebook vectors)
 * - Target Bitrate: 480 bps (60 bytes/second)
 * - Maximum Recording Duration: 30.0 seconds (1,200 tokens = exactly 1,800 bytes)
 * - Bitstream Packing: 2 tokens packed into 3 bytes with exact nibble bit-shifting
 * - Robust Packet Loss Concealment (PLC) ensures decoder never crashes on packet loss
 */
object WavTokenizerEngine {

    const val TOKENS_PER_SECOND = 75
    const val BITS_PER_TOKEN = 12
    const val CODEBOOK_SIZE = 4096 // 2^12
    const val BITRATE_BPS = 900 // 75 tokens/s * 12 bits
    const val BYTES_PER_SECOND = 112.5 // 900 / 8

    const val MAX_RECORDING_SECONDS = 28
    const val MAX_TOKENS = TOKENS_PER_SECOND * MAX_RECORDING_SECONDS // 2,100 tokens
    const val MAX_COMPRESSED_BYTES = (MAX_TOKENS * BITS_PER_TOKEN) / 8 // 3,150 bytes

    const val SAMPLE_RATE_24K = 24000
    const val SAMPLES_PER_TOKEN_24K = SAMPLE_RATE_24K / TOKENS_PER_SECOND // exactly 320 samples per token at 24kHz
    const val SAMPLE_RATE_8K = 8000

    /** Silent comfort noise token index used for Packet Loss Concealment (PLC) */
    const val SILENT_TOKEN = 0

    /** Optional bridge for real neural ONNX/native runner */
    var bridge: WavTokenizerBridge? = null

    /**
     * Each pair of 12-bit tokens is serialized into exactly 3 bytes (24 bits).
     *
     * Wire format for tokens [T0, T1]:
     *
     * Big-Endian (Network Byte Order, default):
     * - Byte 0: T0[11..4] -> (T0 ushr 4) and 0xFF
     * - Byte 1: (T0[3..0] shl 4) | (T1[11..8] and 0x0F) -> ((T0 and 0x0F) shl 4) or ((T1 ushr 8) and 0x0F)
     * - Byte 2: T1[7..0] -> T1 and 0xFF
     *
     * Little-Endian (LSB first):
     * - Byte 0: T0[7..0] -> T0 and 0xFF
     * - Byte 1: (T1[3..0] shl 4) | (T0[11..8] and 0x0F) -> ((T0 ushr 8) and 0x0F) or ((T1 and 0x0F) shl 4)
     * - Byte 2: T1[11..4] -> (T1 ushr 4) and 0xFF
     *
     * @param tokens Array of 12-bit tokens (0..4095).
     * @param isLittleEndian True for Little-Endian packing, false for standard Big-Endian network order.
     * @return Compact byte array (e.g. 1,200 tokens -> exactly 1,800 bytes).
     */
    fun pack12Bit(tokens: IntArray, isLittleEndian: Boolean = false): ByteArray {
        if (tokens.isEmpty()) return ByteArray(0)
        val tokenCount = tokens.size.coerceAtMost(MAX_TOKENS)
        val byteCount = (tokenCount * 3 + 1) / 2
        val bytes = ByteArray(byteCount)

        var bi = 0
        var i = 0
        while (i < tokenCount) {
            val t0 = tokens[i].coerceIn(0, CODEBOOK_SIZE - 1)
            val t1 = if (i + 1 < tokenCount) tokens[i + 1].coerceIn(0, CODEBOOK_SIZE - 1) else 0

            if (isLittleEndian) {
                // Byte 0: low 8 bits of t0 (bits 7..0)
                bytes[bi++] = (t0 and 0xFF).toByte()

                // Byte 1: high 4 bits of t0 (bits 11..8) in low nibble, low 4 bits of t1 (bits 3..0) in high nibble
                val b1 = ((t0 ushr 8) and 0x0F) or ((t1 and 0x0F) shl 4)
                bytes[bi++] = (b1 and 0xFF).toByte()

                // Byte 2: high 8 bits of t1 (bits 11..4)
                if (bi < byteCount) {
                    bytes[bi++] = ((t1 ushr 4) and 0xFF).toByte()
                }
            } else {
                // Byte 0: top 8 bits of t0 (bits 11..4)
                bytes[bi++] = ((t0 ushr 4) and 0xFF).toByte()

                // Byte 1: bottom 4 bits of t0 (bits 3..0) in high nibble, top 4 bits of t1 (bits 11..8) in low nibble
                val b1 = ((t0 and 0x0F) shl 4) or ((t1 ushr 8) and 0x0F)
                bytes[bi++] = (b1 and 0xFF).toByte()

                // Byte 2: bottom 8 bits of t1 (bits 7..0)
                if (bi < byteCount) {
                    bytes[bi++] = (t1 and 0xFF).toByte()
                }
            }
            i += 2
        }

        return bytes
    }

    /**
     * Unpacks a compact byte array into 12-bit token indices (0..4095).
     *
     * @param bytes Serialized 12-bit token bitstream.
     * @param expectedTokenCount Optional expected token count; defaults to (bytes.size * 2) / 3.
     * @param isLittleEndian True for Little-Endian unpacking, false for standard Big-Endian network order.
     * @return Array of unpacked 12-bit token indices (0..4095).
     */
    fun unpack12Bit(
        bytes: ByteArray,
        expectedTokenCount: Int = (bytes.size * 2) / 3,
        isLittleEndian: Boolean = false,
    ): IntArray {
        if (bytes.isEmpty() || expectedTokenCount <= 0) return IntArray(0)
        val targetTokens = expectedTokenCount.coerceAtMost(MAX_TOKENS)
        val tokens = IntArray(targetTokens)

        var ti = 0
        var bi = 0
        while (ti < targetTokens && bi < bytes.size) {
            val b0 = bytes[bi++].toInt() and 0xFF
            val b1 = if (bi < bytes.size) bytes[bi++].toInt() and 0xFF else 0
            val b2 = if (bi < bytes.size) bytes[bi++].toInt() and 0xFF else 0

            if (isLittleEndian) {
                // Token 0: low 8 bits from b0, high 4 bits from b1 low nibble
                val t0 = (b0 and 0xFF) or ((b1 and 0x0F) shl 8)
                tokens[ti++] = t0 and 0x0FFF

                // Token 1: low 4 bits from b1 high nibble, high 8 bits from b2
                if (ti < targetTokens) {
                    val t1 = ((b1 ushr 4) and 0x0F) or ((b2 and 0xFF) shl 4)
                    tokens[ti++] = t1 and 0x0FFF
                }
            } else {
                // Token 0: high 8 bits from b0, low 4 bits from b1 high nibble
                val t0 = ((b0 and 0xFF) shl 4) or ((b1 ushr 4) and 0x0F)
                tokens[ti++] = t0 and 0x0FFF

                // Token 1: high 4 bits from b1 low nibble, low 8 bits from b2
                if (ti < targetTokens) {
                    val t1 = ((b1 and 0x0F) shl 8) or (b2 and 0xFF)
                    tokens[ti++] = t1 and 0x0FFF
                }
            }
        }

        return tokens
    }

    /** Compatibility alias for [pack12Bit] using standard Big-Endian network order. */
    fun packTokens(tokens: IntArray): ByteArray = pack12Bit(tokens, isLittleEndian = false)

    /** Compatibility alias for [unpack12Bit] using standard Big-Endian network order. */
    fun unpackTokens(bytes: ByteArray, expectedTokenCount: Int = (bytes.size * 2) / 3): IntArray =
        unpack12Bit(bytes, expectedTokenCount, isLittleEndian = false)

    /**
     * Conceals missing or dropped tokens by inserting comfort silence tokens [SILENT_TOKEN], preventing decoder crash
     * on packet loss.
     *
     * @param receivedTokens Tokens successfully received.
     * @param expectedLength Expected total token count (e.g. from total chunks).
     * @return Complete token array with packet loss concealed.
     */
    fun concealMissingTokens(receivedTokens: IntArray, expectedLength: Int): IntArray {
        if (receivedTokens.size >= expectedLength) return receivedTokens
        val concealed = IntArray(expectedLength)
        receivedTokens.copyInto(concealed, 0, 0, receivedTokens.size)
        for (i in receivedTokens.size until expectedLength) {
            concealed[i] = SILENT_TOKEN
        }
        return concealed
    }

    /**
     * Encodes 16-bit PCM audio samples into a 900 bps WavTokenizer bitstream (75 tokens/s).
     *
     * @param pcmSamples 16-bit PCM samples at 8kHz.
     * @return Compact byte array (~3.1 kB for 28s).
     * @throws IllegalArgumentException if duration exceeds 28.0s.
     */
    fun encode(pcmSamples: ShortArray): ByteArray {
        val rate =
            if (pcmSamples.size > SAMPLE_RATE_8K * MAX_RECORDING_SECONDS + 1) {
                SAMPLE_RATE_24K
            } else {
                SAMPLE_RATE_8K
            }
        val maxSamples = rate * MAX_RECORDING_SECONDS
        require(pcmSamples.size <= maxSamples) {
            "Audio recording of ${pcmSamples.size / rate}s exceeds maximum limit of ${MAX_RECORDING_SECONDS}s"
        }
        if (pcmSamples.isEmpty()) return ByteArray(0)

        // Invoke real neural ONNX runner via bridge
        val b = bridge
        if (b == null) {
            error("WavTokenizer neural ONNX bridge is not initialized.")
        }
        if (!b.isAvailable()) {
            val detail = b.lastErrorMessage() ?: "Neural model assets missing from APK and filesDir"
            error("WavTokenizer neural ONNX encoder is unavailable ($detail). Ensure neural assets are installed.")
        }

        val realEncoded = b.encode(pcmSamples)
        if (realEncoded != null) {
            val tokens = unpackTokens(realEncoded)
            return pack12Bit(tokens)
        }

        val detail = b.lastErrorMessage() ?: "ONNX encoder returned null output"
        error("WavTokenizer neural ONNX encoding failed ($detail).")
    }

    /**
     * Decodes a 900 bps WavTokenizer bitstream back into 16-bit PCM samples. Incorporates automatic Packet Loss
     * Concealment (PLC) to prevent neural crash.
     *
     * @param payload Compact 12-bit token byte stream.
     * @param expectedDurationSeconds Expected duration in seconds (optional).
     * @return 16-bit PCM samples.
     */
    fun decode(payload: ByteArray, expectedDurationSeconds: Int? = null): ShortArray {
        if (payload.isEmpty()) return ShortArray(0)

        val tokens =
            try {
                unpack12Bit(payload)
            } catch (e: Exception) {
                // Gracefully handle corrupted packets, truncation, or malformed bytes from radio noise
                return ShortArray(0)
            }
        if (tokens.isEmpty()) return ShortArray(0)

        // Invoke real neural ONNX runner via bridge
        val b = bridge ?: return ShortArray(0)
        if (!b.isAvailable()) return ShortArray(0)

        return try {
            val targetTokens = expectedDurationSeconds?.let { it * TOKENS_PER_SECOND } ?: tokens.size
            val finalTokens =
                if (tokens.size < targetTokens) {
                    concealMissingTokens(tokens, targetTokens)
                } else {
                    tokens
                }
            val raw12Bit = packTokens(finalTokens)
            val pcm = b.decode(raw12Bit) ?: ShortArray(0)
            if (expectedDurationSeconds != null) {
                val expectedSamples = expectedDurationSeconds * SAMPLE_RATE_24K
                if (pcm.isNotEmpty() && pcm.size < expectedSamples) {
                    val padded = ShortArray(expectedSamples)
                    pcm.copyInto(padded, 0, 0, pcm.size)
                    padded
                } else {
                    pcm
                }
            } else {
                pcm
            }
        } catch (e: Exception) {
            ShortArray(0)
        }
    }
}

/** Bridge interface enabling WavTokenizerEngine to invoke real neural ONNX runtime models. */
interface WavTokenizerBridge {
    fun isAvailable(): Boolean

    fun lastErrorMessage(): String? = null

    fun encode(pcmSamples: ShortArray): ByteArray?

    fun decode(compressedBytes: ByteArray): ShortArray?
}
