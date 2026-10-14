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

import kotlin.math.ceil

/** Modes of monochrome conversion for 1-bit image rendering. */
enum class DitherMode(val id: Byte) {
    /** Strict thresholding (Y >= 128 -> white, else black). High contrast for text/schematics. */
    THRESHOLD(0x00),

    /** Floyd-Steinberg error diffusion. Preserves tones and gradients for photos/complex diagrams. */
    FLOYD_STEINBERG(0x01),
    ;

    companion object {
        fun fromId(id: Byte): DitherMode = entries.firstOrNull { it.id == id } ?: THRESHOLD
    }
}

/** Result of compressing an image into 1-bit format with container headers. */
data class CompressedImagePayload(
    val width: Int,
    val height: Int,
    val mode: DitherMode,
    val isRleCompressed: Boolean,
    val uncompressedPackedSize: Int,
    val wireBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CompressedImagePayload) return false
        return width == other.width &&
            height == other.height &&
            mode == other.mode &&
            isRleCompressed == other.isRleCompressed &&
            uncompressedPackedSize == other.uncompressedPackedSize &&
            wireBytes.contentEquals(other.wireBytes)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + mode.hashCode()
        result = 31 * result + isRleCompressed.hashCode()
        result = 31 * result + uncompressedPackedSize
        result = 31 * result + wireBytes.contentHashCode()
        return result
    }
}

/** Decompressed 1-bit image ready for rendering into UI or Bitmaps. */
data class Decoded1BitImage(val width: Int, val height: Int, val pixelsArgb: IntArray, val mode: DitherMode) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Decoded1BitImage) return false
        return width == other.width &&
            height == other.height &&
            mode == other.mode &&
            pixelsArgb.contentEquals(other.pixelsArgb)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + mode.hashCode()
        result = 31 * result + pixelsArgb.contentHashCode()
        return result
    }
}

/**
 * High-performance 1-bit Monochrome Image Processor. Preserves native resolution without downscaling, applies
 * thresholding or Floyd-Steinberg dithering, packs 8 pixels per byte, and applies PackBits RLE compression with header
 * metadata.
 */
object Monochrome1BitProcessor {

    private const val MAGIC_BYTE_0: Byte = 0x4D // 'M'
    private const val MAGIC_BYTE_1: Byte = 0x31 // '1'
    private const val HEADER_SIZE = 12 // 2 magic + 2 w + 2 h + 1 mode + 1 rleFlag + 4 uncompressedLen

    /**
     * Converts an ARGB_8888 pixel array into a compressed 1-bit wire payload.
     *
     * @param argbPixels Array of ARGB_8888 integers representing the image.
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param mode Selected dithering mode ([DitherMode.THRESHOLD] or [DitherMode.FLOYD_STEINBERG]).
     * @return [CompressedImagePayload] containing container header and compressed bytes.
     */
    fun encode(
        argbPixels: IntArray,
        width: Int,
        height: Int,
        mode: DitherMode = DitherMode.FLOYD_STEINBERG,
    ): CompressedImagePayload {
        require(width > 0 && height > 0) { "Image dimensions must be positive ($width x $height)" }
        require(argbPixels.size >= width * height) {
            "Pixel array size (${argbPixels.size}) must match or exceed width * height (${width * height})"
        }

        // 1. Convert to 1-bit binary array (boolean array where true = 1/white, false = 0/black)
        val bitGrid =
            when (mode) {
                DitherMode.THRESHOLD -> applyThreshold(argbPixels, width, height)
                DitherMode.FLOYD_STEINBERG -> applyFloydSteinberg(argbPixels, width, height)
            }

        // 2. Pack 8 pixels per byte (MSB first)
        val packedBytes = packBits(bitGrid, width * height)

        // 3. Compress using PackBits RLE
        val rleBytes = RleCompressor.compress(packedBytes)

        // If RLE expanded the data (e.g. extreme noise), fall back to raw packed bytes
        val useRle = rleBytes.size < packedBytes.size
        val payloadData = if (useRle) rleBytes else packedBytes

        // 4. Build binary container
        val wireBytes = ByteArray(HEADER_SIZE + payloadData.size)
        wireBytes[0] = MAGIC_BYTE_0
        wireBytes[1] = MAGIC_BYTE_1
        wireBytes[2] = ((width shr 8) and 0xFF).toByte()
        wireBytes[3] = (width and 0xFF).toByte()
        wireBytes[4] = ((height shr 8) and 0xFF).toByte()
        wireBytes[5] = (height and 0xFF).toByte()
        wireBytes[6] = mode.id
        wireBytes[7] = if (useRle) 0x01 else 0x00

        val uncompressedLen = packedBytes.size
        wireBytes[8] = ((uncompressedLen shr 24) and 0xFF).toByte()
        wireBytes[9] = ((uncompressedLen shr 16) and 0xFF).toByte()
        wireBytes[10] = ((uncompressedLen shr 8) and 0xFF).toByte()
        wireBytes[11] = (uncompressedLen and 0xFF).toByte()

        payloadData.copyInto(wireBytes, destinationOffset = HEADER_SIZE)

        return CompressedImagePayload(
            width = width,
            height = height,
            mode = mode,
            isRleCompressed = useRle,
            uncompressedPackedSize = uncompressedLen,
            wireBytes = wireBytes,
        )
    }

    /** Decodes wire bytes back into an ARGB_8888 pixel array. */
    fun decode(wireBytes: ByteArray): Decoded1BitImage {
        require(wireBytes.size >= HEADER_SIZE) { "Wire payload too small for header: ${wireBytes.size} bytes" }
        require(wireBytes[0] == MAGIC_BYTE_0 && wireBytes[1] == MAGIC_BYTE_1) {
            "Invalid magic bytes in 1-bit payload: [${wireBytes[0]}, ${wireBytes[1]}]"
        }

        val width = ((wireBytes[2].toInt() and 0xFF) shl 8) or (wireBytes[3].toInt() and 0xFF)
        val height = ((wireBytes[4].toInt() and 0xFF) shl 8) or (wireBytes[5].toInt() and 0xFF)
        val mode = DitherMode.fromId(wireBytes[6])
        val isRle = wireBytes[7] == 0x01.toByte()

        val uncompressedLen =
            ((wireBytes[8].toInt() and 0xFF) shl 24) or
                ((wireBytes[9].toInt() and 0xFF) shl 16) or
                ((wireBytes[10].toInt() and 0xFF) shl 8) or
                (wireBytes[11].toInt() and 0xFF)

        val payload = wireBytes.copyOfRange(HEADER_SIZE, wireBytes.size)

        val packedBytes =
            if (isRle) {
                RleCompressor.decompress(payload, expectedSize = uncompressedLen)
            } else {
                payload
            }

        val totalPixels = width * height
        val pixelsArgb = IntArray(totalPixels)
        val whiteArgb = 0xFFFFFFFF.toInt()
        val blackArgb = 0xFF000000.toInt()

        for (i in 0 until totalPixels) {
            val byteIndex = i / 8
            val bitOffset = 7 - (i % 8)
            val bit =
                if (byteIndex < packedBytes.size) {
                    (packedBytes[byteIndex].toInt() shr bitOffset) and 0x01
                } else {
                    0
                }
            pixelsArgb[i] = if (bit == 1) whiteArgb else blackArgb
        }

        return Decoded1BitImage(width = width, height = height, pixelsArgb = pixelsArgb, mode = mode)
    }

    /**
     * Converts a Decoded1BitImage into a standard 32-bit BMP (Bitmap) file ByteArray. Top-down DIB header format
     * supported natively by Coil and all image viewers.
     */
    fun toBmpByteArray(decoded: Decoded1BitImage): ByteArray {
        val width = decoded.width
        val height = decoded.height
        val rowSize = width * 4
        val imageSize = rowSize * height
        val fileSize = 54 + imageSize
        val bmp = ByteArray(fileSize)

        // 1. BMP Header (14 bytes)
        bmp[0] = 0x42 // 'B'
        bmp[1] = 0x4D // 'M'
        bmp[2] = (fileSize and 0xFF).toByte()
        bmp[3] = ((fileSize shr 8) and 0xFF).toByte()
        bmp[4] = ((fileSize shr 16) and 0xFF).toByte()
        bmp[5] = ((fileSize shr 24) and 0xFF).toByte()
        // bytes 6..9 are 0 (reserved)
        bmp[10] = 54 // Offset to pixel data
        bmp[11] = 0
        bmp[12] = 0
        bmp[13] = 0

        // 2. DIB Header (BITMAPINFOHEADER - 40 bytes)
        bmp[14] = 40 // Header size
        bmp[15] = 0
        bmp[16] = 0
        bmp[17] = 0
        // Width (signed 32-bit little-endian)
        bmp[18] = (width and 0xFF).toByte()
        bmp[19] = ((width shr 8) and 0xFF).toByte()
        bmp[20] = ((width shr 16) and 0xFF).toByte()
        bmp[21] = ((width shr 24) and 0xFF).toByte()
        // Height (negative for top-down DIB so row 0 is top row)
        val negHeight = -height
        bmp[22] = (negHeight and 0xFF).toByte()
        bmp[23] = ((negHeight shr 8) and 0xFF).toByte()
        bmp[24] = ((negHeight shr 16) and 0xFF).toByte()
        bmp[25] = ((negHeight shr 24) and 0xFF).toByte()
        // Planes = 1
        bmp[26] = 1
        bmp[27] = 0
        // Bits per pixel = 32 (ARGB)
        bmp[28] = 32
        bmp[29] = 0
        // Compression = 0 (BI_RGB)
        // Image size
        bmp[34] = (imageSize and 0xFF).toByte()
        bmp[35] = ((imageSize shr 8) and 0xFF).toByte()
        bmp[36] = ((imageSize shr 16) and 0xFF).toByte()
        bmp[37] = ((imageSize shr 24) and 0xFF).toByte()
        // X pixels per meter (2835 = 72 DPI)
        bmp[38] = 0x13
        bmp[39] = 0x0B
        bmp[40] = 0
        bmp[41] = 0
        // Y pixels per meter
        bmp[42] = 0x13
        bmp[43] = 0x0B
        bmp[44] = 0
        bmp[45] = 0

        // 3. Pixel data (32-bit BGRA)
        var offset = 54
        for (pixel in decoded.pixelsArgb) {
            bmp[offset] = (pixel and 0xFF).toByte() // Blue
            bmp[offset + 1] = ((pixel shr 8) and 0xFF).toByte() // Green
            bmp[offset + 2] = ((pixel shr 16) and 0xFF).toByte() // Red
            bmp[offset + 3] = ((pixel shr 24) and 0xFF).toByte() // Alpha
            offset += 4
        }

        return bmp
    }

    /** Strict luminance thresholding: Y >= 128 -> white, Y < 128 -> black. */
    private fun applyThreshold(argb: IntArray, width: Int, height: Int): BooleanArray {
        val total = width * height
        val result = BooleanArray(total)
        for (i in 0 until total) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            // ITU-R BT.601 integer luminance: (299*R + 587*G + 114*B) / 1000
            val y = (299 * r + 587 * g + 114 * b) / 1000
            result[i] = y >= 128
        }
        return result
    }

    /** Floyd-Steinberg error diffusion dithering: Error distribution: X 7/16 3/16 5/16 1/16 */
    private fun applyFloydSteinberg(argb: IntArray, width: Int, height: Int): BooleanArray {
        val total = width * height
        val result = BooleanArray(total)

        // Float or Int buffer for luminance with error diffusion
        // Using int scaled by 16 to avoid floating point math overhead
        val lum = IntArray(total)
        for (i in 0 until total) {
            val pixel = argb[i]
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val y = (299 * r + 587 * g + 114 * b) / 1000
            lum[i] = y shl 4 // Scaled by 16
        }

        val thresholdScaled = 128 shl 4
        val maxLumScaled = 255 shl 4

        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                val index = rowStart + x
                val currentVal = lum[index].coerceIn(0, maxLumScaled)
                val isWhite = currentVal >= thresholdScaled
                result[index] = isWhite

                val quantized = if (isWhite) maxLumScaled else 0
                val error = currentVal - quantized

                // Distribute error
                // (x + 1, y) -> 7/16
                if (x + 1 < width) {
                    lum[index + 1] += (error * 7) shr 4
                }
                // (x - 1, y + 1) -> 3/16
                if (x - 1 >= 0 && y + 1 < height) {
                    lum[index + width - 1] += (error * 3) shr 4
                }
                // (x, y + 1) -> 5/16
                if (y + 1 < height) {
                    lum[index + width] += (error * 5) shr 4
                }
                // (x + 1, y + 1) -> 1/16
                if (x + 1 < width && y + 1 < height) {
                    lum[index + width + 1] += error shr 4
                }
            }
        }
        return result
    }

    /** Packs boolean array (true = 1, false = 0) into bytes, 8 bits per byte, MSB first. */
    private fun packBits(bits: BooleanArray, totalBits: Int): ByteArray {
        val byteCount = ceil(totalBits.toDouble() / 8.0).toInt()
        val packed = ByteArray(byteCount)

        var currentByte = 0
        var bitInByte = 7
        var outIndex = 0

        for (i in 0 until totalBits) {
            if (bits[i]) {
                currentByte = currentByte or (1 shl bitInByte)
            }
            bitInByte--
            if (bitInByte < 0) {
                packed[outIndex++] = currentByte.toByte()
                currentByte = 0
                bitInByte = 7
            }
        }

        // Store remaining partial byte if any
        if (bitInByte != 7 && outIndex < byteCount) {
            packed[outIndex] = currentByte.toByte()
        }

        return packed
    }
}
