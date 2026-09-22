import { randomUUID } from 'node:crypto'

/** The broker's view of a daemon. Kept narrow so tests need no real socket. */
export interface DaemonConnection {
  readonly tokenId: string
  readonly account: string
  send(message: Record<string, unknown>): void
  close(code: number, reason: string): void
}

/**
 * A session as announced by a daemon.
 *
 * The broker reads `id` and nothing else — the remaining fields are forwarded
 * to the device verbatim. That keeps the session schema a matter between the
 * daemon and the watch, and keeps the broker out of protocol changes.
 */
export interface AnnouncedSession {
  readonly id: string
  readonly [key: string]: unknown
}

export interface PendingAttachment {
  readonly id: string
  readonly account: string
  readonly sessionId: string
  readonly daemon: DaemonConnection
  readonly createdAt: number
}

/**
 * Who is connected, what sessions exist, and which device sockets are waiting
 * for a daemon to dial back in.
 */
export class Registry {
  private readonly daemons = new Map<string, Set<DaemonConnection>>()
  private readonly catalogs = new Map<string, Map<string, AnnouncedSession[]>>()
  private readonly deviceCounts = new Map<string, number>()
  private readonly attachments = new Map<string, PendingAttachment>()

  constructor(private readonly now: () => number = Date.now) {}

  addDaemon(daemon: DaemonConnection): void {
    const set = this.daemons.get(daemon.account) ?? new Set()
    set.add(daemon)
    this.daemons.set(daemon.account, set)
  }

  removeDaemon(daemon: DaemonConnection): void {
    this.daemons.get(daemon.account)?.delete(daemon)
    this.catalogs.get(daemon.account)?.delete(daemon.tokenId)

    // Any device still waiting on this daemon will never be answered.
    for (const [id, pending] of this.attachments) {
      if (pending.daemon === daemon) this.attachments.delete(id)
    }
  }

  hasDaemon(account: string): boolean {
    return (this.daemons.get(account)?.size ?? 0) > 0
  }

  announceSessions(daemon: DaemonConnection, sessions: AnnouncedSession[]): void {
    const perAccount = this.catalogs.get(daemon.account) ?? new Map()
    perAccount.set(daemon.tokenId, sessions)
    this.catalogs.set(daemon.account, perAccount)
  }

  /** Merged catalog for an account. Later daemons do not clobber earlier ids. */
  listSessions(account: string): AnnouncedSession[] {
    const perAccount = this.catalogs.get(account)
    if (perAccount === undefined) return []

    const seen = new Set<string>()
    const merged: AnnouncedSession[] = []
    for (const sessions of perAccount.values()) {
      for (const session of sessions) {
        if (seen.has(session.id)) continue
        seen.add(session.id)
        merged.push(session)
      }
    }
    return merged
  }

  findDaemonForSession(account: string, sessionId: string): DaemonConnection | null {
    const perAccount = this.catalogs.get(account)
    if (perAccount === undefined) return null

    for (const daemon of this.daemons.get(account) ?? []) {
      const sessions = perAccount.get(daemon.tokenId)
      if (sessions?.some((session) => session.id === sessionId) === true) return daemon
    }
    return null
  }

  openAttachment(account: string, sessionId: string, daemon: DaemonConnection): PendingAttachment {
    const attachment: PendingAttachment = {
      id: randomUUID(),
      account,
      sessionId,
      daemon,
      createdAt: this.now(),
    }
    this.attachments.set(attachment.id, attachment)
    return attachment
  }

  /**
   * Consumes an attachment. The account check matters: without it a daemon
   * token for one account could claim another account's waiting device socket.
   */
  claimAttachment(id: string, account: string): PendingAttachment | null {
    const pending = this.attachments.get(id)
    if (pending === undefined) return null
    if (pending.account !== account) return null
    this.attachments.delete(id)
    return pending
  }

  dropAttachment(id: string): void {
    this.attachments.delete(id)
  }

  get pendingAttachmentCount(): number {
    return this.attachments.size
  }

  /** Returns false when the account is already at its device limit. */
  addDevice(account: string, limit: number): boolean {
    const current = this.deviceCounts.get(account) ?? 0
    if (current >= limit) return false
    this.deviceCounts.set(account, current + 1)
    return true
  }

  removeDevice(account: string): void {
    const current = this.deviceCounts.get(account) ?? 0
    if (current <= 1) this.deviceCounts.delete(account)
    else this.deviceCounts.set(account, current - 1)
  }

  deviceCount(account: string): number {
    return this.deviceCounts.get(account) ?? 0
  }
}
