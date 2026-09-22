import { strict as assert } from 'node:assert'
import { randomBytes } from 'node:crypto'
import test, { after, before, describe } from 'node:test'
import { WebSocket } from 'ws'

import { loadConfig } from '../src/config.js'
import { createBroker, type Broker } from '../src/server.js'
import { mintToken } from '../src/tokens.js'

const secretHex = randomBytes(32).toString('hex')
const config = loadConfig({ BROKER_SECRET: secretHex, PORT: '0' } as NodeJS.ProcessEnv)

let broker: Broker
let base: string

before(async () => {
  broker = createBroker(config)
  const port = await broker.listen(0)
  base = `127.0.0.1:${port}`
})

after(async () => {
  await broker.close()
})

/**
 * Buffers messages from the moment the socket exists.
 *
 * The broker sends `hello` the instant a daemon connects, so a client that
 * attaches its handler after the open event has already missed it. Any real
 * daemon has to do this too — it is noted in the protocol docs.
 */
class Tap {
  private readonly queue: string[] = []
  private readonly waiters: Array<(value: string) => void> = []

  constructor(ws: WebSocket) {
    ws.on('message', (data) => {
      const text = String(data)
      const waiter = this.waiters.shift()
      if (waiter === undefined) this.queue.push(text)
      else waiter(text)
    })
  }

  next(timeoutMs = 5000): Promise<string> {
    const buffered = this.queue.shift()
    if (buffered !== undefined) return Promise.resolve(buffered)

    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        const index = this.waiters.indexOf(settle)
        if (index >= 0) this.waiters.splice(index, 1)
        reject(new Error('timed out waiting for a message'))
      }, timeoutMs)
      const settle = (value: string) => {
        clearTimeout(timer)
        resolve(value)
      }
      this.waiters.push(settle)
    })
  }

  async nextJson(timeoutMs = 5000): Promise<Record<string, unknown>> {
    return JSON.parse(await this.next(timeoutMs)) as Record<string, unknown>
  }
}

function opened(ws: WebSocket): Promise<void> {
  return new Promise((resolve, reject) => {
    ws.once('open', () => resolve())
    ws.once('error', reject)
  })
}

function closedWith(ws: WebSocket, timeoutMs = 5000): Promise<number> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('socket did not close')), timeoutMs)
    ws.once('close', (code) => {
      clearTimeout(timer)
      resolve(code)
    })
  })
}

function auth(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

function daemonToken(account = 'acct-1'): string {
  return mintToken(config.secret, { role: 'daemon', account }).token
}

/** Connects a daemon, announces sessions, and answers attach requests. */
async function connectDaemon(token: string, sessions: Array<{ id: string; [key: string]: unknown }>) {
  const ws = new WebSocket(`ws://${base}/v1/daemon`, auth(token))
  const tap = new Tap(ws)
  await opened(ws)

  const hello = await tap.nextJson()
  assert.equal(hello['type'], 'hello')

  ws.send(JSON.stringify({ type: 'sessions', sessions }))
  const ack = await tap.nextJson()
  assert.equal(ack['type'], 'sessions.ack')

  ws.on('message', (raw) => {
    const message = JSON.parse(String(raw)) as Record<string, unknown>
    if (message['type'] !== 'attach') return
    const attach = new WebSocket(`ws://${base}/v1/daemon/attach/${String(message['attachmentId'])}`, auth(token))
    attach.on('message', (data) => {
      // Echo with a marker so the test can prove the round trip.
      attach.send(`daemon-saw:${String(data)}`)
    })
  })

  return { ws, tap }
}

async function pairDevice(daemon: { ws: WebSocket; tap: Tap }): Promise<string> {
  daemon.ws.send(JSON.stringify({ type: 'pair.begin' }))
  const issued = await daemon.tap.nextJson()
  assert.equal(issued['type'], 'pair.code')

  const response = await fetch(`http://${base}/v1/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code: issued['code'], deviceName: 'Pixel Watch' }),
  })
  assert.equal(response.status, 200)
  const body = (await response.json()) as Record<string, unknown>
  return String(body['token'])
}

describe('broker end to end', () => {
  test('pairs a device and serves the announced catalog', async () => {
    const daemon = await connectDaemon(daemonToken(), [{ id: 's1', title: 'frontend-app' }])
    const deviceToken = await pairDevice(daemon)

    const list = await fetch(`http://${base}/v1/code/sessions`, auth(deviceToken))
    assert.equal(list.status, 200)
    const catalog = (await list.json()) as { data: Array<Record<string, unknown>> }
    assert.equal(catalog.data.length, 1)
    // The broker forwards fields it does not understand, verbatim.
    assert.equal(catalog.data[0]?.['title'], 'frontend-app')

    daemon.ws.close()
  })

  test('forwards a frame from the device to the daemon and back', async () => {
    const daemon = await connectDaemon(daemonToken('acct-pipe'), [{ id: 'sp' }])
    const deviceToken = await pairDevice(daemon)

    const device = new WebSocket(`ws://${base}/v1/code/sessions/sp/stream`, auth(deviceToken))
    const deviceTap = new Tap(device)
    await opened(device)

    device.send('hello-from-watch')
    assert.equal(await deviceTap.next(), 'daemon-saw:hello-from-watch')

    device.close()
    daemon.ws.close()
  })

  test('queues frames sent before the daemon attaches', async () => {
    const daemon = await connectDaemon(daemonToken('acct-queue'), [{ id: 'sq' }])
    const deviceToken = await pairDevice(daemon)

    const device = new WebSocket(`ws://${base}/v1/code/sessions/sq/stream`, auth(deviceToken))
    const deviceTap = new Tap(device)
    await opened(device)
    // Sent immediately, before the daemon's attach socket exists. The watch
    // does exactly this: it fires a snapshot request the moment it opens.
    device.send('early-frame')

    assert.equal(await deviceTap.next(), 'daemon-saw:early-frame')

    device.close()
    daemon.ws.close()
  })

  test('rejects every unauthenticated entry point', async () => {
    assert.equal((await fetch(`http://${base}/v1/code/sessions`)).status, 401)
    await assert.rejects(opened(new WebSocket(`ws://${base}/v1/daemon`)))
    await assert.rejects(opened(new WebSocket(`ws://${base}/v1/daemon`, auth('not-a-token'))))
  })

  test('a device token cannot open a daemon socket', async () => {
    const daemon = await connectDaemon(daemonToken('acct-role'), [{ id: 'sr' }])
    const deviceToken = await pairDevice(daemon)

    // Role confusion is the obvious escalation: a device that could register
    // as a daemon could serve its own fabricated sessions.
    await assert.rejects(opened(new WebSocket(`ws://${base}/v1/daemon`, auth(deviceToken))))

    daemon.ws.close()
  })

  test('a device cannot reach another account\u2019s session', async () => {
    const mine = await connectDaemon(daemonToken('acct-a'), [{ id: 'secret-session' }])
    const theirs = await connectDaemon(daemonToken('acct-b'), [{ id: 'their-session' }])
    const intruderToken = await pairDevice(theirs)

    const attempt = new WebSocket(`ws://${base}/v1/code/sessions/secret-session/stream`, auth(intruderToken))
    await opened(attempt)
    assert.equal(await closedWith(attempt), 4404)

    mine.ws.close()
    theirs.ws.close()
  })

  test('a revoked device token stops working', async () => {
    const daemon = await connectDaemon(daemonToken('acct-revoke'), [{ id: 'sv' }])
    const deviceToken = await pairDevice(daemon)

    assert.equal((await fetch(`http://${base}/v1/code/sessions`, auth(deviceToken))).status, 200)

    const payload = JSON.parse(
      Buffer.from(deviceToken.split('.')[1] as string, 'base64url').toString(),
    ) as Record<string, unknown>
    daemon.ws.send(JSON.stringify({ type: 'device.revoke', tokenId: payload['i'] }))
    const ack = await daemon.tap.nextJson()
    assert.equal(ack['type'], 'device.revoked')

    assert.equal((await fetch(`http://${base}/v1/code/sessions`, auth(deviceToken))).status, 401)

    daemon.ws.close()
  })

  test('a pairing code cannot be redeemed twice', async () => {
    const daemon = await connectDaemon(daemonToken('acct-replay'), [{ id: 'sx' }])

    daemon.ws.send(JSON.stringify({ type: 'pair.begin' }))
    const issued = await daemon.tap.nextJson()

    const redeem = () =>
      fetch(`http://${base}/v1/pair`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ code: issued['code'] }),
      })

    assert.equal((await redeem()).status, 200)
    assert.equal((await redeem()).status, 400)

    daemon.ws.close()
  })

  test('reports no_daemon_connected rather than an empty list', async () => {
    const orphanToken = mintToken(config.secret, { role: 'device', account: 'acct-orphan' }).token
    // An empty 200 would make the watch show "no sessions", which is a
    // different problem from "your laptop is offline".
    assert.equal((await fetch(`http://${base}/v1/code/sessions`, auth(orphanToken))).status, 503)
  })

  test('health needs no credentials', async () => {
    const response = await fetch(`http://${base}/v1/health`)
    assert.equal(response.status, 200)
    assert.deepEqual(await response.json(), { ok: true })
  })
})
