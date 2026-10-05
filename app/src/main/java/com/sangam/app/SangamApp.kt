package com.sangam.app

import android.app.Application
import android.content.Context
import com.sangam.app.ai.GemmaEmbedder
import com.sangam.app.ai.LocalLlm
import com.sangam.app.community.Community
import com.sangam.app.data.LocalStore
import com.sangam.app.mesh.NearbyMesh
import com.sangam.core.matching.MatchEngine
import com.sangam.core.text.Embedder
import com.sangam.core.text.HashingEmbedder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SangamApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
    }
}

class Graph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = LocalStore(app)
    val llm = LocalLlm(app)

    @Volatile private var embedder: Embedder = HashingEmbedder()
    private val _semantic = MutableStateFlow(false)
    /** True once EmbeddingGemma replaced the hashing embedder. */
    val semantic = _semantic.asStateFlow()

    fun engine() = MatchEngine(embedder)

    lateinit var community: Community
        private set

    val mesh = NearbyMesh(
        app, store.selfId, alias = { store.alias },
        onPayload = { bytes, from -> community.onBytes(bytes, from) },
        onConnected = { community.onPeerConnected(it) },
    )

    init {
        community = Community(this)
        scope.launch {
            GemmaEmbedder.create(app)?.let {
                embedder = it
                _semantic.value = true
                community.reembedAll()
            }
        }
    }
}

val Context.graph: Graph get() = (applicationContext as SangamApp).graph
