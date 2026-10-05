package com.sangam.core.matching

import com.sangam.core.model.CapabilityCard
import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.CardKind
import com.sangam.core.model.Field
import com.sangam.core.text.Embedder
import kotlin.math.roundToInt

enum class SearchMode(val label: String, val fields: List<Field>) {
    HELP_ME("Who can help me", listOf(Field.OFFERINGS, Field.EXPERIENCE)),
    I_CAN_HELP("Who needs what I offer", listOf(Field.NEEDS, Field.INTENT)),
    SIMILAR("People into this", listOf(Field.INTERESTS, Field.EXPERIENCE, Field.OFFERINGS)),
    PROJECTS("Projects that need this", listOf(Field.NEEDS, Field.INTENT)),
}

data class RankedCard(val card: CapabilityCard, val score: Double, val similarity: Float, val bestField: Field)

data class Complementarity(val theyHelpMe: Float, val iHelpThem: Float, val threshold: Float = 0.2f) {
    val mutual: Boolean get() = theyHelpMe >= threshold && iHelpThem >= threshold
    val combined: Float get() = (theyHelpMe + iHelpThem) / 2f + (if (mutual) 0.25f else 0f)
}

data class Recommendation(val card: CapabilityCard, val fit: Complementarity, val reasons: List<String>)

/** Field-aware semantic matching, entirely on-device. Search uses reciprocal rank fusion across the relevant fields. */
class MatchEngine(val embedder: Embedder) {
    private val cal = embedder.calibration

    fun card(peerId: String, alias: String, profile: CapabilityProfile, kind: CardKind = CardKind.PERSON, version: Int = 1, projectName: String? = null) =
        CapabilityCard(
            peerId = peerId, alias = alias, profile = profile, kind = kind, version = version, projectName = projectName,
            embeddings = Field.entries.associateWith { embedder.embed(profile.text(it)).toList() },
        )

    private fun vec(c: CapabilityCard, f: Field): FloatArray =
        c.embeddings[f]?.toFloatArray() ?: embedder.embed(c.profile.text(f))

    fun search(query: String, mode: SearchMode, cards: List<CapabilityCard>, k: Int = 10): List<RankedCard> {
        val q = embedder.embed(query)
        val pool = cards.filter { (mode == SearchMode.PROJECTS) == (it.kind == CardKind.PROJECT) }
        if (pool.isEmpty()) return emptyList()
        val sims = pool.associate { c -> c.peerId to mode.fields.associateWith { f -> embedder.cosine(q, vec(c, f)) } }
        val rrf = mutableMapOf<String, Double>()
        for (f in mode.fields) {
            pool.sortedByDescending { sims.getValue(it.peerId).getValue(f) }.forEachIndexed { rank, c ->
                if (sims.getValue(c.peerId).getValue(f) > 0f) rrf[c.peerId] = (rrf[c.peerId] ?: 0.0) + 1.0 / (RRF_K + rank + 1)
            }
        }
        return pool.mapNotNull { c ->
            val best = sims.getValue(c.peerId).maxBy { it.value }
            if (best.value < cal.minSimilarity) null else RankedCard(c, rrf[c.peerId] ?: 0.0, best.value, best.key)
        }.sortedWith(compareByDescending<RankedCard> { it.score }.thenByDescending { it.similarity }).take(k)
    }

    /** "They can help me" = my needs vs their offerings; "I can help them" = their needs vs my offerings. */
    fun complementarity(me: CapabilityCard, other: CapabilityCard) = Complementarity(
        theyHelpMe = embedder.cosine(vec(me, Field.NEEDS), vec(other, Field.OFFERINGS)),
        iHelpThem = embedder.cosine(vec(other, Field.NEEDS), vec(me, Field.OFFERINGS)),
        threshold = cal.mutual,
    )

    fun recommend(me: CapabilityCard, cards: List<CapabilityCard>, k: Int = 10): List<Recommendation> =
        cards.filter { it.peerId != me.peerId && it.kind == CardKind.PERSON }
            .map { Recommendation(it, complementarity(me, it), explain(me.profile, it.profile)) }
            .filter { it.fit.theyHelpMe > 0.05f || it.fit.iHelpThem > 0.05f }
            .sortedByDescending { it.fit.combined }
            .take(k)

    /** How well a person fills a project's missing roles, as a 1–100 percentage. */
    fun projectFit(project: CapabilityCard, person: CapabilityCard): Int {
        val c = embedder.cosine(vec(project, Field.NEEDS), vec(person, Field.OFFERINGS))
        return (100 * ((c - cal.minSimilarity) / (cal.fullFit - cal.minSimilarity))).roundToInt().coerceIn(1, 100)
    }

    /** Deterministic, human-readable reasons: which of my needs meets which of their offerings, and vice versa. */
    fun explain(me: CapabilityProfile, other: CapabilityProfile): List<String> =
        pairs(me.needs, other.offerings).map { (n, o) -> "You need “$n” — they offer “$o”" } +
            pairs(other.needs, me.offerings).map { (n, o) -> "They need “$n” — you offer “$o”" } +
            pairs(me.interests, other.interests).take(1).map { (a, b) -> "Shared interest: “$a” / “$b”" }

    private fun pairs(needs: List<String>, offers: List<String>): List<Pair<String, String>> {
        if (needs.isEmpty() || offers.isEmpty()) return emptyList()
        val offerVecs = offers.map { it to embedder.embed(it) }
        return needs.mapNotNull { n ->
            val nv = embedder.embed(n)
            val best = offerVecs.maxBy { embedder.cosine(nv, it.second) }
            if (embedder.cosine(nv, best.second) >= cal.itemMatch) n to best.first else null
        }.take(3)
    }

    val itemThreshold: Float get() = cal.itemMatch

    /** Best match of one phrase against a list of phrases (used for "unmet needs" on the Event Wall). */
    fun bestMatch(phrase: String, candidates: List<String>): Float {
        if (candidates.isEmpty()) return 0f
        val p = embedder.embed(phrase)
        return candidates.maxOf { embedder.cosine(p, embedder.embed(it)) }
    }

    companion object {
        const val RRF_K = 60
    }
}
