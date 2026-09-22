import { createHmac, randomUUID, timingSafeEqual } from 'node:crypto'

export type Role = 'daemon' | 'device'

export interface TokenPayload {
  /** Role. */
  readonly r: Role
  /** Account id — the unit that daemons and devices are paired within. */
  readonly a: string
  /** Token id, so a single token can be revoked without rotating the secret. */
  readonly i: string
  /** Issued at, epoch millis. */
  readonly t: number
  /** Human label, for listing devices. Never trusted for anything. */
  readonly n?: string
}

const PREFIX = 'wcb1'

function b64url(input: Buffer | string): string {
  return Buffer.from(input).toString('base64url')
}

function sign(secret: Buffer, body: string): Buffer {
  return createHmac('sha256', secret).update(body).digest()
}

export function mintToken(
  secret: Buffer,
  input: { role: Role; account: string; name?: string },
): { token: string; payload: TokenPayload } {
  const payload: TokenPayload = {
    r: input.role,
    a: input.account,
    i: randomUUID(),
    t: Date.now(),
    ...(input.name === undefined ? {} : { n: input.name }),
  }
  const body = `${PREFIX}.${b64url(JSON.stringify(payload))}`
  return { token: `${body}.${b64url(sign(secret, body))}`, payload }
}

/**
 * Verifies a token's signature and shape.
 *
 * Returns null for anything that does not verify — callers must not be able to
 * tell a malformed token from a well-formed one with a bad signature, so there
 * is deliberately no error detail here.
 */
export function verifyToken(secret: Buffer, token: string | undefined | null): TokenPayload | null {
  if (typeof token !== 'string' || token.length === 0 || token.length > 4096) return null

  const parts = token.split('.')
  if (parts.length !== 3) return null
  const [prefix, payloadPart, signaturePart] = parts as [string, string, string]
  if (prefix !== PREFIX) return null

  const expected = sign(secret, `${prefix}.${payloadPart}`)
  let provided: Buffer
  try {
    provided = Buffer.from(signaturePart, 'base64url')
  } catch {
    return null
  }
  // Compare lengths first: timingSafeEqual throws on a mismatch.
  if (provided.length !== expected.length) return null
  if (!timingSafeEqual(provided, expected)) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(Buffer.from(payloadPart, 'base64url').toString('utf8'))
  } catch {
    return null
  }
  if (!isTokenPayload(parsed)) return null
  return parsed
}

function isTokenPayload(value: unknown): value is TokenPayload {
  if (typeof value !== 'object' || value === null) return false
  const v = value as Record<string, unknown>
  if (v['r'] !== 'daemon' && v['r'] !== 'device') return false
  if (typeof v['a'] !== 'string' || v['a'].length === 0 || v['a'].length > 128) return false
  if (typeof v['i'] !== 'string' || v['i'].length === 0 || v['i'].length > 128) return false
  if (typeof v['t'] !== 'number' || !Number.isFinite(v['t'])) return false
  if (v['n'] !== undefined && typeof v['n'] !== 'string') return false
  return true
}

/** Extracts a bearer token from an Authorization header. */
export function bearerFrom(header: string | undefined): string | null {
  if (typeof header !== 'string') return null
  const match = /^Bearer (.+)$/.exec(header.trim())
  return match?.[1]?.trim() ?? null
}
