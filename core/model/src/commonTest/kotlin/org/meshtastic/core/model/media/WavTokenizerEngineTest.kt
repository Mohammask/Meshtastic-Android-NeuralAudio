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

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WavTokenizerEngineTest {

    @kotlin.test.BeforeTest
    fun setUp() {
        WavTokenizerEngine.bridge =
            object : WavTokenizerBridge {
                override fun isAvailable(): Boolean = true

                override fun encode(pcmSamples: ShortArray): ByteArray {
                    val tokenCount =
                        (pcmSamples.size / WavTokenizerEngine.SAMPLE_RATE_8K) * WavTokenizerEngine.TOKENS_PER_SECOND
                    val tokens = IntArray(tokenCount.coerceAtLeast(WavTokenizerEngine.MAX_TOKENS)) { it % 4096 }
                    return WavTokenizerEngine.packTokens(tokens)
                }

                override fun decode(compressedBytes: ByteArray): ShortArray {
                    val tokens = WavTokenizerEngine.unpackTokens(compressedBytes)
                    return ShortArray(28 * WavTokenizerEngine.SAMPLE_RATE_24K)
                }
            }
    }

    @Test
    fun testTwo12BitTokensPackToThreeBytesLossless() {
        val originalTokens = intArrayOf(0x0ABC, 0x0DEF)
        // Big-Endian
        val packedBE = WavTokenizerEngine.pack12Bit(originalTokens, isLittleEndian = false)
        assertEquals(3, packedBE.size, "Two 12-bit tokens must serialize into exactly 3 bytes")
        // Byte 0: 0xAB, Byte 1: 0xCD, Byte 2: 0xEF
        assertEquals(0xAB.toByte(), packedBE[0])
        assertEquals(0xCD.toByte(), packedBE[1])
        assertEquals(0xEF.toByte(), packedBE[2])

        val unpackedBE = WavTokenizerEngine.unpack12Bit(packedBE, expectedTokenCount = 2, isLittleEndian = false)
        assertEquals(2, unpackedBE.size)
        assertEquals(0x0ABC, unpackedBE[0])
        assertEquals(0x0DEF, unpackedBE[1])

        // Little-Endian
        val packedLE = WavTokenizerEngine.pack12Bit(originalTokens, isLittleEndian = true)
        assertEquals(3, packedLE.size)
        // Byte 0: 0xBC (t0[7..0]), Byte 1: 0xFA (t1[3..0]<<4 | t0[11..8]), Byte 2: 0xDE (t1[11..4])
        assertEquals(0xBC.toByte(), packedLE[0])
        assertEquals(0xFA.toByte(), packedLE[1])
        assertEquals(0xDE.toByte(), packedLE[2])

        val unpackedLE = WavTokenizerEngine.unpack12Bit(packedLE, expectedTokenCount = 2, isLittleEndian = true)
        assertEquals(2, unpackedLE.size)
        assertEquals(0x0ABC, unpackedLE[0])
        assertEquals(0x0DEF, unpackedLE[1])
    }

    @Test
    fun testOddNumberOfTokensLossless() {
        val originalTokens = intArrayOf(0x0123, 0x0456, 0x0789)
        // Big-Endian
        val packedBE = WavTokenizerEngine.pack12Bit(originalTokens, isLittleEndian = false)
        assertEquals(5, packedBE.size)
        val unpackedBE = WavTokenizerEngine.unpack12Bit(packedBE, expectedTokenCount = 3, isLittleEndian = false)
        assertEquals(3, unpackedBE.size)
        assertEquals(0x0123, unpackedBE[0])
        assertEquals(0x0456, unpackedBE[1])
        assertEquals(0x0789, unpackedBE[2])

        // Little-Endian
        val packedLE = WavTokenizerEngine.pack12Bit(originalTokens, isLittleEndian = true)
        assertEquals(5, packedLE.size)
        val unpackedLE = WavTokenizerEngine.unpack12Bit(packedLE, expectedTokenCount = 3, isLittleEndian = true)
        assertEquals(3, unpackedLE.size)
        assertEquals(0x0123, unpackedLE[0])
        assertEquals(0x0456, unpackedLE[1])
        assertEquals(0x0789, unpackedLE[2])
    }

    @Test
    fun testTwentyEightSecondsProducesExactly3150Bytes() {
        // 28 seconds at 75 tokens/s = 2100 tokens
        val tokens = IntArray(2100) { i -> i % 4096 }
        val packed = WavTokenizerEngine.packTokens(tokens)
        assertEquals(3150, packed.size, "28 seconds at 900bps (75 tokens/s * 12 bits) must be exactly 3150 bytes")

        val unpacked = WavTokenizerEngine.unpackTokens(packed, expectedTokenCount = 2100)
        assertEquals(2100, unpacked.size)
        for (i in 0 until 2100) {
            assertEquals(i % 4096, unpacked[i], "Token at index $i must match original")
        }
    }

    @Test
    fun testEncoding28SecondsSamplesProduces3150Bytes() {
        val samples =
            ShortArray(WavTokenizerEngine.SAMPLE_RATE_8K * WavTokenizerEngine.MAX_RECORDING_SECONDS) { i ->
                (sin(2.0 * PI * 440.0 * i / 8000.0) * 10000.0).toInt().toShort()
            }
        val encoded = WavTokenizerEngine.encode(samples)
        assertEquals(
            3150,
            encoded.size,
            "Encoded 28s audio must produce exactly 3150 raw 12-bit bytes (was ${encoded.size}B)",
        )
    }

    @Test
    fun testEndToEnd12BitPackingUnpackingRoundtrip() {
        val testTokens =
            intArrayOf(
                0,
                4095,
                1,
                2048,
                4094,
                127,
                128,
                255,
                256,
                511,
                512,
                1023,
                1024,
                42,
                1337,
                3000,
                0,
                0,
                4095,
                4095,
            )
        // Big-Endian
        val packedBE = WavTokenizerEngine.pack12Bit(testTokens, isLittleEndian = false)
        val unpackedBE =
            WavTokenizerEngine.unpack12Bit(packedBE, expectedTokenCount = testTokens.size, isLittleEndian = false)
        assertTrue(unpackedBE.contentEquals(testTokens), "Big-Endian roundtrip must match original tokens exactly")

        // Little-Endian
        val packedLE = WavTokenizerEngine.pack12Bit(testTokens, isLittleEndian = true)
        val unpackedLE =
            WavTokenizerEngine.unpack12Bit(packedLE, expectedTokenCount = testTokens.size, isLittleEndian = true)
        assertTrue(unpackedLE.contentEquals(testTokens), "Little-Endian roundtrip must match original tokens exactly")
    }

    @Test
    fun testRoundtripArbitraryTokenLengths() {
        val random = kotlin.random.Random(12345)
        for (length in listOf(0, 1, 2, 3, 4, 5, 10, 75, 101, 500, 2100)) {
            val original = IntArray(length) { random.nextInt(0, WavTokenizerEngine.CODEBOOK_SIZE) }
            val packed = WavTokenizerEngine.packTokens(original)
            val unpacked = WavTokenizerEngine.unpackTokens(packed, expectedTokenCount = length)
            assertTrue(unpacked.contentEquals(original), "Roundtrip for length $length must be bit-exact")
        }
    }

    @Test
    fun testCorruptedPacketGracefulRecovery() {
        // Empty payload returns empty ShortArray
        assertTrue(WavTokenizerEngine.decode(ByteArray(0)).isEmpty())

        // Corrupted payload that causes bridge to fail/throw
        val failingBridge =
            object : WavTokenizerBridge {
                override fun isAvailable(): Boolean = true

                override fun encode(pcmSamples: ShortArray): ByteArray? = null

                override fun decode(compressedBytes: ByteArray): ShortArray =
                    throw IllegalStateException("ONNX inference failed on malformed packet")
            }
        WavTokenizerEngine.bridge = failingBridge
        val decoded = WavTokenizerEngine.decode(byteArrayOf(0x01, 0x12, 0x34))
        assertTrue(
            decoded.isEmpty(),
            "Corrupt payload causing bridge failure must return empty ShortArray without crashing",
        )

        // Bridge returns null
        val nullBridge =
            object : WavTokenizerBridge {
                override fun isAvailable(): Boolean = true

                override fun encode(pcmSamples: ShortArray): ByteArray? = null

                override fun decode(compressedBytes: ByteArray): ShortArray? = null
            }
        WavTokenizerEngine.bridge = nullBridge
        assertTrue(WavTokenizerEngine.decode(byteArrayOf(0x01, 0x12, 0x34)).isEmpty())

        // Bridge unavailable
        WavTokenizerEngine.bridge = null
        assertTrue(WavTokenizerEngine.decode(byteArrayOf(0x01, 0x12, 0x34)).isEmpty())
    }

    @Test
    fun testTwentyEightSecondsHardCapEnforced() {
        val excessive = ShortArray(WavTokenizerEngine.SAMPLE_RATE_8K * WavTokenizerEngine.MAX_RECORDING_SECONDS + 1)
        assertFailsWith<IllegalArgumentException> { WavTokenizerEngine.encode(excessive) }
    }

    @Test
    fun testPacketLossConcealmentPreventsCrash() {
        // Partial transmission: only 1000 tokens received instead of 2100
        val partialTokens = IntArray(1000) { 100 }
        val concealed = WavTokenizerEngine.concealMissingTokens(partialTokens, expectedLength = 2100)
        assertEquals(2100, concealed.size)
        assertEquals(100, concealed[999])
        assertEquals(WavTokenizerEngine.SILENT_TOKEN, concealed[1000])
        assertEquals(WavTokenizerEngine.SILENT_TOKEN, concealed[2099])

        // Decode must execute gracefully without crash
        val packedPartial = WavTokenizerEngine.packTokens(partialTokens)
        val decoded = WavTokenizerEngine.decode(packedPartial, expectedDurationSeconds = 28)
        assertTrue(decoded.isNotEmpty(), "Decoded PCM with PLC must produce valid samples")
        assertEquals(28 * WavTokenizerEngine.SAMPLE_RATE_24K, decoded.size)
    }

    @Test
    fun testChunkingEngineSplits3150BytesIntoSixteenChunks() {
        val payload = ByteArray(3150) { it.toByte() }
        val chunks =
            ChunkingEngine.split(
                payload = payload,
                payloadType = ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER,
                maxChunkDataSize = 205,
            )
        // 3150 / 205 = 15.36 -> 16 chunks
        assertEquals(16, chunks.size, "3150 bytes at 205B/chunk must produce exactly 16 chunks")

        for (i in 0 until 16) {
            val parsed = ChunkingEngine.parse(chunks[i])
            assertTrue(parsed != null)
            assertEquals(16, parsed.header.totalChunks)
            assertEquals(i, parsed.header.chunkIndex)
            assertEquals(ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER, parsed.header.payloadType)
        }
    }
}
