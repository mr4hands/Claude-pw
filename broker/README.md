# wristcontrol-broker

An authenticated relay so a Wear OS watch and a Claude Code daemon can reach
each other without either one opening an inbound port.

Both ends dial **out** to the broker; it pairs them and forwards frames. That
is the same shape Anthropic's Remote Control, SeaWork and the Telegram bridges
all use — the only difference is who runs the broker. Here, you do.

```
  your machine                    broker                     your watch
  ┌────────────┐                (this)                    ┌────────────┐
  │   daemon   │ ──outbound──►   pairs    ◄──outbound──── │  wear app  │
  └────────────┘                forwards                  └────────────┘
```

## It is deliberately incurious

Once a device socket and a daemon socket are joined, the broker forwards frames
verbatim and never parses them. The only things it understands are:

- authentication and pairing,
- which daemon serves which session id.

So it does not need changing when the wire protocol in `../docs/protocol.md`
does, and the internet-facing surface stays as small as it can be.

**What it can see:** session ids and whatever metadata a daemon announces
(title, host, status), plus connection metadata. **What it cannot see** is
nothing yet — frame payloads pass in clear. End-to-end encrypting them between
daemon and watch is a later step, noted under *Not done yet*.

## Quick start

```bash
npm install
npm run build

# 1. Generate the root secret. Everything the broker trusts derives from it.
export BROKER_SECRET=$(npm run --silent mint -- --secret)

# 2. Mint a token for your dev machine's daemon.
npm run --silent mint -- --daemon --account default --name laptop

# 3. Run it.
BROKER_STATE_FILE=./broker-state.json npm start
```

Put it behind TLS in production — Caddy or your platform's router. The broker
speaks plain HTTP and expects something in front of it to terminate TLS.

## Pairing a watch

Device tokens are **not** mintable from the CLI. The only way to enrol a device
is to be at the daemon, which means a stolen copy of `BROKER_SECRET` is not by
itself enough to attach a watch.

1. The daemon sends `{"type":"pair.begin"}` on its control socket.
2. The broker replies with a 10-character code, good for 120 seconds, single use.
3. The daemon shows it (QR or on screen).
4. The watch `POST`s it to `/v1/pair` and gets back a device token.

Lost the watch: the daemon sends `{"type":"device.revoke","tokenId":"…"}`. The
token stops working immediately and any live socket using it is dropped. Set
`BROKER_STATE_FILE` or revocations do not survive a restart.

## API

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| `GET` | `/v1/health` | none | Liveness. |
| `POST` | `/v1/pair` | none, rate limited | Redeem a pairing code for a device token. |
| `GET` | `/v1/code/sessions` | device | The catalog the daemon announced. |
| `WS` | `/v1/code/sessions/{id}/stream` | device | Attach to a session. |
| `WS` | `/v1/daemon` | daemon | Control channel. |
| `WS` | `/v1/daemon/attach/{id}` | daemon | Data socket, joined to a waiting device. |

Auth is `Authorization: Bearer <token>` on every request, including the
WebSocket upgrades.

### Daemon control channel

The broker sends `hello` **immediately** on connect, so attach your message
handler before the socket opens or you will miss it.

```jsonc
// broker → daemon
{"type": "hello", "account": "default", "tokenId": "…"}
{"type": "attach", "attachmentId": "…", "sessionId": "s1"}   // a device wants in
{"type": "sessions.ack", "count": 2}
{"type": "pair.code", "code": "7K2M9QRW4T", "display": "7K2M9-QRW4T", "expiresAt": 1770000000000}
{"type": "device.revoked", "tokenId": "…"}
{"type": "error", "message": "…"}

// daemon → broker
{"type": "sessions", "sessions": [{"id": "s1", "title": "frontend-app", "host": "laptop"}]}
{"type": "pair.begin"}
{"type": "device.revoke", "tokenId": "…"}
{"type": "ping"}
```

On `attach`, the daemon dials `WS /v1/daemon/attach/{attachmentId}`. The broker
joins that socket to the waiting device socket and steps out of the way. One
socket per attachment means no multiplexer on either side.

Frames the watch sends before the daemon dials in are **queued** (up to 64),
because the watch fires a snapshot request the instant it opens.

## Why the watch needs no code changes

The watch already connects to `GET /v1/code/sessions` and
`WSS /v1/code/sessions/{id}/stream` — that is why those paths look the way they
do. Pointing it here is one build property:

```bash
./gradlew :wear:assembleDebug \
  -Pwristcontrol.apiBaseUrl=https://broker.example.com/ \
  -Pwristcontrol.wsBaseUrl=wss://broker.example.com/
```

## Security notes

The broker is a path to something that runs shell commands, so:

- **Tokens** are HMAC-SHA256 over a JSON payload, compared in constant time,
  with a per-token id so one device can be revoked without rotating the secret.
- **`BROKER_SECRET`** must be ≥32 bytes of hex. A missing or short one is a
  hard startup failure, not a warning.
- **`/v1/pair`** is the only unauthenticated endpoint. Codes are ~50 bits,
  single use, 120-second TTL, looked up in constant time, behind a per-IP rate
  limit. Every rejection returns the same `invalid_code`, so a guesser learns
  nothing from the difference between expired, used, malformed and never-issued.
- **Roles are checked per endpoint**, so a device token cannot register as a
  daemon and serve fabricated sessions.
- **Accounts are checked on attachment claims**, so one account's daemon token
  cannot hijack another's waiting device socket.
- **Frames are capped** at 256 KB and dead sockets are reaped by a 30-second
  heartbeat.

Behind a reverse proxy, `/v1/pair`'s rate limit keys on the proxy's address —
wire up forwarded-header handling in `clientKey()` if that matters to you.

## Not done yet

- **End-to-end encryption.** The broker sees frame contents today. Giving the
  daemon and watch a shared key at pairing time would reduce it to ciphertext
  plus routing metadata.
- **Push delivery.** A held-open WebSocket is not what Wear OS wants for a
  watch that has been asleep for an hour; FCM is the robust answer. Worth
  adding only once a persistent socket is shown to be insufficient in practice.
- **The daemon itself** — this is the relay only.

## Tests

```bash
npm test
```

31 tests. The end-to-end suite starts a real broker and drives the full pairing
flow over real sockets, including the cases that matter: role confusion,
cross-account access, code replay, revocation, and frames queued before the
daemon attaches.
