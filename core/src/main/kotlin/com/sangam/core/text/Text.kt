package com.sangam.core.text

import com.sangam.core.model.CapabilityProfile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.sqrt

/** Anything that turns text into a vector: the hashing fallback here, EmbeddingGemma on the phone. */
/** Score thresholds differ by embedding model: semantic models give unrelated text a higher baseline cosine. */
data class Calibration(val mutual: Float, val itemMatch: Float, val fullFit: Float, val minSimilarity: Float)

interface Embedder {
    val dim: Int
    val id: String
    val calibration: Calibration
    fun embed(text: String): FloatArray

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in 0 until minOf(a.size, b.size)) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0f || nb == 0f) 0f else dot / (sqrt(na) * sqrt(nb))
    }
}

/**
 * Deterministic, model-free embedder: words + concept groups (so "CV" meets "computer vision") + character trigrams,
 * hashed into [dim] buckets. Works offline on any phone; EmbeddingGemma replaces it when installed.
 */
class HashingEmbedder(override val dim: Int = 256) : Embedder {
    override val id = "hash-v2-$dim"
    override val calibration = Calibration(mutual = 0.2f, itemMatch = 0.25f, fullFit = 0.6f, minSimilarity = 0.05f)

    override fun embed(text: String): FloatArray {
        val v = FloatArray(dim)
        val words = tokenize(text)
        for (w in words) {
            add(v, "w:$w", WORD_WEIGHT)
            if (w.length >= 3) for (i in 0..w.length - 3) add(v, "t:${w.substring(i, i + 3)}", TRIGRAM_WEIGHT)
        }
        words.mapNotNull { CONCEPT_OF[it] }.toSet().forEach { add(v, "c:$it", CONCEPT_WEIGHT) }
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    private fun add(v: FloatArray, feature: String, weight: Float) {
        val h = fnv1a(feature)
        val idx = ((h shr 1) % dim.toUInt()).toInt()
        v[idx] += if (h and 1u == 0u) weight else -weight
    }

    private fun fnv1a(s: String): UInt {
        var h = 2166136261u
        for (ch in s) { h = h xor ch.code.toUInt(); h *= 16777619u }
        return h
    }

    companion object {
        private const val WORD_WEIGHT = 1.0f
        private const val CONCEPT_WEIGHT = 1.5f
        private const val TRIGRAM_WEIGHT = 0.3f

        private val STOP = setOf(
            "a", "an", "the", "and", "or", "for", "of", "to", "in", "on", "with", "my", "i", "me", "we", "our", "who", "can",
            "someone", "somebody", "build", "need", "needs", "looking", "help", "want", "is", "are", "be", "at", "by", "it",
            // role nouns: "ML engineer" vs "frontend engineer" must not match on "engineer"
            "engineer", "developer", "dev", "expert", "specialist", "skill", "year", "experience", "good", "strong",
            "team", "person", "people",
        )

        /** Concept groups: a shared concept token lets different words for the same skill meet. */
        private val GROUPS = mapOf(
            "mobile" to listOf("android", "kotlin", "ios", "swift", "mobile", "compose", "jetpack", "flutter", "app", "apps"),
            "vision" to listOf("cv", "vision", "image", "images", "opencv", "yolo", "detection", "recognition", "camera"),
            "ml" to listOf("ml", "machine", "learning", "ai", "pytorch", "tensorflow", "model", "models", "deep", "neural", "llm", "gemma"),
            "design" to listOf("ui", "ux", "design", "designer", "figma", "visual", "interface", "interfaces"),
            "frontend" to listOf("frontend", "react", "web", "css", "javascript", "typescript", "compose", "ui"),
            "backend" to listOf("backend", "api", "apis", "server", "database", "fastapi", "node", "django", "spring"),
            "data" to listOf("data", "analytics", "sql", "pandas", "dashboard"),
            "product" to listOf("product", "pm", "roadmap", "pitch", "business"),
            "food" to listOf("cooking", "baking", "catering", "chef", "food"),
        )
        private val CONCEPT_OF: Map<String, String> = buildMap {
            GROUPS.forEach { (concept, words) -> words.forEach { w -> putIfAbsent(w, concept) } }
        }

        fun tokenize(text: String): List<String> =
            text.lowercase().split(Regex("[^\\p{L}\\p{N}+#]+")).filter { it.isNotBlank() }.map(::stem).filter { it !in STOP }

        /** Minimal plural stemming: designers -> designer, apis -> api (short words and "ss" endings kept). */
        private fun stem(w: String): String = when {
            w.length > 4 && w.endsWith("ies") -> w.dropLast(3) + "y"
            w.length > 3 && w.endsWith("s") && !w.endsWith("ss") -> w.dropLast(1)
            else -> w
        }
    }
}

/** Reads the profile JSON written by the on-device model from a pasted resume / README. */
object ProfileParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    private data class Dto(
        val offerings: List<String> = emptyList(),
        val needs: List<String> = emptyList(),
        val interests: List<String> = emptyList(),
        val experience: List<String> = emptyList(),
        val intent: List<String> = emptyList(),
        @SerialName("collaboration_preferences") val collab: List<String> = emptyList(),
    )

    fun parse(reply: String): CapabilityProfile? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val dto = runCatching { json.decodeFromString<Dto>(reply.substring(start, end + 1)) }.getOrNull() ?: return null
        fun clean(l: List<String>) = l.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
        val p = CapabilityProfile(clean(dto.offerings), clean(dto.needs), clean(dto.interests), clean(dto.experience), clean(dto.intent), clean(dto.collab))
        return p.takeUnless { it.isBlank }
    }

    val SYSTEM = """
        You turn a person's resume, README or self-description into a capability profile for a professional meetup app.
        Use short phrases (2-4 words). Do not include names, emails, phone numbers or company secrets.
        Reply with ONE JSON object only:
        {"offerings":[skills they can contribute],"needs":[skills or help they are looking for],"interests":[topics],
         "experience":[short evidence, e.g. "3 years Android"],"intent":[what they want now, e.g. "project team"],
         "collaboration_preferences":[e.g. "weekends", "remote ok"]}
    """.trimIndent()

    val SCHEMA = """
        {"type":"object","properties":{
          "offerings":{"type":"array","items":{"type":"string"}},
          "needs":{"type":"array","items":{"type":"string"}},
          "interests":{"type":"array","items":{"type":"string"}},
          "experience":{"type":"array","items":{"type":"string"}},
          "intent":{"type":"array","items":{"type":"string"}},
          "collaboration_preferences":{"type":"array","items":{"type":"string"}}},
         "required":["offerings","needs","interests","experience","intent","collaboration_preferences"]}
    """.trimIndent()
}
