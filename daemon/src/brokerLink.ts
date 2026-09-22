import { WebSocket } from 'ws'

import type { AnnouncedSession } from './catalog.js'

/** One attached watch. The link hands frames in; the channel sends frames out. */
export interface SessionChannel {
  onFrame(frame: string): void
  onClose(): void
}

export type SessionFactory = (sessionId: string, send: (frame: string) => void) => SessionChannel

export interface BrokerLinkOptions {
  readonly brokerUrl: string
  readonly token: string
  readonly reconnectBaseMs: number
  readonly reconnectMaxMs: number
  readonly sessionFactory: SessionFactory
  readonly catalog: () => Promise<AnnouncedSession[]>
  readonly log?: (message: string) => void
}

/**
 * The daemon's outbound connection to the broker.
 *
 * Only ever dials out, so the dev machine opens no ports. Reconnects with the
 * same jittered backoff the watch uses, because a laptop lid closing is the
 * normal case here, not an error.
 */
export class BrokerLink {
  private control: WebSocket | null = null
  private attempt = 0
  private stopped = false
  private timer: NodeJS.Timeout | null = null
  private readonly channels = new Set<WebSocket>()

  constructor(private readonly options: BrokerLinkOptions) {}

  start(): void {
    if (this.stopped) return
    this.connect()
  }

  stop(): void {
    this.stopped = true
    if (this.timer !== null) clearTimeout(this.timer)
    for (const socket of this.channels) socket.close(1000, 'daemon stopping')
    this.channels.clear()
    this.control?.close(1000, 'daemon stopping')
    this.control = null
  }

  /** Re-announces the catalog; call after sessions change. */
  async announce(): Promise<void> {
    const socket = this.control
    if (socket === null || socket.readyState !== socket.OPEN) return
    try {
      const sessions = await this.options.catalog()
      socket.send(JSON.stringify({ type: 'sessions', sessions }))
    } catch (error) {
      this.log(`could not list sessions: ${String(error)}`)
    }
  }

  /** Asks the broker for a pairing code to show as a QR. */
  requestPairingCode(): void {
    const socket = this.control
    if (socket === null || socket.readyState !== socket.OPEN) {
      this.log('not connected to the broker yet')
      return
    }
    socket.send(JSON.stringify({ type: 'pair.begin' }))
  }

  private connect(): void {
    const socket = new WebSocket(`${this.options.brokerUrl}/v1/daemon`, {
      headers: { Authorization: `Bearer ${this.options.token}` },
    })
    this.control = socket

    socket.on('open', () => {
      this.attempt = 0
      this.log('connected to broker')
    })

    socket.on('message', (raw) => {
      let message: Record<string, unknown>
      try {
        message = JSON.parse(String(raw)) as Record<string, unknown>
      } catch {
        return
      }
      this.handleControl(message)
    })

    socket.on('close', (code) => {
      this.control = null
      if (this.stopped) return
      // 4001/4003 mean the broker rejected the token; retrying will not fix it.
      if (code === 4001 || code === 4003) {
        this.log(`broker rejected this daemon token (${code}); not retrying`)
        return
      }
      this.scheduleReconnect()
    })

    socket.on('error', (error) => {
      this.log(`broker socket error: ${error.message}`)
    })
  }

  private handleControl(message: Record<string, unknown>): void {
    switch (message['type']) {
      case 'hello':
        void this.announce()
        return

      case 'attach': {
        const attachmentId = message['attachmentId']
        const sessionId = message['sessionId']
        if (typeof attachmentId !== 'string' || typeof sessionId !== 'string') return
        this.openAttachment(attachmentId, sessionId)
        return
      }

      case 'pair.code':
        this.log(`pairing code: ${String(message['display'] ?? message['code'])} (valid 2 minutes)`)
        return

      case 'error':
        this.log(`broker: ${String(message['message'])}`)
        return

      default:
        return
    }
  }

  private openAttachment(attachmentId: string, sessionId: string): void {
    const socket = new WebSocket(`${this.options.brokerUrl}/v1/daemon/attach/${attachmentId}`, {
      headers: { Authorization: `Bearer ${this.options.token}` },
    })
    this.channels.add(socket)

    const send = (frame: string): void => {
      if (socket.readyState === socket.OPEN) socket.send(frame)
    }
    const channel = this.options.sessionFactory(sessionId, send)

    socket.on('message', (raw) => channel.onFrame(String(raw)))
    socket.on('close', () => {
      this.channels.delete(socket)
      channel.onClose()
    })
    socket.on('error', (error) => this.log(`attachment socket error: ${error.message}`))
  }

  private scheduleReconnect(): void {
    this.attempt += 1
    const exponential = Math.min(
      this.options.reconnectMaxMs,
      this.options.reconnectBaseMs * 2 ** Math.min(this.attempt - 1, 5),
    )
    // Jitter, so a fleet of daemons does not stampede after a broker restart.
    const delay = exponential / 2 + Math.random() * (exponential / 2)
    this.log(`reconnecting in ${Math.round(delay)}ms`)
    this.timer = setTimeout(() => this.connect(), delay)
    this.timer.unref()
  }

  private log(message: string): void {
    this.options.log?.(message)
  }
}
