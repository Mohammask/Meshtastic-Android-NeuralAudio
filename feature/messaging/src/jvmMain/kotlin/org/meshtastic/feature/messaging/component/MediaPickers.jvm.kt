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
package org.meshtastic.feature.messaging.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.meshtastic.core.model.media.Monochrome1BitProcessor
import java.io.File
import javax.imageio.ImageIO

@Composable
actual fun rememberAudioRecorderLauncher(
    isSending: Boolean,
    onAudioRecorded: (durationSeconds: Int, audioBytes: ByteArray?) -> Unit,
): AudioRecorderLauncher = remember(isSending) {
    object : AudioRecorderLauncher {
        override val isRecording: Boolean = false
        override val recordingSeconds: Int = 0

        override fun startRecording() {}

        override fun stopRecording() {
            onAudioRecorded(15, null)
        }

        override fun cancelRecording() {}
    }
}

@Composable
actual fun rememberAudioPermissionRequester(onPermissionResult: (granted: Boolean) -> Unit): () -> Unit = {
    onPermissionResult(true)
}

@Composable
actual fun rememberVisualMediaPicker(onMediaPicked: (filePath: String) -> Unit): () -> Unit = remember {
    {
        // Desktop mock picker
    }
}

actual suspend fun processImageFileTo1Bit(filePath: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val file = File(filePath)
        if (!file.exists()) return@withContext null
        val img = ImageIO.read(file) ?: return@withContext null
        val width = img.width
        val height = img.height
        val pixels = IntArray(width * height)
        img.getRGB(0, 0, width, height, pixels, 0, width)
        withContext(Dispatchers.Default) { Monochrome1BitProcessor.encode(pixels, width, height).wireBytes }
    } catch (e: Throwable) {
        null
    }
}
