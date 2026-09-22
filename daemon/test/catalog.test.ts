import { strict as assert } from 'node:assert'
import test, { describe } from 'node:test'

import { announce, titleFor } from '../src/catalog.js'

const base = { sessionId: 'abcdef12-3456-7890-abcd-ef1234567890', lastModified: 1_700_000_000_000 }

describe('session catalog', () => {
  test('prefers a title the user set', () => {
    assert.equal(
      titleFor({ ...base, customTitle: 'Billing fix', summary: 'Generated summary', firstPrompt: 'hello' }),
      'Billing fix',
    )
  })

  test('falls back through summary, then first prompt', () => {
    assert.equal(titleFor({ ...base, summary: 'Refactor the API client' }), 'Refactor the API client')
    assert.equal(titleFor({ ...base, firstPrompt: 'help me debug this' }), 'help me debug this')
  })

  test('falls back to the directory, then the id', () => {
    assert.equal(titleFor({ ...base, cwd: '/home/me/src/frontend-app' }), 'frontend-app')
    assert.equal(titleFor(base), 'abcdef12')
  })

  test('truncates on a word boundary for a narrow screen', () => {
    const summary = 'Investigate the flaky integration test in the billing service pipeline'
    const title = titleFor({ ...base, summary })

    assert.ok(title.length <= 51, `too long for a watch row: ${title}`)
    assert.ok(title.endsWith('...'))

    // "Cut between words" means the kept text is a prefix of the original and
    // the original continues with whitespace — not that the last character
    // happens to be punctuation.
    const stem = title.slice(0, -3).trimEnd()
    assert.ok(summary.startsWith(stem), `not a prefix of the original: ${stem}`)
    assert.match(summary.charAt(stem.length), /\s/, `cut mid-word at "${stem}"`)
  })

  test('collapses newlines out of a multi-line first prompt', () => {
    const title = titleFor({ ...base, firstPrompt: 'fix\n\n  the   thing\nplease' })
    assert.equal(title, 'fix the thing please')
  })

  test('announces the shape the watch decodes', () => {
    const row = announce({ ...base, summary: 'frontend-app', cwd: '/src/fe' }, 'executing', 'laptop')
    assert.deepEqual(row, {
      id: base.sessionId,
      title: 'frontend-app',
      host: 'laptop',
      cwd: '/src/fe',
      status: 'executing',
      last_activity_ms: base.lastModified,
    })
  })
})
