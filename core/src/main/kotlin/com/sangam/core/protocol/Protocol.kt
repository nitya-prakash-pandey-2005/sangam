package com.sangam.core.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
enum class MsgType {
    HELLO, PROFILE_ANNOUNCE, SYNC_REQUEST, SYNC_RESPONSE,
    INTRO_REQUEST, INTRO_RESPONSE, REVEAL,
    CHAT_MESSAGE, PROJECT_ANNOUNCE, PROJECT_INVITE, PROJECT_JOIN, LEAVE,
}

/** Every message on the mesh. [ttl] is the only field that changes in transit, so it is excluded from the MAC. */
@Serializable
data class Envelope(
    val version: Int = 1,
    val messageId: String,
    val roomId: String,
    val senderId: String,
    val type: MsgType,
    val timestamp: Long,
    val ttl: Int,
    val payload: JsonElement,
    /** Set for messages meant for one peer (intro, reveal, direct chat); null = whole room. */
    val to: String? = null,
    /** True when [payload] is a string encrypted with the room key (see [RoomKey.encrypt]). */
    val enc: Boolean = false,
    val mac: String = "",
)

val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Room membership: the room secret travels only in the QR code people scan to join. Payloads are encrypted with a key
 * derived from it (AES-256-GCM) and every envelope carries an HMAC-SHA256, so a phone that is merely nearby (not in
 * the room) can neither read room traffic nor inject messages into it.
 */
class RoomKey(val roomId: String, val roomName: String, private val secret: ByteArray) {

    fun sign(env: Envelope): Envelope = env.copy(roomId = roomId, mac = mac(env))

    fun verify(env: Envelope): Boolean = env.roomId == roomId && env.mac.isNotEmpty() &&
        java.security.MessageDigest.isEqual(env.mac.toByteArray(), mac(env).toByteArray())

    private fun mac(env: Envelope): String {
        val canonical = wireJson.encodeToString(Envelope.serializer(), env.copy(ttl = 0, mac = "", roomId = roomId))
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(canonical.toByteArray()))
    }

    private val encKey by lazy {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        SecretKeySpec(m.doFinal("sangam-room-enc-v1".toByteArray()), "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, encKey, javax.crypto.spec.GCMParameterSpec(128, iv))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(iv + c.doFinal(plaintext.toByteArray()))
    }

    fun decrypt(sealed: String): String? = runCatching {
        val b = Base64.getUrlDecoder().decode(sealed)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, encKey, javax.crypto.spec.GCMParameterSpec(128, b.copyOf(12)))
        String(c.doFinal(b.copyOfRange(12, b.size)))
    }.getOrNull()

    fun toQr(): String =
        "sangam://join?v=1&r=$roomId&n=${URLEncoder.encode(roomName, "UTF-8")}&k=${Base64.getUrlEncoder().withoutPadding().encodeToString(secret)}"

    companion object {
        fun create(name: String): RoomKey =
            RoomKey(UUID.randomUUID().toString(), name, ByteArray(32).also { SecureRandom().nextBytes(it) })

        fun fromQr(text: String): RoomKey? = runCatching {
            require(text.startsWith("sangam://join?"))
            val params = text.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val secret = Base64.getUrlDecoder().decode(params.getValue("k"))
            require(secret.size >= 16)
            RoomKey(params.getValue("r"), URLDecoder.decode(params["n"] ?: "Room", "UTF-8"), secret)
        }.getOrNull()
    }
}

sealed interface RouteDecision {
    data object Duplicate : RouteDecision
    data object DeliverOnly : RouteDecision
    data class DeliverAndForward(val forward: Envelope) : RouteDecision
}

/** Flood-with-TTL gossip: deliver each message once, forward it while hops remain. Event-sized rooms need nothing more. */
class GossipRouter(private val selfId: String, private val capacity: Int = 4096, private val maxTtl: Int = 3) {
    private val seen = object : LinkedHashMap<String, Unit>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>?) = size > capacity
    }

    @Synchronized
    fun markSent(messageId: String) { seen[messageId] = Unit }

    @Synchronized
    fun onReceive(env: Envelope): RouteDecision {
        if (env.senderId == selfId) return RouteDecision.Duplicate
        if (seen.containsKey(env.messageId)) return RouteDecision.Duplicate
        seen[env.messageId] = Unit
        val ttl = minOf(env.ttl, maxTtl) // TTL is outside the MAC, so never trust a larger value
        return if (ttl > 0) RouteDecision.DeliverAndForward(env.copy(ttl = ttl - 1)) else RouteDecision.DeliverOnly
    }
}
