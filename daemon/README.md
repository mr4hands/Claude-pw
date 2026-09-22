# wristcontrol-daemon

Runs Claude Code on your machine and exposes its approvals, status and output
to your watch through the [broker](../broker).

```
your machine
├── wristcontrol-daemon              ← dials out to the broker
│   └── Claude Code (Agent SDK)      ← your files, your credentials, never leave
└── your normal terminal `claude`    ← untouched
```

It **runs** Claude rather than sitting beside it. That is what lets it
intercept `canUseTool`: the SDK calls it and waits, so the promise it returns
is the tool call held open until you tap your watch.

## The handoff

The daemon hosts its own session, so it cannot adopt a terminal session that is
currently live. It *can* resume one you have closed, with its history intact:

1. Working in the terminal, need to leave the desk
2. Exit the session
3. On the watch, pick it from the list — the daemon resumes **that same
   conversation**
4. Keep steering from your wrist

`listSessions()` is what makes the watch's session list real: those rows are
your resumable local sessions, including ones you started in a terminal.

## Setup

```bash
npm install
npm run build

export BROKER_URL=wss://broker.example.com
export DAEMON_TOKEN="…"     # from the broker: npm run mint -- --daemon
export WORKDIR=~/src/my-project

npm start
```

Press `p` then Enter to print a pairing code; redeem it from the watch within
two minutes.

| Variable | Meaning |
| --- | --- |
| `BROKER_URL` | `wss://…` of your broker. Required. |
| `DAEMON_TOKEN` | Minted by the broker's `mint` CLI. Required. |
| `WORKDIR` | Project directory whose sessions are served. Defaults to cwd. |
| `PERMISSION_MODE` | Defaults to `default`. See the warning below. |

> `PERMISSION_MODE=bypassPermissions` auto-approves every tool call, so nothing
> ever reaches your watch and the app becomes decorative. The daemon warns
> loudly at startup if you set it. `acceptEdits` similarly silences edit
> approvals while leaving shell commands prompting.

## How a tool call becomes a buzz

```
Claude wants to run `npm install`
  └─ SDK calls canUseTool(toolName, input, …) and BLOCKS
       └─ daemon emits {"type":"approval_required","request":{…}}
            └─ broker forwards it to the watch
                 └─ tile flips to Deny / Approve, watch buzzes
            ←─ {"type":"approval_response","approved":true}
       └─ canUseTool resolves {behavior:'allow'}
  └─ Claude runs the command
```

Denials return `{behavior:'deny', message}` — the message is shown to the
model, so it knows it was refused rather than that the tool failed.

## Risk levels

The watch buzzes harder for riskier calls. Classification is deliberately
conservative and lives in `src/protocol.ts`:

- **low** — read-only tools (`Read`, `Grep`, `Glob`, `WebSearch`, …)
- **medium** — ordinary shell, file writes, and **anything unrecognised**,
  including every MCP tool
- **high** — shell matching destructive patterns: `rm -rf`, `sudo`, `curl | sh`,
  `git push --force`, `git reset --hard`, `npm publish`, `dd`, `mkfs`,
  `kubectl delete`, `terraform destroy`, `DROP TABLE`, …

This decides how hard the watch buzzes, never whether a command runs. An
unknown tool is a reason for more caution, not less, so it is never `low`.

The command itself is shown **verbatim** and never summarised: an
approximation of a shell command is worse than no command at all.

## Tests

```bash
npm test
```

26 tests. The protocol and catalog layers are pure and tested directly. The
`brokerLink` suite drives the **real broker** from `../broker` over real
sockets — announcing a catalog, pairing a device, and piping a frame through
the watch's exact URL shape — because the two halves were written separately
against a document, and that is the only thing that proves they interoperate.

It skips with a message if `../broker` has not been built.

## What is not covered by tests

Everything that touches the Agent SDK: `SessionHost.run()`, resuming a real
session, and `canUseTool` being invoked by a live Claude. Exercising those
needs real credentials and a real session. The types are checked against the
SDK's own `.d.ts` (0.3.278), and the pure translation either side of the SDK
boundary is tested, but **the first real run is the first real test.**
