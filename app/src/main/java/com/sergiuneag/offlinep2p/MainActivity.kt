package com.sergiuneag.offlinep2p

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.sergiuneag.offlinep2p.ui.theme.OfflineP2PTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var nearbyManager: NearbyManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            nearbyManager.startP2P()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nearbyManager = NearbyManager(this)
        enableEdgeToEdge()

        setContent {
            OfflineP2PTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        nearbyManager = nearbyManager,
                        onRequestPermissions = {
                            requestPermissionLauncher.launch(getRequiredPermissions())
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MainScreen(nearbyManager: NearbyManager, onRequestPermissions: () -> Unit) {
    var messageText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<String>() }
    var connectedId by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Disconnected") }

    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val isKeyboardVisible = WindowInsets.isImeVisible

    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }

    LaunchedEffect(Unit) {
        // 1. Ascultă baza de date pentru mesaje salvate (Istoric)
        db.messageDao().getAllMessages().collect { savedMessages ->
            messages.clear()
            savedMessages.forEach { entity ->
                val prefix = if (entity.isMe) "Me: " else "Peer: "
                messages.add("$prefix${entity.content}")
            }
            if (messages.isNotEmpty()) {
                listState.scrollToItem(messages.size - 1)
            }
        }
    }

    // 2. Ascultă NearbyManager pentru conexiuni noi
    LaunchedEffect(Unit) {
        nearbyManager.onConnectionChanged = { id, newStatus ->
            connectedId = id
            status = newStatus
        }

        nearbyManager.onMessageReceived = { _ ->
            // Nu mai adăugăm manual aici, deoarece Room (getAllMessages)
            // va detecta automat noua intrare în DB și va face update la listă
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { keyboardController?.hide() })
            }
    ) {
        // --- 1. HEADER ---
        Column(modifier = Modifier.padding(16.dp)) {
            Text("P2P Offline Chat", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Status: $status",
                    color = if (connectedId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                if (connectedId == null && (status == "Searching..." || status.contains("started"))) {
                    Spacer(modifier = Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // --- 2. CHAT MESSAGES ---
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(messages) { msg ->
                val isMe = msg.startsWith("Me:")
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(msg, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }

        // --- 3. INPUT AREA ---
        Surface(tonalElevation = 2.dp, shadowElevation = 8.dp) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = messageText,
                        onValueChange = { messageText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Write something...") },
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp) // Dimensiune standard pentru a fi ușor de apăsat
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            enabled = messageText.isNotBlank(),
                            onClick = {
                                // Logica de trimitere executată imediat
                                val textToProcess = messageText
                                if (textToProcess.isNotBlank()) {
                                    // RESETĂM TEXTUL DOAR DUPĂ CE SUNTEM SIGURI CĂ PROCESUL A ÎNCEPUT
                                    nearbyManager.sendMessage(textToProcess, connectedId)
                                    messageText = ""

                                    // Forțăm închiderea tastaturii - crucial pe A51
                                    keyboardController?.hide()

                                    coroutineScope.launch {
                                        if (messages.isNotEmpty()) {
                                            listState.animateScrollToItem(messages.size - 1)
                                        }
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                // Culori extrem de contrastante pentru a vedea starea
                                tint = if (messageText.isBlank()) {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                                } else {
                                    // Dacă e activ, folosim o culoare aprinsă (Primary)
                                    MaterialTheme.colorScheme.primary
                                }
                            )
                        }
                    }
                }

                if (!isKeyboardVisible) {
                    Button(
                        onClick = {
                            status = "Searching..."
                            onRequestPermissions()
                            nearbyManager.startP2P()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Text("Reset & Search for Peers")
                    }
                }
            }
        }
    }
}

fun getRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.NEARBY_WIFI_DEVICES
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    }
}