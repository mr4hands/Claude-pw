# Claude Code Wrist Control

A Wear OS app for watching and steering remote `claude remote-control` sessions from your
wrist: glanceable agent status, one-tap approvals for the commands Claude wants
to run, and voice dictation for redirecting it mid-task.

Built to the design in `docs/Claude_Code_Wrist_Control_SDD.md`, plus a **status
tile** so approvals are reachable without opening the app at all.

## What's here

```
wear/      Wear OS app — UI, tile, relay client, foreground service, voice
mobile/    Phone companion — only needed as an OAuth fallback
docs/      Design document and the relay protocol the client codes against
```

### The watch app

| Surface | What it does |
| --- | --- |
| Session selector | Lists live `claude remote-control` sessions from the cloud relay. |
| Dashboard | Animated status ring — idle / thinking / executing / waiting. |
| Approval gate | The command verbatim, with large approve and deny targets. |
| Voice | Push-to-talk with a live level meter; transcript confirmed before sending. |
| Activity | Bounded transcript of recent tool calls and replies. |
| **Status tile** | All of the above at a swipe, including approve/deny buttons. |

Approvals also arrive as a high-priority notification with a full-screen intent,
so one raised while the screen is off wakes the watch straight onto the gate —
and can be answered from the shade without opening anything.

### The tile

`tile/ClaudeStatusTileService` renders synchronously from the same
`SessionRepository` snapshot the app and the notification use, so the three
surfaces can never disagree about whether Claude is blocked on you.

- **Blocked:** tool name, the command, and Deny / Approve buttons.
- **Working:** a progress arc coloured by state, the session name, and a
  "Speak" chip straight into voice input.
- **Not attached:** a "Choose session" chip.

Tile buttons can only launch an activity, so approve/deny route through
`TileActionActivity` — an invisible, no-display trampoline that writes to the
socket on the application scope and finishes immediately. `TileRefresher` pushes
updates on state changes that the tile actually draws, debounced, rather than
polling.

## Building

Requires JDK 17, the Android SDK (compileSdk 35, build-tools for API 35) and
network access to `dl.google.com` for the AndroidX and Wear artifacts.

```bash
./gradlew :wear:assembleDebug        # watch APK
./gradlew :mobile:assembleDebug      # phone companion (optional)
./gradlew test                       # JVM unit tests
./gradlew lint                       # Android lint
```

Install on a watch or emulator (Wear OS 4 / API 33 or newer):

```bash
adb install -r wear/build/outputs/apk/debug/wear-debug.apk
```

### Configuration

Endpoints and OAuth client details are build properties, so nothing has to be
edited in source to point the app at a different relay. Put them in
`~/.gradle/gradle.properties` or pass them on the command line:

| Property | Default |
| --- | --- |
| `wristcontrol.apiBaseUrl` | `https://api.anthropic.com/` |
| `wristcontrol.wsBaseUrl` | `wss://api.anthropic.com/` |
| `wristcontrol.oauthClientId` | `wristcontrol-wear` |
| `wristcontrol.oauthAuthorizeUrl` | `https://claude.ai/oauth/authorize` |
| `wristcontrol.oauthTokenUrl` | `https://console.anthropic.com/v1/oauth/token` |

> The design document treats the relay endpoints as indicative
> ("`GET /v1/code/sessions` or equivalent"). The defaults above follow that
> shape; `docs/protocol.md` documents exactly what the client sends and expects,
> and the paths are constructor arguments on `HttpClaudeCodeClient` so adapting
> to the real API is a contained change.

### Demo mode

Debug builds ship a scripted relay (`FakeClaudeCodeClient`) that drives the full
loop — status changes, an approval request, an assistant reply — with no account
and no network. Turn it on in Settings. It is the fastest way to see the tile,
the approval notification and TTS working on an emulator, and it is compiled out
of release builds.

## How it fits together

```
Watch (this app) ──WSS──► Anthropic cloud relay ──WSS──► claude remote-control on your laptop
       │
       ├─ SessionRepository   single source of truth, process-wide
       ├─ ForegroundService   keeps the socket alive when the screen sleeps
       ├─ Tile                renders repository state, pushes approvals back
       └─ EncryptedPrefs      Keystore-bound tokens, excluded from backup
```

`SessionRepository` is deliberately the only stateful thing: the UI, the tile,
the notification and the voice flow all read from it and write commands through
it, which is what keeps a tile tap and an open dashboard from opening two
sockets to the same session.

## Testing

`./gradlew test` covers the pieces where a mistake is invisible until it matters:

- **`EventCodecTest`** — every inbound frame shape, both approval spellings, and
  the rule that unknown frames are dropped rather than thrown.
- **`SessionRepositoryTest`** — approval de-duplication across reconnect
  snapshots, optimistic gate-closing, stale approvals dropped when the host
  answers elsewhere, delta buffering.
- **`TileStateTest`** — the shared status copy, including that a dead socket
  never reads as "Executing".

## Status

Phases 1–3 of the design document are implemented, plus tiles. The relay
endpoints are the documented-but-unverified shape described above; swapping in
the real paths touches `HttpClaudeCodeClient` and `EventCodec` only.
