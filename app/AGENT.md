# Project Context: OfflineP2P

## Project Goal
A local, offline peer-to-peer messaging application using Google Nearby Connections API and Room Database for persistent chat history.

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (Material 3)
- **Database:** Room (Single Source of Truth for persistence)
- **Networking:** Google Play Services Nearby Connections (**P2P_STAR strategy**)
- **Security:** AES-128 Symmetric Encryption (via CryptoHelper)
- **Architecture:** MVVM-inspired with reactive Flow updates from Room to Compose UI.

## Coding Standards & Preferences
1.  **State Management:** Use `LaunchedEffect` to observe database changes.
2.  **UI Feedback:** Provide visual status indicators for discovery (e.g., "Searching...", "Connected to [Device]").
3.  **Permissions:** Handle Bluetooth, Wi-Fi, and Location permissions for Android 12+ (API 31+).
4.  **UX:** Auto-scroll to bottom on new messages. Manual keyboard dismissal on send to support mid-range devices (Samsung A51).

## Current Progress / Known Logic
- **NearbyManager:** 
    - Implements **Dual-Role Discovery** (simultaneous Advertising and Scanning).
    - Uses a **Self-Healing loop**: automatically restarts P2P discovery on disconnection.
- **Security Implementation:**
    - **Payload Encryption:** All messages are encrypted using `CryptoHelper` before being sent as `Payload.BYTES`.
    - **Safe Decryption:** Incoming payloads are wrapped in try-catch blocks to prevent crashes from malformed data.
- **Message Flow (Offline-First):**
    1. UI sends message -> Saved to Room with `isSent = false`.
    2. `NearbyManager` encrypts the string and transmits to peer.
    3. On success acknowledgement, the specific Message ID is updated in Room to `isSent = true`.
- **Synchronization:** `syncUnsentMessages()` automatically triggers upon a successful connection to push any messages stored while the device was offline/disconnected.

## Future Requirements to Remember
- **Dynamic Key Exchange:** Replace the static AES key with a Diffie-Hellman handshake for unique session keys.
- **File Sharing:** Support for sending images and documents over the P2P link.
- **Group Chat:** Transition from P2P_STAR to a many-to-many communication model.
