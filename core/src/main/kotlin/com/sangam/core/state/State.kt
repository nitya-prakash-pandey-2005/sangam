package com.sangam.core.state

import com.sangam.core.matching.MatchEngine
import com.sangam.core.model.CapabilityCard
import com.sangam.core.model.CardKind
import com.sangam.core.model.Field
import com.sangam.core.protocol.MsgType
import kotlinx.serialization.Serializable

/** One row of the privacy ledger: what this phone sent, to whom, and which fields. Identity appears only after consent. */
@Serializable
data class LedgerEntry(
    val timestamp: Long,
    val type: MsgType,
    val to: String,
    val fields: List<String>,
    val bytes: Int,
)

/** The phone's local copy of the room: the "community index" lives on each phone, not on a server. */
data class CommunityState(
    val selfId: String,
    val cards: Map<String, CapabilityCard> = emptyMap(),
    val ledger: List<LedgerEntry> = emptyList(),
) {
    fun upsertCard(card: CapabilityCard): CommunityState {
        val existing = cards[card.peerId]
        if (existing != null && existing.version >= card.version) return this
        return copy(cards = cards + (card.peerId to card))
    }

    fun remove(peerId: String) = copy(cards = cards - peerId)

    fun recordShare(entry: LedgerEntry) = copy(ledger = ledger + entry)

    val people: List<CapabilityCard> get() = cards.values.filter { it.kind == CardKind.PERSON }
    val projects: List<CapabilityCard> get() = cards.values.filter { it.kind == CardKind.PROJECT }
}

/** Aggregates for the laptop "Event Wall": anonymous, counts only. */
data class WallStats(
    val people: Int,
    val projects: Int,
    val topOfferings: List<Pair<String, Int>>,
    val topNeeds: List<Pair<String, Int>>,
    val unmetNeeds: List<String>,
) {
    companion object {
        fun compute(cards: List<CapabilityCard>, engine: MatchEngine): WallStats {
            val persons = cards.filter { it.kind == CardKind.PERSON }
            fun top(field: Field) = cards.flatMap { it.profile.items(field) }
                .groupingBy { it.trim().lowercase() }.eachCount()
                .entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
            val unmet = cards.flatMap { c ->
                val others = cards.filter { it.peerId != c.peerId }.flatMap { it.profile.offerings }
                c.profile.needs.filter { engine.bestMatch(it, others) < engine.itemThreshold }
            }.map { it.trim() }.distinctBy { it.lowercase() }.take(8)
            return WallStats(persons.size, cards.size - persons.size, top(Field.OFFERINGS), top(Field.NEEDS), unmet)
        }
    }
}
