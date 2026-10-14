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

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.meshtastic.core.common.ContextServices
import org.meshtastic.core.model.media.AudioDspUtils
import org.meshtastic.core.model.media.WavTokenizerBridge
import org.meshtastic.core.model.media.WavTokenizerEngine
import java.io.File
import java.nio.LongBuffer
import kotlin.math.roundToInt

/**
 * On-demand lazy loader and memory manager for the WavTokenizer ONNX runtime.
 *
 * Calibrated specifically for the official 75 token/sec model:
 * `onnx-community/WavTokenizer-large-speech-75token_decode` (model_int8.onnx).
 * - Native audio output rate: 24,000 Hz Float32 PCM.
 * - Samples per token at 24 kHz: 320 samples (24000 / 75).
 * - Automatic downsampling from 24 kHz to 8 kHz for LoRa mesh playback.
 * - Environment and Session are strictly lazy and completely closed when idle to preserve mobile RAM.
 */
object WavTokenizerOnnxRunner : WavTokenizerBridge {

    private const val TAG = "WavTokenizerOnnx"

    private const val MIN_ENCODER_SIZE = 15_000_000L
    private const val MIN_DECODER_SIZE = 50_000_000L

    private var environment: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var encoderSession: OrtSession? = null
    private val sessionLock = Any()
    private var appContext: Context? = null

    var lastError: String? = null
        private set

    override fun lastErrorMessage(): String? = lastError

    private fun getContext(): Context? = appContext ?: ContextServices.app

    fun initialize(context: Context) {
        appContext = context.applicationContext
        WavTokenizerEngine.bridge = this
        Log.i(TAG, "Initialized WavTokenizerOnnxRunner and hooked as WavTokenizerEngine bridge.")

        // Asynchronously pre-extract neural models in background on app startup
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureModelsExtracted()
            } catch (t: Throwable) {
                Log.w(TAG, "Background model pre-extraction notice: ${t.message}")
            }
        }
    }

    /** Ensures both encoder and decoder models are extracted and ready on disk. */
    fun ensureModelsExtracted(): Boolean {
        val enc = getEncoderFile()
        val dec = getModelFile()
        val ready = enc != null && dec != null
        if (!ready) {
            lastError = "Model files missing or extraction incomplete (enc=${enc != null}, dec=${dec != null})"
        }
        return ready
    }

    /** Initializes or returns the cached ONNX decoder session on [Dispatchers.IO]. */
    suspend fun getOrCreateSession(): OrtSession? = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            if (session != null) {
                return@withContext session
            }

            try {
                if (environment == null) {
                    environment = OrtEnvironment.getEnvironment()
                }

                val modelFile = getModelFile()
                if (modelFile != null && modelFile.exists() && modelFile.length() >= MIN_DECODER_SIZE) {
                    val opts = OrtSession.SessionOptions()
                    opts.setIntraOpNumThreads(2)
                    session = environment?.createSession(modelFile.absolutePath, opts)
                    Log.i(
                        TAG,
                        "Successfully initialized WavTokenizer ONNX session from ${modelFile.absolutePath} (${modelFile.length()} bytes)",
                    )
                } else {
                    lastError =
                        "Decoder file missing or incomplete: ${modelFile?.absolutePath} (${modelFile?.length()} bytes)"
                    Log.w(TAG, lastError!!)
                }
                session
            } catch (e: Throwable) {
                lastError = "[Decoder Init] ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, lastError!!, e)
                null
            }
        }
    }

    /** Initializes or returns the cached ONNX encoder session on [Dispatchers.IO]. */
    suspend fun getOrCreateEncoderSession(): OrtSession? = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            if (encoderSession != null) {
                return@withContext encoderSession
            }

            try {
                if (environment == null) {
                    environment = OrtEnvironment.getEnvironment()
                }

                val encoderFile = getEncoderFile()
                if (encoderFile != null && encoderFile.exists() && encoderFile.length() >= MIN_ENCODER_SIZE) {
                    val opts = OrtSession.SessionOptions()
                    opts.setIntraOpNumThreads(2)
                    encoderSession = environment?.createSession(encoderFile.absolutePath, opts)
                    Log.i(
                        TAG,
                        "Successfully initialized WavTokenizer ONNX encoder session from ${encoderFile.absolutePath} (${encoderFile.length()} bytes)",
                    )
                } else {
                    lastError =
                        "Encoder file missing or incomplete: ${encoderFile?.absolutePath} (${encoderFile?.length()} bytes)"
                    Log.w(TAG, lastError!!)
                }
                encoderSession
            } catch (e: Throwable) {
                lastError = "[Encoder Init] ${e.javaClass.simpleName}: ${e.message}"
                Log.e(TAG, lastError!!, e)
                null
            }
        }
    }

    /**
     * Extracts an asset from the installed APK directly via ZipFile (zero-copy native speed) with fallback to
     * AssetManager and Download folder.
     */
    private fun extractAssetOrApkEntry(ctx: Context, assetPath: String, targetFile: File, minSize: Long): Boolean {
        if (targetFile.exists() && targetFile.length() < minSize) {
            Log.w(
                TAG,
                "Existing '${targetFile.name}' is outdated or incomplete (${targetFile.length()} < $minSize), purging...",
            )
            targetFile.delete()
        }

        targetFile.parentFile?.mkdirs()
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        if (partFile.exists()) partFile.delete()

        var extracted = false

        // 1. Direct extraction from installed APK via ZipFile
        // Completely bypasses Android AssetManager 32-bit and memory limits
        try {
            val apkPath = ctx.applicationInfo.sourceDir
            if (!apkPath.isNullOrBlank()) {
                val apkFile = File(apkPath)
                if (apkFile.exists()) {
                    java.util.zip.ZipFile(apkFile).use { zip ->
                        val candidateEntryNames =
                            listOf(
                                "assets/$assetPath",
                                assetPath,
                                "assets/${File(assetPath).name}",
                                File(assetPath).name,
                            )
                        for (entryName in candidateEntryNames) {
                            val entry = zip.getEntry(entryName)
                            if (entry != null && entry.size >= minSize) {
                                if (targetFile.exists() && targetFile.length() == entry.size) {
                                    Log.i(
                                        TAG,
                                        "'$assetPath' already up-to-date in filesDir (${targetFile.length()} bytes)",
                                    )
                                    return true
                                }
                                zip.getInputStream(entry).use { input ->
                                    partFile.outputStream().use { output ->
                                        val buffer = ByteArray(65536)
                                        var read: Int
                                        while (input.read(buffer).also { read = it } != -1) {
                                            output.write(buffer, 0, read)
                                        }
                                        output.flush()
                                        (output as? java.io.FileOutputStream)?.fd?.sync()
                                    }
                                }
                                if (partFile.exists() && partFile.length() >= minSize) {
                                    extracted = true
                                    Log.i(
                                        TAG,
                                        "Extracted '$assetPath' directly from APK ZipFile (${partFile.length()} bytes)",
                                    )
                                    break
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "ZipFile extraction from APK failed for '$assetPath': ${e.message}")
        }

        if (targetFile.exists() && targetFile.length() >= minSize && !extracted) {
            return true
        }

        // 2. Fallback: Context assets streaming with 64KB chunk buffer
        if (!extracted) {
            val candidateAssetNames = listOf(assetPath, File(assetPath).name)
            for (name in candidateAssetNames) {
                try {
                    ctx.assets.open(name).use { input ->
                        partFile.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var read: Int
                            while (input.read(buffer).also { read = it } != -1) {
                                output.write(buffer, 0, read)
                            }
                            output.flush()
                            (output as? java.io.FileOutputStream)?.fd?.sync()
                        }
                    }
                    if (partFile.exists() && partFile.length() >= minSize) {
                        extracted = true
                        Log.i(TAG, "Streamed asset '$name' to filesDir (${partFile.length()} bytes)")
                        break
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "AssetManager streaming failed for '$name': ${e.message}")
                }
            }
        }

        // 3. Fallback: Check /sdcard/Download or external storage
        if (!extracted) {
            val downloadDir = File("/sdcard/Download")
            val candidateFile = File(downloadDir, File(assetPath).name)
            if (candidateFile.exists() && candidateFile.length() >= minSize) {
                try {
                    candidateFile.inputStream().use { input ->
                        partFile.outputStream().use { output ->
                            input.copyTo(output, 65536)
                            output.flush()
                            (output as? java.io.FileOutputStream)?.fd?.sync()
                        }
                    }
                    if (partFile.exists() && partFile.length() >= minSize) {
                        extracted = true
                        Log.i(TAG, "Found in Download: ${candidateFile.absolutePath}")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Download copy failed: ${e.message}")
                }
            }
        }

        // 4. Safely commit partFile to targetFile with byte-copy fallback if renameTo fails
        if (extracted && partFile.exists() && partFile.length() >= minSize) {
            if (targetFile.exists()) targetFile.delete()
            if (partFile.renameTo(targetFile) && targetFile.exists() && targetFile.length() >= minSize) {
                Log.i(TAG, "Successfully committed ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                return true
            }
            try {
                partFile.inputStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output, 65536)
                        output.flush()
                        (output as? java.io.FileOutputStream)?.fd?.sync()
                    }
                }
                partFile.delete()
                if (targetFile.exists() && targetFile.length() >= minSize) {
                    Log.i(TAG, "Committed via byte-copy: ${targetFile.absolutePath} (${targetFile.length()} bytes)")
                    return true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed committing target file ${targetFile.absolutePath}: ${e.message}", e)
            }
        }

        if (partFile.exists()) partFile.delete()
        return false
    }

    /** Locates or extracts the encoder model file (>= 19MB). */
    private fun getEncoderFile(): File? {
        val ctx = getContext() ?: return null
        val targetFile = File(ctx.filesDir, "ai-model/encoder_int8.onnx")
        val success = extractAssetOrApkEntry(ctx, "ai-model/encoder_int8.onnx", targetFile, MIN_ENCODER_SIZE)
        return if (success && targetFile.exists() && targetFile.length() >= MIN_ENCODER_SIZE) {
            targetFile
        } else if (targetFile.exists() && targetFile.length() >= MIN_ENCODER_SIZE) {
            targetFile
        } else {
            null
        }
    }

    /** Locates or extracts the decoder model file (>= 50MB). */
    private fun getModelFile(): File? {
        val ctx = getContext() ?: return null
        val targetFile = File(ctx.filesDir, "ai-model/model_int8.onnx")
        val success = extractAssetOrApkEntry(ctx, "ai-model/model_int8.onnx", targetFile, MIN_DECODER_SIZE)
        return if (success && targetFile.exists() && targetFile.length() >= MIN_DECODER_SIZE) {
            targetFile
        } else if (targetFile.exists() && targetFile.length() >= MIN_DECODER_SIZE) {
            targetFile
        } else {
            null
        }
    }

    /**
     * Decodes a compressed WavTokenizer bitstream (12-bit packed tokens) using the ONNX decoder.
     *
     * @param compressedBytes The 12-bit packed token stream.
     * @return Decoded 8 kHz 16-bit PCM ShortArray, or null if model is unavailable.
     */
    suspend fun decodeInternal(compressedBytes: ByteArray): ShortArray? = withContext(Dispatchers.IO) {
        if (compressedBytes.isEmpty()) return@withContext ShortArray(0)

        val activeSession = getOrCreateSession() ?: return@withContext null
        val env = environment ?: return@withContext null

        try {
            // Unpack 12-bit tokens (75 tokens/sec)
            val tokens = WavTokenizerEngine.unpack12Bit(compressedBytes, isLittleEndian = false)
            if (tokens.isEmpty()) return@withContext ShortArray(0)

            val inputName = activeSession.inputNames.firstOrNull() ?: "codes"
            val inputInfo = activeSession.inputInfo[inputName]?.info as? TensorInfo

            // WavTokenizer decoder expects 2D tensor [batch_size, sequence_length]
            val shape =
                when (inputInfo?.shape?.size) {
                    2 -> longArrayOf(1, tokens.size.toLong())
                    3 -> longArrayOf(1, 1, tokens.size.toLong())
                    else -> longArrayOf(1, tokens.size.toLong())
                }

            val longBuffer = LongBuffer.allocate(tokens.size)
            for (t in tokens) {
                longBuffer.put(t.toLong())
            }
            longBuffer.flip()

            val inputTensor = OnnxTensor.createTensor(env, longBuffer, shape)
            val output = activeSession.run(mapOf(inputName to inputTensor))

            val outputValue = output[0]?.value
            inputTensor.close()
            output.close()

            val floats24k: FloatArray =
                when (outputValue) {
                    is Array<*> -> {
                        val first = outputValue.firstOrNull()
                        when (first) {
                            is Array<*> -> (first.firstOrNull() as? FloatArray) ?: FloatArray(0)
                            is FloatArray -> first
                            else -> FloatArray(0)
                        }
                    }

                    is FloatArray -> outputValue

                    else -> FloatArray(0)
                }

            if (floats24k.isEmpty()) {
                Log.w(TAG, "ONNX model produced empty output")
                return@withContext null
            }

            // Prepare 24 kHz Float32 output -> 24 kHz 16-bit PCM ShortArray for crystal-clear natural speech
            // playback
            return@withContext prepareOutputFromOnnx(floats24k, targetSampleRate = 24000)
        } catch (e: Throwable) {
            lastError = "[Decode] ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        }
    }

    /**
     * Prepares 16-bit PCM for ONNX inference:
     * 1. Converts 16-bit PCM ShortArray to Float32 [-1.0f, 1.0f] using pure linear scaling (no AGC or DRC).
     * 2. If incoming PCM is already 24000 Hz, feeds it directly into [AudioDspUtils.normalizePeak] without running
     *    [AudioDspUtils.resample8kTo24k].
     * 3. If incoming PCM is 8000 Hz (hardware fallback), applies [AudioDspUtils.resample8kTo24k] before normalization.
     * 4. Applies linear peak normalization via [AudioDspUtils.normalizePeak] (target peak 0.90f).
     */
    fun prepareInputForOnnx(pcmSamples: ShortArray, inputSampleRate: Int = 24000): FloatArray {
        if (pcmSamples.isEmpty()) return FloatArray(0)
        val floats = FloatArray(pcmSamples.size) { pcmSamples[it].toFloat() / 32768.0f }
        val audio24k =
            if (inputSampleRate == 24000) {
                floats
            } else {
                AudioDspUtils.resample8kTo24k(floats)
            }
        return AudioDspUtils.normalizePeak(audio24k, targetPeak = 0.90f)
    }

    /**
     * Processes raw Float32 output from the ONNX decoder (at 24 kHz). By default, preserves the native 24 kHz wideband
     * neural audio and applies utterance-level peak scaling to strictly eliminate digital clipping before converting to
     * 16-bit PCM ShortArray.
     */
    fun prepareOutputFromOnnx(floats24k: FloatArray, targetSampleRate: Int = 24000): ShortArray {
        if (floats24k.isEmpty()) return ShortArray(0)

        // 1. Measure peak: neural vocoders can slightly overshoot 1.0f on loud formants.
        // Scale down smoothly if peak > 1.0f to guarantee 0% clipping distortion.
        var maxAbs = 0f
        for (sample in floats24k) {
            val a = kotlin.math.abs(sample)
            if (a > maxAbs) maxAbs = a
        }
        val scale = if (maxAbs > 1.0f) 0.95f / maxAbs else 1.0f

        // 2. Resample if 8 kHz requested, otherwise keep pristine 24 kHz native rate
        val processedFloats =
            if (targetSampleRate == 8000) {
                AudioDspUtils.resample24kTo8k(floats24k)
            } else {
                floats24k
            }

        val shorts = ShortArray(processedFloats.size)
        for (i in processedFloats.indices) {
            val sample = processedFloats[i] * scale
            val clamped = if (sample.isNaN() || sample.isInfinite()) 0f else sample.coerceIn(-1.0f, 1.0f)
            shorts[i] = (clamped * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return shorts
    }

    /** Linearly normalizes 16-bit PCM Short samples strictly into Float32 in [-1.0f, 1.0f] for ONNX inference. */
    fun normalizePcmToFloat32(shorts: ShortArray): FloatArray {
        val floats = FloatArray(shorts.size)
        for (i in shorts.indices) {
            floats[i] = (shorts[i].toFloat() / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        return floats
    }

    /** Denormalizes Float32 samples in [-1.0f, 1.0f] back to 16-bit PCM Short samples. */
    fun denormalizeFloat32ToPcm(floats: FloatArray): ShortArray {
        val shorts = ShortArray(floats.size)
        for (i in floats.indices) {
            shorts[i] = (floats[i] * 32767.0f).roundToInt().coerceIn(-32768, 32767).toShort()
        }
        return shorts
    }

    /** Recursively extracts integer token values from multidimensional tensor outputs or native buffers. */
    private fun extractTokensRecursively(value: Any?, out: MutableList<Int>) {
        when (value) {
            is LongArray -> for (v in value) out.add(v.toInt())

            is IntArray -> for (v in value) out.add(v)

            is Array<*> -> for (item in value) extractTokensRecursively(item, out)

            is LongBuffer -> {
                val dup = value.duplicate()
                while (dup.hasRemaining()) out.add(dup.get().toInt())
            }

            is java.nio.IntBuffer -> {
                val dup = value.duplicate()
                while (dup.hasRemaining()) out.add(dup.get())
            }

            is Number -> out.add(value.toInt())
        }
    }

    /**
     * Encodes 16-bit PCM audio into 12-bit packed WavTokenizer discrete tokens using the neural ONNX encoder. Supports
     * native 24 kHz speech input as well as 8 kHz fallback input.
     */
    suspend fun encodeInternal(
        pcmSamples: ShortArray,
        sampleRate: Int = AndroidAudioController.activeInstance?.activeSampleRate ?: 24000,
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (pcmSamples.isEmpty()) return@withContext ByteArray(0)

        val activeEncoder = getOrCreateEncoderSession() ?: return@withContext null
        val env = environment ?: return@withContext null

        try {
            // 1. Prepare 24kHz audio:
            // a) If incoming PCM is already 24000 Hz, feed directly into normalizePeak without resample8kTo24k
            // b) If incoming PCM is 8000 Hz (due to hardware fallback), apply resample8kTo24k before normalization
            val audio24k = prepareInputForOnnx(pcmSamples, inputSampleRate = sampleRate)
            if (audio24k.isEmpty()) return@withContext ByteArray(0)

            // 2. SEANet downsampling requires:
            // - Exact multiple of 320 samples (1 token = 320 samples at 24 kHz)
            // - Minimum audio length >= 1280 samples (at least 4 tokens) so the 4 downsampling stages
            //   with reflection padding never encounter an inner latent dimension < 2.
            val minSamples = 1600 // 5 tokens = 66ms, safely above the 1280 threshold
            val targetSamples =
                maxOf(
                    minSamples,
                    if (audio24k.size % 320 == 0) audio24k.size else audio24k.size + (320 - audio24k.size % 320),
                )
            val paddedAudio =
                if (audio24k.size < targetSamples) {
                    val padded = FloatArray(targetSamples)
                    System.arraycopy(audio24k, 0, padded, 0, audio24k.size)
                    padded
                } else {
                    audio24k
                }

            // 3. Apply linear peak normalization immediately prior to feeding input Tensor to the ONNX encoder
            val normalizedAudio = AudioDspUtils.normalizePeak(paddedAudio, targetPeak = 0.90f)

            // 4. Create tensor [1, 1, num_samples]
            val shape = longArrayOf(1, 1, normalizedAudio.size.toLong())
            val floatBuffer = java.nio.FloatBuffer.wrap(normalizedAudio)
            val inputTensor = OnnxTensor.createTensor(env, floatBuffer, shape)

            val inputName = activeEncoder.inputNames.firstOrNull() ?: "audio"
            val output = activeEncoder.run(mapOf(inputName to inputTensor))
            val outputValue = output[0]?.value
            inputTensor.close()
            output.close()

            // 4. Extract token indices (Int64)
            val tokensList = mutableListOf<Int>()
            extractTokensRecursively(outputValue, tokensList)

            if (tokensList.isEmpty()) {
                Log.w(TAG, "ONNX encoder produced empty tokens")
                return@withContext null
            }

            // 5. Pack into standard 12-bit bitstream
            val tokenArray = tokensList.toIntArray()
            return@withContext WavTokenizerEngine.pack12Bit(tokenArray, isLittleEndian = false)
        } catch (e: Throwable) {
            lastError = "[Encode] ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError!!, e)
            null
        }
    }

    /** Releases all ONNX sessions and environments immediately to prevent memory leaks and high heap usage. */
    suspend fun releaseSession() = withContext(Dispatchers.IO) {
        synchronized(sessionLock) {
            try {
                session?.close()
            } catch (ignored: Exception) {}
            session = null

            try {
                encoderSession?.close()
            } catch (ignored: Exception) {}
            encoderSession = null

            try {
                environment?.close()
            } catch (ignored: Exception) {}
            environment = null
        }
    }

    override fun isAvailable(): Boolean {
        if (session != null && encoderSession != null) return true
        val ctx = getContext() ?: return false
        val encoderReady =
            File(ctx.filesDir, "ai-model/encoder_int8.onnx").let { it.exists() && it.length() >= MIN_ENCODER_SIZE }
        val modelReady =
            File(ctx.filesDir, "ai-model/model_int8.onnx").let { it.exists() && it.length() >= MIN_DECODER_SIZE }
        if (encoderReady && modelReady) return true

        // Check if APK has the entries directly via ZipFile
        try {
            val apkPath = ctx.applicationInfo.sourceDir
            if (!apkPath.isNullOrBlank()) {
                val apkFile = File(apkPath)
                if (apkFile.exists()) {
                    java.util.zip.ZipFile(apkFile).use { zip ->
                        val hasEnc =
                            zip.getEntry("assets/ai-model/encoder_int8.onnx") != null ||
                                zip.getEntry("ai-model/encoder_int8.onnx") != null
                        val hasDec =
                            zip.getEntry("assets/ai-model/model_int8.onnx") != null ||
                                zip.getEntry("ai-model/model_int8.onnx") != null
                        if (hasEnc && hasDec) return true
                    }
                }
            }
        } catch (ignored: Throwable) {}

        // Fallback: list assets directory without opening large file streams
        return try {
            val list = ctx.assets.list("ai-model") ?: emptyArray()
            list.any { it.contains("encoder") } && list.any { it.contains("model") }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Downsamples 24 kHz audio to 8 kHz via [AudioDspUtils.resample24kTo8k] for Codec 2 when audio was recorded at 24
     * kHz.
     */
    fun downsampleForCodec2(pcmSamples: ShortArray): ShortArray = AudioDspUtils.resample24kTo8k(pcmSamples)

    override fun encode(pcmSamples: ShortArray): ByteArray? =
        encode(pcmSamples, sampleRate = AndroidAudioController.activeInstance?.activeSampleRate ?: 24000)

    fun encode(
        pcmSamples: ShortArray,
        sampleRate: Int = AndroidAudioController.activeInstance?.activeSampleRate ?: 24000,
    ): ByteArray? = runBlocking { encodeInternal(pcmSamples, sampleRate) }

    override fun decode(compressedBytes: ByteArray): ShortArray? = runBlocking { decodeInternal(compressedBytes) }
}
