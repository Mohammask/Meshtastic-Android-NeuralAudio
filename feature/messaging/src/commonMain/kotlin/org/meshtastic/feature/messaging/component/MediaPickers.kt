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

/** Controller for managing hardware microphone capture and Codec 2 encoding. */
interface AudioRecorderLauncher {
    val isRecording: Boolean
    val recordingSeconds: Int

    fun startRecording()

    fun stopRecording()

    fun cancelRecording()
}

/**
 * Multiplatform composable that wires hardware microphone recording, requests necessary permissions (e.g.
 * android.permission.RECORD_AUDIO), and provides recorded Codec 2 audio bytes on completion.
 */
@Composable
expect fun rememberAudioRecorderLauncher(
    isSending: Boolean = false,
    onAudioRecorded: (durationSeconds: Int, audioBytes: ByteArray?) -> Unit,
): AudioRecorderLauncher

/**
 * Multiplatform composable that triggers visual media selection (e.g. Android Photo Picker) and returns the cached
 * local file path of the chosen image.
 */
@Composable expect fun rememberVisualMediaPicker(onMediaPicked: (filePath: String) -> Unit): () -> Unit

/**
 * Multiplatform composable to request microphone audio permission. Invoking the returned lambda checks permission and
 * triggers system dialog if needed, invoking [onPermissionResult] with true if granted.
 */
@Composable expect fun rememberAudioPermissionRequester(onPermissionResult: (granted: Boolean) -> Unit): () -> Unit

/**
 * Reads the image file at [filePath] on IO, extracts ARGB pixels, and applies 1-bit Floyd-Steinberg dithering and
 * lossless RLE compression on Default dispatcher.
 */
expect suspend fun processImageFileTo1Bit(filePath: String): ByteArray?
