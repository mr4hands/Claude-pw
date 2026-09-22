import { strict as assert } from 'node:assert'
import { randomBytes } from 'node:crypto'
import test, { describe } from 'node:test'

import { bearerFrom, mintToken, verifyToken } from '../src/tokens.js'

const secret = randomBytes(32)
const other = randomBytes(32)

describe('tokens', () => {
  test('round-trips a minted token', () => {
    const { token, payload } = mintToken(secret, { role: 'daemon', account: 'acct-1', name: 'laptop' })
    const verified = verifyToken(secret, token)
    assert.deepEqual(verified, payload)
    assert.equal(verified?.r, 'daemon')
    assert.equal(verified?.a, 'acct-1')
  })

  test('rejects a token signed with a different secret', () => {
    const { token } = mintToken(other, { role: 'device', account: 'acct-1' })
    assert.equal(verifyToken(secret, token), null)
  })

  test('rejects a tampered payload', () => {
    const { token } = mintToken(secret, { role: 'device', account: 'acct-1' })
    const [prefix, payload, signature] = token.split('.') as [string, string, string]

    // Re-encode the payload with the role escalated, keeping the old signature.
    const decoded = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as Record<string, unknown>
    decoded['r'] = 'daemon'
    const forged = Buffer.from(JSON.stringify(decoded)).toString('base64url')

    assert.equal(verifyToken(secret, `${prefix}.${forged}.${signature}`), null)
  })

  test('rejects malformed input without throwing', () => {
    for (const bad of ['', 'nonsense', 'a.b', 'a.b.c.d', 'wcb1..', `wcb1.${'x'.repeat(5000)}.y`]) {
      assert.equal(verifyToken(secret, bad), null, `expected null for ${bad.slice(0, 16)}`)
    }
    assert.equal(verifyToken(secret, null), null)
    assert.equal(verifyToken(secret, undefined), null)
  })

  test('rejects a wrong prefix even with a valid signature shape', () => {
    const { token } = mintToken(secret, { role: 'device', account: 'a' })
    assert.equal(verifyToken(secret, token.replace(/^wcb1/, 'wcb2')), null)
  })

  test('each token gets a distinct id so one can be revoked alone', () => {
    const a = mintToken(secret, { role: 'device', account: 'acct-1' })
    const b = mintToken(secret, { role: 'device', account: 'acct-1' })
    assert.notEqual(a.payload.i, b.payload.i)
  })

  test('parses bearer headers', () => {
    assert.equal(bearerFrom('Bearer abc'), 'abc')
    assert.equal(bearerFrom('  Bearer   abc  '), 'abc')
    assert.equal(bearerFrom('Basic abc'), null)
    assert.equal(bearerFrom(undefined), null)
  })
})
