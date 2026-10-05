package com.sangam.core

import com.sangam.core.protocol.PeerCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerCryptoTest {
    private val alice = PeerCrypto.generate()
    private val bob = PeerCrypto.generate()
    private val eve = PeerCrypto.generate()

    @Test
    fun `only the intended recipient can open a sealed message`() {
        val sealed = alice.seal("""{"name":"Asha","contact":"asha@example.com"}""", bob.publicKey)
        assertEquals("""{"name":"Asha","contact":"asha@example.com"}""", bob.open(sealed, alice.publicKey))
        assertNull(eve.open(sealed, alice.publicKey))
    }

    @Test
    fun `ciphertext does not contain the plaintext and differs every time`() {
        val a = alice.seal("secret phone 98450", bob.publicKey)
        val b = alice.seal("secret phone 98450", bob.publicKey)
        assertNotEquals(a, b)
        assert(!a.contains("98450"))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val sealed = alice.seal("hello", bob.publicKey)
        val flipped = sealed.dropLast(2) + (if (sealed.last() == 'A') "BB" else "AA")
        assertNull(bob.open(flipped, alice.publicKey))
    }

    @Test
    fun `room encryption hides payloads from phones without the room key`() {
        val room = com.sangam.core.protocol.RoomKey.create("Meetup")
        val sealed = room.encrypt("""{"offerings":["Android"]}""")
        assert(!sealed.contains("Android"))
        assertEquals("""{"offerings":["Android"]}""", room.decrypt(sealed))
        assertNull(com.sangam.core.protocol.RoomKey.create("Other").decrypt(sealed))
    }

    @Test
    fun `gossip clamps an inflated ttl`() {
        val router = com.sangam.core.protocol.GossipRouter("me", maxTtl = 3)
        val env = com.sangam.core.protocol.Envelope(messageId = "m", roomId = "r", senderId = "s", type = com.sangam.core.protocol.MsgType.CHAT_MESSAGE, timestamp = 1, ttl = 99, payload = kotlinx.serialization.json.JsonPrimitive(1))
        val d = router.onReceive(env) as com.sangam.core.protocol.RouteDecision.DeliverAndForward
        assertEquals(2, d.forward.ttl)
    }

    @Test
    fun `role nouns and plurals do not create fake matches`() {
        val e = com.sangam.core.text.HashingEmbedder()
        val ml = e.embed("ML engineer")
        // Shared word "engineer" must not beat the real skill overlap.
        assert(e.cosine(ml, e.embed("machine learning")) > e.cosine(ml, e.embed("frontend engineer")))
        assert(e.cosine(e.embed("designers"), e.embed("designer")) > 0.6f)
    }

    @Test
    fun `keys survive export and import`() {
        val restored = PeerCrypto.fromExport(alice.export())!!
        assertEquals(alice.publicKey, restored.publicKey)
        assertEquals("hi", bob.open(restored.seal("hi", bob.publicKey), alice.publicKey))
        assertNull(PeerCrypto.fromExport("garbage"))
    }
}
