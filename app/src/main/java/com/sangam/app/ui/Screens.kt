package com.sangam.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.sangam.app.ai.AiStatus
import com.sangam.app.ai.Models
import com.sangam.app.community.IntroStatus
import com.sangam.core.matching.SearchMode
import com.sangam.core.model.CapabilityCard
import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.Identity

@Composable
private fun Screen(title: String, sub: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp))
        if (sub != null) Hint(sub)
        Spacer(Modifier.height(16.dp))
        content()
        Spacer(Modifier.height(32.dp))
    }
}

// ---------------- Room ----------------

@Composable
fun RoomScreen(vm: SangamViewModel, onWall: () -> Unit, onLedger: () -> Unit) {
    val room by vm.room.collectAsState()
    val mesh by vm.mesh.collectAsState()
    val state by vm.state.collectAsState()
    val rejected by vm.rejected.collectAsState()
    val demo by vm.demo.collectAsState()
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val r = room
    if (r == null) {
        Screen("Sangam", "Find the people in this room who can help you, and the ones you can help. Works offline; your phone does the matching.") {
            Panel {
                Text("Start a room", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(name, { name = it }, label = { Text("Room name, e.g. BLR AI Meetup") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Primary("Create room", { vm.createRoom(name) })
            }
            Spacer(Modifier.height(12.dp))
            Panel {
                Text("Join a room", style = MaterialTheme.typography.titleLarge)
                Hint("Scan the room's QR code on the host's phone or the event screen.")
                Spacer(Modifier.height(8.dp))
                Primary("Scan QR code", {
                    GmsBarcodeScanning.getClient(ctx).startScan()
                        .addOnSuccessListener { b -> if (b.rawValue?.let { vm.joinRoom(it) } != true) error = "That QR code is not a Sangam room." }
                        .addOnFailureListener { error = "Scanner unavailable: ${it.message}" }
                })
                OutlinedTextField(code, { code = it }, label = { Text("Or paste a room link") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { if (!vm.joinRoom(code)) error = "That link is not a Sangam room." }) { Text("Join with link") }
                error?.let { Hint(it, River.alert) }
            }
        }
        return
    }

    val people = state.people.size
    val projects = state.projects.size
    Screen(r.roomName, "$people ${if (people == 1) "person" else "people"} and $projects project${if (projects == 1) "" else "s"} in your phone's copy of the room") {
        Panel {
            val bmp = remember(r.roomId) { vm.qr(r.toQr()).asImageBitmap() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(bmp, contentDescription = "Room QR code", modifier = Modifier.size(132.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Others join by scanning this", style = MaterialTheme.typography.titleMedium)
                    Hint("The code carries the room key. Phones without it can't read or post in this room.")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Panel {
            Text(if (mesh.connected.isEmpty()) "Looking for phones nearby…" else "Connected to ${mesh.connected.size} phone${if (mesh.connected.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
            mesh.connected.values.forEach { Hint("• $it", River.confluence) }
            mesh.error?.let { Hint(it, River.alert) }
            if (rejected > 0) Hint("$rejected messages from outside this room were ignored.", River.inkSoft)
            Hint("Bluetooth and Wi-Fi only. No internet, no server.")
            TextButton(onClick = { vm.restartMesh() }) { Text("Restart discovery") }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Secondary("Event Wall", onWall, Modifier.weight(1f))
            Secondary("Privacy ledger", onLedger, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Demo people", style = MaterialTheme.typography.titleMedium)
                Hint("Adds six sample attendees, marked (demo), so one phone can show matching.")
            }
            Switch(demo, { vm.setDemo(it) })
        }
        TextButton(onClick = { vm.leaveRoom() }) { Text("Leave room", color = River.alert) }
    }
}

// ---------------- Discover ----------------

@Composable
fun DiscoverScreen(vm: SangamViewModel) {
    val state by vm.state.collectAsState()
    val profile by vm.profile.collectAsState()
    var query by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(SearchMode.HELP_ME) }

    Screen("Discover", "Ranked on this phone. People stay anonymous until you both agree.") {
        if (profile.isBlank) { Hint("Fill in your profile on the Me tab to get matches.", River.alert); return@Screen }
        OutlinedTextField(query, { query = it }, label = { Text("Search the room, e.g. someone for Android UI") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchMode.entries.forEach { m -> FilterChip(selected = m == mode, onClick = { mode = m }, label = { Text(m.label) }) }
        }
        Spacer(Modifier.height(8.dp))
        if (query.isNotBlank()) {
            val results by produceState(emptyList<com.sangam.core.matching.RankedCard>(), query, mode, state.cards) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.search(query, mode) }
            }
            if (results.isEmpty()) Hint("Nobody in the room matches that yet.")
            results.forEach { r -> CardRow(vm, r.card, subtitle = "${(r.similarity * 100).toInt()}% match on ${r.bestField.name.lowercase()}") }
        } else {
            Text("Best matches for you", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 8.dp))
            val recs by produceState(emptyList<com.sangam.core.matching.Recommendation>(), state.cards, profile) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.recommendations() }
            }
            if (recs.isEmpty()) Hint("No one here yet. Waiting for nearby phones, or turn on demo people in Room.")
            recs.forEach { rec ->
                Panel(Modifier.padding(bottom = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(rec.card.alias, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        if (rec.fit.mutual) Chip("Mutual match", River.confluence)
                    }
                    ConfluenceBar(rec.fit.theyHelpMe, rec.fit.iHelpThem)
                    rec.reasons.take(3).forEach { Hint("• $it", River.ink) }
                    Hint("Offers: ${rec.card.profile.offerings.joinToString()}")
                    Spacer(Modifier.height(8.dp))
                    Primary("Request introduction", { vm.requestIntro(rec.card, rec.reasons) })
                }
            }
        }
    }
}

@Composable
private fun CardRow(vm: SangamViewModel, card: CapabilityCard, subtitle: String) {
    Panel(Modifier.padding(bottom = 8.dp)) {
        Text(card.projectName ?: card.alias, style = MaterialTheme.typography.titleMedium)
        Hint(subtitle, River.confluence)
        Hint("Offers: ${card.profile.offerings.joinToString().ifBlank { "—" }}")
        Hint("Needs: ${card.profile.needs.joinToString().ifBlank { "—" }}")
        TextButton(onClick = { vm.requestIntro(card, vm.reasons(card)) }) { Text("Request introduction") }
    }
}

// ---------------- Inbox ----------------

@Composable
fun InboxScreen(vm: SangamViewModel, onChat: (String, String) -> Unit) {
    val intros by vm.intros.collectAsState()
    val invites by vm.invites.collectAsState()
    Screen("Inbox", "Introductions need both people to agree. Only then are names shared.") {
        val incoming = intros.filter { it.incoming && it.status == IntroStatus.PENDING }
        if (incoming.isNotEmpty()) Text("Asking to meet you", style = MaterialTheme.typography.titleLarge)
        incoming.forEach { i ->
            Panel(Modifier.padding(vertical = 6.dp)) {
                Text(i.alias, style = MaterialTheme.typography.titleMedium)
                i.reasons.take(3).forEach { Hint("• $it", River.ink) }
                Hint("Accepting shares your name and contact details with ${i.alias} only.")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Secondary("Not now", { vm.respond(i, false) }, Modifier.weight(1f))
                    Primary("Accept", { vm.respond(i, true) }, Modifier.weight(1f))
                }
            }
        }
        val connected = intros.filter { it.status == IntroStatus.ACCEPTED }
        Text("Connected", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
        if (connected.isEmpty()) Hint("No introductions yet. Find matches in Discover.")
        connected.forEach { i ->
            Panel(Modifier.padding(vertical = 6.dp).clickable { onChat(vm.conversationWith(i.peerId), i.peerId) }) {
                Text(i.identity?.name?.ifBlank { null } ?: i.alias, style = MaterialTheme.typography.titleMedium)
                i.identity?.headline?.takeIf { it.isNotBlank() }?.let { Hint(it) }
                i.identity?.github?.takeIf { it.isNotBlank() }?.let { Hint(it, River.indigo) }
                i.identity?.contact?.takeIf { it.isNotBlank() }?.let { Hint(it, River.indigo) }
                Hint("Tap to chat", River.confluence)
            }
        }
        val waiting = intros.filter { !it.incoming && it.status == IntroStatus.PENDING }
        if (waiting.isNotEmpty()) {
            Text("Waiting for an answer", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            waiting.forEach { Hint("• ${it.alias}") }
        }
        val openInvites = invites.filter { !it.joined }
        if (openInvites.isNotEmpty()) {
            Text("Project invitations", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
            openInvites.forEach { inv ->
                Panel(Modifier.padding(vertical = 6.dp)) {
                    Text(inv.projectName, style = MaterialTheme.typography.titleMedium)
                    Hint("You fit ${inv.fit}% of what this team is missing.")
                    Primary("Join project", { vm.acceptInvite(inv) })
                }
            }
        }
    }
}

@Composable
fun ChatScreen(vm: SangamViewModel, conversationId: String, peerId: String?, onBack: () -> Unit) {
    val chats by vm.chats.collectAsState()
    var text by remember { mutableStateOf("") }
    val msgs = chats[conversationId].orEmpty()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Conversation", style = MaterialTheme.typography.titleLarge)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(msgs) { m ->
                Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromSelf) Alignment.CenterEnd else Alignment.CenterStart) {
                    Text(
                        m.text, style = MaterialTheme.typography.bodyLarge,
                        color = if (m.fromSelf) River.paper else River.ink,
                        modifier = Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                            .background(if (m.fromSelf) River.ink else River.paper).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f), placeholder = { Text("Message") })
            TextButton(onClick = { if (text.isNotBlank()) { vm.sendChat(conversationId, text, peerId); text = "" } }) { Text("Send") }
        }
        Hint("Sent phone-to-phone in this room. Nothing is stored on a server.")
    }
}

// ---------------- Projects ----------------

@Composable
fun ProjectsScreen(vm: SangamViewModel) {
    val state by vm.state.collectAsState()
    var name by remember { mutableStateOf("") }
    var has by remember { mutableStateOf("") }
    var needs by remember { mutableStateOf("") }
    var goal by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }

    Screen("Projects", "Describe what your team has and what it's missing. Sangam finds people nearby who fill the gap.") {
        state.projects.forEach { p ->
            Panel(Modifier.padding(bottom = 10.dp).clickable { open = if (open == p.peerId) null else p.peerId }) {
                Text(p.projectName ?: p.alias, style = MaterialTheme.typography.titleLarge)
                Hint("Has: ${p.profile.offerings.joinToString()}")
                Hint("Missing: ${p.profile.needs.joinToString()}", River.marigold)
                Hint("Team: ${p.members.joinToString()}")
                if (open == p.peerId && p.ownerId == vm.selfId) {
                    Text("People nearby who fill the gap", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                    val candidates by produceState(emptyList<Pair<CapabilityCard, Int>>(), p, state.cards) {
                        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.projectCandidates(p) }
                    }
                    // Near-zero fits are noise, not suggestions.
                    val useful = candidates.filter { it.second >= 10 }
                    if (useful.isEmpty()) Hint("No one in the room fills this gap yet. New people appear here as they join.")
                    useful.take(5).forEach { (person, fit) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(person.alias, style = MaterialTheme.typography.titleMedium)
                                Hint(person.profile.offerings.joinToString())
                            }
                            Chip("$fit% fit", if (fit >= 60) River.confluence else River.inkSoft)
                            TextButton(onClick = { vm.invite(p, person, fit) }) { Text("Invite") }
                        }
                    }
                }
            }
        }
        Panel {
            Text("Start a project", style = MaterialTheme.typography.titleLarge)
            if (name.isBlank() && needs.isBlank()) {
                TextButton(onClick = {
                    name = "Offline accessibility app"
                    goal = "Help visually impaired users read signboards offline"
                    has = "computer vision, PyTorch, backend APIs"
                    needs = "Android, UI/UX design"
                }) { Text("Try an example project") }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(goal, { goal = it }, label = { Text("Goal") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(has, { has = it }, label = { Text("Skills the team has (comma separated)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(needs, { needs = it }, label = { Text("Skills you're missing (comma separated)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Primary("Create project", {
                vm.createProject(name.ifBlank { "Untitled project" }, CapabilityProfile(offerings = split(has), needs = split(needs), intent = listOfNotNull(goal.ifBlank { null })))
                name = ""; has = ""; needs = ""; goal = ""
            }, enabled = needs.isNotBlank())
        }
    }
}

private fun split(s: String) = s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

// ---------------- Me ----------------

@Composable
fun MeScreen(vm: SangamViewModel) {
    val profile by vm.profile.collectAsState()
    val identity by vm.identity.collectAsState()
    val ai by vm.ai.collectAsState()
    val semantic by vm.semantic.collectAsState()
    val extracting by vm.extracting.collectAsState()
    val ctx = LocalContext.current

    var offerings by remember(profile) { mutableStateOf(profile.offerings.joinToString(", ")) }
    var needs by remember(profile) { mutableStateOf(profile.needs.joinToString(", ")) }
    var interests by remember(profile) { mutableStateOf(profile.interests.joinToString(", ")) }
    var experience by remember(profile) { mutableStateOf(profile.experience.joinToString(", ")) }
    var intent by remember(profile) { mutableStateOf(profile.intent.joinToString(", ")) }
    var resume by remember { mutableStateOf("") }
    var name by remember(identity) { mutableStateOf(identity.name) }
    var headline by remember(identity) { mutableStateOf(identity.headline) }
    var github by remember(identity) { mutableStateOf(identity.github.orEmpty()) }
    var contact by remember(identity) { mutableStateOf(identity.contact.orEmpty()) }

    Screen("Me", "You appear as ${vm.alias}. Others see only what you can offer and need, never your name, until you both agree.") {
        Panel {
            Text("Fill from your resume", style = MaterialTheme.typography.titleLarge)
            Hint("Paste a resume, GitHub README or a few lines about yourself. Gemma reads it on this phone; the text never leaves it.")
            OutlinedTextField(resume, { resume = it }, modifier = Modifier.fillMaxWidth().height(120.dp).padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            Primary(if (extracting) "Reading on your phone…" else "Fill my profile", {
                vm.extractProfile(resume) { p ->
                    offerings = p.offerings.joinToString(", "); needs = p.needs.joinToString(", "); interests = p.interests.joinToString(", ")
                    experience = p.experience.joinToString(", "); intent = p.intent.joinToString(", ")
                }
            }, enabled = resume.isNotBlank() && !extracting)
        }
        Spacer(Modifier.height(12.dp))
        Panel {
            Text("What you share anonymously", style = MaterialTheme.typography.titleLarge)
            if (offerings.isBlank() && needs.isBlank()) {
                TextButton(onClick = {
                    offerings = "computer vision, PyTorch, backend APIs"
                    needs = "Android developer, UI design"
                    interests = "edge AI, accessibility"
                    experience = "3 years ML, 2 shipped apps"
                    intent = "project team"
                }) { Text("Try an example profile") }
            }
            Field("I can offer", offerings) { offerings = it }
            Field("I'm looking for", needs) { needs = it }
            Field("I'm interested in", interests) { interests = it }
            Field("Experience", experience) { experience = it }
            Field("Right now I want", intent) { intent = it }
            Spacer(Modifier.height(8.dp))
            Primary("Save and share with the room", {
                vm.saveProfile(CapabilityProfile(split(offerings), split(needs), split(interests), split(experience), split(intent), profile.collab))
            })
        }
        Spacer(Modifier.height(12.dp))
        Panel {
            Text("Shared only after you both agree", style = MaterialTheme.typography.titleLarge)
            Field("Name", name) { name = it }
            Field("Headline", headline) { headline = it }
            Field("GitHub or portfolio", github) { github = it }
            Field("Contact (phone, email or handle)", contact) { contact = it }
            Spacer(Modifier.height(8.dp))
            Secondary("Save identity", { vm.saveIdentity(Identity(name, headline, github.ifBlank { null }, contact.ifBlank { null })) }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
        Panel {
            Text("On-device AI", style = MaterialTheme.typography.titleLarge)
            Hint(
                when (val s = ai) {
                    AiStatus.None -> "No Gemma model installed. Profiles still work by typing."
                    AiStatus.Idle -> "Gemma is installed; it loads the first time you use it."
                    AiStatus.Loading -> "Loading Gemma…"
                    is AiStatus.Ready -> "${s.name} is ready on the GPU."
                    is AiStatus.Failed -> "Gemma failed to load: ${s.message}"
                },
            )
            Hint(if (semantic) "Matching uses EmbeddingGemma on this phone." else "Matching uses the built-in offline matcher. Add an EmbeddingGemma model for semantic matching.")
            Hint("Models folder: ${Models.dir(ctx).absolutePath}")
            if (ai == AiStatus.Idle) TextButton(onClick = { vm.loadAi() }) { Text("Load Gemma now") }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
