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

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.meshtastic.core.model.media.Codec2Engine
import org.meshtastic.core.model.media.WavTokenizerEngine
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

actual object AudioPlayer {
    private val _isPlaying = MutableStateFlow(false)
    actual val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _activeMediaPath = MutableStateFlow<String?>(null)
    actual val activeMediaPath: StateFlow<String?> = _activeMediaPath.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var activeAudioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val playbackLock = Any()

    /**
     * Identity of the playback that is allowed to touch shared state. Every play() and stop() bumps it. A previous
     * playback job that is still unwinding on the IO thread (blocked in a write, or draining) sees a changed value and
     * stops writing / stops flipping [_isPlaying] for the playback that replaced it. A single shared "stopped" flag
     * could be reset by the new playback while the old job was still alive, letting two jobs play at once.
     */
    private val generation = AtomicInteger(0)

    private fun isCurrent(myGen: Int): Boolean = generation.get() == myGen

    private fun buildAudioTrack(sampleRate: Int, bufferSize: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(bufferSize)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

    actual fun play(mediaPath: String?, onComplete: () -> Unit) {
        if (mediaPath.isNullOrBlank()) {
            onComplete()
            return
        }

        val file = File(mediaPath)
        if (!file.exists() || file.length() == 0L) {
            onComplete()
            return
        }

        stop()

        synchronized(playbackLock) {
            val myGen = generation.incrementAndGet()
            _activeMediaPath.value = mediaPath

            playbackJob =
                scope.launch(Dispatchers.IO) {
                    var track: AudioTrack? = null
                    try {
                        val bytes = file.readBytes()
                        val isWav = mediaPath.endsWith(".wav")
                        val isWavTokenizer = mediaPath.endsWith(".wt")

                        val (rawPcmSamples: ShortArray, sampleRate: Int) =
                            when {
                                isWav -> {
                                    if (bytes.size >= 44) {
                                        val sRate =
                                            (bytes[24].toInt() and 0xFF) or
                                                ((bytes[25].toInt() and 0xFF) shl 8) or
                                                ((bytes[26].toInt() and 0xFF) shl 16) or
                                                ((bytes[27].toInt() and 0xFF) shl 24)
                                        val sampleCount = (bytes.size - 44) / 2
                                        val shorts = ShortArray(sampleCount)
                                        for (i in 0 until sampleCount) {
                                            val b0 = bytes[44 + i * 2].toInt() and 0xFF
                                            val b1 = bytes[44 + i * 2 + 1].toInt()
                                            shorts[i] = ((b1 shl 8) or b0).toShort()
                                        }
                                        shorts to
                                            (
                                                if (sRate == 8000 || sRate <= 0) {
                                                    WavTokenizerEngine.SAMPLE_RATE_24K
                                                } else {
                                                    sRate
                                                }
                                                )
                                    } else {
                                        ShortArray(0) to WavTokenizerEngine.SAMPLE_RATE_24K
                                    }
                                }

                                isWavTokenizer -> {
                                    WavTokenizerEngine.decode(bytes) to WavTokenizerEngine.SAMPLE_RATE_24K
                                }

                                else -> {
                                    Codec2Engine.decode(bytes) to Codec2Engine.SAMPLE_RATE
                                }
                            }

                        val pcmSamples = rawPcmSamples

                        if (pcmSamples.isNotEmpty() && isActive && isCurrent(myGen)) {
                            val minBufferSize =
                                AudioTrack.getMinBufferSize(
                                    sampleRate,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                )
                            val bufferSize = (minBufferSize * 4).coerceAtLeast(4096)

                            val currentTrack = buildAudioTrack(sampleRate, bufferSize)
                            if (currentTrack.state != AudioTrack.STATE_INITIALIZED) {
                                try {
                                    currentTrack.release()
                                } catch (ignored: Exception) {}
                                return@launch
                            }

                            track = currentTrack
                            synchronized(playbackLock) {
                                if (!isCurrent(myGen)) {
                                    try {
                                        currentTrack.release()
                                    } catch (ignored: Exception) {}
                                    return@launch
                                }
                                activeAudioTrack = currentTrack
                                _isPlaying.value = true
                            }

                            currentTrack.play()

                            // Stream audio in standard buffered chunks using blocking writes
                            val chunkSize = minBufferSize.coerceAtLeast(1024)
                            var offset = 0
                            while (offset < pcmSamples.size && isActive && isCurrent(myGen)) {
                                val toWrite = (pcmSamples.size - offset).coerceAtMost(chunkSize)
                                val written = currentTrack.write(pcmSamples, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                                if (written <= 0) break
                                offset += written
                            }

                            // Graceful buffer drain: stop accepting new writes and allow hardware to play out remaining
                            // buffer
                            if (isActive && isCurrent(myGen)) {
                                try {
                                    currentTrack.stop()
                                    val totalFrames = pcmSamples.size
                                    while (
                                        isActive &&
                                        isCurrent(myGen) &&
                                        currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING
                                    ) {
                                        if (currentTrack.playbackHeadPosition >= totalFrames) break
                                        delay(25L)
                                    }
                                } catch (ignored: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        // Interrupted or cancelled
                    } finally {
                        synchronized(playbackLock) {
                            track?.let { t ->
                                try {
                                    t.pause()
                                    t.flush()
                                    t.release()
                                } catch (ignored: Exception) {}
                            }
                            if (activeAudioTrack == track) {
                                activeAudioTrack = null
                            }
                            if (isCurrent(myGen)) {
                                _isPlaying.value = false
                                _activeMediaPath.value = null
                            }
                        }
                        withContext(NonCancellable + Dispatchers.Main) { onComplete() }
                    }
                }
        }
    }

    actual fun playRawAudio(
        audioBytes: ByteArray,
        isWavTokenizer: Boolean,
        onPlaybackStarting: () -> Unit,
        onComplete: () -> Unit,
    ) {
        if (audioBytes.isEmpty()) {
            onComplete()
            return
        }

        stop()

        synchronized(playbackLock) {
            val myGen = generation.incrementAndGet()

            playbackJob =
                scope.launch(Dispatchers.IO) {
                    var track: AudioTrack? = null
                    try {
                        val (rawPcmSamples: ShortArray, sampleRate: Int) =
                            if (isWavTokenizer) {
                                val wtDecoded = runCatching { WavTokenizerEngine.decode(audioBytes) }.getOrNull()
                                if (wtDecoded != null && wtDecoded.isNotEmpty()) {
                                    wtDecoded to WavTokenizerEngine.SAMPLE_RATE_24K
                                } else {
                                    val c2Decoded = runCatching { Codec2Engine.decode(audioBytes) }.getOrNull()
                                    if (c2Decoded != null && c2Decoded.isNotEmpty()) {
                                        c2Decoded to Codec2Engine.SAMPLE_RATE
                                    } else {
                                        ShortArray(0) to WavTokenizerEngine.SAMPLE_RATE_24K
                                    }
                                }
                            } else {
                                val c2Decoded = runCatching { Codec2Engine.decode(audioBytes) }.getOrNull()
                                if (c2Decoded != null && c2Decoded.isNotEmpty()) {
                                    c2Decoded to Codec2Engine.SAMPLE_RATE
                                } else {
                                    val wtDecoded = runCatching { WavTokenizerEngine.decode(audioBytes) }.getOrNull()
                                    if (wtDecoded != null && wtDecoded.isNotEmpty()) {
                                        wtDecoded to WavTokenizerEngine.SAMPLE_RATE_24K
                                    } else {
                                        ShortArray(0) to Codec2Engine.SAMPLE_RATE
                                    }
                                }
                            }
                        val pcmSamples = rawPcmSamples

                        if (pcmSamples.isNotEmpty() && isActive && isCurrent(myGen)) {
                            val minBufferSize =
                                AudioTrack.getMinBufferSize(
                                    sampleRate,
                                    AudioFormat.CHANNEL_OUT_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT,
                                )
                            val bufferSize = (minBufferSize * 4).coerceAtLeast(4096)

                            val currentTrack = buildAudioTrack(sampleRate, bufferSize)
                            if (currentTrack.state != AudioTrack.STATE_INITIALIZED) {
                                try {
                                    currentTrack.release()
                                } catch (ignored: Exception) {}
                                return@launch
                            }

                            track = currentTrack
                            synchronized(playbackLock) {
                                if (!isCurrent(myGen)) {
                                    try {
                                        currentTrack.release()
                                    } catch (ignored: Exception) {}
                                    return@launch
                                }
                                activeAudioTrack = currentTrack
                                _isPlaying.value = true
                            }

                            withContext(Dispatchers.Main) { onPlaybackStarting() }
                            currentTrack.play()

                            val chunkSize = minBufferSize.coerceAtLeast(1024)
                            var offset = 0
                            while (offset < pcmSamples.size && isActive && isCurrent(myGen)) {
                                val toWrite = (pcmSamples.size - offset).coerceAtMost(chunkSize)
                                val written = currentTrack.write(pcmSamples, offset, toWrite, AudioTrack.WRITE_BLOCKING)
                                if (written <= 0) break
                                offset += written
                            }

                            // Hardware buffer drain lock: wait until HAL buffer plays out completely plus safety margin
                            if (isActive && isCurrent(myGen)) {
                                try {
                                    val totalFrames = rawPcmSamples.size
                                    val drainTimeoutMs = (totalFrames * 1000L / sampleRate) + 500L
                                    val drainStart = System.currentTimeMillis()
                                    while (
                                        isActive && isCurrent(myGen) && currentTrack.playbackHeadPosition < totalFrames
                                    ) {
                                        if (System.currentTimeMillis() - drainStart > drainTimeoutMs) break
                                        delay(20L)
                                    }
                                    delay(ACOUSTIC_TAIL_MS) // Acoustic safety margin (HAL/route latency)
                                    currentTrack.stop()
                                    currentTrack.release()
                                } catch (ignored: Exception) {}
                            }
                        }
                    } catch (e: Exception) {
                        // Interrupted or cancelled
                    } finally {
                        synchronized(playbackLock) {
                            track?.let { t ->
                                try {
                                    t.pause()
                                    t.flush()
                                    t.release()
                                } catch (ignored: Exception) {}
                            }
                            if (activeAudioTrack == track) {
                                activeAudioTrack = null
                            }
                            if (isCurrent(myGen)) _isPlaying.value = false
                        }
                        withContext(NonCancellable + Dispatchers.Main) { onComplete() }
                    }
                }
        }
    }

    private const val ACOUSTIC_TAIL_MS = 250L

    actual fun stop() {
        synchronized(playbackLock) {
            generation.incrementAndGet()
            playbackJob?.cancel()
            playbackJob = null
            activeAudioTrack?.let { track ->
                try {
                    track.pause()
                    track.flush()
                    track.release()
                } catch (ignored: Exception) {}
            }
            activeAudioTrack = null
            _isPlaying.value = false
            _activeMediaPath.value = null
        }
    }
}
