package com.meshchat

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import org.json.JSONObject
import java.util.UUID

data class MeshMessage(
    val id: String,
    val origin: String,
    val text: String,
    val sos: Boolean,
    val ttl: Int,      // remaining hops allowed
    val hops: Int,     // links travelled so far
    val path: String,  // e.g. "PhoneA → PhoneB → PhoneC"
    val time: Long
)

/**
 * Flood-routing mesh on top of Google Nearby Connections (P2P_CLUSTER).
 *  - every message has a unique id (duplicates are dropped)
 *  - every message has a TTL (limits flooding)
 *  - every phone forwards to all other neighbours
 *  - history is re-sent to newly connected neighbours (store-and-forward)
 */
class MeshManager(
    context: Context,
    private val myName: String,
    private val listener: Listener
) {
    interface Listener {
        fun onMessage(m: MeshMessage, mine: Boolean)
        fun onPeersChanged(names: List<String>)
        fun onStatus(text: String)
    }

    companion object {
        const val SERVICE_ID = "com.meshchat.mesh"
        const val MAX_TTL = 6
        const val HISTORY_LIMIT = 50
    }

    private val client = Nearby.getConnectionsClient(context)
    private val handler = Handler(Looper.getMainLooper())
    private val endpointName = "$myName|${UUID.randomUUID().toString().take(6)}"

    private val peers = linkedMapOf<String, String>()   // endpointId -> display name
    private val names = mutableMapOf<String, String>()  // endpointId -> raw endpoint name
    private val pending = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()
    private val history = mutableListOf<MeshMessage>()

    // ---------- lifecycle ----------

    fun start() {
        startAdvertising()
        startDiscovery()
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        peers.clear(); pending.clear(); names.clear()
    }

    private fun startAdvertising() {
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(endpointName, SERVICE_ID, connectionCallback, opts)
            .addOnSuccessListener { listener.onStatus("Advertising OK") }
            .addOnFailureListener { listener.onStatus("Advertising failed: ${it.message}") }
    }

    private fun startDiscovery() {
        val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, opts)
            .addOnSuccessListener { listener.onStatus("Searching for nearby phones...") }
            .addOnFailureListener { listener.onStatus("Discovery failed: ${it.message}") }
    }

    private fun restartDiscovery() {
        client.stopDiscovery()
        handler.postDelayed({ startDiscovery() }, 1000)
    }

    // ---------- discovery / connection ----------

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) {
            if (peers.containsKey(id) || pending.contains(id)) return
            // Tie-break so both phones don't request each other at the same moment.
            if (endpointName < info.endpointName) {
                requestConnection(id)
            } else {
                // Give the other side time to connect to us; fall back if it doesn't.
                handler.postDelayed({
                    if (!peers.containsKey(id) && !pending.contains(id)) requestConnection(id)
                }, 4000)
            }
        }

        override fun onEndpointLost(id: String) {}
    }

    private fun requestConnection(id: String) {
        pending.add(id)
        client.requestConnection(endpointName, id, connectionCallback)
            .addOnFailureListener { pending.remove(id) }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) {
            pending.add(id)
            names[id] = info.endpointName
            client.acceptConnection(id, payloadCallback)
        }

        override fun onConnectionResult(id: String, result: ConnectionResolution) {
            pending.remove(id)
            if (result.status.isSuccess) {
                peers[id] = (names[id] ?: "?").substringBefore("|")
                listener.onPeersChanged(peers.values.toList())
                syncHistoryTo(id)
            } else {
                names.remove(id)
                restartDiscovery()
            }
        }

        override fun onDisconnected(id: String) {
            peers.remove(id)
            names.remove(id)
            listener.onPeersChanged(peers.values.toList())
            restartDiscovery()
        }
    }

    // ---------- messaging ----------

    fun send(text: String, sos: Boolean) {
        val m = MeshMessage(
            id = UUID.randomUUID().toString(), origin = myName, text = text, sos = sos,
            ttl = MAX_TTL, hops = 0, path = myName, time = System.currentTimeMillis()
        )
        seen.add(m.id)
        remember(m)
        listener.onMessage(m, true)
        broadcast(m, null)
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(fromId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            try {
                handleIncoming(fromId, fromJson(String(bytes, Charsets.UTF_8)))
            } catch (_: Exception) { }
        }

        override fun onPayloadTransferUpdate(id: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIncoming(fromId: String, raw: MeshMessage) {
        if (!seen.add(raw.id)) return // already seen -> drop (prevents loops)

        val m = raw.copy(
            ttl = raw.ttl - 1,
            hops = raw.hops + 1,
            path = raw.path + " → " + myName
        )
        remember(m)
        listener.onMessage(m, false)

        if (m.ttl > 0) broadcast(m, fromId) // relay to everyone except the sender
    }

    private fun broadcast(m: MeshMessage, except: String?) {
        val payload = toJson(m).toByteArray(Charsets.UTF_8)
        for (id in peers.keys) {
            if (id != except) client.sendPayload(id, Payload.fromBytes(payload))
        }
    }

    /** Store-and-forward: a phone that just came into range gets what it missed. */
    private fun syncHistoryTo(id: String) {
        for (m in history.toList()) {
            if (m.ttl > 0) client.sendPayload(id, Payload.fromBytes(toJson(m).toByteArray(Charsets.UTF_8)))
        }
    }

    private fun remember(m: MeshMessage) {
        history.add(m)
        if (history.size > HISTORY_LIMIT) history.removeAt(0)
    }

    private fun toJson(m: MeshMessage) = JSONObject().apply {
        put("id", m.id); put("origin", m.origin); put("text", m.text); put("sos", m.sos)
        put("ttl", m.ttl); put("hops", m.hops); put("path", m.path); put("time", m.time)
    }.toString()

    private fun fromJson(s: String): MeshMessage {
        val o = JSONObject(s)
        return MeshMessage(
            o.getString("id"), o.getString("origin"), o.getString("text"), o.getBoolean("sos"),
            o.getInt("ttl"), o.getInt("hops"), o.getString("path"), o.getLong("time")
        )
    }
}
