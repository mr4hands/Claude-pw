# Relay protocol

The design document specifies the transport (HTTPS + WSS to Anthropic's cloud,
bearer-token auth) but treats the exact endpoints as indicative — it names
`GET /v1/code/sessions` "or equivalent". This file pins down the shape the app
codes against so that adapting to the real endpoints is a contained change.

Everything here lives in two places:

- `data/net/HttpClaudeCodeClient.kt` — paths, configurable via constructor args.
- `data/net/EventCodec.kt` — frame parsing and encoding.

Base URLs come from `BuildConfig` and are overridable at build time:

```
./gradlew :wear:assembleDebug \
  -Pwristcontrol.apiBaseUrl=https://staging.example.com/ \
  -Pwristcontrol.wsBaseUrl=wss://staging.example.com/
```

## REST

### `GET /v1/code/sessions`

Lists the caller's live `claude remote-control` sessions.

The decoder accepts a bare array or an object wrapping one under `data` or
`sessions`, and tolerates two spellings of each field:

| Field | Aliases | Notes |
| --- | --- | --- |
| `id` | `session_id` | Required; the row is dropped without it. |
| `title` | `name` | Defaults to `"Session"`. |
| `host` | `hostname` | Shown as `Host - title` in the picker. |
| `cwd` | `working_directory` | Optional. |
| `status` | — | One of the agent states below; unknown values become `unknown`. |
| `last_activity_ms` | — | Epoch millis. |

## WebSocket

### `/v1/code/sessions/{sessionId}/stream`

Authenticated with the same `Authorization: Bearer` header. The client sends a
`snapshot_request` immediately on connect — and on every reconnect — so that a
watch that was asleep recovers the current status and any approval still
pending.

### Inbound frames (relay to watch)

```jsonc
{"type": "status", "status": "executing"}

{"type": "approval_required", "session_id": "s1", "request": {
  "id": "req-7", "tool": "bash", "command": "npm install",
  "explanation": "Adds the query client the refactor needs.",
  "risk": "medium"
}}

{"type": "approval_resolved", "request_id": "req-7", "approved": true}

{"type": "log", "entry": {"id": "l1", "role": "tool", "text": "npm test", "ts": "1700000000000"}}

{"type": "assistant_delta", "message_id": "m1", "text": "Installed ", "final": false}
{"type": "assistant_delta", "message_id": "m1", "text": "the dep.", "final": true}

{"type": "session_ended", "reason": "host exited"}

{"type": "error", "message": "…"}
```

Agent states: `idle`, `thinking`, `executing`, `awaiting_approval`, `error`.
Risk levels: `low`, `medium`, `high` — an unrecognised or missing value is
treated as `medium`, never as safe.

`approval_required` is also accepted flattened (`request_id`, `tool_name`,
`input` at the top level), because the relay has shipped both shapes.

Unknown frame types and unparsable text are dropped silently. A watch that
goes blank because the server added a field is worse than one that ignores it.

### Outbound frames (watch to relay)

```jsonc
{"type": "approval_response", "request_id": "req-7", "approved": true, "remember": false}
{"type": "user_prompt", "text": "Skip the CSS changes and fix the schema"}
{"type": "interrupt"}
{"type": "snapshot_request"}
```

## Reconnection

`WebSocketSessionStream` owns its own loop:

- Exponential backoff from 1 s to 30 s, halved and re-jittered so a fleet of
  watches does not stampede the relay after an outage.
- `401`/`403` triggers exactly one silent token refresh; a second rejection
  surfaces as `ConnectionState.Failed` and sends the user back to sign-in.
- A clean server close (code 1000) ends the stream instead of reconnecting.
- OkHttp pings every 30 s, because carrier NAT will otherwise drop an idle
  socket while the screen is off.

## Authentication

`RemoteAuthClient` (Wear) hands an authorization URL to the paired phone, which
opens a real browser. Only the authorization code returns to the watch; the
code-for-token exchange happens on the watch, so the phone never holds an
access token. Tokens live in `EncryptedSharedPreferences` and are excluded from
backup and device transfer — they are Keystore-bound and would not decrypt on
another watch anyway.

On devices without `RemoteAuthClient`, the companion phone app runs the flow
itself and pushes the token payload over the Wearable Data Layer at
`/wristcontrol/auth-token`. That channel is encrypted and only delivers to an
app with the same package name and signing certificate.
