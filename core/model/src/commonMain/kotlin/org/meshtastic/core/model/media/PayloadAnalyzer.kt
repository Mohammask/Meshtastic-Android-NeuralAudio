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

import org.meshtastic.core.model.ChannelOption
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Calculates LoRa RF Time-on-Air (ToA) using the physical layer Semtech Chirp Spread Spectrum (CSS) formulas, and
 * enforces the 20-second safety limit to prevent duty-cycle exhaustion.
 */
object PayloadAnalyzer {

    const val MAX_ALLOWED_TX_TIME_SECONDS = 20.0
    const val DEFAULT_MAX_CHUNK_PAYLOAD_BYTES = 200
    const val DEFAULT_PREAMBLE_SYMBOLS = 16
    const val INTER_PACKET_CADENCE_SECONDS = 0.100 // 100ms inter-chunk delay
    const val MIN_PACING_DELAY_MS = 150L
    const val SAFETY_MARGIN_MS = 150L
    const val FALLBACK_DELAY_LONG_MS = 1800L
    const val FALLBACK_DELAY_SHORT_MS = 800L

    /**
     * Calculates the required inter-chunk pacing delay in milliseconds to prevent overflowing the radio's hardware FIFO
     * buffer (e.g. SX1262 on ESP32/nRF52).
     *
     * If [preset] is provided, computes dynamic Time-on-Air plus [SAFETY_MARGIN_MS] (150ms) with a floor of 800ms on
     * LongFast and slower presets (or [MIN_PACING_DELAY_MS] on fast presets). If [preset] is null, falls back to a safe
     * conservative window (1800ms for large chunks, 800ms for small chunks).
     */
    fun calculateInterChunkDelayMs(chunkSizeBytes: Int, preset: ModemPreset?): Long {
        if (preset != null) {
            val sf = resolveSpreadingFactor(preset)
            val bw = resolveBandwidthHz(preset)
            val toaSec = calculateSinglePacketToaSeconds(payloadBytes = chunkSizeBytes, sf = sf, bandwidthHz = bw)
            val calculatedMs = (toaSec * 1000).toLong() + SAFETY_MARGIN_MS
            val isSlowOrLongPreset =
                preset == ModemPreset.LONG_FAST ||
                    preset == ModemPreset.LONG_SLOW ||
                    preset == ModemPreset.LONG_MODERATE ||
                    preset == ModemPreset.MEDIUM_SLOW ||
                    preset == ModemPreset.VERY_LONG_SLOW
            val minDelay = if (isSlowOrLongPreset) 800L else MIN_PACING_DELAY_MS
            return maxOf(minDelay, calculatedMs)
        }
        return if (chunkSizeBytes >= 100) FALLBACK_DELAY_LONG_MS else FALLBACK_DELAY_SHORT_MS
    }

    /** Resolves the spreading factor for a standard Meshtastic [ModemPreset]. */
    fun resolveSpreadingFactor(preset: ModemPreset): Int = when (preset) {
        ModemPreset.SHORT_FAST,
        ModemPreset.SHORT_TURBO,
        ModemPreset.TINY_FAST,
        -> 7

        ModemPreset.SHORT_SLOW,
        ModemPreset.TINY_SLOW,
        -> 8

        ModemPreset.MEDIUM_FAST,
        ModemPreset.MEDIUM_TURBO,
        ModemPreset.LITE_FAST,
        ModemPreset.LONG_TURBO,
        -> 9

        ModemPreset.MEDIUM_SLOW,
        ModemPreset.NARROW_FAST,
        -> 10

        ModemPreset.LONG_FAST,
        ModemPreset.LONG_MODERATE,
        -> 11

        ModemPreset.LONG_SLOW,
        ModemPreset.VERY_LONG_SLOW,
        ModemPreset.NARROW_SLOW,
        ModemPreset.LITE_SLOW,
        -> 12
    }

    /** Resolves the channel bandwidth in Hertz for a [ModemPreset]. */
    fun resolveBandwidthHz(preset: ModemPreset): Long {
        val option = ChannelOption.from(preset) ?: ChannelOption.DEFAULT
        return (option.bandwidth * 1_000_000).toLong()
    }

    /**
     * Calculates the Time-on-Air for a single LoRa packet based on Semtech SX126x/SX127x specifications.
     *
     * @param payloadBytes Size of the packet payload in bytes.
     * @param sf Spreading Factor (7 to 12).
     * @param bandwidthHz Modulation bandwidth in Hz (e.g. 250_000 for 250 kHz).
     * @param codingRate Coding rate index (1 = 4/5, 2 = 4/6, 3 = 4/7, 4 = 4/8).
     * @param preambleSymbols Number of programmed preamble symbols (default 16).
     * @param lowDataRateOptimization Force LDRO enable/disable, or null for auto-detect (T_sym > 16.38ms).
     * @return Single packet Time-on-Air in seconds.
     */
    fun calculateSinglePacketToaSeconds(
        payloadBytes: Int,
        sf: Int,
        bandwidthHz: Long,
        codingRate: Int = 1,
        preambleSymbols: Int = DEFAULT_PREAMBLE_SYMBOLS,
        lowDataRateOptimization: Boolean? = null,
    ): Double {
        require(sf in 7..12) { "Spreading factor must be between 7 and 12, was $sf" }
        require(bandwidthHz > 0) { "Bandwidth must be positive, was $bandwidthHz" }
        require(payloadBytes >= 0) { "Payload bytes must be non-negative, was $payloadBytes" }

        // T_sym = 2^SF / BW
        val tSym = 2.0.pow(sf.toDouble()) / bandwidthHz.toDouble()

        // T_preamble = (N_preamble + 4.25) * T_sym
        val tPreamble = (preambleSymbols.toDouble() + 4.25) * tSym

        // Low Data Rate Optimization (DE = 1 if T_sym > 16.38ms)
        val de = lowDataRateOptimization ?: (tSym > 0.01638)
        val deVal = if (de) 1 else 0

        // Explicit header, CRC enabled
        // numerator = 8*PL - 4*SF + 28 + 16*CRC - 20*IH
        val numerator = (8 * payloadBytes) - (4 * sf) + 28 + 16
        val denominator = 4 * (sf - (2 * deVal))

        val ceilTerm = ceil(numerator.toDouble() / denominator.toDouble()).coerceAtLeast(0.0)
        val nPayload = 8.0 + max(0.0, ceilTerm * (codingRate + 4))

        val tPayload = nPayload * tSym
        return tPreamble + tPayload
    }

    /**
     * Calculates the total transmission time required for a multi-chunk media payload.
     *
     * @param totalBytes Total size of the compressed byte array.
     * @param sf Spreading Factor.
     * @param bandwidthHz Bandwidth in Hz.
     * @param maxChunkPayload Maximum payload bytes per LoRa chunk (default 200).
     */
    fun calculateTotalTransmissionTimeSeconds(
        totalBytes: Int,
        sf: Int,
        bandwidthHz: Long,
        maxChunkPayload: Int = DEFAULT_MAX_CHUNK_PAYLOAD_BYTES,
    ): Double {
        if (totalBytes <= 0) return 0.0
        val chunks = ceil(totalBytes.toDouble() / maxChunkPayload.toDouble()).toInt().coerceAtLeast(1)
        val singlePacketToa =
            calculateSinglePacketToaSeconds(
                payloadBytes = maxChunkPayload.coerceAtMost(totalBytes),
                sf = sf,
                bandwidthHz = bandwidthHz,
            )
        return (chunks * singlePacketToa) + ((chunks - 1) * INTER_PACKET_CADENCE_SECONDS)
    }

    /**
     * The LoRa 20-Second Time Gate: Calculates the estimated transmission time and validates that it does not exceed
     * [maxAllowedSeconds]. If the limit is exceeded, throws [TxDutyCycleExceededException] to halt the transmission
     * pipeline immediately.
     *
     * @return The estimated Time-on-Air in seconds.
     */
    fun validateTransmissionTime(
        totalBytes: Int,
        sf: Int,
        bandwidthHz: Long,
        maxAllowedSeconds: Double = MAX_ALLOWED_TX_TIME_SECONDS,
        maxChunkPayload: Int = DEFAULT_MAX_CHUNK_PAYLOAD_BYTES,
    ): Double {
        val toa = calculateTotalTransmissionTimeSeconds(totalBytes, sf, bandwidthHz, maxChunkPayload)
        if (toa > maxAllowedSeconds) {
            val roundedToa = (toa * 10).roundToInt() / 10.0
            throw TxDutyCycleExceededException(
                message =
                "Transmission time of ${roundedToa}s exceeds safety limit of ${maxAllowedSeconds}s (SF$sf, ${bandwidthHz / 1000}kHz). Aborting to prevent duty-cycle lockout.",
                estimatedToaSeconds = toa,
                maxAllowedSeconds = maxAllowedSeconds,
            )
        }
        return toa
    }

    /** Overload for direct validation using a [ModemPreset]. */
    fun validateTransmissionTime(
        totalBytes: Int,
        preset: ModemPreset,
        maxAllowedSeconds: Double = MAX_ALLOWED_TX_TIME_SECONDS,
    ): Double {
        val sf = resolveSpreadingFactor(preset)
        val bw = resolveBandwidthHz(preset)
        return validateTransmissionTime(totalBytes, sf, bw, maxAllowedSeconds)
    }
}
