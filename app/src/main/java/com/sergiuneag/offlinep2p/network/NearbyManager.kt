package com.sergiuneag.offlinep2p.network

import android.content.Context
import android.os.Build
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.sergiuneag.offlinep2p.data.AppDatabase
import com.sergiuneag.offlinep2p.data.MessageEntity
import com.sergiuneag.offlinep2p.data.PeerEntity
import com.sergiuneag.offlinep2p.data.TrustLevel
import com.sergiuneag.offlinep2p.security.CryptoHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.crypto.SecretKey

class NearbyManager(private val context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val messageDao = db.messageDao()
    private val peerDao = db.peerDao()
    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val SERVICE_ID = "com.sergiuneag.offlinep2p.SERVICE_ID"
    private val STRATEGY = Strategy.P2P_STAR

    // Security State
    private var sessionKey: SecretKey? = null
    private var currentPeerPublicKey: String? = null
    private var pendingAuthDigits: String? = null
    private var isPeerVerifiedLocally = false
    private var isPeerVerifiedRemotely = false

    // UI Listeners
    var onMessageReceived: ((String) -> Unit)? = null
    var onConnectionChanged: ((String?, String) -> Unit)? = null
    var onVerificationRequired: ((String, String) -> Unit)? = null // publicKey, authDigits
    var onTrustLevelChanged: ((Boolean) -> Unit)? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val receivedBytes = payload.asBytes() ?: return

                try {
                    // 1. Try to parse as a raw string to check for Handshake Signals
                    // This avoids trying to "decrypt" a raw identity exchange
                    val rawContent = try { String(receivedBytes) } catch (e: Exception) { "" }

                    if (rawContent.startsWith("IDENTITY:")) {
                        handleIdentityExchange(endpointId, rawContent.substring(9))
                        return
                    }
                    
                    if (rawContent == "TRUST_CONFIRMED") {
                        handleRemoteTrustConfirmation()
                        return
                    }

                    // 2. If it's not a handshake signal, it MUST be an encrypted message
                    if (!isPeerVerifiedLocally || !isPeerVerifiedRemotely) {
                        Log.w("P2P", "Encrypted message received but trust not yet established.")
                        return
                    }

                    val currentKey = sessionKey ?: return
                    val decryptedData = CryptoHelper.decrypt(receivedBytes, currentKey)

                    if (decryptedData == "Decryption Error") {
                        Log.e("P2P", "Decryption failed for an incoming message.")
                        return
                    }

                    GlobalScope.launch(Dispatchers.IO) {
                        messageDao.insert(
                            MessageEntity(content = decryptedData, isMe = false, isSent = true)
                        )
                    }
                    onMessageReceived?.invoke(decryptedData)
                } catch (e: Exception) {
                    Log.e("P2P", "Fatal error processing payload: ${e.message}")
                }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private fun handleIdentityExchange(endpointId: String, publicKeyString: String) {
        GlobalScope.launch(Dispatchers.IO) {
            currentPeerPublicKey = publicKeyString
            val existingPeer = peerDao.getPeerByPublicKey(publicKeyString)
            
            if (existingPeer != null && existingPeer.trustLevel == TrustLevel.VERIFIED) {
                Log.d("P2P", "Identity matched VERIFIED peer. Mutual trust established.")
                isPeerVerifiedLocally = true
                isPeerVerifiedRemotely = true 
                android.os.Handler(Looper.getMainLooper()).post {
                    onTrustLevelChanged?.invoke(true)
                }
            } else {
                if (existingPeer == null) {
                    peerDao.insert(
                        PeerEntity(
                            publicKey = publicKeyString,
                            peerName = Build.MODEL,
                            trustLevel = TrustLevel.UNVERIFIED
                        )
                    )
                }
                Log.d("P2P", "Identity is UNVERIFIED. Waiting for manual user confirmation.")
                isPeerVerifiedLocally = false
                isPeerVerifiedRemotely = false
                android.os.Handler(Looper.getMainLooper()).post {
                    onTrustLevelChanged?.invoke(false)
                    pendingAuthDigits?.let { code ->
                        onVerificationRequired?.invoke(publicKeyString, code)
                    }
                }
            }
        }
    }

    private fun handleRemoteTrustConfirmation() {
        Log.d("P2P", "Remote peer confirmed trust.")
        isPeerVerifiedRemotely = true
        checkMutualTrust()
    }

    private fun checkMutualTrust() {
        if (isPeerVerifiedLocally && isPeerVerifiedRemotely) {
            Log.d("P2P", "Mutual trust established! Chat unlocked.")
            android.os.Handler(Looper.getMainLooper()).post {
                onTrustLevelChanged?.invoke(true)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("P2P", "Handshake started with ${info.endpointName}")
            sessionKey = CryptoHelper.deriveKey(info.rawAuthenticationToken)
            pendingAuthDigits = info.authenticationDigits
            
            // Auto-accept the bridge to exchange identities (the actual trust happens later)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d("P2P", "Connection bridge established. Exchanging public keys...")
                sendIdentity(endpointId)
                syncUnsentMessages(endpointId)
                onConnectionChanged?.invoke(endpointId, "Connected")
            } else {
                onConnectionChanged?.invoke(null, "Connection Failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            sessionKey = null
            currentPeerPublicKey = null
            pendingAuthDigits = null
            isPeerVerifiedLocally = false
            isPeerVerifiedRemotely = false
            onConnectionChanged?.invoke(null, "Disconnected")
            onTrustLevelChanged?.invoke(false)
            startP2P()
        }
    }

    fun acceptConnection(endpointId: String) {
        // User pressed "Accept" on the dialog
        isPeerVerifiedLocally = true
        Log.d("P2P", "Local user accepted connection. Notifying peer...")
        // Tell the other device we have confirmed them
        connectionsClient.sendPayload(endpointId, Payload.fromBytes("TRUST_CONFIRMED".toByteArray()))
        checkMutualTrust()
    }

    fun rejectConnection(endpointId: String) {
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    private fun sendIdentity(endpointId: String) {
        val myPubKey = CryptoHelper.getMyPublicKeyString()
        connectionsClient.sendPayload(endpointId, Payload.fromBytes("IDENTITY:$myPubKey".toByteArray()))
    }

    fun sendMessage(message: String, endpointId: String?) {
        // Only send if mutual trust is established
        if (!isPeerVerifiedLocally || !isPeerVerifiedRemotely) {
            Log.e("P2P", "Message blocked: Mutual trust not established.")
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            val messageId = messageDao.insert(
                MessageEntity(content = message, isMe = true, isSent = false)
            ).toInt()

            if (endpointId != null && sessionKey != null) {
                val encryptedBytes = CryptoHelper.encrypt(message, sessionKey!!)
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(encryptedBytes))
                    .addOnSuccessListener {
                        GlobalScope.launch(Dispatchers.IO) {
                            messageDao.updateMessageSentStatus(messageId, true)
                        }
                    }
            }
        }
    }

    fun syncUnsentMessages(endpointId: String) {
        // Only sync if mutual trust is established
        if (!isPeerVerifiedLocally || !isPeerVerifiedRemotely) return

        GlobalScope.launch(Dispatchers.IO) {
            val unsent = messageDao.getUnsentMessages()
            val currentKey = sessionKey ?: return@launch
            unsent.forEach { msg ->
                val encryptedData = CryptoHelper.encrypt(msg.content, currentKey)
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
        connectionsClient.stopAllEndpoints()
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        startAdvertising()
        startDiscovery()
    }

    private fun startAdvertising() {
        val options = AdvertisingOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startAdvertising(Build.MODEL, SERVICE_ID, connectionLifecycleCallback, options)
    }

    private fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(STRATEGY).build()
        connectionsClient.startDiscovery(SERVICE_ID, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                connectionsClient.requestConnection(Build.MODEL, endpointId, connectionLifecycleCallback)
            }
            override fun onEndpointLost(endpointId: String) {}
        }, options)
    }
}
