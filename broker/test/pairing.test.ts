import { strict as assert } from 'node:assert'
import test, { describe } from 'node:test'

import { PairingCodes, formatForDisplay, normalise } from '../src/pairing.js'

describe('pairing codes', () => {
  test('redeems once and only once', () => {
    const codes = new PairingCodes(60_000)
    const issued = codes.issue('acct-1')

    assert.equal(codes.redeem(issued.code), 'acct-1')
    // A replayed code must not enrol a second device.
    assert.equal(codes.redeem(issued.code), null)
  })

  test('expires', () => {
    let now = 1_000
    const codes = new PairingCodes(500, () => now)
    const issued = codes.issue('acct-1')

    now = 1_600
    assert.equal(codes.redeem(issued.code), null)
  })

  test('accepts the displayed form with a dash', () => {
    const codes = new PairingCodes(60_000)
    const issued = codes.issue('acct-1')
    assert.equal(codes.redeem(formatForDisplay(issued.code)), 'acct-1')
  })

  test('is case insensitive', () => {
    const codes = new PairingCodes(60_000)
    const issued = codes.issue('acct-1')
    assert.equal(codes.redeem(issued.code.toLowerCase()), 'acct-1')
  })

  test('rejects garbage without consuming a live code', () => {
    const codes = new PairingCodes(60_000)
    const issued = codes.issue('acct-1')

    for (const bad of ['', 'SHORT', 'IIIIIIIIII', '!!!!!!!!!!', 'A'.repeat(64)]) {
      assert.equal(codes.redeem(bad), null)
    }
    assert.equal(codes.redeem(issued.code), 'acct-1')
  })

  test('normalise rejects the ambiguous letters left out of the alphabet', () => {
    // I, L, O and U are excluded so a code read off a watch is unambiguous.
    assert.equal(normalise('IIIIIIIIII'), null)
    assert.equal(normalise('LLLLLLLLLL'), null)
    assert.equal(normalise('OOOOOOOOOO'), null)
    assert.equal(normalise('UUUUUUUUUU'), null)
  })

  test('sweeps expired codes rather than growing unbounded', () => {
    let now = 0
    const codes = new PairingCodes(100, () => now)
    for (let i = 0; i < 50; i++) codes.issue('acct-1')
    assert.equal(codes.size, 50)

    now = 1_000
    assert.equal(codes.size, 0)
  })
})
