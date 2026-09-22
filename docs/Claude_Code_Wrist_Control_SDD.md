# **Design Document: Claude Code Wrist Control (Wear OS)**

**Version:** 2.0 (Direct Cloud Integration)

## **1\. Overview**

**Claude Code Wrist Control** is a Wear OS companion application designed for developers. It connects directly to a user's Anthropic account to interface with active Claude Code (claude \--rc) remote sessions. The app provides a wrist-based, voice-driven interface to monitor AI agent progress, approve/deny terminal commands, and issue prompt directions from anywhere, without requiring a custom local network proxy or daemon.

## **2\. Goals & Non-Goals**

### **Goals:**

* **Zero-Config Setup:** Users can simply log in via OAuth and instantly see their active Claude Code sessions. No custom background scripts required.  
* **Glanceable Monitoring:** Provide real-time status of the Claude Code agent (e.g., *Idle, Thinking, Executing, Awaiting Approval*).  
* **Frictionless Approvals:** Enable one-tap or voice-activated approvals/denials for file modifications and terminal commands.  
* **Voice-First Interaction:** Utilize Wear OS native Speech-to-Text (STT) and Text-to-Speech (TTS) for full-duplex communication with the agent.

### **Non-Goals:**

* Full code editing or file viewing on the watch screen.  
* Executing tasks on the watch itself (the host machine running claude \--rc still performs the actual file/terminal operations).

## **3\. System Architecture**

The architecture leverages Anthropic's existing cloud infrastructure. When a developer runs claude \--rc on their laptop, the CLI establishes an outbound WebSocket connection to Anthropic's servers. The Pixel Watch app acts as a remote client connecting to that same cloud session.

┌─────────────────┐       HTTPS / WSS        ┌───────────────────────┐  
│   Pixel Watch   ├─────────────────────────►│  Anthropic Cloud API  │  
│   (Wear OS)     │  (OAuth Auth Token)      │   (claude.ai / code)  │  
│                 │◄─────────────────────────┤  \- Session Manager    │  
│ \- UI (Compose)  │                          └───────────┬───────────┘  
│ \- STT / TTS     │                                      │ WSS Relay  
│ \- Auth Client   │                                      │  
└────────┬────────┘                                      ▼  
         │ BLE / WiFi                        ┌───────────────────────┐  
         ▼                                   │ Local Dev Workstation │  
┌─────────────────┐                          │ (macOS/Linux/Win)     │  
│  Android Phone  │                          │                       │  
│  (For OAuth     │                          │ \- claude \--rc process │  
│   Login Flow)   │                          │ \- Modifies local code │  
└─────────────────┘                          └───────────────────────┘

### **Components:**

1. **Wear OS Client (App):** Built with Jetpack Compose for Wear OS. Handles OAuth token storage, session discovery, WSS streaming, STT/TTS, and UI.  
2. **Anthropic Cloud API:** The official backend infrastructure that synchronizes remote Claude Code sessions.  
3. **Local Dev Workstation:** The developer's machine running the official claude npm package in remote control mode (--rc).

## **4\. User Interface & UX (Jetpack Compose)**

(Optimized for circular displays)

* **Screen 1: Session Selector:** A simple list showing active claude \--rc sessions (e.g., Desktop \- frontend-app (Active)).  
* **Screen 2: The Main Dashboard:** A large, animated status ring mapping to Claude's state (Thinking, Executing, Awaiting Approval). Swipe up for logs, swipe right for quick actions.  
* **Screen 3: The Approval Gate:** Split-screen high-contrast buttons (Green Check / Red X) to approve or deny proposed terminal commands.  
* **Screen 4: Voice Input / Push-to-Talk:** Audio waveform for voice dictation triggered by a physical button press or screen tap.

## **5\. Core Interactions & Data Flow**

### **Flow A: Agent Requires Approval**

1. **Host Machine:** claude \--rc attempts to run a shell command. It pauses and sends an approval\_required event up to the Anthropic Cloud.  
2. **Anthropic Cloud:** Pushes the event via WSS to all connected remote clients, including the Pixel Watch.  
3. **Watch:** Receives payload. Vibrates vigorously and wakes to the **Approval Gate** screen. TTS announces: *"Claude requests permission to execute npm install."*  
4. **User:** Taps the Green Check.  
5. **Watch:** Sends an approval\_granted payload to the Anthropic API.  
6. **Anthropic Cloud:** Relays the approval to the host machine.  
7. **Host Machine:** Claude continues executing the task.

### **Flow B: User Initiates Voice Command**

1. **User:** Long-presses the watch crown and speaks: *"Skip the CSS changes and just fix the database schema."*  
2. **Watch:** Converts audio to text natively on the watch.  
3. **Watch:** Sends the text as a user prompt payload over HTTPS/WSS to the active Anthropic session.  
4. **Anthropic Cloud:** Routes the prompt to the running CLI on the host machine.  
5. **Watch:** Receives streamed text response back from the cloud.  
6. **Watch:** TTS engine reads the summary response out loud.

## **6\. Security & Authentication (Native OAuth)**

To ensure ease of use without compromising security, the app uses standard Wear OS authentication flows.

1. **Wear OS RemoteAuthClient:**  
   * When the user opens the watch app for the first time, they are prompted to log in.  
   * Using the androidx.wear.phone.interactions.authentication library, the watch triggers a secure OAuth browser tab on the paired Android phone.  
2. **Phone Flow:**  
   * The user logs into claude.ai on their phone.  
   * The phone receives the OAuth Access Token and securely transmits it back to the watch via the encrypted Google Wearable Data Layer.  
3. **Encrypted Data Store:** The token is stored securely on the watch using Android's EncryptedSharedPreferences.  
4. **Token Revocation:** If the watch is lost, the user can revoke the session directly from their Anthropic account dashboard on the web.

## **7\. Technical Stack**

* **Platform:** Wear OS 4.0 / 5.0 (Android 13+)  
* **Language:** Kotlin  
* **UI Framework:** Jetpack Compose for Wear OS (wear.compose.material)  
* **Networking:** Ktor Client or OkHttp (for REST API & WebSockets)  
* **Authentication:** androidx.wear.phone.interactions (Remote Auth)  
* **Voice/Audio:** android.speech.SpeechRecognizer, android.speech.tts.TextToSpeech  
* **Background:** Android WorkManager & Foreground Services to maintain the WSS connection when the screen sleeps.

## **8\. Implementation Phases**

* **Phase 1: Authentication & API Discovery**  
  * Implement the RemoteAuthClient to pass tokens from the phone to the watch.  
  * Write the network layer to hit GET /v1/code/sessions (or equivalent Anthropic endpoint) and parse the active sessions list.  
* **Phase 2: Wear OS UI & Navigation**  
  * Scaffold the Jetpack Compose app.  
  * Build the Session Selector, Status Dashboard, and Approval Gate UI components with mock data.  
* **Phase 3: Real-Time WebSocket & Voice**  
  * Connect the Watch UI to the Anthropic Cloud WSS relay.  
  * Integrate Speech-to-Text for sending commands, and Text-to-Speech for reading agent status updates.