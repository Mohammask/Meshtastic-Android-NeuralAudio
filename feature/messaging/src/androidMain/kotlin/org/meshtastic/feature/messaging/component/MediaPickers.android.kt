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

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.meshtastic.core.model.media.Monochrome1BitProcessor
import org.meshtastic.core.model.media.PcmAudioUtils
import org.meshtastic.feature.messaging.audio.AndroidAudioController
import java.io.File
import java.io.FileOutputStream

private fun pcmShortsToBytes(samples: ShortArray?): ByteArray? {
    if (samples == null || samples.isEmpty()) return null
    return PcmAudioUtils.pcmShortsToBytes(samples)
}

@Composable
actual fun rememberAudioRecorderLauncher(
    isSending: Boolean,
    onAudioRecorded: (durationSeconds: Int, audioBytes: ByteArray?) -> Unit,
): AudioRecorderLauncher {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // The controller owns its own IO scope. It must NOT run on the composition scope: that scope is cancelled as soon
    // as
    // the screen leaves composition, which used to strand a live AudioRecord (and its stop) mid-capture.
    val audioController = remember { AndroidAudioController(context.applicationContext) }

    // The callbacks below are captured once by long-lived objects (the launcher, the 30 s cap callback). Always go
    // through these State holders so they never invoke a stale lambda or read a stale `isSending`.
    val currentOnAudioRecorded by rememberUpdatedState(onAudioRecorded)
    val currentIsSending by rememberUpdatedState(isSending)

    val isRecordingState by audioController.isRecording.collectAsState()
    val durationSecondsFloat by audioController.recordingDurationSeconds.collectAsState()

    var pendingStartRecording by remember { mutableStateOf(false) }

    // Guarantee the microphone is released if the screen disappears while a press is still active.
    DisposableEffect(audioController) { onDispose { audioController.cancelRecording() } }

    /** Stops the capture and reports it exactly once. `audioBytes == null` means nothing usable was captured. */
    val finishRecording: () -> Unit =
        remember(audioController, coroutineScope) {
            {
                coroutineScope.launch {
                    val bytes =
                        withContext(Dispatchers.Default) { pcmShortsToBytes(audioController.stopRecordingPcm()) }
                    val duration =
                        if (bytes != null && bytes.isNotEmpty()) {
                            (bytes.size / (audioController.activeSampleRate * 2)).coerceAtLeast(1)
                        } else {
                            0
                        }
                    currentOnAudioRecorded(duration, bytes)
                }
                Unit
            }
        }

    val beginRecording: () -> Unit =
        remember(audioController, finishRecording) {
            { audioController.startRecording(onMaxDurationReached = finishRecording) }
        }

    val permissionLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingStartRecording) {
                pendingStartRecording = false
                if (currentIsSending) return@rememberLauncherForActivityResult
                beginRecording()
            } else {
                pendingStartRecording = false
            }
        }

    // One stable object for the lifetime of the controller: it used to be rebuilt on every `isRecording` /
    // `isSending` change, so a gesture that captured the first instance kept talking to a stale snapshot.
    return remember(audioController, beginRecording, finishRecording, permissionLauncher, context) {
        object : AudioRecorderLauncher {
            override val isRecording: Boolean
                get() = isRecordingState

            override val recordingSeconds: Int
                get() = durationSecondsFloat.toInt()

            override fun startRecording() {
                if (currentIsSending) return
                val hasPermission =
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                if (hasPermission) {
                    beginRecording()
                } else {
                    pendingStartRecording = true
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            override fun stopRecording() = finishRecording()

            override fun cancelRecording() {
                pendingStartRecording = false
                audioController.cancelRecording()
            }
        }
    }
}

@Composable
actual fun rememberAudioPermissionRequester(onPermissionResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.RequestPermission()) { granted ->
            onPermissionResult(granted)
        }

    return remember(launcher, context) {
        {
            val hasPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                onPermissionResult(true)
            } else {
                launcher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }
}

@Composable
actual fun rememberVisualMediaPicker(onMediaPicked: (filePath: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val launcher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
            if (uri != null) {
                coroutineScope.launch {
                    val filePath =
                        withContext(Dispatchers.IO) {
                            try {
                                val inputStream =
                                    context.contentResolver.openInputStream(uri) ?: return@withContext null
                                val attachmentsDir = File(context.cacheDir, "attachments").apply { mkdirs() }
                                val targetFile = File(attachmentsDir, "img_${System.currentTimeMillis()}.jpg")
                                FileOutputStream(targetFile).use { out -> inputStream.copyTo(out) }
                                targetFile.absolutePath
                            } catch (e: Exception) {
                                null
                            }
                        }
                    if (filePath != null) {
                        onMediaPicked(filePath)
                    }
                }
            }
        }

    return remember(launcher) {
        { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    }
}

actual suspend fun processImageFileTo1Bit(filePath: String): ByteArray? = withContext(Dispatchers.IO) {
    try {
        val file = File(filePath)
        if (!file.exists()) return@withContext null
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()
        withContext(Dispatchers.Default) { Monochrome1BitProcessor.encode(pixels, width, height).wireBytes }
    } catch (e: Throwable) {
        null
    }
}
