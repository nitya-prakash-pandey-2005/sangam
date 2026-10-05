package com.sangam.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.sangam.app.community.Intro
import com.sangam.app.community.ProjectInvite
import com.sangam.app.graph
import com.sangam.core.matching.RankedCard
import com.sangam.core.matching.Recommendation
import com.sangam.core.matching.SearchMode
import com.sangam.core.model.CapabilityCard
import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.Identity
import com.sangam.core.protocol.RoomKey
import com.sangam.core.state.WallStats
import com.sangam.core.text.ProfileParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SangamViewModel(app: Application) : AndroidViewModel(app) {
    val graph = app.graph
    private val community = graph.community

    val state = community.state
    val room = community.room
    val intros = community.intros
    val chats = community.chats
    val invites = community.invites
    val mesh = graph.mesh.status
    val ai = graph.llm.status
    val modelDownload = graph.modelDownload.state
    val semantic = graph.semantic
    val rejected = community.rejectedMessages

    private val _profile = MutableStateFlow(graph.store.profile)
    val profile = _profile.asStateFlow()
    private val _identity = MutableStateFlow(graph.store.identity)
    val identity = _identity.asStateFlow()
    private val _demo = MutableStateFlow(graph.store.demoPeers)
    val demo = _demo.asStateFlow()
    private val _extracting = MutableStateFlow(false)
    val extracting = _extracting.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun messageShown() { _message.value = null }

    val selfId get() = graph.store.selfId
    val alias get() = graph.store.alias

    /** Collaboration preferences Gemma found; there is no field for them, so they're applied on save. */
    private var extractedCollab: List<String>? = null

    fun saveProfile(p: CapabilityProfile) {
        val full = extractedCollab?.let { p.copy(collab = it) } ?: p
        extractedCollab = null
        _profile.value = full
        community.updateProfile(full)
    }
    fun saveIdentity(i: Identity) { _identity.value = i; graph.store.identity = i }

    private val _extractStatus = MutableStateFlow<String?>(null)
    /** Live "Loading Gemma on the GPU · 14 s" style status while a profile is being read. */
    val extractStatus = _extractStatus.asStateFlow()
    private var extractJob: kotlinx.coroutines.Job? = null

    fun cancelExtraction() {
        extractJob?.cancel()
        _extracting.value = false
        _extractStatus.value = null
        _message.value = "Stopped."
    }

    /** Pasted resume / README → structured capability profile, with Gemma running on this phone. */
    fun extractProfile(text: String, onResult: (CapabilityProfile) -> Unit) {
        extractJob?.cancel()
        extractJob = viewModelScope.launch { extract(text, onResult) }
    }

    private suspend fun extract(text: String, onResult: (CapabilityProfile) -> Unit) = kotlinx.coroutines.coroutineScope {
        _extracting.value = true
        val started = System.currentTimeMillis()
        val ticker = launch {
            graph.llm.activity.collectLatest { activity ->
                while (true) {
                    val secs = (System.currentTimeMillis() - started) / 1000
                    _extractStatus.value = "${activity ?: "Starting Gemma"} · $secs s"
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
        var reply = graph.llm.generate(ProfileParser.SYSTEM, text.take(6000), ProfileParser.SCHEMA)
        var p = reply?.let { ProfileParser.parse(it) }
        // Retry in plain mode only if the first answer came back quickly but unusable, never after a timeout.
        if (p == null && reply != null && graph.llm.lastError == null && System.currentTimeMillis() - started < 60_000) {
            // Some phones handle constrained JSON output poorly; ask once more in plain mode.
            android.util.Log.i("SangamAI", "First reply not usable, retrying without JSON mode: ${reply.take(300)}")
            reply = graph.llm.generate(ProfileParser.SYSTEM, text.take(6000), null) ?: reply
            p = ProfileParser.parse(reply)
        }
        ticker.cancel()
        _extracting.value = false
        _extractStatus.value = null
        android.util.Log.i("SangamAI", "Profile reply (${reply?.length ?: 0} chars): ${reply?.take(600)} -> ${if (p == null) "not usable" else "parsed"}")
        if (p == null) _message.value = when {
            reply != null && graph.llm.lastError != null -> "Gemma couldn't finish: ${graph.llm.lastError}. Try a shorter text."
            reply != null -> "Could not read a profile from that text. Try adding what you can offer and what you're looking for." +
                reply.trim().take(80).let { if (it.isEmpty()) " (Gemma gave an empty reply.)" else " (Gemma replied: \"$it…\")" }
            else -> when (val s = graph.llm.status.value) {
                com.sangam.app.ai.AiStatus.None -> "Gemma isn't on this phone yet. Download it above, or fill the fields by hand."
                is com.sangam.app.ai.AiStatus.Failed -> "Gemma couldn't load on this phone (${s.message}). Fill the fields by hand."
                else -> "Gemma couldn't answer: ${graph.llm.lastError ?: "unknown error"}. Try a shorter text."
            }
        }
        // Only the on-screen fields change here; nothing is saved or shared until the user taps Save.
        else { extractedCollab = p.collab; onResult(p) }
    }

    fun createRoom(name: String) = community.create(name)
    fun joinRoom(code: String): Boolean = RoomKey.fromQr(code.trim())?.let { community.join(it); true } ?: false
    fun leaveRoom() = community.leave()
    fun restartMesh() { room.value?.let { graph.mesh.start(it.roomId); community.announceSelf() } }

    fun setDemo(on: Boolean) { _demo.value = on; community.setDemoPeers(on) }

    fun me(): CapabilityCard = community.myCard()
    fun recommendations(): List<Recommendation> = graph.engine().recommend(me(), state.value.people)
    fun search(q: String, mode: SearchMode): List<RankedCard> = graph.engine().search(q, mode, state.value.cards.values.filter { it.peerId != selfId })
    fun reasons(card: CapabilityCard) = graph.engine().explain(graph.store.profile, card.profile)
    // People already on the team are not candidates any more.
    fun projectCandidates(project: CapabilityCard) = state.value.people.filter { it.peerId != selfId && it.alias !in project.members }
        .map { it to graph.engine().projectFit(project, it) }.sortedByDescending { it.second }
    fun wall() = WallStats.compute(state.value.cards.values.toList(), graph.engine())

    fun requestIntro(card: CapabilityCard, reasons: List<String>) {
        community.requestIntro(card, "Hi! Sangam thinks we can help each other.", reasons)
        _message.value = "Introduction requested. ${card.alias} sees your capabilities, not your name."
    }
    fun respond(intro: Intro, accept: Boolean) = community.respond(intro, accept)
    fun conversationWith(peerId: String) = community.conversationWith(peerId)
    fun sendChat(conversationId: String, text: String, toPeer: String?) = community.sendChat(conversationId, text, toPeer)
    fun createProject(name: String, profile: CapabilityProfile) = community.createProject(name, profile)
    fun invite(project: CapabilityCard, person: CapabilityCard, fit: Int) {
        community.invite(project, person, fit); _message.value = "Invited ${person.alias}"
    }
    fun acceptInvite(i: ProjectInvite) = community.acceptInvite(i)

    fun qr(text: String, size: Int = 720): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.rgb(30, 42, 90) else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }

    fun loadAi() = viewModelScope.launch { graph.llm.ensureLoaded() }
    fun downloadModel() = graph.modelDownload.start()
    fun cancelModelDownload() = graph.modelDownload.cancel()
    fun redownloadModel() { graph.llm.deleteModel(); graph.modelDownload.start() }
    fun switchToCpuModel() { graph.llm.deleteModel(); graph.modelDownload.start(com.sangam.app.ai.ModelDownloader.Variant.CPU) }
    val recommendedModel = graph.modelDownload.recommended
}
