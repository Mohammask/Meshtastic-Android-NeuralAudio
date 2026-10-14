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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Codec2EngineTest {

    @BeforeTest
    fun setUp() {
        Codec2Engine.nativeBridge =
            object : Codec2NativeBridge {
                override fun isAvailable(): Boolean = true

                override fun encode(pcmSamples: ShortArray): ByteArray? {
                    val numFrames = pcmSamples.size / Codec2Engine.SAMPLES_PER_FRAME
                    val numPairs = numFrames / 2
                    val hasOdd = numFrames % 2
                    val totalBytes = numPairs * 7 + if (hasOdd != 0) 4 else 0
                    return ByteArray(totalBytes)
                }

                override fun decode(compressedBytes: ByteArray): ShortArray? {
                    val numPairs = compressedBytes.size / 7
                    val remBytes = compressedBytes.size % 7
                    val hasOdd = if (remBytes >= 4) 1 else 0
                    val totalFrames = numPairs * 2 + hasOdd
                    return ShortArray(totalFrames * Codec2Engine.SAMPLES_PER_FRAME)
                }
            }
    }

    @AfterTest
    fun tearDown() {
        Codec2Engine.nativeBridge = null
    }

    @Test
    fun testTwoFramesEncodeToSevenBytes() {
        // 2 frames = 640 samples = 56 bits = 7 bytes
        val samples = ShortArray(640) { i -> (sin(2.0 * PI * 440.0 * i / 8000.0) * 10000.0).toInt().toShort() }
        val encoded = Codec2Engine.encode(samples)
        assertEquals(7, encoded.size, "2 frames of 28 bits each must pack into exactly 7 bytes")

        val decoded = Codec2Engine.decode(encoded)
        assertEquals(640, decoded.size, "Decoded PCM must equal 640 samples")
    }

    @Test
    fun testThirtySecondHardLimitEnforced() {
        // 30 seconds + 1 sample = 240,001 samples
        val excessiveSamples = ShortArray(Codec2Engine.MAX_SAMPLES + 1)
        assertFailsWith<IllegalArgumentException> { Codec2Engine.encode(excessiveSamples) }
    }

    @Test
    fun testThirtySecondsEncodesToMax2625Bytes() {
        // 750 frames = 240,000 samples = 30 seconds
        val samples =
            ShortArray(Codec2Engine.MAX_SAMPLES) { i ->
                (sin(2.0 * PI * 300.0 * i / 8000.0) * 8000.0).toInt().toShort()
            }
        val encoded = Codec2Engine.encode(samples)
        assertEquals(Codec2Engine.MAX_COMPRESSED_BYTES, encoded.size, "30s audio at 700bps must be 2625 bytes")
    }

    @Test
    fun testTimeGateIntegrationThrowsOnSlowPreset() {
        // 30 seconds of audio on Very Long Slow will exceed 20s ToA limit
        val samples = ShortArray(Codec2Engine.MAX_SAMPLES)
        assertFailsWith<TxDutyCycleExceededException> {
            Codec2Engine.encode(samples, modemPreset = ModemPreset.VERY_LONG_SLOW)
        }
    }

    @Test
    fun testFailsWhenNativeBridgeUnavailable() {
        Codec2Engine.nativeBridge = null
        assertFailsWith<IllegalStateException> { Codec2Engine.encode(ShortArray(640)) }
        assertFailsWith<IllegalStateException> { Codec2Engine.decode(ByteArray(7)) }
    }
}
