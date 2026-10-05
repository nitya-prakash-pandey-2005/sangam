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

    fun saveProfile(p: CapabilityProfile) { _profile.value = p; community.updateProfile(p) }
    fun saveIdentity(i: Identity) { _identity.value = i; graph.store.identity = i }

    /** Pasted resume / README → structured capability profile, with Gemma running on this phone. */
    fun extractProfile(text: String, onResult: (CapabilityProfile) -> Unit) = viewModelScope.launch {
        _extracting.value = true
        val reply = graph.llm.generate(ProfileParser.SYSTEM, text.take(6000), ProfileParser.SCHEMA)
        _extracting.value = false
        val p = reply?.let { ProfileParser.parse(it) }
        if (p == null) _message.value = if (reply == null) "On-device model not installed. Fill the fields by hand, or add a Gemma model (see Me › AI)." else "Could not read a profile from that text."
        else { _profile.value = _profile.value.copy(collab = p.collab); onResult(p) }
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
}
