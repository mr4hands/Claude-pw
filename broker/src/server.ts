import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import type { Duplex } from 'node:stream'
import { WebSocketServer, type WebSocket, type RawData } from 'ws'

import type { Config } from './config.js'
import { PairingCodes, formatForDisplay } from './pairing.js'
import { RateLimiter } from './ratelimit.js'
import { Registry, type AnnouncedSession, type DaemonConnection } from './registry.js'
import { RevocationStore } from './revocation.js'
import { bearerFrom, mintToken, verifyToken, type TokenPayload } from './tokens.js'

const MAX_CONTROL_BYTES = 64 * 1024

export interface Broker {
  readonly server: Server
  readonly registry: Registry
  readonly revocations: RevocationStore
  listen(port: number): Promise<number>
  close(): Promise<void>
}

/**
 * The broker.
 *
 * It is deliberately incurious: once a device socket and a daemon socket are
 * joined it forwards frames verbatim and never parses them. The only things it
 * understands are authentication, pairing, and which daemon serves which
 * session id. That keeps it out of the way when the wire protocol changes, and
 * keeps the internet-facing surface as small as it can be.
 */
export function createBroker(config: Config): Broker {
  const registry = new Registry()
  const revocations = new RevocationStore(config.stateFile)
  const pairing = new PairingCodes(config.pairingTtlMs)
  // The only unauthenticated endpoint, so the only one worth limiting.
  const pairLimiter = new RateLimiter(10, 60_000)

  const wss = new WebSocketServer({ noServer: true, maxPayload: config.maxPayloadBytes })
  const alive = new WeakMap<WebSocket, boolean>()

  const server = createServer((request, response) => {
    handleHttp(request, response).catch((error: unknown) => {
      console.error('[broker] request failed', error)
      if (!response.headersSent) sendJson(response, 500, { error: 'internal_error' })
    })
  })

  server.on('upgrade', (request, socket, head) => {
    handleUpgrade(request, socket, head)
  })

  const heartbeat = setInterval(() => {
    for (const socket of wss.clients) {
      if (alive.get(socket) === false) {
        socket.terminate()
        continue
      }
      alive.set(socket, false)
      socket.ping()
    }
  }, config.heartbeatMs)
  heartbeat.unref()

  // ---------------------------------------------------------------- HTTP ---

  async function handleHttp(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const url = new URL(request.url ?? '/', 'http://broker.local')

    if (request.method === 'GET' && url.pathname === '/v1/health') {
      sendJson(response, 200, { ok: true })
      return
    }

    if (request.method === 'POST' && url.pathname === '/v1/pair') {
      await handlePair(request, response)
      return
    }

    if (request.method === 'GET' && url.pathname === '/v1/code/sessions') {
      const auth = authenticate(request, 'device')
      if (auth === null) {
        sendJson(response, 401, { error: 'unauthorized' })
        return
      }
      if (!registry.hasDaemon(auth.a)) {
        // The watch shows "Could not reach Claude Code", which is the truth:
        // the broker is up but nothing is serving this account.
        sendJson(response, 503, { error: 'no_daemon_connected' })
        return
      }
      sendJson(response, 200, { data: registry.listSessions(auth.a) })
      return
    }

    sendJson(response, 404, { error: 'not_found' })
  }

  async function handlePair(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const client = clientKey(request)
    if (!pairLimiter.tryConsume(client)) {
      sendJson(response, 429, { error: 'too_many_requests' })
      return
    }

    let body: unknown
    try {
      body = JSON.parse(await readBody(request, 4096))
    } catch {
      sendJson(response, 400, { error: 'bad_request' })
      return
    }
    if (typeof body !== 'object' || body === null) {
      sendJson(response, 400, { error: 'bad_request' })
      return
    }

    const fields = body as Record<string, unknown>
    const account = pairing.redeem(fields['code'] as string)
    if (account === null) {
      // One message for expired, malformed, already-used and never-existed, so
      // a guesser learns nothing from the difference.
      sendJson(response, 400, { error: 'invalid_code' })
      return
    }

    const rawName = fields['deviceName']
    const name = typeof rawName === 'string' ? rawName.slice(0, 64) : undefined
    const minted = mintToken(config.secret, {
      role: 'device',
      account,
      ...(name === undefined ? {} : { name }),
    })

    sendJson(response, 200, {
      token: minted.token,
      account,
      tokenId: minted.payload.i,
    })
  }

  // ----------------------------------------------------------- WebSocket ---

  function handleUpgrade(request: IncomingMessage, socket: Duplex, head: Buffer): void {
    const url = new URL(request.url ?? '/', 'http://broker.local')
    const path = url.pathname

    if (path === '/v1/daemon') {
      const auth = authenticate(request, 'daemon')
      if (auth === null) return reject(socket, 401)
      wss.handleUpgrade(request, socket, head, (ws) => acceptDaemon(ws, auth))
      return
    }

    const attachMatch = /^\/v1\/daemon\/attach\/([A-Za-z0-9-]{1,64})$/.exec(path)
    if (attachMatch !== null) {
      const auth = authenticate(request, 'daemon')
      if (auth === null) return reject(socket, 401)
      const attachmentId = attachMatch[1] as string
      wss.handleUpgrade(request, socket, head, (ws) => acceptDaemonAttach(ws, auth, attachmentId))
      return
    }

    const streamMatch = /^\/v1\/code\/sessions\/([^/]{1,256})\/stream$/.exec(path)
    if (streamMatch !== null) {
      const auth = authenticate(request, 'device')
      if (auth === null) return reject(socket, 401)
      const sessionId = decodeURIComponent(streamMatch[1] as string)
      wss.handleUpgrade(request, socket, head, (ws) => acceptDevice(ws, auth, sessionId))
      return
    }

    reject(socket, 404)
  }

  function acceptDaemon(ws: WebSocket, auth: TokenPayload): void {
    track(ws)

    const daemon: DaemonConnection = {
      tokenId: auth.i,
      account: auth.a,
      send: (message) => sendMessage(ws, message),
      close: (code, reason) => ws.close(code, reason),
    }
    registry.addDaemon(daemon)
    daemon.send({ type: 'hello', account: auth.a, tokenId: auth.i })

    ws.on('message', (data) => {
      const message = parseControl(data)
      if (message === null) {
        daemon.send({ type: 'error', message: 'unparseable control frame' })
        return
      }
      handleDaemonControl(daemon, message)
    })

    ws.on('close', () => registry.removeDaemon(daemon))
    ws.on('error', () => registry.removeDaemon(daemon))
  }

  function handleDaemonControl(daemon: DaemonConnection, message: Record<string, unknown>): void {
    switch (message['type']) {
      case 'sessions': {
        const announced = toSessions(message['sessions'])
        if (announced === null) {
          daemon.send({ type: 'error', message: 'sessions must be an array of objects with string ids' })
          return
        }
        registry.announceSessions(daemon, announced)
        daemon.send({ type: 'sessions.ack', count: announced.length })
        return
      }

      case 'pair.begin': {
        const issued = pairing.issue(daemon.account)
        daemon.send({
          type: 'pair.code',
          code: issued.code,
          display: formatForDisplay(issued.code),
          expiresAt: issued.expiresAt,
        })
        return
      }

      case 'device.revoke': {
        const tokenId = message['tokenId']
        if (typeof tokenId !== 'string' || tokenId.length === 0) {
          daemon.send({ type: 'error', message: 'device.revoke needs a tokenId' })
          return
        }
        revocations.revoke(tokenId)
        // Drop any live socket already using it.
        for (const socket of wss.clients) {
          if (deviceTokenIds.get(socket) === tokenId) socket.close(4003, 'revoked')
        }
        daemon.send({ type: 'device.revoked', tokenId })
        return
      }

      case 'ping':
        daemon.send({ type: 'pong' })
        return

      default:
        daemon.send({ type: 'error', message: `unknown control type: ${String(message['type'])}` })
    }
  }

  const deviceTokenIds = new WeakMap<WebSocket, string>()

  function acceptDevice(ws: WebSocket, auth: TokenPayload, sessionId: string): void {
    track(ws)
    deviceTokenIds.set(ws, auth.i)

    if (!registry.addDevice(auth.a, config.maxDevicesPerAccount)) {
      ws.close(4004, 'too many devices')
      return
    }
    ws.once('close', () => registry.removeDevice(auth.a))

    const daemon = registry.findDaemonForSession(auth.a, sessionId)
    if (daemon === null) {
      ws.close(4404, 'no daemon serving that session')
      return
    }

    // Frames can arrive before the daemon dials back in. Hold them rather than
    // dropping them: the watch sends a snapshot request the moment it opens.
    const queued: Array<{ data: RawData; binary: boolean }> = []
    let joined = false
    const collect = (data: RawData, binary: boolean): void => {
      if (joined) return
      if (queued.length < 64) queued.push({ data, binary })
    }
    ws.on('message', collect)

    const attachment = registry.openAttachment(auth.a, sessionId, daemon)
    daemon.send({ type: 'attach', attachmentId: attachment.id, sessionId })

    const timer = setTimeout(() => {
      if (joined) return
      registry.dropAttachment(attachment.id)
      ws.close(4408, 'daemon did not attach in time')
    }, config.attachTimeoutMs)
    timer.unref()

    waiting.set(attachment.id, {
      device: ws,
      onJoin: (daemonSocket) => {
        joined = true
        clearTimeout(timer)
        ws.off('message', collect)
        for (const frame of queued) daemonSocket.send(frame.data as Buffer, { binary: frame.binary })
        queued.length = 0
        pipe(ws, daemonSocket)
      },
    })

    ws.once('close', () => {
      clearTimeout(timer)
      waiting.delete(attachment.id)
      registry.dropAttachment(attachment.id)
    })
  }

  const waiting = new Map<string, { device: WebSocket; onJoin: (daemon: WebSocket) => void }>()

  function acceptDaemonAttach(ws: WebSocket, auth: TokenPayload, attachmentId: string): void {
    track(ws)

    const pending = registry.claimAttachment(attachmentId, auth.a)
    const entry = waiting.get(attachmentId)
    if (pending === null || entry === undefined) {
      ws.close(4404, 'unknown attachment')
      return
    }
    waiting.delete(attachmentId)
    entry.onJoin(ws)
  }

  // --------------------------------------------------------------- shared ---

  function authenticate(request: IncomingMessage, role: 'daemon' | 'device'): TokenPayload | null {
    const payload = verifyToken(config.secret, bearerFrom(request.headers.authorization))
    if (payload === null) return null
    if (payload.r !== role) return null
    if (revocations.isRevoked(payload.i)) return null
    return payload
  }

  function track(ws: WebSocket): void {
    alive.set(ws, true)
    ws.on('pong', () => alive.set(ws, true))
  }

  return {
    server,
    registry,
    revocations,
    listen(port: number): Promise<number> {
      return new Promise((resolve, reject) => {
        server.once('error', reject)
        server.listen(port, () => {
          const address = server.address()
          resolve(typeof address === 'object' && address !== null ? address.port : port)
        })
      })
    },
    close(): Promise<void> {
      clearInterval(heartbeat)
      for (const socket of wss.clients) socket.terminate()
      return new Promise((resolve) => {
        wss.close(() => server.close(() => resolve()))
      })
    },
  }
}

/** Joins two sockets: everything one sends, the other receives, verbatim. */
function pipe(a: WebSocket, b: WebSocket): void {
  const forward = (from: WebSocket, to: WebSocket) => {
    from.on('message', (data: RawData, binary: boolean) => {
      if (to.readyState === to.OPEN) to.send(data as Buffer, { binary })
    })
    from.on('close', (code, reason) => {
      if (to.readyState === to.OPEN) {
        // Codes below 4000 outside the allowed set cannot be forwarded as-is.
        to.close(code >= 4000 && code <= 4999 ? code : 1000, reason.toString().slice(0, 120))
      }
    })
    from.on('error', () => {
      if (to.readyState === to.OPEN) to.close(1011, 'peer error')
    })
  }
  forward(a, b)
  forward(b, a)
}

function toSessions(value: unknown): AnnouncedSession[] | null {
  if (!Array.isArray(value)) return null
  const out: AnnouncedSession[] = []
  for (const entry of value) {
    if (typeof entry !== 'object' || entry === null) return null
    const id = (entry as Record<string, unknown>)['id']
    if (typeof id !== 'string' || id.length === 0 || id.length > 256) return null
    out.push(entry as AnnouncedSession)
  }
  return out
}

function parseControl(data: RawData): Record<string, unknown> | null {
  const text = data.toString()
  if (text.length > MAX_CONTROL_BYTES) return null
  try {
    const parsed: unknown = JSON.parse(text)
    if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return null
    return parsed as Record<string, unknown>
  } catch {
    return null
  }
}

function sendMessage(ws: WebSocket, message: Record<string, unknown>): void {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(message))
}

function sendJson(response: ServerResponse, status: number, body: unknown): void {
  const text = JSON.stringify(body)
  response.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': Buffer.byteLength(text),
    'cache-control': 'no-store',
  })
  response.end(text)
}

function reject(socket: Duplex, status: number): void {
  const reason = status === 401 ? 'Unauthorized' : 'Not Found'
  socket.write(`HTTP/1.1 ${status} ${reason}\r\nConnection: close\r\n\r\n`)
  socket.destroy()
}

async function readBody(request: IncomingMessage, limit: number): Promise<string> {
  const chunks: Buffer[] = []
  let total = 0
  for await (const chunk of request) {
    const buffer = chunk as Buffer
    total += buffer.length
    if (total > limit) throw new Error('body too large')
    chunks.push(buffer)
  }
  return Buffer.concat(chunks).toString('utf8')
}

function clientKey(request: IncomingMessage): string {
  // Behind a reverse proxy this is the proxy's address, which is why the limit
  // is generous. Set trust-proxy handling here if you terminate TLS elsewhere.
  return request.socket.remoteAddress ?? 'unknown'
}
