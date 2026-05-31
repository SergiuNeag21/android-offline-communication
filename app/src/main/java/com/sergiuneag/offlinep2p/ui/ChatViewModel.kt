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

    init {
        // Observe Database changes
        viewModelScope.launch {
            db.messageDao().getAllMessages().collect { savedMessages ->
                messages.clear()
                messages.addAll(savedMessages)
            }
        }

        // Setup Nearby Callbacks
        nearbyManager.onConnectionChanged = { id, newStatus ->
            connectedId.value = id
            status.value = newStatus
            if (id == null) {
                _verificationCode.value = null
                _pendingPublicKey.value = null
                _isTrustEstablished.value = false
            }
        }

        nearbyManager.onMessageReceived = { _ ->
            // Room Flow automatically updates the 'messages' list
        }

        nearbyManager.onVerificationRequired = { publicKey, code ->
            _verificationCode.value = code
            _pendingPublicKey.value = publicKey
        }

        nearbyManager.onTrustLevelChanged = { isEstablished ->
            _isTrustEstablished.value = isEstablished
            if (isEstablished) {
                status.value = "Securely Connected"
            }
        }
    }

    fun acceptConnection() {
        _pendingPublicKey.value?.let { pubKey ->
            viewModelScope.launch(Dispatchers.IO) {
                db.peerDao().updateTrustLevel(pubKey, TrustLevel.VERIFIED)
                
                android.os.Handler(android.os.Looper.getMainLooper()).post {
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
        connectedId.value?.let { nearbyManager.rejectConnection(it) }
    }

    fun sendMessage(text: String) {
        if (text.isNotBlank() && _isTrustEstablished.value) {
            nearbyManager.sendMessage(text, connectedId.value)
        }
    }

    fun startDiscovery() {
        status.value = "Searching..."
        nearbyManager.startP2P()
    }
}
