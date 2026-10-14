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

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioDspUtilsTest {

    @Test
    fun testDcBlockRemovesConstantOffset() {
        // Feed 1000 samples of a heavy DC offset (+15,000)
        val dcOffset = ShortArray(1000) { 15000 }
        val filtered = AudioDspUtils.applyDcBlock(dcOffset)

        assertEquals(1000, filtered.size)
        // After settling (500 samples), DC offset should decay to near 0 (< 200)
        assertTrue(abs(filtered[filtered.size - 1].toInt()) < 200, "DC blocker must attenuate DC offset to near zero")
    }

    @Test
    fun testAgcAndSoftClipPreventsLoudWrapping() {
        // Severe loud signal that would normally wrap or clip
        val loudSignal = ShortArray(800) { if (it % 2 == 0) 32767 else -32768 }
        val processed = AudioDspUtils.applyAgcAndSoftClip(loudSignal)

        assertEquals(800, processed.size)
        for (sample in processed) {
            assertTrue(sample in -1.0f..1.0f, "Sample $sample must be strictly within [-1.0, 1.0]")
            assertTrue(!sample.isNaN() && !sample.isInfinite(), "Sample must not be NaN or Infinite")
        }
    }

    @Test
    fun testResampling8kTo24kMultipliesLengthByThree() {
        val input8k = FloatArray(800) { (it % 100).toFloat() / 100.0f }
        val upsampled24k = AudioDspUtils.resample8kTo24k(input8k)
        assertEquals(2400, upsampled24k.size, "8k to 24k must produce exactly 3x samples")

        val downsampled8k = AudioDspUtils.resample24kTo8k(upsampled24k)
        assertEquals(800, downsampled8k.size, "24k to 8k must restore original sample count")
    }

    @Test
    fun testNoiseGateMutesLowEnergyRoomNoise() {
        // Quiet room noise: amplitude +/- 100 (RMS ~ 70 / 32768 ≈ 0.0021 < 0.015f threshold)
        val roomNoise = ShortArray(640) { (if (it % 2 == 0) 100 else -100).toShort() }
        val gated = AudioDspUtils.applyPreAmpAndNoiseGate(roomNoise, gainDb = 9.0f, gateThresholdRms = 0.015f)

        assertEquals(640, gated.size)
        // All samples should be zeroed out
        for (sample in gated) {
            assertEquals(0.toShort(), sample, "Noise gate must completely mute sub-threshold room noise")
        }
    }

    @Test
    fun testPreAmpBoostsVoiceAndSoftLimitsLoudPeaks() {
        // Voice signal with normal amplitude (+/- 3000)
        val voiceSignal = ShortArray(640) { (if (it % 2 == 0) 3000 else -3000).toShort() }
        val boosted = AudioDspUtils.applyPreAmpAndNoiseGate(voiceSignal, gainDb = 9.0f, gateThresholdRms = 0.015f)

        assertEquals(640, boosted.size)
        // Gain boost (+9dB) should amplify 3000 by ~2.82x to around ~8455
        val sampleVal = abs(boosted[100].toInt())
        assertTrue(sampleVal in 7500..9500, "Voice signal must be amplified by ~2.82x (+9dB), got $sampleVal")

        // Loud signal that would overflow without soft-limiting
        val loudSignal = ShortArray(640) { (if (it % 2 == 0) 25000 else -25000).toShort() }
        val limited = AudioDspUtils.applyPreAmpAndNoiseGate(loudSignal, gainDb = 9.0f, gateThresholdRms = 0.015f)
        for (sample in limited) {
            assertTrue(sample.toInt() in -32768..32767, "Sample must never exceed 16-bit limits")
        }
    }

    @Test
    fun testProcessVoiceForCodec2PreservesSpeechAndSoftLimits() {
        // Quiet consonant / speech signal (+/- 300) should be boosted into audible range without muting
        val quietSpeech = ShortArray(640) { (if (it % 2 == 0) 300 else -300).toShort() }
        val processed = AudioDspUtils.processVoiceForCodec2(quietSpeech)

        assertEquals(640, processed.size)
        val sampleVal = abs(processed[100].toInt())
        assertTrue(sampleVal > 300, "Quiet speech should be smoothly amplified, got $sampleVal")
        for (sample in processed) {
            assertTrue(sample.toInt() != 0, "Consonants and phonemes must not be zeroed out")
            assertTrue(sample.toInt() in -32768..32767, "Must be within valid 16-bit range")
        }
    }

    @Test
    fun testPrepareVoiceForWavTokenizerAmplifiesQuietMicAndLimits() {
        // Quiet microphone audio: amplitude +/- 1000 (norm ~0.03)
        val quietMic = ShortArray(640) { (if (it % 2 == 0) 1000 else -1000).toShort() }
        val normalized = AudioDspUtils.prepareVoiceForWavTokenizer(quietMic)

        assertEquals(640, normalized.size)
        // Peak should be elevated close to target 0.85
        var peak = 0f
        for (s in normalized) {
            val a = abs(s)
            if (a > peak) peak = a
            assertTrue(s in -1.0f..1.0f, "Sample must be in [-1.0, 1.0]")
        }
        assertTrue(
            peak in 0.45f..0.86f,
            "Quiet microphone audio must be amplified to WavTokenizer range, got peak $peak",
        )

        // Pure silence (< 1e-4) should remain untouched
        val silence = FloatArray(100) { 0.00001f }
        val processedSilence = AudioDspUtils.prepareVoiceForWavTokenizer(silence)
        assertEquals(0.00001f, processedSilence[0], 1e-6f)
    }

    @Test
    fun testTrimLeadingSilenceStripsDeadAirBeforeSpeechWithLeadInMargin() {
        // 8000 samples (1s at 8kHz) of quiet room noise (+/- 100, RMS ~0.003)
        // followed by 8000 samples of speech (+/- 3000, RMS ~0.09)
        val roomNoise = ShortArray(8000) { (if (it % 2 == 0) 100 else -100).toShort() }
        val speech = ShortArray(8000) { (if (it % 2 == 0) 3000 else -3000).toShort() }
        val fullClip = ShortArray(16000)
        roomNoise.copyInto(fullClip, 0)
        speech.copyInto(fullClip, 8000)

        val trimmed =
            AudioDspUtils.trimLeadingSilence(fullClip, sampleRate = 8000, thresholdRms = 0.020f, leadInMs = 80)

        // Lead-in of 80ms at 8kHz is 640 samples.
        // Speech onset detected at 8000.
        // Trim start is 8000 - 640 = 7360.
        // Expected trimmed length: 16000 - 7360 = 8640 samples.
        assertEquals(8640, trimmed.size, "Dead air before (onset - leadIn) must be stripped")
        // First 640 samples are lead-in ambient noise
        assertEquals(100.toShort(), trimmed[0])
        // Sample at index 640 corresponds to original index 8000 (speech onset)
        assertEquals(3000.toShort(), trimmed[640])
    }

    @Test
    fun testTrimLeadingSilencePreservesImmediateSpeech() {
        // Speech starting immediately at sample 0
        val speech = ShortArray(1600) { (if (it % 2 == 0) 3000 else -3000).toShort() }
        val trimmed = AudioDspUtils.trimLeadingSilence(speech, sampleRate = 8000, thresholdRms = 0.020f, leadInMs = 80)

        assertEquals(1600, trimmed.size, "Immediate speech starting at sample 0 must not be stripped")
    }

    @Test
    fun testTrimLeadingSilencePreservesAllQuietAudioWhenNoSpeechDetected() {
        // Only ambient room noise (+/- 100), never exceeding 0.020f RMS
        val roomNoise = ShortArray(1600) { (if (it % 2 == 0) 100 else -100).toShort() }
        val trimmed =
            AudioDspUtils.trimLeadingSilence(roomNoise, sampleRate = 8000, thresholdRms = 0.020f, leadInMs = 80)

        assertEquals(1600, trimmed.size, "Audio must be returned intact when no speech onset is detected")
    }

    @Test
    fun testTrimLeadingSilenceFloatOverload() {
        // FloatArray: 8000 samples of quiet noise (0.003f) followed by 8000 samples of speech (0.09f)
        val roomNoise = FloatArray(8000) { if (it % 2 == 0) 0.003f else -0.003f }
        val speech = FloatArray(8000) { if (it % 2 == 0) 0.09f else -0.09f }
        val fullClip = FloatArray(16000)
        roomNoise.copyInto(fullClip, 0)
        speech.copyInto(fullClip, 8000)

        val trimmed =
            AudioDspUtils.trimLeadingSilence(fullClip, sampleRate = 8000, thresholdRms = 0.020f, leadInMs = 80)
        assertEquals(8640, trimmed.size, "FloatArray overload must strip dead air preserving 80ms lead-in")
    }

    @Test
    fun testTrimLeadingSilenceAt24kHzPreserves1920LeadInSamples() {
        // 24000 samples of room noise followed by 24000 samples of speech
        val roomNoise = ShortArray(24000) { (if (it % 2 == 0) 100 else -100).toShort() }
        val speech = ShortArray(24000) { (if (it % 2 == 0) 3000 else -3000).toShort() }
        val fullClip = ShortArray(48000)
        roomNoise.copyInto(fullClip, 0)
        speech.copyInto(fullClip, 24000)

        val trimmed =
            AudioDspUtils.trimLeadingSilence(fullClip, sampleRate = 24000, thresholdRms = 0.020f, leadInMs = 80)

        // Lead-in of 80ms at 24kHz is 1920 samples.
        // Speech onset detected at 24000.
        // Trim start is 24000 - 1920 = 22080.
        // Expected trimmed length: 48000 - 22080 = 25920 samples.
        assertEquals(
            25920,
            trimmed.size,
            "Dead air before (onset - leadIn) must be stripped preserving 1920 samples at 24kHz",
        )
        assertEquals(100.toShort(), trimmed[0])
        assertEquals(3000.toShort(), trimmed[1920])
    }

    @Test
    fun testTrimLeadingSilenceFloatAt24kHz() {
        // FloatArray: 24000 samples of quiet noise (0.003f) followed by 24000 samples of speech (0.09f)
        val roomNoise = FloatArray(24000) { if (it % 2 == 0) 0.003f else -0.003f }
        val speech = FloatArray(24000) { if (it % 2 == 0) 0.09f else -0.09f }
        val fullClip = FloatArray(48000)
        roomNoise.copyInto(fullClip, 0)
        speech.copyInto(fullClip, 24000)

        val trimmed =
            AudioDspUtils.trimLeadingSilence(fullClip, sampleRate = 24000, thresholdRms = 0.020f, leadInMs = 80)
        assertEquals(
            25920,
            trimmed.size,
            "FloatArray overload must strip dead air preserving 1920 lead-in samples at 24kHz",
        )
        assertEquals(0.003f, trimmed[0])
        assertEquals(0.09f, trimmed[1920])
    }

    @Test
    fun testNormalizePeakScalesSpeechLinearlyToTarget() {
        // Peak is 0.45f; targetPeak is 0.90f -> scale factor is 2.0
        val speech = floatArrayOf(0.10f, -0.20f, 0.45f, -0.30f, 0.05f)
        val normalized = AudioDspUtils.normalizePeak(speech, targetPeak = 0.90f)

        assertEquals(speech.size, normalized.size)
        assertEquals(0.20f, normalized[0], 1e-6f)
        assertEquals(-0.40f, normalized[1], 1e-6f)
        assertEquals(0.90f, normalized[2], 1e-6f)
        assertEquals(-0.60f, normalized[3], 1e-6f)
        assertEquals(0.10f, normalized[4], 1e-6f)

        val maxPeak = normalized.maxOfOrNull { abs(it) } ?: 0f
        assertEquals(0.90f, maxPeak, 1e-6f)
    }

    @Test
    fun testNormalizePeakLeavesSilenceAndHissUntouched() {
        // Peak < 0.08f (ambient hiss / room breath ~0.015f - 0.075f)
        val silence = floatArrayOf(0.015f, -0.020f, 0.050f, -0.075f, 0.000f)
        val normalized = AudioDspUtils.normalizePeak(silence, targetPeak = 0.90f)

        assertEquals(silence.size, normalized.size)
        for (i in silence.indices) {
            assertEquals(
                silence[i],
                normalized[i],
                1e-6f,
                "Room hiss and breath (< 0.08f) must remain completely untouched",
            )
        }
    }

    @Test
    fun testNormalizePeakCapsMaxGainToThree() {
        // Peak is 0.10f (>= 0.08f); targetPeak is 0.90f -> uncapped scale is 9.0, capped scale is 3.0
        val quietSpeech = floatArrayOf(0.02f, -0.05f, 0.10f, -0.08f)
        val normalized = AudioDspUtils.normalizePeak(quietSpeech, targetPeak = 0.90f)

        assertEquals(quietSpeech.size, normalized.size)
        assertEquals(0.06f, normalized[0], 1e-6f)
        assertEquals(-0.15f, normalized[1], 1e-6f)
        assertEquals(0.30f, normalized[2], 1e-6f)
        assertEquals(-0.24f, normalized[3], 1e-6f)

        val maxPeak = normalized.maxOfOrNull { abs(it) } ?: 0f
        assertEquals(0.30f, maxPeak, 1e-6f)
    }

    @Test
    fun testNormalizePeakDownscalesLoudSignalWithoutClipping() {
        // Peak is 1.50f (overshoot); targetPeak is 0.90f -> scale factor is 0.90 / 1.50 = 0.60
        val loud = floatArrayOf(0.50f, -1.50f, 1.00f)
        val normalized = AudioDspUtils.normalizePeak(loud, targetPeak = 0.90f)

        assertEquals(loud.size, normalized.size)
        assertEquals(0.30f, normalized[0], 1e-6f)
        assertEquals(-0.90f, normalized[1], 1e-6f)
        assertEquals(0.60f, normalized[2], 1e-6f)

        for (s in normalized) {
            assertTrue(s in -1.0f..1.0f, "Samples must be clamped within [-1.0f, 1.0f]")
        }
    }

    @Test
    fun testNormalizePeakShortArrayOverload() {
        // Speech ShortArray: peak 16384 (norm ~0.5f >= 0.08f, scale ~ 1.8 <= 3.0)
        val speechShorts = shortArrayOf(1638, -3276, 16384, -8192)
        val normalizedSpeech = AudioDspUtils.normalizePeak(speechShorts, targetPeak = 0.90f)

        assertEquals(speechShorts.size, normalizedSpeech.size)
        val speechPeak = normalizedSpeech.maxOfOrNull { abs(it) } ?: 0f
        assertEquals(0.90f, speechPeak, 1e-4f)

        // Silence ShortArray: peak 1000 (norm ~0.0305f < 0.08f)
        val silenceShorts = shortArrayOf(500, -1000, 200)
        val normalizedSilence = AudioDspUtils.normalizePeak(silenceShorts, targetPeak = 0.90f)

        assertEquals(silenceShorts.size, normalizedSilence.size)
        val silencePeak = normalizedSilence.maxOfOrNull { abs(it) } ?: 0f
        assertTrue(silencePeak < 0.08f, "Silence in ShortArray must not be amplified, got $silencePeak")
        assertEquals(silenceShorts[1].toFloat() / 32768.0f, normalizedSilence[1], 1e-6f)
    }

    @Test
    fun testNormalizePeakEmptyArrays() {
        assertEquals(0, AudioDspUtils.normalizePeak(FloatArray(0)).size)
        assertEquals(0, AudioDspUtils.normalizePeak(ShortArray(0)).size)
    }
}
