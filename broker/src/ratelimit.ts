/**
 * Fixed-window limiter, keyed by client address.
 *
 * Exists for one reason: /v1/pair is the only unauthenticated endpoint, so it
 * is the only place an attacker can guess at anything.
 */
export class RateLimiter {
  private readonly hits = new Map<string, { count: number; resetAt: number }>()

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  /** Returns true when the caller is within budget. */
  tryConsume(key: string): boolean {
    const current = this.now()
    const entry = this.hits.get(key)

    if (entry === undefined || entry.resetAt <= current) {
      this.hits.set(key, { count: 1, resetAt: current + this.windowMs })
      this.sweep(current)
      return true
    }
    if (entry.count >= this.limit) return false
    entry.count += 1
    return true
  }

  private sweep(current: number): void {
    if (this.hits.size < 1024) return
    for (const [key, entry] of this.hits) {
      if (entry.resetAt <= current) this.hits.delete(key)
    }
  }
}
