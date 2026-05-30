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

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    val nearbyManager = NearbyManager(application)

    // UI States
    var status = mutableStateOf("Disconnected")
    var connectedId = mutableStateOf<String?>(null)
    val messages = mutableStateListOf<MessageEntity>()

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
        }

        nearbyManager.onMessageReceived = { _ ->
            // Room Flow automatically updates the 'messages' list
        }
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