package com.gemmatranslator.translation

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class TranslationEngine(
    private val context: Context,
    private val modelPath: String,
) : AutoCloseable {

    sealed class LoadingState {
        object Idle : LoadingState()
        data class Loading(val progress: Float) : LoadingState()
        object Ready : LoadingState()
        data class Error(val cause: Throwable) : LoadingState()
    }

    private val _loadingState = MutableStateFlow<LoadingState>(LoadingState.Idle)
    val loadingState: StateFlow<LoadingState> = _loadingState

    val isReady: Boolean get() = _loadingState.value is LoadingState.Ready

    companion object {
        private const val TAG = "TranslationEngine"
    }

    private val inferenceMutex = Mutex()
    @Volatile private var engine: Engine? = null
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val languageDetector = LanguageDetector { text ->
        runInference(PromptTemplates.languageDetection(text))
    }

    fun load(onProgress: ((Float) -> Unit)? = null) {
        val current = _loadingState.value
        if (current is LoadingState.Loading || current is LoadingState.Ready) return

        engineScope.launch {
            _loadingState.value = LoadingState.Loading(0f)
            try {
                onProgress?.invoke(0.05f)
                _loadingState.value = LoadingState.Loading(0.05f)

                val e = withContext(Dispatchers.IO) { buildEngine() }

                _loadingState.value = LoadingState.Loading(0.5f)
                onProgress?.invoke(0.5f)

                withContext(Dispatchers.IO) { e.initialize() }

                _loadingState.value = LoadingState.Loading(1f)
                onProgress?.invoke(1f)

                engine = e
                _loadingState.value = LoadingState.Ready
                Log.i(TAG, "Gemma E2B model loaded from $modelPath")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load Gemma model", e)
                _loadingState.value = LoadingState.Error(e)
            }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
        _loadingState.value = LoadingState.Idle
    }

    suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        requireReady()
        if (text.isBlank()) return ""
        val prompt = PromptTemplates.translation(text, sourceLang, targetLang)
        return runInference(prompt).trim()
    }

    suspend fun translateToTarget(text: String, targetLang: String): String {
        requireReady()
        if (text.isBlank()) return ""
        val prompt = PromptTemplates.translationToTarget(text, targetLang)
        return runInference(prompt).trim()
    }

    suspend fun detectLanguage(text: String): String =
        languageDetector.detectLanguage(text)

    suspend fun translateStreaming(
        text: String,
        sourceLang: String,
        targetLang: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
    ) {
        requireReady()
        if (text.isBlank()) { onDone(""); return }
        val prompt = PromptTemplates.translation(text, sourceLang, targetLang)
        runInferenceStreaming(prompt, onPartial, onDone)
    }

    private suspend fun runInference(prompt: String): String =
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                val e = engine ?: throw IllegalStateException("Engine not loaded")
                e.createConversation().use { conv ->
                    val message = conv.sendMessage(prompt)
                    extractText(message)
                }
            }
        }

    private fun extractText(message: Message): String {
        val contents = message.contents.contents
        return contents.filterIsInstance<Content.Text>()
            .joinToString("") { it.text }
    }

    private suspend fun runInferenceStreaming(
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
    ) = inferenceMutex.withLock {
        withContext(Dispatchers.Default) {
            val e = engine ?: throw IllegalStateException("Engine not loaded")
            val fullBuilder = StringBuilder()
            e.createConversation().use { conv ->
                conv.sendMessageAsync(prompt).collect { message ->
                    val chunk = extractText(message)
                    fullBuilder.append(chunk)
                    onPartial(chunk)
                }
            }
            onDone(fullBuilder.toString().trim())
        }
    }

    private fun buildEngine(): Engine {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
        )
        return Engine(config)
    }

    private fun requireReady() {
        check(_loadingState.value is LoadingState.Ready) {
            "TranslationEngine is not ready (state=${_loadingState.value})"
        }
    }
}
