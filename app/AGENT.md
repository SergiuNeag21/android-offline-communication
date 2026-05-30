# Project Context: OfflineP2P

## Project Goal
A local, offline peer-to-peer messaging application using Google Nearby Connections API and Room Database for persistent chat history. Developed as a Bachelor's Thesis project focusing on decentralized communication and mobile security.

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3) with Scaffold & TopAppBar
- **Database:** Room (Single Source of Truth)
- **Networking:** Google Play Services Nearby Connections (**P2P_STAR strategy**)
- **Security:** AES-128 Symmetric Encryption
- **Architecture:** **Clean MVVM (Model-View-ViewModel)** with Layered Package structure.

## Architecture (New)
1. **`data/`**: Room Entities, DAOs, and AppDatabase.
2. **`network/`**: `NearbyManager` handling all P2P discovery and transmission.
3. **`security/`**: Isolated `CryptoHelper` for encryption/decryption logic.
4. **`ui/`**: Compose Screens and `ChatViewModel`.

## Coding Standards & Preferences
1.  **State Management:** UI observes `messages` as a `SnapshotStateList` populated by a Room `Flow` within the `ViewModel`.
2.  **Lifecycle Awareness:** `ChatViewModel` (AndroidViewModel) manages the lifecycle of networking and database connections, surviving activity recreations.
3.  **UI Feedback:** Visual status indicators for discovery and real-time connection state updates.
4.  **UX:** Auto-scroll to bottom via `LaunchedEffect(messages.size)`. Manual keyboard dismissal on send to support mid-range devices (Samsung A51).

## Current Progress / Logic
- **NearbyManager:** 
    - Implements **Dual-Role Discovery** (simultaneous Advertising and Scanning).
    - Uses a **Self-Healing loop**: automatically restarts P2P discovery on disconnection.
- **Security Implementation:**
    - **Payload Encryption:** Messages encrypted via AES-128 before transmission as `Payload.BYTES`.
    - **Isolation:** Moved to dedicated package to allow for future cryptographic algorithm swaps.
- **Message Flow (Offline-First):**
    1. UI triggers `viewModel.sendMessage()`.
    2. Message is saved to Room (immediate local update).
    3. `NearbyManager` transmits encrypted payload.
    4. Room Flow automatically updates the UI upon data insertion/update.
- **Synchronization:** `syncUnsentMessages()` triggers upon successful peer connection to push pending messages.

## Future Requirements to Remember
- **Dynamic Key Exchange:** Replace static AES key with a Diffie-Hellman handshake.
- **File Sharing:** Support for sending images/documents over P2P.
- **Group Chat:** Transition from P2P_STAR to many-to-many model.
