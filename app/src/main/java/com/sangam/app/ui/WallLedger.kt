package com.sangam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The Event Wall: an anonymous live map of the room, designed to be mirrored to a laptop, TV or projector.
 * Counts only: no names, no individual profiles.
 */
@Composable
fun WallScreen(vm: SangamViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val room by vm.room.collectAsState()
    val intros by vm.intros.collectAsState()
    val stats by androidx.compose.runtime.produceState(com.sangam.core.state.WallStats(0, 0, emptyList(), emptyList(), emptyList()), state.cards) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { vm.wall() }
    }
    val maxOffer = stats.topOfferings.maxOfOrNull { it.second } ?: 1
    Column(Modifier.fillMaxSize().background(River.ink).verticalScroll(rememberScrollState()).padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(room?.roomName ?: "Sangam", style = MaterialTheme.typography.headlineMedium, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Close", color = River.marigold) }
        }
        Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Big("${stats.people}", "people")
            Big("${stats.projects}", "projects")
            Big("${intros.count { it.status.name == "ACCEPTED" }}", "introductions")
        }
        Text("What the room can offer", style = MaterialTheme.typography.titleLarge, color = River.marigold)
        stats.topOfferings.forEach { (skill, n) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text(skill, style = MaterialTheme.typography.bodyLarge, color = Color.White, modifier = Modifier.width(170.dp))
                Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.1f))) {
                    Box(Modifier.fillMaxWidth(n.toFloat() / maxOffer).height(12.dp).clip(RoundedCornerShape(6.dp)).background(River.marigold))
                }
                Text("  $n", color = Color.White)
            }
        }
        Spacer(Modifier.height(20.dp))
        Text("Still looking for", style = MaterialTheme.typography.titleLarge, color = Color(0xFF9FB4FF))
        if (stats.unmetNeeds.isEmpty()) Text("Every need in the room has someone who can help.", color = Color.White)
        stats.unmetNeeds.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = Color.White) }
        Spacer(Modifier.height(20.dp))
        Text("Join by scanning the room QR on any member's phone. Matching runs on each phone; nothing goes to a server.", color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun Big(n: String, label: String) = Column {
    Text(n, style = MaterialTheme.typography.displayMedium, color = Color.White)
    Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.75f))
}

/** Every byte this phone has shared, with whom, and which fields. Identity rows appear only after an accepted introduction. */
@Composable
fun LedgerScreen(vm: SangamViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Privacy ledger", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Back") }
        }
        val identityShares = state.ledger.count { e -> e.fields.any { it == "name" || it == "contact" } }
        val n = state.ledger.size
        Hint(
            "$n message${if (n == 1) "" else "s"} sent, ${state.ledger.sumOf { it.bytes }} bytes in total. " +
                if (identityShares == 0) "Your name hasn't been shared with anyone."
                else "Your name was shared $identityShares time${if (identityShares == 1) "" else "s"}, each time only after you accepted an introduction.",
        )
        Spacer(Modifier.height(12.dp))
        if (state.ledger.isEmpty()) Hint("Nothing shared yet.")
        state.ledger.reversed().forEach { e ->
            val identity = e.fields.any { it == "name" || it == "contact" }
            Panel(Modifier.padding(bottom = 8.dp)) {
                Row {
                    Text(e.type.name.lowercase().replace('_', ' '), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(fmt.format(Date(e.timestamp)), style = MaterialTheme.typography.labelMedium, color = River.inkSoft)
                }
                Hint("To: ${e.to}")
                Hint("Shared: ${e.fields.joinToString()}", if (identity) River.marigold else River.ink)
                if (e.bytes > 0) Hint("${e.bytes} bytes")
            }
        }
    }
}
