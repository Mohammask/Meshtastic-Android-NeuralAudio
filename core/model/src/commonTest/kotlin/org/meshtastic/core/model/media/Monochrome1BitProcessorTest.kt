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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Monochrome1BitProcessorTest {

    @Test
    fun testRleCompressAndDecompressLossless() {
        val original = ByteArray(256) { (it / 32).toByte() } // Repetitive chunks
        val compressed = RleCompressor.compress(original)
        assertTrue(compressed.size < original.size, "RLE should compress repetitive data")

        val decompressed = RleCompressor.decompress(compressed, expectedSize = original.size)
        assertTrue(original.contentEquals(decompressed), "Decompressed array must match original exactly")
    }

    @Test
    fun testMonochromeThresholdEncodeDecode() {
        val width = 16
        val height = 16
        val pixels =
            IntArray(width * height) { idx ->
                // Half white, half black
                if (idx < 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
            }

        val encoded =
            Monochrome1BitProcessor.encode(
                argbPixels = pixels,
                width = width,
                height = height,
                mode = DitherMode.THRESHOLD,
            )

        assertEquals(width, encoded.width)
        assertEquals(height, encoded.height)
        assertEquals(DitherMode.THRESHOLD, encoded.mode)

        val decoded = Monochrome1BitProcessor.decode(encoded.wireBytes)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals(DitherMode.THRESHOLD, decoded.mode)

        // Verify first 128 are white, remaining 128 are black
        for (i in 0 until 128) {
            assertEquals(0xFFFFFFFF.toInt(), decoded.pixelsArgb[i], "Pixel $i should be white")
        }
        for (i in 128 until 256) {
            assertEquals(0xFF000000.toInt(), decoded.pixelsArgb[i], "Pixel $i should be black")
        }
    }

    @Test
    fun testMonochromeFloydSteinbergEncodeDecode() {
        val width = 8
        val height = 8
        // Gradient from 0 to 255
        val pixels =
            IntArray(width * height) { idx ->
                val v = (idx * 255 / 63).coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }

        val encoded =
            Monochrome1BitProcessor.encode(
                argbPixels = pixels,
                width = width,
                height = height,
                mode = DitherMode.FLOYD_STEINBERG,
            )

        val decoded = Monochrome1BitProcessor.decode(encoded.wireBytes)
        assertEquals(width, decoded.width)
        assertEquals(height, decoded.height)
        assertEquals(DitherMode.FLOYD_STEINBERG, decoded.mode)
        assertEquals(64, decoded.pixelsArgb.size)

        // All output pixels must be either pure white or pure black
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        for (pixel in decoded.pixelsArgb) {
            assertTrue(pixel == white || pixel == black, "Pixel must be strictly 1-bit monochrome")
        }
    }
}
