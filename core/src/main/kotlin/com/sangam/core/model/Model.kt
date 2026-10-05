package com.sangam.core.model

import kotlinx.serialization.Serializable

/** The six semantic fields of a capability profile; each gets its own embedding. */
@Serializable
enum class Field { OFFERINGS, NEEDS, INTERESTS, EXPERIENCE, INTENT, COLLAB }

/** What I can do and what I need — deliberately NOT who I am. */
@Serializable
data class CapabilityProfile(
    val offerings: List<String> = emptyList(),
    val needs: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val experience: List<String> = emptyList(),
    val intent: List<String> = emptyList(),
    val collab: List<String> = emptyList(),
) {
    fun items(field: Field): List<String> = when (field) {
        Field.OFFERINGS -> offerings
        Field.NEEDS -> needs
        Field.INTERESTS -> interests
        Field.EXPERIENCE -> experience
        Field.INTENT -> intent
        Field.COLLAB -> collab
    }

    fun text(field: Field): String = items(field).joinToString(", ")

    val isBlank: Boolean get() = Field.entries.all { items(it).isEmpty() }
}

@Serializable
enum class CardKind { PERSON, PROJECT }

/** The only thing other phones ever see before mutual consent: an anonymous capability card. */
@Serializable
data class CapabilityCard(
    val peerId: String,
    val alias: String,
    val profile: CapabilityProfile,
    val kind: CardKind = CardKind.PERSON,
    val version: Int = 1,
    val embeddings: Map<Field, List<Float>> = emptyMap(),
    val projectName: String? = null,
    val ownerId: String? = null,
    val members: List<String> = emptyList(),
)

/** Revealed only to a peer after both sides accept an introduction. */
@Serializable
data class Identity(
    val name: String,
    val headline: String = "",
    val github: String? = null,
    val contact: String? = null,
)

@Serializable
enum class RevealLevel { ANONYMOUS, PROFILE, IDENTITY }
