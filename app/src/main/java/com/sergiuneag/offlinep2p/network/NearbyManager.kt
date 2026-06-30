package com.sergiuneag.offlinep2p.network

import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.delay
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
    var onTrustLevelChanged: ((Boolean, String?) -> Unit)? = null // isEstablished, peerPublicKey

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
                        Log.w("P2P", "Encrypted message received from $endpointId but trust not yet established.")
                        return
                    }

                    val currentKey = sessionKey ?: run {
                        Log.e("P2P", "No session key available to decrypt payload from $endpointId")
                        return
                    }
                    val decryptedData = CryptoHelper.decrypt(receivedBytes, currentKey)

                    if (decryptedData == "Decryption Error") {
                        Log.e("P2P", "Decryption failed for an incoming message from $endpointId. Check keys.")
                        return
                    }

                    Log.d("P2P", "Successfully decrypted message from $endpointId: $decryptedData")

                    GlobalScope.launch(Dispatchers.IO) {
                        messageDao.insert(
                            MessageEntity(content = decryptedData, isMe = false, isSent = true, peerPublicKey = currentPeerPublicKey)
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
                GlobalScope.launch(Dispatchers.Main) {
                    onTrustLevelChanged?.invoke(true, currentPeerPublicKey)
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
                GlobalScope.launch(Dispatchers.Main) {
                    onTrustLevelChanged?.invoke(false, null)
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
            
            // SYNC ON TRUST: Trigger sync as soon as trust is established
            val endpointId = lastConnectedEndpointId
            if (endpointId != null) {
                // Ensure we use the correct dispatcher for the network and DB calls
                GlobalScope.launch(Dispatchers.IO) {
                    delay(2000) // Stability buffer
                    syncUnsentMessages(endpointId)
                }
            }

            GlobalScope.launch(Dispatchers.Main) {
                onTrustLevelChanged?.invoke(true, currentPeerPublicKey)
            }
        }
    }

    private var lastConnectedEndpointId: String? = null

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d("P2P", "Handshake started with ${info.endpointName}")
            lastConnectedEndpointId = endpointId
            sessionKey = CryptoHelper.deriveKey(info.rawAuthenticationToken)
            pendingAuthDigits = info.authenticationDigits
            
            // Auto-accept the bridge to exchange identities (the actual trust happens later)
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                Log.d("P2P", "Connection bridge established. Exchanging public keys...")
                sendIdentity(endpointId)
                
                // We no longer sync here because trust might not be established yet.
                // Sync is now triggered in checkMutualTrust()
                
                onConnectionChanged?.invoke(endpointId, "Connected")
            } else {
                lastConnectedEndpointId = null
                onConnectionChanged?.invoke(null, "Connection Failed")
            }
        }

        override fun onDisconnected(endpointId: String) {
            sessionKey = null
            lastConnectedEndpointId = null
            // currentPeerPublicKey = null // PERSISTENCE FIX
            pendingAuthDigits = null
            isPeerVerifiedLocally = false
            isPeerVerifiedRemotely = false
            onConnectionChanged?.invoke(null, "Disconnected")
            onTrustLevelChanged?.invoke(false, null)
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
        // PERSISTENCE-FIRST: Always save to DB
        // If we don't have a peer yet, we save it as a "Global" message (null recipient)
        // It will be sent to the first peer we establish trust with.
        val targetKey = currentPeerPublicKey

        GlobalScope.launch(Dispatchers.IO) {
            val messageId = messageDao.insert(
                MessageEntity(content = message, isMe = true, isSent = false, peerPublicKey = targetKey)
            ).toInt()

            // ATTEMPT TRANSMISSION: Only if connected and trusted
            if (endpointId != null && sessionKey != null && isPeerVerifiedLocally && isPeerVerifiedRemotely) {
                val encryptedBytes = CryptoHelper.encrypt(message, sessionKey!!)
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(encryptedBytes))
                    .addOnSuccessListener {
                        GlobalScope.launch(Dispatchers.IO) {
                            messageDao.updateMessageSentStatus(messageId, true)
                        }
                    }
            } else {
                Log.d("P2P", "Message saved locally (Offline/Untrusted). Will sync later.")
            }
        }
    }

    fun syncUnsentMessages(endpointId: String) {
        // Only sync if mutual trust is established
        val targetPeerKey = currentPeerPublicKey ?: return
        if (!isPeerVerifiedLocally || !isPeerVerifiedRemotely) {
            Log.d("P2P", "Sync aborted: Trust not established.")
            return
        }

        GlobalScope.launch(Dispatchers.IO) {
            val unsent = messageDao.getUnsentMessages(targetPeerKey)
            Log.d("P2P", "Syncing ${unsent.size} unsent messages to $endpointId")
            
            val currentKey = sessionKey ?: return@launch
            unsent.forEach { msg ->
                val encryptedData = CryptoHelper.encrypt(msg.content, currentKey)
                connectionsClient.sendPayload(endpointId, Payload.fromBytes(encryptedData))
                    .addOnSuccessListener {
                        Log.d("P2P", "Payload for message ${msg.id} sent successfully.")
                        GlobalScope.launch(Dispatchers.IO) {
                            messageDao.updateMessageSentStatus(msg.id, true)
                        }
                    }
                    .addOnFailureListener { e ->
                        Log.e("P2P", "Failed to send payload for message ${msg.id}: ${e.message}")
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
