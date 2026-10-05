package com.sangam.app.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class MeshStatus(
    val running: Boolean = false,
    /** endpointId -> alias, for phones that proved they hold the room key. */
    val connected: Map<String, String> = emptyMap(),
    val nearby: Int = 0,
    val error: String? = null,
)

/**
 * Offline phone-to-phone transport using Google Nearby Connections (P2P_CLUSTER: many-to-many over Bluetooth + Wi-Fi).
 *
 * Reliability: every found endpoint is remembered; the lower id connects first, the higher id follows after a delay
 * if nothing happened (discovery is often one-sided); failures and disconnects retry with backoff while the endpoint
 * is still nearby.
 *
 * Privacy: room data is only sent to endpoints the protocol layer has [markVerified] (they sent a valid, room-signed
 * message). Unverified endpoints only ever receive our signed HELLO.
 */
class NearbyMesh(
    context: Context,
    private val selfId: String,
    private val alias: () -> String,
    private val onPayload: (bytes: ByteArray, fromEndpoint: String) -> Unit,
    private val onConnected: (endpointId: String) -> Unit,
) {
    private val client = Nearby.getConnectionsClient(context)
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(MeshStatus())
    val status: StateFlow<MeshStatus> = _status.asStateFlow()
    private var roomPrefix = ""
    private var running = false

    private data class Peer(val selfId: String, val alias: String, val room: String)

    private val found = ConcurrentHashMap<String, Peer>()
    private val linked = ConcurrentHashMap<String, String>() // connected at transport level (verified or not)
    private val verified = ConcurrentHashMap.newKeySet<String>()
    private val attempts = ConcurrentHashMap<String, Int>()

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.asBytes()?.let { onPayload(it, endpointId) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = parse(info.endpointName)
            if (peer == null || peer.room != roomPrefix) {
                client.rejectConnection(endpointId)
                return
            }
            found.putIfAbsent(endpointId, peer)
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            when (resolution.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK, ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> {
                    linked[endpointId] = found[endpointId]?.alias ?: "Peer"
                    attempts.remove(endpointId)
                    _status.update { it.copy(error = null) }
                    onConnected(endpointId)
                }
                else -> scheduleRetry(endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            linked.remove(endpointId)
            verified.remove(endpointId)
            publish()
            scheduleRetry(endpointId)
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peer = parse(info.endpointName) ?: return
            if (peer.room != roomPrefix || peer.selfId == selfId) return
            found[endpointId] = peer
            publish()
            // Lower id connects now; higher id waits, then connects if nothing happened (one-sided discovery).
            val delay = if (selfId < peer.selfId) 0L else FOLLOW_UP_MS
            main.postDelayed({ connect(endpointId) }, delay)
        }

        override fun onEndpointLost(endpointId: String) {
            found.remove(endpointId)
            publish()
        }
    }

    private fun connect(endpointId: String) {
        if (!running || linked.containsKey(endpointId) || !found.containsKey(endpointId)) return
        client.requestConnection(endpointName(), endpointId, lifecycle)
            .addOnFailureListener { e ->
                val code = (e as? ApiException)?.statusCode
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) return@addOnFailureListener
                scheduleRetry(endpointId)
            }
    }

    private fun scheduleRetry(endpointId: String) {
        if (!running || !found.containsKey(endpointId)) return
        val n = (attempts[endpointId] ?: 0) + 1
        attempts[endpointId] = n
        if (n > MAX_ATTEMPTS) {
            _status.update { it.copy(error = "Couldn't connect to a nearby phone. Tap Restart discovery.") }
            return
        }
        main.postDelayed({ connect(endpointId) }, minOf(BASE_RETRY_MS * (1L shl (n - 1)), 30_000L))
    }

    /** Called by the protocol layer once an endpoint sent a valid room-signed message. */
    fun markVerified(endpointId: String) {
        if (verified.add(endpointId)) publish()
    }

    fun isVerified(endpointId: String) = endpointId in verified

    private fun publish() = _status.update { s ->
        s.copy(connected = linked.filterKeys { it in verified }, nearby = found.size)
    }

    fun start(roomId: String) {
        stop()
        running = true
        roomPrefix = roomId.take(8)
        val strategy = Strategy.P2P_CLUSTER
        client.startAdvertising(endpointName(), SERVICE_ID, lifecycle, AdvertisingOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> _status.update { it.copy(error = "Advertising failed: ${e.message}. Check Bluetooth, Wi-Fi, Location and permissions.") } }
        client.startDiscovery(SERVICE_ID, discovery, DiscoveryOptions.Builder().setStrategy(strategy).build())
            .addOnFailureListener { e -> _status.update { it.copy(error = "Discovery failed: ${e.message}. Check Bluetooth, Wi-Fi, Location and permissions.") } }
        _status.value = MeshStatus(running = true)
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        runCatching {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        }
        found.clear(); linked.clear(); verified.clear(); attempts.clear()
        _status.value = MeshStatus(running = false)
    }

    /** Room traffic: verified endpoints only. Returns false if the payload is too large for Nearby. */
    fun broadcast(bytes: ByteArray, except: String? = null): Boolean {
        if (bytes.size > MAX_BYTES) return false
        val targets = verified.filter { it != except && linked.containsKey(it) }
        if (targets.isNotEmpty()) client.sendPayload(targets, Payload.fromBytes(bytes))
        return true
    }

    /** Direct send (used for the HELLO handshake and the sync to a just-verified endpoint). */
    fun sendTo(endpointId: String, bytes: ByteArray): Boolean {
        if (bytes.size > MAX_BYTES || !linked.containsKey(endpointId)) return false
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
        return true
    }

    private fun endpointName() = "$selfId|${alias()}|$roomPrefix"

    private fun parse(name: String): Peer? = name.split('|').takeIf { it.size == 3 }?.let { Peer(it[0], it[1], it[2]) }

    companion object {
        const val SERVICE_ID = "com.sangam.mesh"
        /** Nearby BYTES payloads are capped at 32 KB. */
        const val MAX_BYTES = 32 * 1024
        private const val FOLLOW_UP_MS = 4_000L
        private const val BASE_RETRY_MS = 2_000L
        private const val MAX_ATTEMPTS = 6
    }
}
