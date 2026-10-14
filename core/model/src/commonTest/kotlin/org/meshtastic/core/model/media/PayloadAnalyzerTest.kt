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
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PayloadAnalyzerTest {

    @Test
    fun testShortFastToaUnderLimit() {
        // SF7, 250kHz, 200 bytes
        val toa = PayloadAnalyzer.calculateSinglePacketToaSeconds(payloadBytes = 200, sf = 7, bandwidthHz = 250_000)
        // SF7 on 250kHz with 200 bytes should be ~0.15 - 0.25 seconds
        assertTrue(toa in 0.10..0.35, "Expected ToA between 0.10s and 0.35s, got $toa")
    }

    @Test
    fun test20SecondGatePermitsAcceptablePayload() {
        // 500 bytes on Short Fast (SF7, 250kHz)
        val toa = PayloadAnalyzer.validateTransmissionTime(totalBytes = 500, preset = ModemPreset.SHORT_FAST)
        assertTrue(toa < 20.0, "Short fast 500 bytes should easily be under 20s, got $toa")
    }

    @Test
    fun test20SecondGateThrowsOnExcessivePayload() {
        // Very Long Slow (SF12, 62.5kHz) with 2625 bytes will exceed 20s
        assertFailsWith<TxDutyCycleExceededException> {
            PayloadAnalyzer.validateTransmissionTime(
                totalBytes = 2625,
                preset = ModemPreset.VERY_LONG_SLOW,
                maxAllowedSeconds = 20.0,
            )
        }
    }

    @Test
    fun testCalculateInterChunkDelayMsWithPresets() {
        // LONG_FAST (SF11, 250kHz) for 200 bytes should be ~1.8s (1800ms+)
        val longFastDelay = PayloadAnalyzer.calculateInterChunkDelayMs(200, ModemPreset.LONG_FAST)
        assertTrue(longFastDelay in 1500L..2500L, "LONG_FAST delay should be ~1.8s, was $longFastDelay")

        // SHORT_FAST (SF7, 250kHz) for 200 bytes should be ~213ms
        val shortFastDelay = PayloadAnalyzer.calculateInterChunkDelayMs(200, ModemPreset.SHORT_FAST)
        assertTrue(shortFastDelay in 180L..350L, "SHORT_FAST delay should be ~213ms, was $shortFastDelay")
    }

    @Test
    fun testCalculateInterChunkDelayMsFallbackWhenPresetNull() {
        val largeFallback = PayloadAnalyzer.calculateInterChunkDelayMs(200, preset = null)
        assertTrue(largeFallback == 1800L, "Fallback for >=100 bytes should be 1800ms, was $largeFallback")

        val exactFallback = PayloadAnalyzer.calculateInterChunkDelayMs(100, preset = null)
        assertTrue(exactFallback == 1800L, "Fallback for exactly 100 bytes should be 1800ms, was $exactFallback")

        val smallFallback = PayloadAnalyzer.calculateInterChunkDelayMs(50, preset = null)
        assertTrue(smallFallback == 800L, "Fallback for <100 bytes should be 800ms, was $smallFallback")
    }
}
