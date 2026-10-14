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

import java.nio.ByteBuffer
import java.nio.ByteOrder

actual object PcmAudioUtils {
    actual fun pcmBytesToShorts(bytes: ByteArray): ShortArray {
        if (bytes.isEmpty()) return ShortArray(0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shorts = ShortArray(buffer.remaining())
        buffer.get(shorts)
        return shorts
    }

    actual fun pcmShortsToBytes(shorts: ShortArray): ByteArray {
        if (shorts.isEmpty()) return ByteArray(0)
        val buffer = ByteBuffer.allocate(shorts.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(shorts)
        return buffer.array()
    }
}
