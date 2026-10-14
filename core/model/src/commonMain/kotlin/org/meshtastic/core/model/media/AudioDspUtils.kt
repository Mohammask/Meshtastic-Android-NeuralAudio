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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Digital Signal Processing (DSP) utilities for voice communication over LoRa:
 * - Single-pole DC-blocking high-pass filter (~50 Hz cutoff at 8 kHz).
 * - Automatic Gain Control (AGC) with hyperbolic tangent (tanh) soft-clipping.
 * - Bandlimited Catmull-Rom cubic spline resampling (8 kHz <-> 24 kHz / 16 kHz).
 */
object AudioDspUtils {

    /**
     * Applies a single-pole DC-blocking filter (fc ≈ 50 Hz at 8000 Hz, R ≈ 0.9607). Eliminates microphone DC offset and
     * sub-audible breath thumps that corrupt LPC-10 autocorrelation and cause speech-correlated static.
     *
     * Transfer function: H(z) = (1 - z^-1) / (1 - R * z^-1)
     */
    fun applyDcBlock(samples: ShortArray, r: Float = 0.9607f): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val out = ShortArray(samples.size)
        var prevX = 0f
        var prevY = 0f
        for (i in samples.indices) {
            val x = samples[i].toFloat()
            val y = x - prevX + r * prevY
            prevX = x
            prevY = y
            out[i] = y.roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Applies DC-blocking filter to Float32 samples in-place or returning a new array. */
    fun applyDcBlock(samples: FloatArray, r: Float = 0.9607f): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val out = FloatArray(samples.size)
        var prevX = 0f
        var prevY = 0f
        for (i in samples.indices) {
            val x = samples[i]
            val y = x - prevX + r * prevY
            prevX = x
            prevY = y
            out[i] = y
        }
        return out
    }

    /**
     * Automatic Gain Control (AGC) + Hyperbolic Tangent (tanh) soft-clipping.
     *
     * Computes RMS speech energy, dynamically applies smooth headroom gain (target ~ -13 dBFS), and processes peaks
     * through a smooth analog-like tanh curve to strictly eliminate hard clipping, digital distortion, and wrapping on
     * loud utterances.
     *
     * @param samples Raw 16-bit PCM samples.
     * @param targetRms Target RMS amplitude in normalized float space (default 0.22f ≈ -13 dBFS).
     * @param maxGain Maximum upward amplification factor for whispers (default 3.5f).
     * @param minGain Minimum downward attenuation factor for shouting (default 0.25f).
     * @return Float32 samples strictly normalized in [-0.999f, 0.999f].
     */
    fun applyAgcAndSoftClip(
        samples: ShortArray,
        targetRms: Float = 0.22f,
        maxGain: Float = 3.5f,
        minGain: Float = 0.25f,
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val n = samples.size
        val floats = FloatArray(n)

        // 1. Calculate overall RMS energy of utterance
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = samples[i].toFloat() / 32768.0f
            sumSq += s * s
        }
        val currentRms = sqrt(sumSq / n.coerceAtLeast(1)).toFloat()

        // 2. Compute smooth AGC gain factor
        val desiredGain =
            if (currentRms > 1e-4f) {
                (targetRms / currentRms).coerceIn(minGain, maxGain)
            } else {
                1.0f
            }

        // 3. Apply AGC gain and hyperbolic tangent (tanh) soft-clipping
        for (i in 0 until n) {
            val rawNorm = samples[i].toFloat() / 32768.0f
            val amplified = rawNorm * desiredGain
            // tanh smoothly compresses loud peaks without introducing square-wave harmonics
            floats[i] = tanh(amplified.toDouble()).toFloat().coerceIn(-0.999f, 0.999f)
        }

        return floats
    }

    /**
     * Prepares human speech specifically for WavTokenizer discrete acoustic tokenizer:
     * 1. Measures peak speech amplitude.
     * 2. Scales quiet microphone input into WavTokenizer's optimal codebook activation range (target peak ~0.85f).
     * 3. Applies smooth hyperbolic tangent (tanh) soft-limiting above 0.85f to strictly prevent digital clipping.
     * 4. Leaves pure silence / low-level room noise untouched (< 1e-4f).
     *
     * @param floats Raw Float32 PCM samples in [-1.0f, 1.0f].
     * @param targetPeak Target peak amplitude (default 0.85f).
     * @param maxGain Maximum upward amplification factor for quiet microphones (default 16.0f ≈ +24 dB).
     * @return Processed Float32 samples normalized to WavTokenizer's sweet spot.
     */
    fun prepareVoiceForWavTokenizer(floats: FloatArray, targetPeak: Float = 0.85f, maxGain: Float = 16.0f): FloatArray {
        if (floats.isEmpty()) return FloatArray(0)
        var maxAbs = 0f
        for (s in floats) {
            val a = abs(s)
            if (a > maxAbs) maxAbs = a
        }
        if (maxAbs < 1e-4f) {
            return floats.copyOf()
        }

        val gain = (targetPeak / maxAbs).coerceIn(1.0f, maxGain)
        val out = FloatArray(floats.size)
        val knee = 0.85f
        val headroom = 0.149f

        for (i in floats.indices) {
            val s = floats[i] * gain
            val absVal = abs(s)
            if (absVal <= knee) {
                out[i] = s
            } else {
                val sign = if (s >= 0f) 1.0f else -1.0f
                val excess = absVal - knee
                val compressed = knee + headroom * tanh((excess / headroom).toDouble()).toFloat()
                out[i] = (sign * compressed).coerceIn(-1.0f, 1.0f)
            }
        }
        return out
    }

    /** Prepares 16-bit PCM ShortArray speech for WavTokenizer. */
    fun prepareVoiceForWavTokenizer(shorts: ShortArray, targetPeak: Float = 0.85f, maxGain: Float = 16.0f): FloatArray {
        if (shorts.isEmpty()) return FloatArray(0)
        val rawFloats = FloatArray(shorts.size) { shorts[it].toFloat() / 32768.0f }
        return prepareVoiceForWavTokenizer(rawFloats, targetPeak, maxGain)
    }

    /**
     * Pure linear peak normalization:
     * - Finds the absolute maximum peak: `val maxPeak = samples.maxOfOrNull { abs(it) } ?: 0f`.
     * - If `maxPeak > 0.01f`, scales all samples uniformly by `(targetPeak / maxPeak)` and clamps within `[-1.0f,
     *   1.0f]`.
     * - If `maxPeak <= 0.01f` (pure silence / ambient hiss), does not amplify to prevent boosting background noise.
     *
     * @param samples Float32 PCM samples in [-1.0f, 1.0f].
     * @param targetPeak Desired peak amplitude (default 0.90f ≈ -1 dBFS).
     * @return Uniformly scaled Float32 samples.
     */
    fun normalizePeak(samples: FloatArray, targetPeak: Float = 0.90f): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val maxPeak = samples.maxOfOrNull { abs(it) } ?: 0f
        if (maxPeak < 0.08f) {
            return samples.copyOf()
        }
        val scale = (targetPeak / maxPeak).coerceAtMost(3.0f)
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            out[i] = (samples[i] * scale).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    /**
     * Pure linear peak normalization for 16-bit PCM ShortArray samples: Converts to normalized Float32 in [-1.0f, 1.0f]
     * and applies linear peak normalization.
     *
     * @param samples 16-bit PCM ShortArray samples.
     * @param targetPeak Desired peak amplitude (default 0.90f ≈ -1 dBFS).
     * @return Uniformly scaled Float32 samples.
     */
    fun normalizePeak(samples: ShortArray, targetPeak: Float = 0.90f): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)
        val rawFloats = FloatArray(samples.size) { samples[it].toFloat() / 32768.0f }
        return normalizePeak(rawFloats, targetPeak)
    }

    /**
     * Prepares and optimizes human speech for Codec 2 (LPC-10 / sinusoidal vocoder):
     * 1. High-pass DC-blocking filter (~50 Hz cutoff) to eliminate DC offsets and plosive breath thumps.
     * 2. Utterance-level smooth Automatic Gain Control (AGC):
     *     - Computes RMS energy across the speech signal.
     *     - Dynamically scales quiet microphone audio into Codec 2's optimal operating range (-16 dBFS / RMS ~0.15f).
     *     - Allows up to 6.0x (+15.5 dB) amplification for soft speech or distant phone microphones.
     * 3. Soft-knee limiter:
     *     - Completely linear and transparent for |sample| <= 24,000 (~ -2.7 dBFS).
     *     - Smooth hyperbolic tangent (tanh) analog saturation above 24,000, strictly preventing digital wrapping or
     *       harsh square-wave clipping.
     * 4. NEVER zeroes out frames: preserves 100% of unvoiced fricatives ('s', 'th', 'f'), plosives ('t', 'p', 'k'), and
     *    quiet word endings, ensuring continuous pitch tracker lock and high human speech intelligibility.
     *
     * @param samples 16-bit PCM input at 8 kHz.
     * @param targetRms Target RMS in normalized float domain (default 0.15f ≈ -16.5 dBFS).
     * @param maxGain Maximum upward amplification factor for quiet speech (default 6.0f ≈ +15.5 dB).
     * @return Processed 16-bit PCM ShortArray.
     */

    /**
     * Prepares human speech for Codec 2 (LPC-10 / sinusoidal vocoder):
     * 1. High-pass DC-blocking filter (~50 Hz cutoff) to eliminate microphone DC offset and breath thumps.
     * 2. Preserves authentic glottal spectral tilt: David Rowe's Codec 2 vector quantizers and codebooks were trained
     *    on raw speech. Feeding natural, unwarped speech ensures MSE codebook distances match the correct centroids,
     *    preventing phoneme corruption and vowel shifting.
     * 3. Utterance-level smooth Automatic Gain Control (AGC):
     *     - Computes RMS energy across speech.
     *     - Dynamically scales quiet microphone audio into Codec 2's optimal operating range (~ -21 dBFS / RMS ~0.09f).
     * 4. Transparent soft-knee limiter:
     *     - Completely linear and transparent for |sample| <= 22,000 (~ -3.4 dBFS).
     *     - Smooth hyperbolic tangent (tanh) analog saturation above 22,000, preventing digital clipping or square-wave
     *       distortion.
     * 5. Preserves 100% of unvoiced fricatives ('s', 'sh', 'f', 'ح', 'خ') and plosives ('t', 'k', 'p') without zeroing
     *    out frames or gating.
     *
     * @param samples 16-bit PCM input at 8 kHz.
     * @param targetRms Target RMS in normalized float domain (default 0.09f ≈ -21 dBFS).
     * @param maxGain Maximum upward amplification factor for quiet speech (default 2.5f).
     * @return Clean 16-bit PCM ShortArray.
     */
    fun processVoiceForCodec2(samples: ShortArray, targetRms: Float = 0.09f, maxGain: Float = 2.5f): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val n = samples.size

        // 1. Single-pole DC blocking (fc ≈ 50 Hz at 8000 Hz)
        val dcBlocked = applyDcBlock(samples)

        // 2. Measure overall utterance RMS energy directly on natural DC-blocked signal
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = dcBlocked[i].toDouble() / 32768.0
            sumSq += s * s
        }
        val currentRms = sqrt(sumSq / n.coerceAtLeast(1)).toFloat()

        // 3. Compute smooth linear boost gain.
        // If utterance is quiet ambient room noise (< 0.006f RMS / -44 dBFS), do NOT boost room hiss/fans.
        val boostGain =
            if (currentRms > 0.006f) {
                (targetRms / currentRms).coerceIn(1.0f, maxGain)
            } else {
                1.0f
            }

        val out = ShortArray(n)
        val knee = 22000.0f
        val headRoom = 32767.0f - knee

        // 4. Apply amplification with soft-knee limiter directly on natural speech
        for (i in 0 until n) {
            val raw = dcBlocked[i].toFloat()
            val amplified = raw * boostGain
            val absVal = abs(amplified)
            val processed =
                if (absVal <= knee) {
                    amplified.roundToInt().coerceIn(-32768, 32767).toShort()
                } else {
                    val sign = if (amplified >= 0f) 1.0f else -1.0f
                    val excess = absVal - knee
                    val compressed = knee + headRoom * tanh((excess / headRoom).toDouble()).toFloat()
                    (sign * compressed).roundToInt().coerceIn(-32768, 32767).toShort()
                }
            out[i] = processed
        }

        return out
    }

    /**
     * Digital Pre-Amp and Noise Gate DSP processor for sub-threshold filtering. Note: For full human speech voice
     * notes, use [processVoiceForCodec2].
     */
    fun applyPreAmpAndNoiseGate(
        samples: ShortArray,
        gainDb: Float = 9.0f,
        gateThresholdRms: Float = 0.015f,
        frameSize: Int = 320,
        hangoverFrames: Int = 2,
    ): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)
        val out = ShortArray(samples.size)
        val linearGain = 10.0.pow(gainDb / 20.0).toFloat()

        val knee = 24000.0f
        val headRoom = 32767.0f - knee

        var holdCounter = 0
        var prevGateOpen = false

        val numFrames = (samples.size + frameSize - 1) / frameSize

        for (f in 0 until numFrames) {
            val start = f * frameSize
            val end = (start + frameSize).coerceAtMost(samples.size)
            val count = end - start

            var sumSq = 0.0
            for (i in start until end) {
                val norm = samples[i].toDouble() / 32768.0
                sumSq += norm * norm
            }
            val frameRms = sqrt(sumSq / count.coerceAtLeast(1)).toFloat()

            val isSpeech = frameRms >= gateThresholdRms
            if (isSpeech) {
                holdCounter = hangoverFrames
            } else if (holdCounter > 0) {
                holdCounter--
            }
            val gateOpen = isSpeech || holdCounter > 0

            if (!gateOpen) {
                for (i in start until end) {
                    out[i] = 0
                }
            } else {
                val fadeSteps = 16.coerceAtMost(count)
                val needFadeIn = !prevGateOpen && isSpeech

                for (i in start until end) {
                    val sampleIdx = i - start
                    var s = samples[i].toFloat() * linearGain

                    if (needFadeIn && sampleIdx < fadeSteps) {
                        s *= (sampleIdx + 1).toFloat() / fadeSteps.toFloat()
                    }

                    val absVal = abs(s)
                    val processedSample =
                        if (absVal <= knee) {
                            s.roundToInt().coerceIn(-32768, 32767).toShort()
                        } else {
                            val sign = if (s >= 0f) 1.0f else -1.0f
                            val excess = absVal - knee
                            val compressed = knee + headRoom * tanh((excess / headRoom).toDouble()).toFloat()
                            (sign * compressed).roundToInt().coerceIn(-32768, 32767).toShort()
                        }
                    out[i] = processedSample
                }
            }
            prevGateOpen = gateOpen
        }

        return out
    }

    /**
     * Upsamples 8 kHz PCM to 24 kHz (3:1 integer ratio) using Catmull-Rom cubic Hermite spline interpolation.
     * Guarantees C^1-continuous derivatives and zero phase delay.
     */
    fun resample8kTo24k(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val outLen = input.size * 3
        val output = FloatArray(outLen)
        val n = input.size

        for (j in 0 until outLen) {
            val i = j / 3
            val mod = j % 3
            if (mod == 0) {
                output[j] = input[i]
            } else {
                val frac = mod / 3.0f
                val p0 = if (i > 0) input[i - 1] else input[0]
                val p1 = input[i]
                val p2 = if (i + 1 < n) input[i + 1] else input[n - 1]
                val p3 = if (i + 2 < n) input[i + 2] else p2

                val a = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3
                val b = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3
                val c = -0.5f * p0 + 0.5f * p2
                val d = p1

                val interp = ((a * frac + b) * frac + c) * frac + d
                output[j] = interp.coerceIn(-1.0f, 1.0f)
            }
        }
        return output
    }

    /** Downsamples 24 kHz PCM to 8 kHz (1:3 integer ratio) with a 7-tap linear-phase anti-aliasing FIR filter. */
    fun resample24kTo8k(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val outLen = input.size / 3
        val output = FloatArray(outLen)
        val coeffs = floatArrayOf(0.0385f, 0.1238f, 0.2377f, 0.3000f, 0.2377f, 0.1238f, 0.0385f)
        val halfFilter = coeffs.size / 2

        for (k in 0 until outLen) {
            val center = k * 3
            var sum = 0f
            for (m in coeffs.indices) {
                val idx = (center + m - halfFilter).coerceIn(0, input.size - 1)
                sum += coeffs[m] * input[idx]
            }
            output[k] = sum.coerceIn(-1.0f, 1.0f)
        }
        return output
    }

    fun resample8kTo24k(input: ShortArray): ShortArray {
        val floats = FloatArray(input.size) { input[it].toFloat() / 32768.0f }
        val upsampled = resample8kTo24k(floats)
        return ShortArray(upsampled.size) { (upsampled[it] * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort() }
    }

    fun resample24kTo8k(input: ShortArray): ShortArray {
        val floats = FloatArray(input.size) { input[it].toFloat() / 32768.0f }
        val downsampled = resample24kTo8k(floats)
        return ShortArray(downsampled.size) {
            (downsampled[it] * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Strips initial dead air / silence prior to speech onset, preserving soft opening consonants:
     * 1. Detects speech onset using a realistic RMS energy threshold (~0.020f to 0.025f / ~ -32 dBFS) so faint ambient
     *    breath or room hiss does not falsely trigger speech detection at sample 0.
     * 2. Preserves a mandatory 80ms acoustic lead-in margin before the detected speech index to preserve soft opening
     *    consonants ('s', 'f', 't', 'k', 'ح', 'خ'). At 24,000 Hz, this corresponds to exactly 1,920 samples; at 8,000
     *    Hz, 640 samples.
     * 3. Strips only the dead air that occurs prior to (onsetIndex - leadInSamples).
     *
     * Note: Spoken sentences naturally decay in energy, so trailing silence is intentionally preserved.
     *
     * @param samples 16-bit PCM samples.
     * @param sampleRate Sampling rate in Hz (default 8000 Hz).
     * @param thresholdRms RMS energy threshold for speech onset (default 0.020f ≈ -34 dBFS).
     * @param leadInMs Acoustic lead-in margin in milliseconds (default 80 ms).
     * @return Trimmed 16-bit PCM ShortArray.
     */
    fun trimLeadingSilence(
        samples: ShortArray,
        sampleRate: Int = 8000,
        thresholdRms: Float = 0.020f,
        leadInMs: Int = 80,
    ): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)

        val windowSize = (sampleRate * 10) / 1000
        val hopSize = (sampleRate * 10) / 1000
        var onsetIndex = -1

        var offset = 0
        while (offset + windowSize <= samples.size) {
            var sumSq = 0.0
            for (i in offset until offset + windowSize) {
                val s = samples[i].toDouble() / 32768.0
                sumSq += s * s
            }
            val frameRms = sqrt(sumSq / windowSize).toFloat()
            if (frameRms >= thresholdRms) {
                onsetIndex = offset
                break
            }
            offset += hopSize
        }

        if (onsetIndex == -1 && offset < samples.size) {
            val remaining = samples.size - offset
            var sumSq = 0.0
            for (i in offset until samples.size) {
                val s = samples[i].toDouble() / 32768.0
                sumSq += s * s
            }
            val frameRms = sqrt(sumSq / remaining).toFloat()
            if (frameRms >= thresholdRms) {
                onsetIndex = offset
            }
        }

        if (onsetIndex == -1) {
            return samples
        }

        // Dynamically compute lead-in margin: 1920 samples at 24 kHz, 640 samples at 8 kHz
        val leadInSamples = (sampleRate * leadInMs) / 1000
        val trimStart = (onsetIndex - leadInSamples).coerceAtLeast(0)
        if (trimStart <= 0) return samples

        return samples.copyOfRange(trimStart, samples.size)
    }

    /** Strips initial dead air / silence prior to speech onset on Float32 samples in [-1.0f, 1.0f]. */
    fun trimLeadingSilence(
        samples: FloatArray,
        sampleRate: Int = 8000,
        thresholdRms: Float = 0.020f,
        leadInMs: Int = 80,
    ): FloatArray {
        if (samples.isEmpty()) return FloatArray(0)

        val windowSize = (sampleRate * 10) / 1000
        val hopSize = (sampleRate * 10) / 1000
        var onsetIndex = -1

        var offset = 0
        while (offset + windowSize <= samples.size) {
            var sumSq = 0.0
            for (i in offset until offset + windowSize) {
                val s = samples[i].toDouble()
                sumSq += s * s
            }
            val frameRms = sqrt(sumSq / windowSize).toFloat()
            if (frameRms >= thresholdRms) {
                onsetIndex = offset
                break
            }
            offset += hopSize
        }

        if (onsetIndex == -1 && offset < samples.size) {
            val remaining = samples.size - offset
            var sumSq = 0.0
            for (i in offset until samples.size) {
                val s = samples[i].toDouble()
                sumSq += s * s
            }
            val frameRms = sqrt(sumSq / remaining).toFloat()
            if (frameRms >= thresholdRms) {
                onsetIndex = offset
            }
        }

        if (onsetIndex == -1) {
            return samples
        }

        // Dynamically compute lead-in margin: 1920 samples at 24 kHz, 640 samples at 8 kHz
        val leadInSamples = (sampleRate * leadInMs) / 1000
        val trimStart = (onsetIndex - leadInSamples).coerceAtLeast(0)
        if (trimStart <= 0) return samples

        return samples.copyOfRange(trimStart, samples.size)
    }

    /** Packages 16-bit mono PCM audio samples into a standard 44-byte RIFF/WAVE container at 24000 Hz. */
    fun createWavFile(pcmSamples: ShortArray, sampleRate: Int = WavTokenizerEngine.SAMPLE_RATE_24K): ByteArray {
        val dataSize = pcmSamples.size * 2
        val totalSize = 36 + dataSize
        val out = ByteArray(44 + dataSize)
        out[0] = 'R'.code.toByte()
        out[1] = 'I'.code.toByte()
        out[2] = 'F'.code.toByte()
        out[3] = 'F'.code.toByte()
        out[4] = (totalSize and 0xFF).toByte()
        out[5] = ((totalSize shr 8) and 0xFF).toByte()
        out[6] = ((totalSize shr 16) and 0xFF).toByte()
        out[7] = ((totalSize shr 24) and 0xFF).toByte()
        out[8] = 'W'.code.toByte()
        out[9] = 'A'.code.toByte()
        out[10] = 'V'.code.toByte()
        out[11] = 'E'.code.toByte()
        out[12] = 'f'.code.toByte()
        out[13] = 'm'.code.toByte()
        out[14] = 't'.code.toByte()
        out[15] = ' '.code.toByte()
        out[16] = 16
        out[17] = 0
        out[18] = 0
        out[19] = 0
        out[20] = 1
        out[21] = 0
        out[22] = 1
        out[23] = 0
        out[24] = (sampleRate and 0xFF).toByte()
        out[25] = ((sampleRate shr 8) and 0xFF).toByte()
        out[26] = ((sampleRate shr 16) and 0xFF).toByte()
        out[27] = ((sampleRate shr 24) and 0xFF).toByte()
        val byteRate = sampleRate * 2
        out[28] = (byteRate and 0xFF).toByte()
        out[29] = ((byteRate shr 8) and 0xFF).toByte()
        out[30] = ((byteRate shr 16) and 0xFF).toByte()
        out[31] = ((byteRate shr 24) and 0xFF).toByte()
        out[32] = 2
        out[33] = 0
        out[34] = 16
        out[35] = 0
        out[36] = 'd'.code.toByte()
        out[37] = 'a'.code.toByte()
        out[38] = 't'.code.toByte()
        out[39] = 'a'.code.toByte()
        out[40] = (dataSize and 0xFF).toByte()
        out[41] = ((dataSize shr 8) and 0xFF).toByte()
        out[42] = ((dataSize shr 16) and 0xFF).toByte()
        out[43] = ((dataSize shr 24) and 0xFF).toByte()
        for (i in pcmSamples.indices) {
            val s = pcmSamples[i].toInt()
            out[44 + i * 2] = (s and 0xFF).toByte()
            out[44 + i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Salvages arrived chunks and synthesizes zero-amplitude PCM cross-faded silence for missing chunk windows.
     * Prevents vocoder screeching and acoustic pops at chunk boundaries.
     */
    fun salvageAndSynthesizeConcealedAudio(
        completed: CompletedMediaPayload,
        sampleRate: Int =
            if (completed.payloadType == ChunkingEngine.TYPE_AUDIO_CODEC2) {
                Codec2Engine.SAMPLE_RATE
            } else {
                WavTokenizerEngine.SAMPLE_RATE_24K
            },
    ): ShortArray {
        val isWavTokenizer = completed.payloadType == ChunkingEngine.TYPE_AUDIO_WAVTOKENIZER
        val totalChunks = completed.totalChunks.coerceAtLeast(1)
        val missingChunks = completed.missingChunks
        val chunkMap = completed.chunkDataMap

        val pcmChunks = mutableListOf<ShortArray>()
        val wavTokenizerChunkSamples = WavTokenizerEngine.SAMPLES_PER_TOKEN_24K * 2 // 640 samples per 3-byte token pair

        for (i in 0 until totalChunks) {
            val isMissing = i in missingChunks || !chunkMap.containsKey(i)
            if (isMissing) {
                val refChunkSize = chunkMap.values.firstOrNull()?.size ?: 204
                val estimatedSamples =
                    if (isWavTokenizer) {
                        ((refChunkSize / 3) * wavTokenizerChunkSamples).coerceAtLeast(wavTokenizerChunkSamples)
                    } else {
                        ((refChunkSize / 7) * 320).coerceAtLeast(320)
                    }
                pcmChunks.add(ShortArray(estimatedSamples))
            } else {
                val chunkBytes = chunkMap[i] ?: ByteArray(0)
                val decoded =
                    try {
                        if (isWavTokenizer) {
                            WavTokenizerEngine.decode(chunkBytes)
                        } else if (Codec2Engine.isAvailable()) {
                            Codec2Engine.decode(chunkBytes)
                        } else {
                            ShortArray((chunkBytes.size / 7) * 320)
                        }
                    } catch (_: Throwable) {
                        ShortArray(
                            if (isWavTokenizer) {
                                (chunkBytes.size / 3) * wavTokenizerChunkSamples
                            } else {
                                (chunkBytes.size / 7) * 320
                            },
                        )
                    }
                pcmChunks.add(decoded)
            }
        }

        // Apply smooth cross-fade at boundaries between speech and silence
        val fadeSamples = (sampleRate * 10 / 1000).coerceIn(16, 480) // 10ms window
        for (i in 0 until totalChunks) {
            val isCurrentMissing = i in missingChunks || !chunkMap.containsKey(i)
            if (!isCurrentMissing) {
                val currentPcm = pcmChunks[i]
                if (currentPcm.size > fadeSamples) {
                    val prevMissing = (i > 0) && ((i - 1) in missingChunks || !chunkMap.containsKey(i - 1))
                    if (prevMissing) {
                        for (k in 0 until fadeSamples) {
                            val factor = k.toFloat() / fadeSamples.toFloat()
                            currentPcm[k] = (currentPcm[k] * factor).roundToInt().toShort()
                        }
                    }
                    val nextMissing =
                        (i < totalChunks - 1) && ((i + 1) in missingChunks || !chunkMap.containsKey(i + 1))
                    if (nextMissing) {
                        val startFade = currentPcm.size - fadeSamples
                        for (k in 0 until fadeSamples) {
                            val factor = 1.0f - (k.toFloat() / fadeSamples.toFloat())
                            currentPcm[startFade + k] = (currentPcm[startFade + k] * factor).roundToInt().toShort()
                        }
                    }
                }
            }
        }

        val totalSamples = pcmChunks.sumOf { it.size }
        val out = ShortArray(totalSamples)
        var destOffset = 0
        for (chunk in pcmChunks) {
            chunk.copyInto(out, destinationOffset = destOffset)
            destOffset += chunk.size
        }
        return out
    }
}
