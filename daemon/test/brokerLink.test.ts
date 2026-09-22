import { strict as assert } from 'node:assert'
import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import test, { describe } from 'node:test'
import { WebSocket } from 'ws'

import { BrokerLink, type SessionChannel } from '../src/brokerLink.js'

/**
 * Drives the daemon's broker link against the *real* broker from ../../broker.
 *
 * The two halves were written separately against a document; this is the only
 * thing that proves they actually interoperate over sockets rather than each
 * matching my reading of the spec.
 */
const here = dirname(fileURLToPath(import.meta.url))
const brokerDist = resolve(here, '../../../broker/dist/src')
const brokerBuilt = existsSync(resolve(brokerDist, 'server.js'))

describe('daemon <-> broker', { skip: brokerBuilt ? false : 'broker not built (run npm run build in ../broker)' }, () => {
  test('announces sessions, pairs a device, and pipes frames to a session', async () => {
    // Loaded by computed path so TypeScript does not try to resolve across
    // package boundaries; the shapes are asserted below rather than typed.
    const { createBroker } = (await import(`${brokerDist}/server.js`)) as {
      createBroker: (config: unknown) => {
        listen(port: number): Promise<number>
        close(): Promise<void>
      }
    }
    const { loadConfig } = (await import(`${brokerDist}/config.js`)) as {
      loadConfig: (env: Record<string, string>) => { secret: Buffer }
    }
    const { mintToken } = (await import(`${brokerDist}/tokens.js`)) as {
      mintToken: (secret: Buffer, input: { role: string; account: string }) => { token: string }
    }

    const secretHex = randomBytes(32).toString('hex')
    const config = loadConfig({ BROKER_SECRET: secretHex, PORT: '0' })
    const broker = createBroker(config)
    const port = await broker.listen(0)
    const base = `127.0.0.1:${port}`

    const daemonToken = mintToken(config.secret, { role: 'daemon', account: 'it' }).token

    // A stand-in for SessionHost: echoes so the test can see the round trip.
    const received: string[] = []
    const logs: string[] = []
    let closed = false
    const link = new BrokerLink({
      log: (message) => logs.push(message),
      brokerUrl: `ws://${base}`,
      token: daemonToken,
      reconnectBaseMs: 50,
      reconnectMaxMs: 200,
      catalog: async () => [
        { id: 'sess-1', title: 'frontend-app', host: 'laptop', cwd: '/src', status: 'idle', last_activity_ms: 1 },
      ],
      sessionFactory: (sessionId, send): SessionChannel => ({
        onFrame(frame) {
          received.push(frame)
          send(JSON.stringify({ type: 'status', status: 'executing', echoOf: sessionId }))
        },
        onClose() {
          closed = true
        },
      }),
    })

    try {
      link.start()

      // The broker only serves a catalog once a daemon has announced one.
      const deviceToken = await pairViaDaemon(base, link, logs)
      await waitFor(async () => {
        const response = await fetch(`http://${base}/v1/code/sessions`, bearer(deviceToken))
        if (response.status !== 200) return false
        const body = (await response.json()) as { data: Array<{ id: string }> }
        return body.data.some((row) => row.id === 'sess-1')
      }, 'catalog never reached the device')

      // Now attach the way the watch does, on the watch's URL shape.
      const device = new WebSocket(`ws://${base}/v1/code/sessions/sess-1/stream`, bearer(deviceToken))
      const reply = new Promise<string>((res, rej) => {
        const timer = setTimeout(() => rej(new Error('daemon never answered')), 8000)
        device.on('message', (data) => {
          clearTimeout(timer)
          res(String(data))
        })
      })
      await new Promise<void>((res, rej) => {
        device.once('open', () => res())
        device.once('error', rej)
      })

      device.send('{"type":"snapshot_request"}')

      const answer = JSON.parse(await reply) as Record<string, unknown>
      assert.equal(answer['echoOf'], 'sess-1', 'daemon was handed the wrong session id')
      assert.deepEqual(received, ['{"type":"snapshot_request"}'])

      device.close()
      await waitFor(async () => closed, 'daemon never saw the watch detach')
    } finally {
      link.stop()
      await broker.close()
    }
  })
})

function bearer(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/** Uses the daemon's own pairing path, then redeems the code like a watch. */
async function pairViaDaemon(base: string, link: BrokerLink, logs: string[]): Promise<string> {
  let code: string | null = null

  await waitFor(async () => {
    link.requestPairingCode()
    await delay(120)
    for (const line of logs) {
      const match = /pairing code: ([A-Z0-9]+)-([A-Z0-9]+)/.exec(line)
      if (match !== null) {
        code = `${match[1]}${match[2]}`
        return true
      }
    }
    return false
  }, 'daemon never got a pairing code')

  const response = await fetch(`http://${base}/v1/pair`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ code, deviceName: 'test watch' }),
  })
  assert.equal(response.status, 200, 'pairing was rejected')
  return String(((await response.json()) as Record<string, unknown>)['token'])
}

async function waitFor(condition: () => Promise<boolean>, message: string, timeoutMs = 8000): Promise<void> {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    if (await condition()) return
    await delay(100)
  }
  throw new Error(message)
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
