import { listSessions } from '@anthropic-ai/claude-agent-sdk'

import { announce, type AnnouncedSession } from './catalog.js'
import { BrokerLink, type SessionChannel } from './brokerLink.js'
import { ConfigError, loadConfig } from './config.js'
import { parseClientFrame } from './protocol.js'
import { SessionHost } from './sessionHost.js'

async function main(): Promise<void> {
  let config
  try {
    config = loadConfig()
  } catch (error) {
    if (error instanceof ConfigError) {
      console.error(`[daemon] ${error.message}`)
      process.exit(78) // EX_CONFIG
    }
    throw error
  }

  const log = (message: string): void => console.log(`[daemon] ${message}`)

  // One host per session id, shared across attachments so two watches see the
  // same session rather than resuming it twice.
  const hosts = new Map<string, SessionHost>()

  const link = new BrokerLink({
    brokerUrl: config.brokerUrl,
    token: config.token,
    reconnectBaseMs: config.reconnectBaseMs,
    reconnectMaxMs: config.reconnectMaxMs,
    log,
    catalog: async (): Promise<AnnouncedSession[]> => {
      const sessions = await listSessions({ dir: config.workdir, limit: 25 })
      return sessions.map((info) =>
        announce(info, hosts.get(info.sessionId)?.currentStatus ?? 'idle'),
      )
    },
    sessionFactory: (sessionId, send): SessionChannel => {
      let host = hosts.get(sessionId)
      if (host === undefined) {
        host = new SessionHost({
          sessionId,
          cwd: config.workdir,
          permissionMode: config.permissionMode,
          emit: send,
        })
        hosts.set(sessionId, host)
        log(`resuming session ${sessionId}`)
      }
      const active = host

      return {
        onFrame(frame: string): void {
          const command = parseClientFrame(frame)
          if (command === null) return
          switch (command.kind) {
            case 'approval':
              active.resolveApproval(command.requestId, command.approved)
              return
            case 'prompt':
              active.sendPrompt(command.text)
              return
            case 'interrupt':
              active.interrupt()
              return
            case 'snapshot':
              active.snapshot()
              return
          }
        },
        onClose(): void {
          // The host outlives the socket on purpose: the watch reconnects
          // constantly, and resuming the session each time would be wasteful
          // and would lose in-flight approvals.
          log(`watch detached from ${sessionId}`)
        },
      }
    },
  })

  link.start()
  log(`serving sessions in ${config.workdir}`)
  log('press "p" then Enter for a pairing code')

  // Re-announce periodically so sessions started in a terminal show up.
  const refresh = setInterval(() => void link.announce(), config.catalogRefreshMs)
  refresh.unref()

  process.stdin.on('data', (data) => {
    if (data.toString().trim().toLowerCase() === 'p') link.requestPairingCode()
  })

  const shutdown = (signal: string): void => {
    log(`${signal}, shutting down`)
    clearInterval(refresh)
    for (const host of hosts.values()) host.stop()
    link.stop()
    process.exit(0)
  }
  process.on('SIGTERM', () => shutdown('SIGTERM'))
  process.on('SIGINT', () => shutdown('SIGINT'))
}

void main()
