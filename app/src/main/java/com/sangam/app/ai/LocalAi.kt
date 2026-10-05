package com.sangam.app.ai

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import com.sangam.core.text.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

object Models {
    fun dir(context: Context) = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private fun files(context: Context) = dir(context).listFiles { f -> f.isFile }?.toList().orEmpty()
    fun llm(context: Context) = files(context).filter { it.name.endsWith(".litertlm") && !it.name.contains("embed", true) }
        .sortedByDescending { it.name.contains("gemma-4", true) }.firstOrNull()
    fun embedder(context: Context) = files(context).firstOrNull { it.name.contains("embed", true) && it.name.endsWith(".litertlm") }
}

sealed interface AiStatus {
    data object None : AiStatus
    data object Idle : AiStatus
    data object Loading : AiStatus
    data class Ready(val name: String, val backend: String) : AiStatus
    data class Failed(val message: String, val file: String = "") : AiStatus {
        /** The file itself is unreadable or corrupt, so downloading it again can help. */
        val damagedFile get() = message.contains("magic number", ignoreCase = true)
        /** A GPU-only model on a phone whose GPU can't run it: the CPU version is the way out. */
        val gpuOnlyFileFailed get() = !damagedFile && ModelDownloader.Variant.GPU.matches(file)
    }
}

private const val TAG = "SangamAI"

/** On-device Gemma for profile extraction and icebreakers. Nothing leaves the phone. */
class LocalLlm(private val context: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private val _status = MutableStateFlow<AiStatus>(if (Models.llm(context) == null) AiStatus.None else AiStatus.Idle)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    private val _activity = MutableStateFlow<String?>(null)
    /** What Gemma is doing right now ("Loading Gemma on the GPU", "Reading your text · 220 characters"), for the UI. */
    val activity: StateFlow<String?> = _activity.asStateFlow()

    /** Removes an unusable model so a fresh copy can be downloaded. */
    fun deleteModel() {
        if (engine != null) return
        Models.llm(context)?.delete()
        refresh()
    }

    /** A model file appeared or disappeared (e.g. a download finished). */
    fun refresh() {
        if (engine == null) _status.value = if (Models.llm(context) == null) AiStatus.None else AiStatus.Idle
    }

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (engine != null) return@withContext true
            val file = Models.llm(context) ?: run { _status.value = AiStatus.None; return@withContext false }
            _status.value = AiStatus.Loading
            // Google's GPU build only runs on the GPU; the standard build runs on the CPU.
            val backends = if (ModelDownloader.Variant.GPU.matches(file.name)) listOf("GPU" to Backend.GPU())
            else listOf("CPU" to Backend.CPU(), "GPU" to Backend.GPU())
            val errors = mutableListOf<String>()
            for ((label, backend) in backends) {
                _activity.value = "Loading Gemma on the $label (the first time takes longest)"
                val result = runCatching {
                    Engine(EngineConfig(modelPath = file.absolutePath, backend = backend, maxNumTokens = 4096, cacheDir = context.cacheDir.path))
                        .also { it.initialize() }
                }
                result.onSuccess {
                    engine = it
                    _status.value = AiStatus.Ready(file.name, label)
                    Log.i(TAG, "Loaded ${file.name} on $label")
                }
                if (engine != null) break
                val e = result.exceptionOrNull()
                Log.e(TAG, "Loading ${file.name} on $label failed", e)
                errors += "$label: ${e?.message ?: e?.javaClass?.simpleName ?: "unknown error"}"
            }
            if (engine == null) _status.value = AiStatus.Failed(errors.joinToString("; "), file.name)
            _activity.value = null
            engine != null
        }
    }

    /** Why the last generation failed, for the message shown to the user. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Streams Gemma's answer with hard limits, so a slow or rambling model can never hang the app:
     * at most [maxOutputTokens], no hidden "thinking" pass, stop as soon as a complete JSON object
     * has arrived, and give up after [timeoutMs] (keeping whatever arrived so far).
     */
    suspend fun generate(
        system: String,
        prompt: String,
        jsonSchema: String? = null,
        maxOutputTokens: Int = 512,
        timeoutMs: Long = 120_000,
    ): String? {
        lastError = null
        if (!ensureLoaded()) return null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val e = engine ?: return@withContext null
                val text = StringBuilder()
                val started = System.currentTimeMillis()
                _activity.value = "Reading your text"
                try {
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 3),
                        enableResponseFormat = jsonSchema != null,
                    )
                    e.createConversation(config).use { conv -> try {
                        var finished = false
                        val completed = withTimeoutOrNull(timeoutMs) {
                            conv.sendMessageAsync(
                                prompt,
                                maxOutputToken = maxOutputTokens,
                                thinkingConfig = ThinkingConfig(false),
                                responseFormat = jsonSchema?.let { ResponseFormat.json(it) },
                            ).takeWhile { message ->
                                val chunk = message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                                text.append(chunk)
                                _activity.value = "Reading your text · ${text.length} characters"
                                // A profile is one JSON object: once it's closed there's nothing more to wait for.
                                !(jsonSchema != null && closesJsonObject(text))
                            }.collect { }
                            finished = true
                        }
                        if (completed == null || !finished) {
                            runCatching { conv.cancelProcess() }
                            if (completed == null) lastError = "Gemma took longer than ${timeoutMs / 1000} seconds"
                        } else if (jsonSchema != null && closesJsonObject(text)) {
                            runCatching { conv.cancelProcess() }
                        }
                    } catch (stop: kotlinx.coroutines.CancellationException) {
                        // The user tapped Stop: halt native generation too, not just our waiting.
                        runCatching { conv.cancelProcess() }
                        throw stop
                    } }
                    Log.i(TAG, "Generated ${text.length} chars in ${System.currentTimeMillis() - started} ms")
                    text.toString()
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e(TAG, "Generation failed", t)
                    lastError = t.message ?: t.javaClass.simpleName
                    text.toString().ifBlank { null }
                } finally {
                    _activity.value = null
                }
            }
        }
    }

    /** True once the first top-level JSON object in [s] has been closed (braces inside strings ignored). */
    private fun closesJsonObject(s: CharSequence): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        var opened = false
        for (c in s) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> { depth++; opened = true }
                '}' -> { depth--; if (opened && depth == 0) return true }
            }
        }
        return false
    }
}

/** EmbeddingGemma (or any LiteRT-LM embedding model) as a Sangam [Embedder]. */
class GemmaEmbedder private constructor(private val engine: EmbeddingEngine, override val dim: Int) : Embedder {
    override val id = "embeddinggemma-$dim"
    /** Semantic models score unrelated text well above zero, so thresholds are higher than the hashing matcher's. */
    override val calibration = com.sangam.core.text.Calibration(mutual = 0.45f, itemMatch = 0.55f, fullFit = 0.8f, minSimilarity = 0.3f)
    @Synchronized
    override fun embed(text: String): FloatArray =
        if (text.isBlank()) FloatArray(dim)
        else engine.computeEmbedding(listOf(InputData.Text(text)), EmbeddingOptions(normalize = true, outputSize = dim)).embedding

    companion object {
        fun create(context: Context, dim: Int = 256): GemmaEmbedder? {
            val f = Models.embedder(context) ?: return null
            return runCatching {
                val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = f.absolutePath, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                e.initialize()
                GemmaEmbedder(e, dim).also { it.embed("warm up") }
            }.getOrNull()
        }
    }
}
