package com.gemmatranslator.model

import android.content.Context
import android.util.Log
import com.gemmatranslator.audio.MmsTtsEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private const val TAG = "ModelManager"

// ~109 MB per model (model.onnx ~108MB + tokens.txt ~1MB)
private const val MODEL_SIZE_BYTES = 114_294_784L

private const val HF_BASE_URL =
    "https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main"

// Codes confirmed NOT available in any ONNX repo
private val UNAVAILABLE_CODES = setOf("amh", "guk", "sgw", "tir", "zul", "xho", "cmn", "jpn")

// ---------------------------------------------------------------------------
// Download state
// ---------------------------------------------------------------------------

data class ModelDownloadState(
    val languageCode: String,
    val progress: Float,          // 0–1
    val totalBytes: Long,
    val downloadedBytes: Long,
    val isComplete: Boolean,
    val error: String? = null,
)

// ---------------------------------------------------------------------------
// ModelManager
// ---------------------------------------------------------------------------

class ModelManager(private val context: Context) {

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), "mms-tts")

    /** Active download jobs keyed by iso3 code so we can cancel them. */
    private val activeDownloads = ConcurrentHashMap<String, Job>()

    // ── Query ─────────────────────────────────────────────────────────────────

    fun isModelDownloaded(language: Language): Boolean {
        val iso3 = MmsTtsEngine.bcp47ToMmsCode(language.bcp47) ?: return false
        return File(modelsDir, "$iso3/model.onnx").exists() &&
                File(modelsDir, "$iso3/tokens.txt").exists()
    }

    fun getDownloadedLanguages(): List<Language> =
        Language.entries.filter { isModelDownloaded(it) }

    fun isDownloadable(language: Language): Boolean {
        val iso3 = MmsTtsEngine.bcp47ToMmsCode(language.bcp47) ?: return false
        return iso3 !in UNAVAILABLE_CODES
    }

    fun getDownloadableLanguages(): List<Language> =
        Language.entries.filter { !isModelDownloaded(it) && isDownloadable(it) }

    /** Estimated size per model in bytes (~109 MB). */
    fun getModelSizeBytes(): Long = MODEL_SIZE_BYTES

    /** Total bytes used by all downloaded models on disk. */
    fun getTotalStorageUsedBytes(): Long {
        val dir = modelsDir
        if (!dir.exists()) return 0L
        return dir.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Downloads model.onnx and tokens.txt for the given language.
     * Emits [ModelDownloadState] progress updates as a cold Flow.
     * The flow runs on [Dispatchers.IO] — collect on any dispatcher.
     * If the model is already downloaded this emits a single complete state immediately.
     */
    fun downloadModel(language: Language): Flow<ModelDownloadState> = flow {
        val iso3 = MmsTtsEngine.bcp47ToMmsCode(language.bcp47)
        if (iso3 == null) {
            emit(
                ModelDownloadState(
                    languageCode = language.bcp47,
                    progress = 0f,
                    totalBytes = 0L,
                    downloadedBytes = 0L,
                    isComplete = false,
                    error = "No MMS code mapping for ${language.bcp47}",
                )
            )
            return@flow
        }

        // Already on disk — fast path
        if (isModelDownloaded(language)) {
            emit(
                ModelDownloadState(
                    languageCode = iso3,
                    progress = 1f,
                    totalBytes = MODEL_SIZE_BYTES,
                    downloadedBytes = MODEL_SIZE_BYTES,
                    isComplete = true,
                )
            )
            return@flow
        }

        val destDir = File(modelsDir, iso3)
        destDir.mkdirs()

        // Files to download in order: model.onnx first (large), then tokens.txt (small)
        val files = listOf("model.onnx", "tokens.txt")

        var totalDownloaded = 0L
        // We track combined progress; approximate total size up front
        var grandTotal = MODEL_SIZE_BYTES

        for (fileName in files) {
            val destFile = File(destDir, fileName)

            // Skip if already present (allows resuming after partial download)
            if (destFile.exists() && destFile.length() > 0) {
                totalDownloaded += destFile.length()
                emit(
                    ModelDownloadState(
                        languageCode = iso3,
                        progress = (totalDownloaded.toFloat() / grandTotal).coerceIn(0f, 0.99f),
                        totalBytes = grandTotal,
                        downloadedBytes = totalDownloaded,
                        isComplete = false,
                    )
                )
                continue
            }

            val tmpFile = File(destDir, "$fileName.tmp")
            val url = "$HF_BASE_URL/$iso3/$fileName"

            try {
                downloadFile(
                    url = url,
                    dest = tmpFile,
                    alreadyDownloaded = totalDownloaded,
                    grandTotal = grandTotal,
                    onProgress = { downloaded, total ->
                        grandTotal = total
                        emit(
                            ModelDownloadState(
                                languageCode = iso3,
                                progress = (downloaded.toFloat() / total).coerceIn(0f, 0.99f),
                                totalBytes = total,
                                downloadedBytes = downloaded,
                                isComplete = false,
                            )
                        )
                    },
                )
                tmpFile.renameTo(destFile)
                totalDownloaded = destDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } catch (e: CancellationException) {
                tmpFile.delete()
                Log.i(TAG, "Download cancelled for $iso3/$fileName")
                throw e
            } catch (e: IOException) {
                tmpFile.delete()
                Log.e(TAG, "Download failed for $iso3/$fileName", e)
                emit(
                    ModelDownloadState(
                        languageCode = iso3,
                        progress = totalDownloaded.toFloat() / grandTotal,
                        totalBytes = grandTotal,
                        downloadedBytes = totalDownloaded,
                        isComplete = false,
                        error = "Download failed: ${e.message}",
                    )
                )
                return@flow
            }
        }

        Log.i(TAG, "Model download complete for $iso3")
        emit(
            ModelDownloadState(
                languageCode = iso3,
                progress = 1f,
                totalBytes = grandTotal,
                downloadedBytes = grandTotal,
                isComplete = true,
            )
        )
    }.flowOn(Dispatchers.IO)

    // ── Cancel / delete ───────────────────────────────────────────────────────

    /**
     * Cancels an in-progress download.
     * The [downloadModel] flow will throw [CancellationException] at the next
     * suspension point, which causes the flow to terminate cleanly.
     */
    fun cancelDownload(language: Language) {
        val iso3 = MmsTtsEngine.bcp47ToMmsCode(language.bcp47) ?: return
        activeDownloads.remove(iso3)?.cancel()
    }

    /**
     * Deletes all files for the given language model.
     */
    fun deleteModel(language: Language) {
        val iso3 = MmsTtsEngine.bcp47ToMmsCode(language.bcp47) ?: return
        cancelDownload(language)
        File(modelsDir, iso3).deleteRecursively()
        Log.i(TAG, "Deleted model for $iso3")
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Downloads [url] into [dest], calling [onProgress] with (totalDownloaded, grandTotal)
     * at regular intervals.
     * Throws [IOException] on network/HTTP errors and [CancellationException] on cancellation.
     */
    private suspend fun downloadFile(
        url: String,
        dest: File,
        alreadyDownloaded: Long,
        grandTotal: Long,
        onProgress: suspend (Long, Long) -> Unit,
    ) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GemmaTranslator/1.0")
            connect()
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IOException("HTTP $responseCode for $url")
        }

        val contentLength = connection.contentLengthLong
        val fileTotal = if (contentLength > 0) contentLength else (MODEL_SIZE_BYTES - alreadyDownloaded)
        val total = (alreadyDownloaded + fileTotal).coerceAtLeast(grandTotal)

        withContext(Dispatchers.IO) {
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(128 * 1024)  // 128 KB chunks
                    var fileDownloaded = 0L
                    var lastEmittedAt = 0L

                    while (coroutineContext.isActive) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        fileDownloaded += read

                        val totalDownloaded = alreadyDownloaded + fileDownloaded
                        // Throttle progress emissions to every 512 KB
                        if (totalDownloaded - lastEmittedAt >= 512 * 1024 || fileDownloaded == fileTotal) {
                            lastEmittedAt = totalDownloaded
                            onProgress(totalDownloaded, total)
                        }
                    }

                    if (!coroutineContext.isActive) {
                        throw CancellationException("Download cancelled")
                    }
                }
            }
        }

        connection.disconnect()
    }
}
