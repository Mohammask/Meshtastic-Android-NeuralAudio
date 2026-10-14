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
package org.meshtastic.feature.messaging.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual object AudioPlayer {
    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _activeMediaPath = MutableStateFlow<String?>(null)
    actual val activeMediaPath: StateFlow<String?> = _activeMediaPath.asStateFlow()

    actual fun play(mediaPath: String?, onComplete: () -> Unit) {
        _isPlaying.value = true
        _activeMediaPath.value = mediaPath
        // No-op simulation on desktop
        _isPlaying.value = false
        _activeMediaPath.value = null
        onComplete()
    }

    actual fun playRawAudio(
        audioBytes: ByteArray,
        isWavTokenizer: Boolean,
        onPlaybackStarting: () -> Unit,
        onComplete: () -> Unit,
    ) {
        onPlaybackStarting()
        _isPlaying.value = true
        _isPlaying.value = false
        onComplete()
    }

    actual fun stop() {
        _isPlaying.value = false
        _activeMediaPath.value = null
    }
}
