package com.sangam.core.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-phone end-to-end encryption for private messages (introductions, identity reveal, direct chat).
 * The mesh relays them through other phones; only the recipient can read them.
 * ECDH on P-256 → HKDF-SHA256 → AES-256-GCM with a fresh 96-bit IV per message. Standard JCA, works on JVM and Android.
 */
class PeerCrypto private constructor(private val keys: KeyPair) {

    /** Base64 X.509 public key, published in the capability card. */
    val publicKey: String = b64(keys.public.encoded)

    fun seal(plaintext: String, recipientPublicKey: String): String {
        val key = sharedKey(decodePublic(recipientPublicKey))
        val iv = ByteArray(12).also { RNG.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return b64(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    /** Returns null if the message was not meant for us, or was tampered with. */
    fun open(sealed: String, senderPublicKey: String): String? = runCatching {
        val bytes = Base64.getUrlDecoder().decode(sealed)
        val key = sharedKey(decodePublic(senderPublicKey))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOf(12))) }
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    fun export(): String = b64(keys.private.encoded) + "." + b64(keys.public.encoded)

    private fun sharedKey(peer: PublicKey): SecretKeySpec {
        val secret = KeyAgreement.getInstance("ECDH").apply { init(keys.private); doPhase(peer, true) }.generateSecret()
        // Both sides derive the same key: the info string uses the two public keys in sorted order.
        val pubs = listOf(publicKey, b64(peer.encoded)).sorted().joinToString("|")
        return SecretKeySpec(hkdf(secret, "sangam-e2e-v1|$pubs".toByteArray()), "AES")
    }

    companion object {
        private val RNG = SecureRandom()

        fun generate(): PeerCrypto =
            PeerCrypto(KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), RNG) }.generateKeyPair())

        fun fromExport(text: String): PeerCrypto? = runCatching {
            val (priv, pub) = text.split('.').also { require(it.size == 2) }
            val kf = KeyFactory.getInstance("EC")
            val privateKey: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getUrlDecoder().decode(priv)))
            val publicKey: PublicKey = kf.generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(pub)))
            PeerCrypto(KeyPair(publicKey, privateKey))
        }.getOrNull()

        private fun decodePublic(b64: String): PublicKey =
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(b64)))

        /** HKDF-SHA256 (RFC 5869) extract-and-expand to 32 bytes. */
        private fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
            val salt = MessageDigest.getInstance("SHA-256").digest("sangam-salt".toByteArray())
            val prk = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }.doFinal(ikm)
            return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }.doFinal(info + byteArrayOf(1))
        }

        private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }
}
