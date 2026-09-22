import { readFileSync, writeFileSync, renameSync } from 'node:fs'
import { dirname, join } from 'node:path'

/**
 * Revoked token ids.
 *
 * Tokens are stateless and do not expire, which is the right trade for a
 * device that may be offline for days — but it means losing a watch has to be
 * recoverable without rotating the secret and re-pairing everything.
 */
export class RevocationStore {
  private readonly revoked = new Set<string>()

  constructor(private readonly file: string | null) {
    this.load()
  }

  isRevoked(tokenId: string): boolean {
    return this.revoked.has(tokenId)
  }

  revoke(tokenId: string): void {
    if (this.revoked.has(tokenId)) return
    this.revoked.add(tokenId)
    this.persist()
  }

  list(): string[] {
    return [...this.revoked]
  }

  private load(): void {
    if (this.file === null) return
    try {
      const parsed: unknown = JSON.parse(readFileSync(this.file, 'utf8'))
      if (Array.isArray(parsed)) {
        for (const id of parsed) {
          if (typeof id === 'string') this.revoked.add(id)
        }
      }
    } catch {
      // A missing or unreadable file means nothing has been revoked yet. This
      // fails open by design: refusing to start because a state file is absent
      // would make first boot needlessly fragile.
    }
  }

  private persist(): void {
    if (this.file === null) return
    // Write-then-rename so a crash mid-write cannot leave a truncated file
    // that silently un-revokes a stolen token on next boot.
    const temporary = join(dirname(this.file), `.${Date.now()}.broker-state.tmp`)
    try {
      writeFileSync(temporary, JSON.stringify([...this.revoked]), 'utf8')
      renameSync(temporary, this.file)
    } catch (error) {
      // Surfaced rather than swallowed: a revocation that did not persist is a
      // security-relevant failure the operator needs to know about.
      console.error('[broker] failed to persist revocations', error)
    }
  }
}
