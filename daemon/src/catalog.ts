import { hostname } from 'node:os'
import { basename } from 'node:path'

/** Shape the SDK's listSessions() returns, narrowed to what we use. */
export interface SessionInfoLike {
  readonly sessionId: string
  readonly summary?: string
  readonly customTitle?: string
  readonly firstPrompt?: string
  readonly cwd?: string
  readonly gitBranch?: string
  readonly lastModified: number
}

/** What the daemon announces to the broker, and the watch renders. */
export interface AnnouncedSession {
  readonly id: string
  readonly title: string
  readonly host: string
  readonly cwd: string | null
  readonly status: string
  readonly last_activity_ms: number
}

const MAX_TITLE = 48

/**
 * Turns a resumable local session into a row on the watch's session list.
 *
 * Titles come from whatever is most deliberate: a title the user set, then the
 * generated summary, then their first prompt. A watch row is about four words
 * wide, so anything long is cut on a word boundary rather than mid-token.
 */
export function announce(
  info: SessionInfoLike,
  status: string,
  host: string = hostname(),
): AnnouncedSession {
  return {
    id: info.sessionId,
    title: titleFor(info),
    host,
    cwd: info.cwd ?? null,
    status,
    last_activity_ms: info.lastModified,
  }
}

export function titleFor(info: SessionInfoLike): string {
  const candidate =
    firstNonEmpty(info.customTitle, info.summary, info.firstPrompt) ??
    (info.cwd !== undefined ? basename(info.cwd) : null) ??
    info.sessionId.slice(0, 8)

  return truncateOnWord(collapseWhitespace(candidate), MAX_TITLE)
}

function firstNonEmpty(...values: Array<string | undefined>): string | null {
  for (const value of values) {
    if (typeof value === 'string' && value.trim() !== '') return value.trim()
  }
  return null
}

function collapseWhitespace(value: string): string {
  return value.replace(/\s+/g, ' ').trim()
}

function truncateOnWord(value: string, limit: number): string {
  if (value.length <= limit) return value
  const cut = value.slice(0, limit)
  const lastSpace = cut.lastIndexOf(' ')
  // Only break on a word if that leaves something worth reading.
  const trimmed = lastSpace > limit * 0.6 ? cut.slice(0, lastSpace) : cut
  return `${trimmed.trimEnd()}...`
}
