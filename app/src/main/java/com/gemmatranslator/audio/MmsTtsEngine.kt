package com.gemmatranslator.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MmsTtsEngine"

class MmsTtsEngine(private val context: Context) {

    private val mutex = Mutex()
    @Volatile private var currentTts: OfflineTts? = null
    @Volatile private var currentLangCode: String? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var speechRate: Float = 1.0f

    private val modelsDir: File
        get() = File(context.filesDir, "mms-tts")

    fun isLanguageAvailable(bcp47: String): Boolean {
        val iso3 = bcp47ToMmsCode(bcp47) ?: return false
        val modelFile = File(modelsDir, "$iso3/model.onnx")
        return modelFile.exists()
    }

    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
    }

    suspend fun speak(
        text: String,
        languageBcp47: String,
        pan: Float = 0f,
    ): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            val iso3 = bcp47ToMmsCode(languageBcp47)
            if (iso3 == null) {
                Log.w(TAG, "No MMS code mapping for $languageBcp47")
                return@withContext false
            }

            if (!ensureLoaded(iso3)) return@withContext false

            val tts = currentTts ?: return@withContext false

            try {
                val audio = tts.generateWithConfig(
                    text = text,
                    config = GenerationConfig(sid = 0, speed = speechRate),
                )
                val samples = audio.samples
                if (samples.isEmpty()) {
                    Log.w(TAG, "TTS generated empty audio for $iso3")
                    return@withContext false
                }

                val track = getOrCreateAudioTrack(tts.sampleRate(), pan)
                track.play()
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.stop()
                Log.d(TAG, "Spoke in $iso3: \"${text.take(50)}...\"")
                true
            } catch (e: Exception) {
                Log.e(TAG, "TTS generation failed for $iso3", e)
                false
            }
        }
    }

    fun stop() {
        audioTrack?.apply {
            try { stop() } catch (_: Exception) {}
            flush()
        }
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
        currentTts?.free()
        currentTts = null
        currentLangCode = null
    }

    private fun ensureLoaded(iso3: String): Boolean {
        if (currentLangCode == iso3 && currentTts != null) return true

        currentTts?.free()
        currentTts = null
        currentLangCode = null

        val modelDir = File(modelsDir, iso3)
        val modelFile = File(modelDir, "model.onnx")
        val tokensFile = File(modelDir, "tokens.txt")

        if (!modelFile.exists()) {
            Log.w(TAG, "Model not found: ${modelFile.absolutePath}")
            return false
        }
        if (!tokensFile.exists()) {
            Log.w(TAG, "Tokens not found: ${tokensFile.absolutePath}")
            return false
        }

        Log.i(TAG, "Loading MMS model for $iso3...")
        val startTime = System.currentTimeMillis()

        try {
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelFile.absolutePath,
                        tokens = tokensFile.absolutePath,
                        noiseScale = 0.667f,
                        noiseScaleW = 0.8f,
                        lengthScale = 1.0f,
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
            )
            currentTts = OfflineTts(assetManager = null, config = config)
            currentLangCode = iso3
            Log.i(TAG, "MMS model loaded for $iso3 in ${System.currentTimeMillis() - startTime}ms")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load MMS model for $iso3", e)
            return false
        }
    }

    private fun getOrCreateAudioTrack(sampleRate: Int, pan: Float): AudioTrack {
        audioTrack?.let { existing ->
            if (existing.sampleRate == sampleRate) {
                existing.setVolume(1.0f)
                return existing
            }
            existing.release()
        }

        val bufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setSampleRate(sampleRate)
                .build(),
            bufSize * 2,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )

        audioTrack = track
        return track
    }

    companion object {
        fun bcp47ToMmsCode(bcp47: String): String? {
            val lang = bcp47.substringBefore('-').lowercase()
            return ISO639_1_TO_MMS[lang]
        }

        // Verified against willwade/mms-tts-multilingual-models-onnx languages-supported.json
        private val ISO639_1_TO_MMS = mapOf(
            "ar" to "ara", "bn" to "ben", "en" to "eng", "fr" to "fra",
            "de" to "deu", "hi" to "hin", "id" to "ind", "pt" to "por",
            "ru" to "rus", "es" to "spa", "sw" to "swh", "th" to "tha",
            "tr" to "tur", "vi" to "vie", "yo" to "yor", "ha" to "hau",
            "nl" to "nld", "fi" to "fin", "el" to "ell", "he" to "heb",
            "hu" to "hun", "pl" to "pol", "ro" to "ron", "sv" to "swe",
            "uk" to "ukr", "bg" to "bul", "my" to "mya", "ca" to "cat",
            "fa" to "fas", "gu" to "guj", "is" to "isl", "kn" to "kan",
            "kk" to "kaz", "km" to "khm", "lv" to "lav", "ml" to "mal",
            "mr" to "mar", "mn" to "mon", "pa" to "pan", "so" to "som",
            "ta" to "tam", "te" to "tel", "cy" to "cym", "ms" to "zlm",
            "hy" to "hyw", "az" to "azj-script_latin",
            "fil" to "tgl", "uz" to "uzb-script_latin",
            "ur" to "urd-script_arabic",
        )
    }
}
