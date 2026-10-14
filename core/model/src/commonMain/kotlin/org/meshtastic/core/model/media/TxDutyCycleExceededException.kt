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
 * Thrown when an outgoing media payload (compressed Voice Note or 1-bit Image) would exceed the 20-second Time-on-Air
 * limit on the current LoRa configuration, protecting regional duty-cycle limits and preventing channel exhaustion.
 */
class TxDutyCycleExceededException(
    message: String,
    val estimatedToaSeconds: Double = 0.0,
    val maxAllowedSeconds: Double = 20.0,
) : IllegalStateException(message)
