import { strict as assert } from 'node:assert'
import test, { describe } from 'node:test'

import { Registry, type DaemonConnection } from '../src/registry.js'

function fakeDaemon(account: string, tokenId: string): DaemonConnection & { sent: unknown[] } {
  const sent: unknown[] = []
  return {
    account,
    tokenId,
    sent,
    send: (message) => void sent.push(message),
    close: () => undefined,
  }
}

describe('registry', () => {
  test('routes a session to the daemon that announced it', () => {
    const registry = new Registry()
    const a = fakeDaemon('acct-1', 'd-a')
    const b = fakeDaemon('acct-1', 'd-b')
    registry.addDaemon(a)
    registry.addDaemon(b)

    registry.announceSessions(a, [{ id: 's1' }])
    registry.announceSessions(b, [{ id: 's2' }])

    assert.equal(registry.findDaemonForSession('acct-1', 's1'), a)
    assert.equal(registry.findDaemonForSession('acct-1', 's2'), b)
    assert.equal(registry.findDaemonForSession('acct-1', 'nope'), null)
  })

  test('never routes across accounts', () => {
    const registry = new Registry()
    const mine = fakeDaemon('acct-1', 'd-a')
    registry.addDaemon(mine)
    registry.announceSessions(mine, [{ id: 's1' }])

    assert.equal(registry.findDaemonForSession('acct-2', 's1'), null)
    assert.deepEqual(registry.listSessions('acct-2'), [])
  })

  test('an attachment cannot be claimed by another account', () => {
    const registry = new Registry()
    const daemon = fakeDaemon('acct-1', 'd-a')
    registry.addDaemon(daemon)
    const attachment = registry.openAttachment('acct-1', 's1', daemon)

    // This is the hijack the account check exists to stop.
    assert.equal(registry.claimAttachment(attachment.id, 'acct-2'), null)
    // And the rightful owner can still claim it afterwards.
    assert.equal(registry.claimAttachment(attachment.id, 'acct-1')?.id, attachment.id)
  })

  test('an attachment is single use', () => {
    const registry = new Registry()
    const daemon = fakeDaemon('acct-1', 'd-a')
    registry.addDaemon(daemon)
    const attachment = registry.openAttachment('acct-1', 's1', daemon)

    assert.ok(registry.claimAttachment(attachment.id, 'acct-1'))
    assert.equal(registry.claimAttachment(attachment.id, 'acct-1'), null)
  })

  test('dropping a daemon clears its catalog and its pending attachments', () => {
    const registry = new Registry()
    const daemon = fakeDaemon('acct-1', 'd-a')
    registry.addDaemon(daemon)
    registry.announceSessions(daemon, [{ id: 's1' }])
    registry.openAttachment('acct-1', 's1', daemon)
    assert.equal(registry.pendingAttachmentCount, 1)

    registry.removeDaemon(daemon)

    assert.equal(registry.hasDaemon('acct-1'), false)
    assert.deepEqual(registry.listSessions('acct-1'), [])
    // Otherwise the device would wait out its timeout for a daemon that is gone.
    assert.equal(registry.pendingAttachmentCount, 0)
  })

  test('merges catalogs without duplicating ids', () => {
    const registry = new Registry()
    const a = fakeDaemon('acct-1', 'd-a')
    const b = fakeDaemon('acct-1', 'd-b')
    registry.addDaemon(a)
    registry.addDaemon(b)
    registry.announceSessions(a, [{ id: 's1', title: 'from-a' }])
    registry.announceSessions(b, [{ id: 's1', title: 'from-b' }, { id: 's2' }])

    const merged = registry.listSessions('acct-1')
    assert.equal(merged.length, 2)
    assert.equal(merged.filter((s) => s.id === 's1').length, 1)
  })

  test('caps devices per account', () => {
    const registry = new Registry()
    assert.equal(registry.addDevice('acct-1', 2), true)
    assert.equal(registry.addDevice('acct-1', 2), true)
    assert.equal(registry.addDevice('acct-1', 2), false)

    registry.removeDevice('acct-1')
    assert.equal(registry.addDevice('acct-1', 2), true)
  })
})
