package com.sergiuneag.offlinep2p.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.sergiuneag.offlinep2p.ui.theme.OfflineP2PTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            viewModel.startDiscovery()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            OfflineP2PTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onRequestPermissions = {
                            requestPermissionLauncher.launch(getRequiredPermissions())
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ChatViewModel, onRequestPermissions: () -> Unit) {
    var messageText by remember { mutableStateOf("") }
    val messages = viewModel.messages
    val status by viewModel.status
    val connectedId by viewModel.connectedId
    
    val verificationCode by viewModel.verificationCode
    val pendingPublicKey by viewModel.pendingPublicKey
    val isTrustEstablished by viewModel.isTrustEstablished

    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val isKeyboardVisible = WindowInsets.isImeVisible

    // Security Dialog
    if (verificationCode != null && pendingPublicKey != null) {
        AlertDialog(
            onDismissRequest = { viewModel.rejectConnection() },
            title = { Text("Security Verification") },
            text = { 
                Column {
                    Text("Verify this code with the peer to ensure a secure connection:")
                    Text(
                        text = verificationCode!!,
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.padding(vertical = 16.dp).align(Alignment.CenterHorizontally)
                    )
                }
            },
            confirmButton = {
                Button(onClick = { viewModel.acceptConnection() }) {
                    Text("Accept")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.rejectConnection() }) {
                    Text("Reject")
                }
            }
        )
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("P2P Offline Chat", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Status: $status",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isTrustEstablished) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                    }
                },
                actions = {
                    if (connectedId == null && status.contains("Searching")) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .consumeWindowInsets(paddingValues)
                .imePadding()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { keyboardController?.hide() })
                }
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty()) {
                    Text(
                        "No messages yet. Start discovery to chat!",
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(messages) { message ->
                        val isMe = message.isMe
                        Column(
                            modifier = Alignment.End.let { if (isMe) Modifier.fillMaxWidth() else Modifier.fillMaxWidth() }, // Placeholder for alignment logic
                            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                        ) {
                            Text(
                                text = if (isMe) "ME" else "PEER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isMe) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.secondaryContainer
                                ),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Text(message.content, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            }

            Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = messageText,
                            onValueChange = { messageText = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(if (isTrustEstablished) "Write something..." else "Awaiting verification...") },
                            maxLines = 3,
                            enabled = isTrustEstablished,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            enabled = messageText.isNotBlank() && isTrustEstablished,
                            onClick = {
                                viewModel.sendMessage(messageText)
                                messageText = ""
                                keyboardController?.hide()
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = if (messageText.isNotBlank() && isTrustEstablished) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    if (!isKeyboardVisible) {
                        Button(
                            onClick = { onRequestPermissions() },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text("Reset & Search for Peers")
                        }
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
