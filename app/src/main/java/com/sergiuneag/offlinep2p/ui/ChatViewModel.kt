package com.sergiuneag.offlinep2p.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sergiuneag.offlinep2p.data.AppDatabase
import com.sergiuneag.offlinep2p.data.MessageEntity
import com.sergiuneag.offlinep2p.network.NearbyManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.State
import com.sergiuneag.offlinep2p.data.PeerEntity
import com.sergiuneag.offlinep2p.data.TrustLevel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    val nearbyManager = NearbyManager(application)

    // UI States
    var status = mutableStateOf("Disconnected")
    var connectedId = mutableStateOf<String?>(null)
    val messages = mutableStateListOf<MessageEntity>()
    
    // Security States
    private val _verificationCode = mutableStateOf<String?>(null)
    val verificationCode: State<String?> = _verificationCode

    private val _pendingPublicKey = mutableStateOf<String?>(null)
    val pendingPublicKey: State<String?> = _pendingPublicKey

    private val _isTrustEstablished = mutableStateOf(false)
    val isTrustEstablished: State<Boolean> = _isTrustEstablished

    private var messageCollectionJob: Job? = null

    init {
        // Setup Nearby Callbacks
        nearbyManager.onConnectionChanged = { id, newStatus ->
            connectedId.value = id
            status.value = newStatus
            if (id == null) {
                _verificationCode.value = null
                _pendingPublicKey.value = null
                _isTrustEstablished.value = false
                stopObservingMessages()
            }
        }

        nearbyManager.onMessageReceived = { _ ->
            // Room Flow automatically updates the 'messages' list
        }

        nearbyManager.onVerificationRequired = { publicKey, code ->
            _verificationCode.value = code
            _pendingPublicKey.value = publicKey
        }

        nearbyManager.onTrustLevelChanged = { isEstablished, publicKey ->
            _isTrustEstablished.value = isEstablished
            if (isEstablished && publicKey != null) {
                status.value = "Securely Connected"
                startObservingMessages(publicKey)
            } else {
                stopObservingMessages()
            }
        }
    }

    private fun startObservingMessages(peerPublicKey: String) {
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            db.messageDao().getMessagesByPeer(peerPublicKey).collect { savedMessages ->
                messages.clear()
                messages.addAll(savedMessages)
            }
        }
    }

    private fun stopObservingMessages() {
        messageCollectionJob?.cancel()
        messages.clear()
    }

    fun acceptConnection() {
        _pendingPublicKey.value?.let { pubKey ->
            viewModelScope.launch(Dispatchers.IO) {
                db.peerDao().updateTrustLevel(pubKey, TrustLevel.VERIFIED)
                
                viewModelScope.launch(Dispatchers.Main) {
                    connectedId.value?.let { nearbyManager.acceptConnection(it) }
                    _verificationCode.value = null
                    _pendingPublicKey.value = null
                }
            }
        }
    }

    fun rejectConnection() {
        _verificationCode.value = null
        _pendingPublicKey.value = null
        _isTrustEstablished.value = false
        stopObservingMessages()
        connectedId.value?.let { nearbyManager.rejectConnection(it) }
    }

    fun sendMessage(text: String) {
        if (text.isNotBlank()) {
            nearbyManager.sendMessage(text, connectedId.value)
        }
    }

    fun startDiscovery() {
        status.value = "Searching..."
        nearbyManager.startP2P()
    }
}
