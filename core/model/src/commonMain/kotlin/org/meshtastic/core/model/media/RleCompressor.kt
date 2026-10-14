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
 * High-efficiency Run-Length Encoding (RLE) implementation based on the standard PackBits algorithm. Optimized for
 * compressing 1-bit monochrome bit-packed data where long runs of 0x00 (black) or 0xFF (white) are prevalent.
 *
 * Algorithm semantics:
 * - A run of 2 to 128 identical bytes is encoded as: `(1 - count)` as a signed byte (0xFF down to 0x81), followed by
 *   the single repeated byte.
 * - A literal run of 1 to 128 non-repeating bytes is encoded as: `(count - 1)` (0x00 to 0x7F), followed by the literal
 *   bytes verbatim.
 * - Control byte 0x80 (-128) is reserved / no-op.
 */
object RleCompressor {

    private const val MAX_RUN_LENGTH = 128
    private const val MAX_LITERAL_LENGTH = 128

    /**
     * Compresses the provided [input] byte array using the PackBits RLE scheme. Guaranteed to never expand random data
     * beyond ~0.8% overhead.
     */
    fun compress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        val output = mutableListOf<Byte>()
        var index = 0
        val length = input.size

        while (index < length) {
            // Check for run of identical bytes
            var runLength = 1
            while (
                index + runLength < length && runLength < MAX_RUN_LENGTH && input[index + runLength] == input[index]
            ) {
                runLength++
            }

            if (runLength >= 3 || (runLength == 2 && index + 2 >= length)) {
                // Encode compressed repeat run: -(runLength - 1)
                val flag = (-(runLength - 1)).toByte()
                output.add(flag)
                output.add(input[index])
                index += runLength
            } else {
                // Find length of non-repeating literal bytes
                var litLength = 0
                while (index + litLength < length && litLength < MAX_LITERAL_LENGTH) {
                    // Peek ahead to see if a run of >= 3 identical bytes begins
                    if (
                        index + litLength + 2 < length &&
                        input[index + litLength] == input[index + litLength + 1] &&
                        input[index + litLength] == input[index + litLength + 2]
                    ) {
                        break
                    }
                    litLength++
                }

                if (litLength > 0) {
                    // Encode literal run: (litLength - 1)
                    val flag = (litLength - 1).toByte()
                    output.add(flag)
                    for (i in 0 until litLength) {
                        output.add(input[index + i])
                    }
                    index += litLength
                }
            }
        }

        return output.toByteArray()
    }

    /**
     * Decompresses a PackBits RLE byte array into its original uncompressed form.
     *
     * @param input Compressed byte stream.
     * @param expectedSize Optional expected uncompressed size in bytes for allocation optimization.
     * @return Decoded byte array.
     */
    fun decompress(input: ByteArray, expectedSize: Int? = null): ByteArray {
        if (input.isEmpty()) return ByteArray(0)

        val output = expectedSize?.let { ArrayList<Byte>(it) } ?: mutableListOf<Byte>()
        var index = 0
        val length = input.size

        while (index < length) {
            val flag = input[index++].toInt()

            when {
                // Literal run: flag is between 0 and 127 -> read flag + 1 bytes
                flag in 0..127 -> {
                    val count = flag + 1
                    for (i in 0 until count) {
                        if (index < length) {
                            output.add(input[index++])
                        }
                    }
                }

                // Repeat run: flag is between -127 and -1 (0x81 to 0xFF) -> repeat next byte (1 - flag) times
                flag in -127..-1 -> {
                    val count = 1 - flag
                    if (index < length) {
                        val repeatedByte = input[index++]
                        for (i in 0 until count) {
                            output.add(repeatedByte)
                        }
                    }
                }

                // Flag == -128 (0x80) is no-op, ignore
                else -> {
                    // No-op
                }
            }
        }

        return output.toByteArray()
    }
}
