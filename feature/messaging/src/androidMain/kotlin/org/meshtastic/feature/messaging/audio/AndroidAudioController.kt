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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.meshtastic.core.model.media.AudioDspUtils
import org.meshtastic.core.model.media.Codec2Engine
import org.meshtastic.proto.Config.LoRaConfig.ModemPreset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Production Android implementation of [AudioController]. Interfaces with hardware [AudioRecord] (24kHz / 8kHz 16-bit
 * Mono) and [AudioTrack] (speaker playback), tightly integrated with pure Kotlin [Codec2Engine] and the 20-second LoRa
 * Time-on-Air gate.
 *
 * Recording lifecycle: every press creates one self-contained [RecordingSession] that owns its own [AudioRecord], its
 * own sample buffer and its own sticky stop flag. Nothing about a session is shared with the next press, which removes
 * the old class of races where a late-initialising recorder from press N (HAL init can take hundreds of milliseconds)
 * reacted to the state, the `pendingStop` flag or the `audioRecord` field of press N+1: a lost stop, a clobbered state
 * machine, a leaked recorder, or a recording that kept running until the 30 s cap. Hardware access is serialised by
 * [hardwareMutex], so a new press waits for the previous recorder to be released instead of being silently ignored.
 */
class AndroidAudioController(
    private val context: Context,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AudioController {

    /** One press-to-release capture. Written by its own job, read by the stopper only after [done] completes. */
    private class RecordingSession {
        val stopRequested = AtomicBoolean(false)
        val discard = AtomicBoolean(false)

        @Volatile var stopRequestedAtMs: Long = 0L

        @Volatile var hardwareReady: Boolean = false
        val done = CompletableDeferred<Unit>()

        private var samples = ShortArray(INITIAL_CAPACITY)

        @Volatile
        var size: Int = 0
            private set

        fun append(buffer: ShortArray, count: Int) {
            if (size + count > samples.size) {
                samples = samples.copyOf(maxOf(samples.size * 2, size + count))
            }
            buffer.copyInto(samples, destinationOffset = size, startIndex = 0, endIndex = count)
            size += count
        }

        fun snapshot(): ShortArray = samples.copyOf(size)

        /** Sticky: even if the hardware is not ready yet, the session observes this as soon as it is. */
        fun requestStop(discardAudio: Boolean) {
            if (discardAudio) discard.set(true)
            if (stopRequested.compareAndSet(false, true)) {
                stopRequestedAtMs = SystemClock.elapsedRealtime()
            }
        }
    }

    private val sessionLock = Any()
    private var activeSession: RecordingSession? = null
    private val hardwareMutex = Mutex()

    private val _isRecording = MutableStateFlow(false)
    override val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _recordingDurationSeconds = MutableStateFlow(0f)
    override val recordingDurationSeconds: StateFlow<Float> = _recordingDurationSeconds.asStateFlow()

    @Volatile
    private var _activeSampleRate: Int = SAMPLE_RATE_24K
        set(value) {
            field = value
            AudioController.activeSampleRate = value
        }

    val activeSampleRate: Int
        get() = _activeSampleRate

    init {
        activeInstance = this
        AudioController.activeSampleRate = _activeSampleRate
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null

    @SuppressLint("MissingPermission")
    override fun startRecording(onMaxDurationReached: () -> Unit) {
        if (!hasRecordPermission()) {
            _isRecording.value = false
            return
        }

        val session = RecordingSession()
        synchronized(sessionLock) {
            val previous = activeSession
            if (previous != null && !previous.done.isCompleted && !previous.stopRequested.get()) {
                Log.d(TAG, "startRecording ignored: a recording is already in progress")
                return
            }
            // A stopping / finished-but-unconsumed session is superseded. It releases its hardware on its own.
            previous?.requestStop(discardAudio = true)
            activeSession = session
        }

        _recordingDurationSeconds.value = 0f
        _isRecording.value = true

        coroutineScope.launch(Dispatchers.IO) { hardwareMutex.withLock { runSession(session, onMaxDurationReached) } }
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens the first working (sample rate, source) combination. May block for a while; never cancellable. */
    @SuppressLint("MissingPermission")
    private fun openAudioRecord(): AudioRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val candidateSampleRates = intArrayOf(SAMPLE_RATE_24K, SAMPLE_RATE_8K)
        val candidateAudioSources =
            intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)

        for (candidateRate in candidateSampleRates) {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(candidateRate, channelConfig, audioFormat)
                if (minBufferSize <= 0) {
                    Log.w(TAG, "getMinBufferSize returned $minBufferSize for $candidateRate Hz, trying fallback")
                    continue
                }
                val bufferSize = maxOf(minBufferSize * 2, candidateRate * 2)
                for (source in candidateAudioSources) {
                    try {
                        val candidate = AudioRecord(source, candidateRate, channelConfig, audioFormat, bufferSize)
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            _activeSampleRate = candidateRate
                            Log.i(TAG, "AudioRecord ready at $candidateRate Hz, source $source, buffer $bufferSize")
                            return candidate
                        }
                        candidate.release()
                    } catch (e: Throwable) {
                        Log.w(TAG, "AudioRecord init failed (source $source, $candidateRate Hz): ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed initializing AudioRecord at $candidateRate Hz: ${e.message}")
            }
        }
        return null
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    private suspend fun runSession(session: RecordingSession, onMaxDurationReached: () -> Unit) {
        var record: AudioRecord? = null
        try {
            // Released before we even touched the hardware: nothing to do.
            if (session.stopRequested.get()) return

            record = openAudioRecord() ?: return

            // Released while the HAL was initialising: the press is over, there is no audio to keep.
            if (session.stopRequested.get()) return

            record.startRecording()
            session.hardwareReady = true

            val sampleRate = record.sampleRate.takeIf { it > 0 } ?: _activeSampleRate
            _activeSampleRate = sampleRate
            val chunkSize = if (sampleRate == SAMPLE_RATE_24K) CHUNK_SIZE_24K else CHUNK_SIZE_8K
            val chunkBuffer = ShortArray(chunkSize)

            // Discard stale pre-roll sitting in the HAL buffer so the capture starts at the physical press.
            try {
                val flushBuffer = ShortArray(chunkSize)
                while (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    if (record.read(flushBuffer, 0, flushBuffer.size, AudioRecord.READ_NON_BLOCKING) <= 0) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing pre-roll audio frames", e)
            }

            val maxSamples = MAX_RECORDING_DURATION_S * sampleRate
            var maxReached = false
            while (currentCoroutineContext().isActive) {
                if (session.stopRequested.get()) {
                    // Keep capturing for a short tail so trailing syllables still in the HAL buffer are kept.
                    val tailOver =
                        session.discard.get() ||
                            SystemClock.elapsedRealtime() - session.stopRequestedAtMs >= RECORDING_TAIL_MARGIN_MS
                    if (tailOver) break
                }
                val read = record.read(chunkBuffer, 0, chunkBuffer.size)
                if (read <= 0) break // never treat negative error codes as sample counts
                session.append(chunkBuffer, read)
                _recordingDurationSeconds.value = session.size.toFloat() / sampleRate.toFloat()
                if (session.size >= maxSamples) {
                    maxReached = true
                    break
                }
            }

            // Stop, then drain whatever is still queued in the HAL so the end of the utterance is not cut off.
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                if (!session.discard.get()) {
                    val drainBuffer = ShortArray(chunkSize)
                    while (true) {
                        val read = record.read(drainBuffer, 0, drainBuffer.size, AudioRecord.READ_NON_BLOCKING)
                        if (read <= 0) break
                        session.append(drainBuffer, read)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping/draining AudioRecord", e)
            }

            if (maxReached && !session.stopRequested.get()) {
                // Hard cap hit while the finger is still down: hand the audio over (it stays in this session).
                coroutineScope.launch(Dispatchers.Main) { onMaxDurationReached() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during audio recording: ${e.message}", e)
        } finally {
            try {
                record?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord", e)
            }
            synchronized(sessionLock) { if (activeSession === session) _isRecording.value = false }
            session.done.complete(Unit)
        }
    }

    init {
        activeInstance = this
        Codec2Jni.initialize()
        WavTokenizerOnnxRunner.initialize(context)
    }

    override fun stopRecordingPcm(): ShortArray? {
        val session =
            synchronized(sessionLock) {
                val current = activeSession
                activeSession = null
                current
            } ?: return null

        _isRecording.value = false
        session.requestStop(discardAudio = false)

        if (Looper.myLooper() == Looper.getMainLooper()) {
            Log.w(TAG, "stopRecordingPcm() was called on the main UI thread. Call it from a background dispatcher.")
        }

        // Released before the microphone was ready: the session aborts by itself and there is nothing to return.
        if (!session.hardwareReady && !session.done.isCompleted) {
            _recordingDurationSeconds.value = 0f
            return null
        }

        runBlocking { withTimeoutOrNull(STOP_WAIT_TIMEOUT_MS) { session.done.await() } }

        _recordingDurationSeconds.value = 0f
        val samples = session.snapshot()
        return samples.takeIf { it.isNotEmpty() }
    }

    override fun stopRecording(modemPreset: ModemPreset?): ByteArray? {
        val samples = stopRecordingPcm() ?: return null
        val c2Samples = if (activeSampleRate == SAMPLE_RATE_24K) AudioDspUtils.resample24kTo8k(samples) else samples
        return Codec2Engine.encode(c2Samples, modemPreset)
    }

    /** Non-blocking: discards the active capture. The session releases the microphone on its own thread. */
    override fun cancelRecording() {
        val session =
            synchronized(sessionLock) {
                val current = activeSession
                activeSession = null
                current
            }
        _isRecording.value = false
        _recordingDurationSeconds.value = 0f
        session?.requestStop(discardAudio = true)
    }

    override fun play(pcmSamples: ShortArray, onComplete: () -> Unit) {
        play(pcmSamples, sampleRate = activeSampleRate, onComplete = onComplete)
    }

    fun play(pcmSamples: ShortArray, sampleRate: Int = activeSampleRate, onComplete: () -> Unit = {}) {
        if (pcmSamples.isEmpty()) return
        stopPlayback()

        playbackJob =
            coroutineScope.launch(Dispatchers.IO) {
                _isPlaying.value = true

                val bufferSize =
                    AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                        .coerceAtLeast(pcmSamples.size * 2)

                val track =
                    AudioTrack.Builder()
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

                audioTrack = track
                try {
                    track.play()
                    track.write(pcmSamples, 0, pcmSamples.size)
                    track.stop()
                    val totalFrames = pcmSamples.size
                    while (isActive && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        if (track.playbackHeadPosition >= totalFrames) break
                        kotlinx.coroutines.delay(20L)
                    }
                    kotlinx.coroutines.delay(100L) // 100ms acoustic safety margin
                } catch (e: Exception) {
                    // Ignore audio interruption
                } finally {
                    try {
                        track.pause()
                        track.flush()
                        track.release()
                    } catch (e: Exception) {
                        // Ignore
                    }
                    audioTrack = null
                    _isPlaying.value = false
                    withContext(NonCancellable + Dispatchers.Main) { onComplete() }
                }
            }
    }

    override fun playCodec2(codec2Bytes: ByteArray, onComplete: () -> Unit) {
        if (codec2Bytes.isEmpty()) return
        val pcm = Codec2Engine.decode(codec2Bytes)
        play(pcm, sampleRate = SAMPLE_RATE_8K, onComplete = onComplete)
    }

    fun playWavTokenizer(wavTokenizerBytes: ByteArray, onComplete: () -> Unit = {}) {
        if (wavTokenizerBytes.isEmpty()) return
        val pcm = org.meshtastic.core.model.media.WavTokenizerEngine.decode(wavTokenizerBytes)
        play(pcm, sampleRate = SAMPLE_RATE_24K, onComplete = onComplete)
    }

    override fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {
            // Ignore
        } finally {
            audioTrack = null
            _isPlaying.value = false
        }
    }

    companion object {
        private const val TAG = "AndroidAudioController"
        private const val RECORDING_TAIL_MARGIN_MS = 150L
        private const val STOP_WAIT_TIMEOUT_MS = 3_000L
        private const val INITIAL_CAPACITY = 24_000 * 4

        const val MAX_RECORDING_DURATION_S = 30
        const val SAMPLE_RATE_24K = 24000
        const val SAMPLE_RATE_8K = 8000
        const val CHUNK_SIZE_24K = 960 // 40ms at 24kHz
        const val CHUNK_SIZE_8K = 320 // 40ms at 8kHz

        @Volatile
        var activeInstance: AndroidAudioController? = null
            internal set
    }
}
