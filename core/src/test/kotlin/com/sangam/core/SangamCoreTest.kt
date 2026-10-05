package com.sangam.core

import com.sangam.core.matching.MatchEngine
import com.sangam.core.matching.SearchMode
import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.CardKind
import com.sangam.core.model.Field
import com.sangam.core.protocol.Envelope
import com.sangam.core.protocol.GossipRouter
import com.sangam.core.protocol.MsgType
import com.sangam.core.protocol.RoomKey
import com.sangam.core.protocol.RouteDecision
import com.sangam.core.state.CommunityState
import com.sangam.core.state.LedgerEntry
import com.sangam.core.state.WallStats
import com.sangam.core.text.HashingEmbedder
import com.sangam.core.text.ProfileParser
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SangamCoreTest {
    private val embedder = HashingEmbedder()
    private val engine = MatchEngine(embedder)

    private val me = CapabilityProfile(
        offerings = listOf("computer vision", "PyTorch", "backend APIs"),
        needs = listOf("Android developer", "UI design"),
        interests = listOf("edge AI", "accessibility"),
        intent = listOf("project team"),
    )
    private val android = CapabilityProfile(
        offerings = listOf("Android", "Kotlin", "Jetpack Compose UI"),
        needs = listOf("machine learning", "image recognition"),
        interests = listOf("on-device AI"),
        intent = listOf("join a project team"),
    )
    private val designer = CapabilityProfile(
        offerings = listOf("UX research", "Figma", "visual design"),
        needs = listOf("frontend engineer"),
        interests = listOf("fintech"),
    )
    private val chef = CapabilityProfile(offerings = listOf("baking", "catering"), needs = listOf("food photographer"), interests = listOf("cooking"))

    private fun card(id: String, p: CapabilityProfile, kind: CardKind = CardKind.PERSON) = engine.card(id, "Sangam #$id", p, kind)

    @Test
    fun `embeddings put related skills closer than unrelated ones`() {
        val a = embedder.embed("Android Kotlin mobile app")
        val b = embedder.embed("mobile developer for Android")
        val c = embedder.embed("baking and catering")
        assertTrue(embedder.cosine(a, b) > embedder.cosine(a, c) + 0.1)
    }

    @Test
    fun `synonyms connect cv to computer vision and ml to machine learning`() {
        val cv = embedder.embed("CV")
        assertTrue(embedder.cosine(cv, embedder.embed("computer vision")) > embedder.cosine(cv, embedder.embed("catering")))
        val ml = embedder.embed("ML engineer")
        assertTrue(embedder.cosine(ml, embedder.embed("machine learning")) > 0.3)
    }

    @Test
    fun `help-me search finds the Android developer first`() {
        val results = engine.search("someone who can build my Android app", SearchMode.HELP_ME, listOf(card("a", android), card("d", designer), card("c", chef)))
        assertEquals("a", results.first().card.peerId)
    }

    @Test
    fun `i-can-help search finds people who need my skills`() {
        val myOffer = "computer vision and machine learning"
        val results = engine.search(myOffer, SearchMode.I_CAN_HELP, listOf(card("d", designer), card("a", android), card("c", chef)))
        assertEquals("a", results.first().card.peerId)
    }

    @Test
    fun `mutual complementarity is detected both ways`() {
        val c = engine.complementarity(card("me", me), card("a", android))
        assertTrue(c.theyHelpMe > 0.2)
        assertTrue(c.iHelpThem > 0.2)
        assertTrue(c.mutual)
        val chefFit = engine.complementarity(card("me", me), card("c", chef))
        assertFalse(chefFit.mutual)
    }

    @Test
    fun `recommendations rank the mutual match first and explain why`() {
        val recs = engine.recommend(card("me", me), listOf(card("c", chef), card("d", designer), card("a", android)))
        assertEquals("a", recs.first().card.peerId)
        val why = recs.first().reasons.joinToString(" ")
        assertTrue(why, why.contains("Android", ignoreCase = true))
    }

    @Test
    fun `project search finds a person for a missing role`() {
        val project = card("p1", CapabilityProfile(offerings = listOf("computer vision", "backend"), needs = listOf("Android", "UI/UX design"), intent = listOf("offline accessibility app")), CardKind.PROJECT)
        val fit = engine.projectFit(project, card("a", android))
        val chefFit = engine.projectFit(project, card("c", chef))
        assertTrue(fit in 1..100)
        assertTrue(fit > chefFit + 15)
    }

    @Test
    fun `cards carry one embedding per field`() {
        val c = card("a", android)
        assertEquals(Field.entries.toSet(), c.embeddings.keys)
        assertEquals(embedder.dim, c.embeddings.getValue(Field.OFFERINGS).size)
    }

    @Test
    fun `room key signs envelopes and rejects tampering or other rooms`() {
        val key = RoomKey.create("Bengaluru AI Meetup")
        val env = key.sign(Envelope(messageId = "m1", roomId = key.roomId, senderId = "s", type = MsgType.CHAT_MESSAGE, timestamp = 1, ttl = 3, payload = JsonPrimitive("hi")))
        assertTrue(key.verify(env))
        assertFalse(key.verify(env.copy(payload = JsonPrimitive("bye"))))
        val other = RoomKey.create("Other room")
        assertFalse(other.verify(env))
        val parsed = RoomKey.fromQr(key.toQr())
        assertNotNull(parsed)
        assertTrue(parsed!!.verify(env))
        assertNull(RoomKey.fromQr("garbage"))
    }

    @Test
    fun `gossip delivers once, forwards with decremented ttl, and stops at zero`() {
        val router = GossipRouter("me")
        val env = Envelope(messageId = "x", roomId = "r", senderId = "s", type = MsgType.PROFILE_ANNOUNCE, timestamp = 1, ttl = 2, payload = JsonPrimitive(1))
        val first = router.onReceive(env)
        assertTrue(first is RouteDecision.DeliverAndForward)
        assertEquals(1, (first as RouteDecision.DeliverAndForward).forward.ttl)
        assertEquals(RouteDecision.Duplicate, router.onReceive(env))
        assertEquals(RouteDecision.DeliverOnly, GossipRouter("me").onReceive(env.copy(messageId = "y", ttl = 0)))
        assertEquals(RouteDecision.Duplicate, router.onReceive(env.copy(senderId = "me", messageId = "z")))
    }

    @Test
    fun `state keeps the newest profile version and records every outgoing share in the ledger`() {
        var s = CommunityState(selfId = "me")
        val v1 = card("a", android).copy(version = 1)
        val v2 = v1.copy(version = 2, alias = "Sangam #A2")
        s = s.upsertCard(v2).upsertCard(v1)
        assertEquals("Sangam #A2", s.cards.getValue("a").alias)
        s = s.recordShare(LedgerEntry(timestamp = 5, type = MsgType.PROFILE_ANNOUNCE, to = "room", fields = listOf("offerings", "needs"), bytes = 812))
        assertEquals(1, s.ledger.size)
        assertEquals(812, s.ledger.sumOf { it.bytes })
        assertFalse("identity never in an announce", s.ledger.first().fields.contains("identity"))
    }

    @Test
    fun `wall stats find unmet needs in the room`() {
        val cards = listOf(card("me", me), card("a", android), card("d", designer))
        val stats = WallStats.compute(cards, engine)
        assertEquals(3, stats.people)
        assertTrue(stats.topOfferings.isNotEmpty())
        // "frontend engineer" (designer's need) is partially met by Android/Compose UI; "food photographer" absent here.
        assertTrue(stats.unmetNeeds.none { it.contains("Android", ignoreCase = true) })
    }

    @Test
    fun `profile parser reads model JSON and ignores chatter`() {
        val reply = """Here you go: ```json {"offerings":["Kotlin","Android"],"needs":["designer"],"interests":["edge AI"],"experience":["3 years at a fintech"],"intent":["side project"],"collaboration_preferences":["remote ok"]} ```"""
        val p = ProfileParser.parse(reply)!!
        assertEquals(listOf("Kotlin", "Android"), p.offerings)
        assertEquals(listOf("remote ok"), p.collab)
        assertNull(ProfileParser.parse("no json here"))
    }
}
