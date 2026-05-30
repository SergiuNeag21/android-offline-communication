package com.sergiuneag.offlinep2p.network

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.sergiuneag.offlinep2p.data.AppDatabase
import com.sergiuneag.offlinep2p.data.MessageEntity
import com.sergiuneag.offlinep2p.security.CryptoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NearbyManager(private val context: Context) {

    private val db = AppDatabase.Companion.getDatabase(context)
    private val messageDao = db.messageDao()
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.sergiuneag.offlinep2p.SERVICE_ID"
    private val STRATEGY = Strategy.P2P_STAR

    // UI Listeners
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((String?, String) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {    if (payload.type == Payload.Type.BYTES) {
            val receivedBytes = payload.asBytes() ?: return

            try {
                // Decrypt the incoming data
                val decryptedData = CryptoHelper.decrypt(receivedBytes)

                // SALVARE ÎN DB (Mesaj primit)
                GlobalScope.launch(Dispatchers.IO) {
                    messageDao.insert(
                        MessageEntity(
                            content = decryptedData,
                            isMe = false,
                            isSent = true
                        )
                    )
                }

                onMessageReceived?.invoke(decryptedData)
            } catch (e: Exception) {
                Log.e("P2P", "Decryption failed: ${e.message}")
                // Optional: Show an error message or "Encrypted message received"
            }
        }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private var discoveryStartTime: Long = 0
    private var connectionStartTime: Long = 0

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val discoveryDuration = System.currentTimeMillis() - discoveryStartTime
            Log.d("P2P_PERF", "Peer found: ${info.endpointName} after ${discoveryDuration}ms")

            connectionStartTime = System.currentTimeMillis()
            Handler(Looper.getMainLooper()).postDelayed({
                connectionsClient.requestConnection(
                    Build.MODEL,
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    Log.e("P2P", "Request failed: ${e.message}")
                }
            }, 2000)
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d("P2P", "Endpoint lost: $endpointId")
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("P2P", "Accepting connection from ${info.endpointName}")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val connectionDuration = System.currentTimeMillis() - connectionStartTime
            if (result.status.isSuccess) {
                Log.d("P2P_PERF", "Connected to $endpointId. Handshake took: ${connectionDuration}ms")

                // Triggers synchronization of offline messages
                syncUnsentMessages(endpointId)

                connectionsClient.stopDiscovery()
                connectionsClient.stopAdvertising()
                onConnectionChanged?.invoke(endpointId, "Connected")
            } else {
                Log.e("P2P", "Connection failed: ${result.status.statusCode}")
                onConnectionChanged?.invoke(null, "Connection Failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d("P2P", "Disconnected")
            onConnectionChanged?.invoke(null, "Disconnected")
            startP2P() // Restart searching
        }
    }

    fun sendMessage(message: String, endpointId: String?) {
        // Save to DB immediately (Persistence)
        GlobalScope.launch(Dispatchers.IO) {
            val messageId = messageDao.insert(
                MessageEntity(
                    content = message,
                    isMe = true,
                    isSent = false
                )
            ).toInt()

            // If we are currently connected, try to send it now
            if (endpointId != null) {
                val encryptedBytes = CryptoHelper.encrypt(message)
                val payload = Payload.fromBytes(encryptedBytes)

                connectionsClient.sendPayload(endpointId, payload)
                    .addOnSuccessListener {
                        Log.d("P2P", "Sent & Updating Status")
                        GlobalScope.launch(Dispatchers.IO) {
                            messageDao.updateMessageSentStatus(messageId, true)
                        }
                    }
            }
        }
    }

    fun syncUnsentMessages(endpointId: String) {
        GlobalScope.launch(Dispatchers.IO) {
            val unsent = messageDao.getUnsentMessages()
            Log.d("P2P", "Syncing ${unsent.size} unsent messages")
            unsent.forEach { msg ->
                val encryptedData = CryptoHelper.encrypt(msg.content)
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(encryptedData))
                    .addOnSuccessListener {
                        GlobalScope.launch(Dispatchers.IO) {
                            messageDao.updateMessageSentStatus(msg.id, true)
                        }
                    }
            }
        }
    }

    fun startP2P() {
        discoveryStartTime = System.currentTimeMillis()
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()

        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(Build.MODEL, SERVICE_ID, connectionLifecycleCallback, options)
            .addOnSuccessListener { Log.d("P2P", "Adv started") }
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, options)
            .addOnSuccessListener { Log.d("P2P", "Disc started") }
    }
}