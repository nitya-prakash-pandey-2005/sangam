package com.sangam.app.ai

import android.content.Context
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
    data class Ready(val name: String) : AiStatus
    data class Failed(val message: String) : AiStatus
}

/** On-device Gemma for profile extraction and icebreakers. Nothing leaves the phone. */
class LocalLlm(private val context: Context) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private val _status = MutableStateFlow<AiStatus>(if (Models.llm(context) == null) AiStatus.None else AiStatus.Idle)
    val status: StateFlow<AiStatus> = _status.asStateFlow()

    suspend fun ensureLoaded(): Boolean = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (engine != null) return@withContext true
            val file = Models.llm(context) ?: run { _status.value = AiStatus.None; return@withContext false }
            _status.value = AiStatus.Loading
            runCatching {
                Engine(EngineConfig(modelPath = file.absolutePath, backend = Backend.GPU(), maxNumTokens = 4096, cacheDir = context.cacheDir.path))
                    .also { it.initialize() }
            }.onSuccess { engine = it; _status.value = AiStatus.Ready(file.name) }
                .onFailure { _status.value = AiStatus.Failed(it.message ?: "Load failed") }
            engine != null
        }
    }

    suspend fun generate(system: String, prompt: String, jsonSchema: String? = null): String? {
        if (!ensureLoaded()) return null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                val e = engine ?: return@withContext null
                runCatching {
                    val config = ConversationConfig(
                        systemInstruction = Contents.of(system),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 3),
                        enableResponseFormat = jsonSchema != null,
                    )
                    e.createConversation(config).use { conv ->
                        val reply = if (jsonSchema != null) conv.sendMessage(prompt, responseFormat = ResponseFormat.json(jsonSchema)) else conv.sendMessage(prompt)
                        reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    }
                }.getOrNull()
            }
        }
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
