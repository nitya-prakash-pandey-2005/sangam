package com.sangam.core.protocol

import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.CardKind
import com.sangam.core.model.Identity
import kotlinx.serialization.Serializable

/** Card as it travels: profile phrases only. Each phone computes embeddings locally with its own model. */
@Serializable
data class CardPayload(
    val peerId: String,
    val alias: String,
    val profile: CapabilityProfile,
    val kind: CardKind = CardKind.PERSON,
    val version: Int = 1,
    val projectName: String? = null,
    val ownerId: String? = null,
    val members: List<String> = emptyList(),
    /** The phone's E2E public key (P-256), so others can seal private messages to it. */
    val publicKey: String? = null,
)

/** Wrapper for any message addressed to one peer: only that peer's private key can open it. */
@Serializable
data class SealedPayload(val sealed: String)

@Serializable
data class SyncPayload(val cards: List<CardPayload>)

@Serializable
data class IntroRequest(val requestId: String, val fromAlias: String, val note: String, val reasons: List<String>)

@Serializable
data class IntroResponse(val requestId: String, val accepted: Boolean, val identity: Identity? = null)

@Serializable
data class RevealPayload(val requestId: String, val identity: Identity)

@Serializable
data class ChatPayload(val conversationId: String, val fromAlias: String, val text: String)

@Serializable
data class ProjectInvitePayload(val projectId: String, val projectName: String, val fit: Int, val note: String)

@Serializable
data class ProjectJoinPayload(val projectId: String, val peerId: String, val alias: String)
