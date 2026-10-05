package com.sangam.app.data

import android.content.Context
import androidx.core.content.edit
import com.sangam.core.model.CapabilityProfile
import com.sangam.core.model.Identity
import com.sangam.core.protocol.CardPayload
import com.sangam.core.protocol.wireJson
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

/** Everything about "me" stays on this phone: guest id, alias, profile, identity, current room. No account, no login. */
class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("sangam", Context.MODE_PRIVATE)

    val selfId: String = prefs.getString("self_id", null) ?: UUID.randomUUID().toString().replace("-", "").take(12).also {
        prefs.edit { putString("self_id", it) }
    }

    /** This phone's end-to-end key pair, created once and kept only on this device. */
    val crypto: com.sangam.core.protocol.PeerCrypto =
        prefs.getString("e2e_keys", null)?.let { com.sangam.core.protocol.PeerCrypto.fromExport(it) }
            ?: com.sangam.core.protocol.PeerCrypto.generate().also { k -> prefs.edit { putString("e2e_keys", k.export()) } }

    val alias: String get() = "Sangam #${selfId.take(4).uppercase()}"

    var profile: CapabilityProfile
        get() = prefs.getString("profile", null)?.let { runCatching { wireJson.decodeFromString(CapabilityProfile.serializer(), it) }.getOrNull() } ?: CapabilityProfile()
        set(v) = prefs.edit {
            putString("profile", wireJson.encodeToString(CapabilityProfile.serializer(), v))
            putInt("profile_version", profileVersion + 1)
        }

    val profileVersion: Int get() = prefs.getInt("profile_version", 1)

    var identity: Identity
        get() = prefs.getString("identity", null)?.let { runCatching { wireJson.decodeFromString(Identity.serializer(), it) }.getOrNull() } ?: Identity(name = "")
        set(v) = prefs.edit { putString("identity", wireJson.encodeToString(Identity.serializer(), v)) }

    var roomQr: String?
        get() = prefs.getString("room_qr", null)
        set(v) = prefs.edit { if (v == null) remove("room_qr") else putString("room_qr", v) }

    /** Projects this phone owns, so they survive restarts and room changes. */
    var myProjects: List<CardPayload>
        get() = prefs.getString("my_projects", null)?.let {
            runCatching { wireJson.decodeFromString(ListSerializer(CardPayload.serializer()), it) }.getOrNull()
        } ?: emptyList()
        set(v) = prefs.edit { putString("my_projects", wireJson.encodeToString(ListSerializer(CardPayload.serializer()), v)) }

    var demoPeers: Boolean
        get() = prefs.getBoolean("demo_peers", false)
        set(v) = prefs.edit { putBoolean("demo_peers", v) }
}
