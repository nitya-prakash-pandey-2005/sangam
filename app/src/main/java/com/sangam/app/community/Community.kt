package com.sangam.app.community

import com.sangam.app.Graph
import com.sangam.core.model.CapabilityCard
import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.CardKind
import com.sangam.core.model.Identity
import com.sangam.core.protocol.CardPayload
import com.sangam.core.protocol.ChatPayload
import com.sangam.core.protocol.Envelope
import com.sangam.core.protocol.GossipRouter
import com.sangam.core.protocol.IntroRequest
import com.sangam.core.protocol.IntroResponse
import com.sangam.core.protocol.MsgType
import com.sangam.core.protocol.ProjectInvitePayload
import com.sangam.core.protocol.ProjectJoinPayload
import com.sangam.core.protocol.RoomKey
import com.sangam.core.protocol.RouteDecision
import com.sangam.core.protocol.SealedPayload
import com.sangam.core.protocol.wireJson
import com.sangam.core.state.CommunityState
import com.sangam.core.state.LedgerEntry
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import java.util.UUID

enum class IntroStatus { PENDING, ACCEPTED, DECLINED }

data class Intro(
    val requestId: String,
    val peerId: String,
    val alias: String,
    val note: String,
    val reasons: List<String>,
    val incoming: Boolean,
    val status: IntroStatus = IntroStatus.PENDING,
    val identity: Identity? = null,
)

data class ChatMsg(val fromSelf: Boolean, val alias: String, val text: String, val at: Long)

data class ProjectInvite(val projectId: String, val projectName: String, val fromPeer: String, val fit: Int, val note: String, val joined: Boolean = false)

/**
 * The room as this phone sees it. Handles every protocol message, keeps the local community index, and records every
 * byte this phone shares in the privacy ledger.
 */
class Community(private val graph: Graph) {
    private val store = graph.store
    private val router = GossipRouter(store.selfId, maxTtl = TTL)
    /** Inbound messages are processed here, never on the main thread (embedding can be slow). */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    /** (projectId, peerId) pairs we invited: only these may join our projects. */
    private val invited = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val peerKeys = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _state = MutableStateFlow(CommunityState(selfId = store.selfId))
    val state: StateFlow<CommunityState> = _state.asStateFlow()
    private val _room = MutableStateFlow<RoomKey?>(null)
    val room: StateFlow<RoomKey?> = _room.asStateFlow()
    private val _intros = MutableStateFlow<List<Intro>>(emptyList())
    val intros: StateFlow<List<Intro>> = _intros.asStateFlow()
    private val _chats = MutableStateFlow<Map<String, List<ChatMsg>>>(emptyMap())
    val chats: StateFlow<Map<String, List<ChatMsg>>> = _chats.asStateFlow()
    private val _invites = MutableStateFlow<List<ProjectInvite>>(emptyList())
    val invites: StateFlow<List<ProjectInvite>> = _invites.asStateFlow()
    private val _sendErrors = MutableStateFlow(0)
    /** Private messages not sent because the peer's encryption key was not known yet. */
    val sendErrors: StateFlow<Int> = _sendErrors.asStateFlow()
    private val _rejected = MutableStateFlow(0)
    val rejectedMessages: StateFlow<Int> = _rejected.asStateFlow()

    init {
        restoreMyProjects()
        store.roomQr?.let { RoomKey.fromQr(it) }?.let { join(it) }
        if (store.demoPeers) addDemoPeers()
    }

    // ---------- room ----------

    fun create(name: String) = join(RoomKey.create(name.ifBlank { "Meetup" }))

    fun join(key: RoomKey) {
        _room.value = key
        store.roomQr = key.toQr()
        graph.mesh.start(key.roomId)
        upsertLocal(myCard())
        announceSelf()
        myProjects().forEach { send(MsgType.PROJECT_ANNOUNCE, CardPayload.serializer(), cardPayload(it), fields = listOf("project name", "has", "needs", "goal")) }
    }

    fun leave() {
        send(MsgType.LEAVE, kotlinx.serialization.json.JsonPrimitive(store.selfId), fields = listOf("alias"))
        graph.scope.launch { delay(500); graph.mesh.stop() }
        _room.value = null
        store.roomQr = null
        _state.value = CommunityState(selfId = store.selfId)
        restoreMyProjects()
        if (store.demoPeers) addDemoPeers()
    }

    // ---------- me ----------

    fun myCard(): CapabilityCard = graph.engine().card(store.selfId, store.alias, store.profile, version = store.profileVersion)

    fun updateProfile(p: CapabilityProfile) {
        store.profile = p
        upsertLocal(myCard())
        announceSelf()
    }

    fun announceSelf() {
        val p = store.profile
        send(MsgType.PROFILE_ANNOUNCE, CardPayload.serializer(), cardPayload(myCard()), fields = nonEmptyFields(p))
    }

    // ---------- inbound ----------

    /** Transport connected: prove we hold the room key. Room data flows only after the other side proves it too. */
    fun onPeerConnected(endpointId: String) {
        val key = _room.value ?: return
        val hello = key.sign(envelope(MsgType.HELLO, kotlinx.serialization.json.JsonPrimitive(key.encrypt(store.alias)), to = null, ttl = 0).copy(enc = true))
        router.markSent(hello.messageId)
        graph.mesh.sendTo(endpointId, encode(hello))
    }

    /** The endpoint proved it holds the room key: give it everything we know, one card per message, without relaying. */
    private fun syncTo(endpointId: String) {
        val key = _room.value ?: return
        _state.value.cards.values.filterNot { it.peerId.startsWith(DEMO) }.forEach { c ->
            val json = wireJson.encodeToString(CardPayload.serializer(), cardPayload(c))
            val env = key.sign(envelope(MsgType.SYNC_RESPONSE, kotlinx.serialization.json.JsonPrimitive(key.encrypt(json)), to = null, ttl = 0).copy(enc = true))
            router.markSent(env.messageId)
            graph.mesh.sendTo(endpointId, encode(env))
        }
        record(MsgType.SYNC_RESPONSE, "a newly verified phone in the room", listOf("capability cards (no identities)"), 0)
    }

    fun onBytes(bytes: ByteArray, fromEndpoint: String) {
        graph.scope.launch(worker) { process(bytes, fromEndpoint) }
    }

    private fun process(bytes: ByteArray, fromEndpoint: String) {
        val key = _room.value ?: return
        val env = runCatching { wireJson.decodeFromString(Envelope.serializer(), bytes.decodeToString()) }.getOrNull() ?: return
        if (!key.verify(env)) { _rejected.update { it + 1 }; return }
        if (!graph.mesh.isVerified(fromEndpoint)) {
            graph.mesh.markVerified(fromEndpoint)
            syncTo(fromEndpoint)
        }
        if (env.type == MsgType.HELLO) return
        when (val d = router.onReceive(env)) {
            RouteDecision.Duplicate -> return
            RouteDecision.DeliverOnly -> Unit
            is RouteDecision.DeliverAndForward -> graph.mesh.broadcast(encode(d.forward), except = fromEndpoint)
        }
        if (env.to != null && env.to != store.selfId) return
        val clear = if (env.enc) {
            val json = (env.payload as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { key.decrypt(it) } ?: return
            env.copy(payload = wireJson.parseToJsonElement(json), enc = false)
        } else env
        handle(clear)
    }

    private fun handle(env: Envelope) {
        when (env.type) {
            MsgType.PROFILE_ANNOUNCE, MsgType.SYNC_RESPONSE, MsgType.PROJECT_ANNOUNCE ->
                decode(env, CardPayload.serializer())?.let {
                    if (it.peerId == store.selfId) return@let
                    if (it.kind == CardKind.PERSON) it.publicKey?.let { k -> peerKeys[it.peerId] = k }
                    upsertLocal(toCard(it))
                }
            MsgType.LEAVE -> _state.update { it.remove(env.senderId) }
            MsgType.INTRO_REQUEST -> decode(env, IntroRequest.serializer())?.let { r ->
                _intros.update { it + Intro(r.requestId, env.senderId, r.fromAlias, r.note, r.reasons, incoming = true) }
            }
            MsgType.INTRO_RESPONSE -> decode(env, IntroResponse.serializer())?.let { r ->
                _intros.update { list ->
                    list.map {
                        val legit = it.requestId == r.requestId && !it.incoming && it.peerId == env.senderId
                        if (legit) it.copy(status = if (r.accepted) IntroStatus.ACCEPTED else IntroStatus.DECLINED, identity = r.identity) else it
                    }
                }
            }
            MsgType.CHAT_MESSAGE -> decode(env, ChatPayload.serializer())?.let { m -> appendChat(m.conversationId, ChatMsg(false, m.fromAlias, m.text, env.timestamp)) }
            MsgType.PROJECT_INVITE -> decode(env, ProjectInvitePayload.serializer())?.let { i ->
                _invites.update { it + ProjectInvite(i.projectId, i.projectName, env.senderId, i.fit, i.note) }
            }
            MsgType.PROJECT_JOIN -> decode(env, ProjectJoinPayload.serializer())?.let { j ->
                // Only the owner adds members, and only people it invited; everyone else learns from the owner's announce.
                if (j.peerId == env.senderId && (j.projectId to env.senderId) in invited) addMember(j.projectId, j.peerId, j.alias)
            }
            MsgType.HELLO -> Unit
            MsgType.SYNC_REQUEST, MsgType.REVEAL -> Unit
        }
    }

    // ---------- introductions ----------

    fun requestIntro(card: CapabilityCard, note: String, reasons: List<String>) {
        val id = UUID.randomUUID().toString()
        _intros.update { it + Intro(id, card.peerId, card.alias, note, reasons, incoming = false) }
        if (card.peerId.startsWith(DEMO)) {
            record(MsgType.INTRO_REQUEST, card.alias, listOf("alias", "note", "(end-to-end encrypted)"), 0)
            simulateDemoAccept(id, card)
            return
        }
        send(MsgType.INTRO_REQUEST, IntroRequest.serializer(), IntroRequest(id, store.alias, note, reasons), to = card.peerId, fields = listOf("alias", "note"))
    }

    fun respond(intro: Intro, accept: Boolean) {
        val identity = if (accept) store.identity else null
        _intros.update { list -> list.map { if (it.requestId == intro.requestId) it.copy(status = if (accept) IntroStatus.ACCEPTED else IntroStatus.DECLINED) else it } }
        val fields = if (accept) identityFields(store.identity) else listOf("decline")
        send(MsgType.INTRO_RESPONSE, IntroResponse.serializer(), IntroResponse(intro.requestId, accept, identity), to = intro.peerId, fields = fields)
    }

    // ---------- chat ----------

    fun conversationWith(peerId: String) = listOf(store.selfId, peerId).sorted().joinToString(":")

    fun sendChat(conversationId: String, text: String, toPeer: String?) {
        appendChat(conversationId, ChatMsg(true, store.alias, text, System.currentTimeMillis()))
        if (toPeer?.startsWith(DEMO) == true) {
            record(MsgType.CHAT_MESSAGE, _state.value.cards[toPeer]?.alias ?: "demo person", listOf("message", "(end-to-end encrypted)"), 0)
            graph.scope.launch { delay(1200); appendChat(conversationId, ChatMsg(false, "Demo peer", "Sounds good, see you at the coffee counter!", System.currentTimeMillis())) }
            return
        }
        send(MsgType.CHAT_MESSAGE, ChatPayload.serializer(), ChatPayload(conversationId, store.alias, text), to = toPeer, fields = listOf("message"))
    }

    private fun appendChat(id: String, m: ChatMsg) = _chats.update { it + (id to (it[id].orEmpty() + m)) }

    // ---------- projects ----------

    fun createProject(name: String, profile: CapabilityProfile): CapabilityCard {
        val card = graph.engine().card("p-" + UUID.randomUUID().toString().take(8), name, profile, CardKind.PROJECT, projectName = name)
            .copy(ownerId = store.selfId, members = listOf(store.alias))
        upsertLocal(card)
        saveMyProjects()
        send(MsgType.PROJECT_ANNOUNCE, CardPayload.serializer(), cardPayload(card), fields = listOf("project name", "has", "needs", "goal"))
        return card
    }

    fun invite(project: CapabilityCard, person: CapabilityCard, fit: Int) {
        if (person.peerId.startsWith(DEMO)) {
            graph.scope.launch { delay(1500); addMember(project.peerId, person.peerId, person.alias) }
            record(MsgType.PROJECT_INVITE, person.alias, listOf("project name", "fit"), 0)
            return
        }
        invited += project.peerId to person.peerId
        send(MsgType.PROJECT_INVITE, ProjectInvitePayload.serializer(), ProjectInvitePayload(project.peerId, project.projectName ?: project.alias, fit, "Join us?"), to = person.peerId, fields = listOf("project name", "fit"))
    }

    fun acceptInvite(invite: ProjectInvite) {
        _invites.update { list -> list.map { if (it.projectId == invite.projectId) it.copy(joined = true) else it } }
        send(MsgType.PROJECT_JOIN, ProjectJoinPayload.serializer(), ProjectJoinPayload(invite.projectId, store.selfId, store.alias), fields = listOf("alias"))
    }

    private fun addMember(projectId: String, peerId: String, alias: String) {
        val p = _state.value.cards[projectId] ?: return
        if (alias in p.members) return
        val updated = p.copy(members = p.members + alias, version = p.version + 1)
        _state.update { it.upsertCard(updated) }
        if (p.ownerId == store.selfId) saveMyProjects()
        if (p.ownerId == store.selfId) send(MsgType.PROJECT_ANNOUNCE, CardPayload.serializer(), cardPayload(updated), fields = listOf("project members"))
        appendChat(projectId, ChatMsg(false, "Sangam", "$alias joined ${p.projectName ?: "the project"}", System.currentTimeMillis()))
    }

    /** The embedding model changed (e.g. EmbeddingGemma finished loading): recompute every card in the same space. */
    fun reembedAll() = _state.update { s ->
        s.copy(cards = s.cards.mapValues { (_, c) ->
            graph.engine().card(c.peerId, c.alias, c.profile, c.kind, c.version, c.projectName).copy(ownerId = c.ownerId, members = c.members)
        })
    }

    // ---------- demo peers (one-phone demos; clearly labelled) ----------

    fun setDemoPeers(on: Boolean) {
        store.demoPeers = on
        if (on) addDemoPeers() else _state.update { s -> s.copy(cards = s.cards.filterKeys { !it.startsWith(DEMO) }) }
    }

    private fun addDemoPeers() = DemoPeers.profiles.forEachIndexed { i, (alias, p) ->
        upsertLocal(graph.engine().card("$DEMO$i", "$alias (demo)", p))
    }

    private fun simulateDemoAccept(requestId: String, card: CapabilityCard) = graph.scope.launch {
        delay(1800)
        val name = card.alias.removeSuffix(" (demo)")
        _intros.update { list ->
            list.map { if (it.requestId == requestId) it.copy(status = IntroStatus.ACCEPTED, identity = Identity("$name (demo)", "Demo attendee", github = "github.com/demo")) else it }
        }
    }

    // ---------- plumbing ----------

    private fun upsertLocal(card: CapabilityCard) = _state.update { it.upsertCard(card) }

    private fun myProjects() = _state.value.cards.values.filter { it.kind == CardKind.PROJECT && it.ownerId == store.selfId }
    private fun saveMyProjects() { store.myProjects = myProjects().map { cardPayload(it).copy(publicKey = null) } }
    private fun restoreMyProjects() = store.myProjects.forEach { upsertLocal(toCard(it)) }

    private fun toCard(p: CardPayload) = graph.engine().card(p.peerId, p.alias, p.profile, p.kind, p.version, p.projectName)
        .copy(ownerId = p.ownerId, members = p.members)

    private fun cardPayload(c: CapabilityCard) = CardPayload(
        c.peerId, c.alias, c.profile, c.kind, c.version, c.projectName, c.ownerId, c.members,
        publicKey = if (c.peerId == store.selfId) store.crypto.publicKey else peerKeys[c.peerId],
    )

    private fun <T> send(type: MsgType, serializer: KSerializer<T>, payload: T, to: String? = null, fields: List<String>) {
        if (to == null) return send(type, wireJson.encodeToJsonElement(serializer, payload), null, fields)
        // Private message: sealed end-to-end so relaying phones can't read it. Without the peer's key, nothing is sent.
        val key = peerKeys[to] ?: run { _sendErrors.update { it + 1 }; return }
        val sealed = store.crypto.seal(wireJson.encodeToString(serializer, payload), key)
        send(type, wireJson.encodeToJsonElement(SealedPayload.serializer(), SealedPayload(sealed)), to, fields + "(end-to-end encrypted)")
    }

    private fun send(type: MsgType, payload: kotlinx.serialization.json.JsonElement, to: String? = null, fields: List<String>) {
        val key = _room.value ?: return
        val encrypted = kotlinx.serialization.json.JsonPrimitive(key.encrypt(payload.toString()))
        val env = key.sign(envelope(type, encrypted, to).copy(enc = true))
        router.markSent(env.messageId)
        val bytes = encode(env)
        if (!graph.mesh.broadcast(bytes)) { _sendErrors.update { it + 1 }; return }
        record(type, to?.let { id -> _state.value.cards[id]?.alias ?: "one person" } ?: "everyone in ${key.roomName}", fields, bytes.size)
    }

    private fun envelope(type: MsgType, payload: kotlinx.serialization.json.JsonElement, to: String?, ttl: Int = TTL) = Envelope(
        messageId = UUID.randomUUID().toString(), roomId = _room.value?.roomId.orEmpty(), senderId = store.selfId,
        type = type, timestamp = System.currentTimeMillis(), ttl = ttl, payload = payload, to = to,
    )

    private fun record(type: MsgType, to: String, fields: List<String>, bytes: Int) =
        _state.update { it.recordShare(LedgerEntry(System.currentTimeMillis(), type, to, fields, bytes)) }

    private fun encode(env: Envelope) = wireJson.encodeToString(Envelope.serializer(), env).encodeToByteArray()

    private fun <T> decode(env: Envelope, s: KSerializer<T>): T? = runCatching {
        if (env.to != null) {
            val sealed = wireJson.decodeFromJsonElement(SealedPayload.serializer(), env.payload).sealed
            val senderKey = peerKeys[env.senderId] ?: return null
            val plain = store.crypto.open(sealed, senderKey) ?: return null
            wireJson.decodeFromString(s, plain)
        } else {
            wireJson.decodeFromJsonElement(s, env.payload)
        }
    }.getOrNull()

    private fun nonEmptyFields(p: CapabilityProfile) = listOfNotNull(
        "offerings".takeIf { p.offerings.isNotEmpty() }, "needs".takeIf { p.needs.isNotEmpty() },
        "interests".takeIf { p.interests.isNotEmpty() }, "experience".takeIf { p.experience.isNotEmpty() },
        "intent".takeIf { p.intent.isNotEmpty() }, "collaboration".takeIf { p.collab.isNotEmpty() },
    )

    private fun identityFields(i: Identity) = listOfNotNull(
        "name", "headline".takeIf { i.headline.isNotBlank() }, "GitHub".takeIf { !i.github.isNullOrBlank() }, "contact".takeIf { !i.contact.isNullOrBlank() },
    )

    companion object {
        const val DEMO = "demo-"
        const val TTL = 3
    }
}

/** Sample attendees for single-phone demos. Always shown with "(demo)". */
object DemoPeers {
    val profiles = listOf(
        "Aarav" to CapabilityProfile(offerings = listOf("Android", "Kotlin", "Jetpack Compose"), needs = listOf("computer vision", "ML models"), interests = listOf("on-device AI"), intent = listOf("project team")),
        "Meera" to CapabilityProfile(offerings = listOf("UX research", "Figma", "visual design"), needs = listOf("frontend engineer"), interests = listOf("accessibility"), intent = listOf("side project")),
        "Kabir" to CapabilityProfile(offerings = listOf("FastAPI backend", "PostgreSQL", "cloud deployment"), needs = listOf("mobile developer"), interests = listOf("fintech"), intent = listOf("co-founder")),
        "Ananya" to CapabilityProfile(offerings = listOf("PyTorch", "computer vision", "data labelling"), needs = listOf("UI design", "product pitch"), interests = listOf("healthcare AI"), intent = listOf("project team")),
        "Rohan" to CapabilityProfile(offerings = listOf("product management", "pitch decks", "go-to-market"), needs = listOf("ML engineer", "backend APIs"), interests = listOf("edtech"), intent = listOf("startup idea")),
        "Ishita" to CapabilityProfile(offerings = listOf("React", "TypeScript", "web dashboards"), needs = listOf("data analytics"), interests = listOf("climate tech"), intent = listOf("open source")),
    )
}
