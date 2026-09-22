import { randomInt, timingSafeEqual } from 'node:crypto'

/**
 * Crockford-style alphabet with the characters people misread removed (I, L,
 * O, U). The code gets read off a watch screen or scanned from a QR, so
 * ambiguity costs more than the two bits.
 */
const ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'
const CODE_LENGTH = 10

export interface PairingCode {
  readonly code: string
  readonly account: string
  readonly expiresAt: number
}

/**
 * Short-lived, single-use codes that let a device trade a QR scan for a token.
 *
 * ~50 bits of entropy over a 120-second window, single use, plus the rate limit
 * the HTTP layer applies. Brute force is not a realistic path.
 */
export class PairingCodes {
  private readonly codes = new Map<string, PairingCode>()

  constructor(
    private readonly ttlMs: number,
    private readonly now: () => number = Date.now,
  ) {}

  issue(account: string): PairingCode {
    this.sweep()
    let code = generateCode()
    // Collisions are vanishingly unlikely, but a silent overwrite would hand
    // two daemons the same code.
    while (this.codes.has(code)) code = generateCode()

    const entry: PairingCode = { code, account, expiresAt: this.now() + this.ttlMs }
    this.codes.set(code, entry)
    return entry
  }

  /** Returns the account the code belongs to, consuming it. Null if unusable. */
  redeem(candidate: string): string | null {
    this.sweep()
    const normalised = normalise(candidate)
    if (normalised === null) return null

    // Look the code up in constant time with respect to the stored set, so a
    // caller cannot learn which prefixes exist from response timing.
    let found: PairingCode | null = null
    for (const entry of this.codes.values()) {
      if (constantTimeEquals(entry.code, normalised)) found = entry
    }
    if (found === null) return null

    this.codes.delete(found.code)
    if (found.expiresAt <= this.now()) return null
    return found.account
  }

  get size(): number {
    this.sweep()
    return this.codes.size
  }

  private sweep(): void {
    const cutoff = this.now()
    for (const [code, entry] of this.codes) {
      if (entry.expiresAt <= cutoff) this.codes.delete(code)
    }
  }
}

function generateCode(): string {
  let out = ''
  for (let i = 0; i < CODE_LENGTH; i++) {
    out += ALPHABET[randomInt(ALPHABET.length)]
  }
  return out
}

/** Accepts the code with or without the dashes a UI might add. */
export function normalise(candidate: unknown): string | null {
  if (typeof candidate !== 'string') return null
  const cleaned = candidate.replace(/[\s-]/g, '').toUpperCase()
  if (cleaned.length !== CODE_LENGTH) return null
  for (const ch of cleaned) {
    if (!ALPHABET.includes(ch)) return null
  }
  return cleaned
}

/** Formats as XXXXX-XXXXX for display. */
export function formatForDisplay(code: string): string {
  return `${code.slice(0, 5)}-${code.slice(5)}`
}

function constantTimeEquals(a: string, b: string): boolean {
  const left = Buffer.from(a)
  const right = Buffer.from(b)
  if (left.length !== right.length) return false
  return timingSafeEqual(left, right)
}
