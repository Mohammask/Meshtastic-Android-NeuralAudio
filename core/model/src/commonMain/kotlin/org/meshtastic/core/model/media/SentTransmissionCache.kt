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

/** Cache for outbound media chunks to enable multi-round Selective NACK retransmissions. */
object SentTransmissionCache {
    const val MAX_RETRIES = 3

    data class CachedTransmission(
        val messageId: Byte,
        val chunks: List<ByteArray>,
        val destination: String,
        val channel: Int,
        var retryCount: Int = 0,
        val timestamp: Long = 0L,
    ) {
        val hasRetried: Boolean
            get() = retryCount >= MAX_RETRIES
    }

    private val cache = mutableMapOf<Byte, CachedTransmission>()
    private val lock = Any()

    fun store(messageId: Byte, chunks: List<ByteArray>, destination: String, channel: Int, currentTimeMs: Long = 0L) {
        synchronized(lock) {
            if (currentTimeMs > 0L) {
                cache.entries.removeAll { currentTimeMs - it.value.timestamp > 60_000L }
            }
            cache[messageId] = CachedTransmission(messageId, chunks, destination, channel, timestamp = currentTimeMs)
        }
    }

    fun get(messageId: Byte): CachedTransmission? = synchronized(lock) { cache[messageId] }

    fun markRetried(messageId: Byte) {
        synchronized(lock) { cache[messageId]?.let { it.retryCount++ } }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }
}
