import { randomBytes } from 'node:crypto'

export interface Config {
  readonly port: number
  readonly secret: Buffer
  readonly stateFile: string | null
  /** How long a pairing code stays redeemable. Short on purpose. */
  readonly pairingTtlMs: number
  /** How long a device waits for its daemon to dial back in. */
  readonly attachTimeoutMs: number
  readonly maxPayloadBytes: number
  readonly heartbeatMs: number
  readonly maxDevicesPerAccount: number
}

export class ConfigError extends Error {}

const MIN_SECRET_BYTES = 32

/**
 * Reads configuration from the environment.
 *
 * The secret is the root of every trust decision the broker makes, so a weak
 * or missing one is a hard startup failure rather than a warning — this
 * process is reachable from the internet and sits in front of something that
 * runs shell commands.
 */
export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const rawSecret = (env['BROKER_SECRET'] ?? '').trim()
  if (rawSecret === '') {
    throw new ConfigError(
      'BROKER_SECRET is not set. Generate one with:\n' +
        "  node -e \"console.log(require('crypto').randomBytes(32).toString('hex'))\"",
    )
  }
  if (!/^[0-9a-fA-F]+$/.test(rawSecret) || rawSecret.length % 2 !== 0) {
    throw new ConfigError('BROKER_SECRET must be hex.')
  }
  const secret = Buffer.from(rawSecret, 'hex')
  if (secret.length < MIN_SECRET_BYTES) {
    throw new ConfigError(
      `BROKER_SECRET must be at least ${MIN_SECRET_BYTES} bytes (${MIN_SECRET_BYTES * 2} hex chars); got ${secret.length}.`,
    )
  }

  // 0 is allowed and means "bind any free port" — useful under a supervisor
  // that passes one in, and what the tests use.
  const port = Number.parseInt(env['PORT'] ?? '8787', 10)
  if (!Number.isInteger(port) || port < 0 || port > 65535) {
    throw new ConfigError(`PORT must be between 0 and 65535, got "${env['PORT']}".`)
  }

  const stateFile = (env['BROKER_STATE_FILE'] ?? '').trim()

  return {
    port,
    secret,
    stateFile: stateFile === '' ? null : stateFile,
    pairingTtlMs: 120_000,
    attachTimeoutMs: 15_000,
    // Frames are agent chatter, not file transfers. A cap keeps one misbehaving
    // client from ballooning broker memory.
    maxPayloadBytes: 256 * 1024,
    heartbeatMs: 30_000,
    maxDevicesPerAccount: 16,
  }
}

/** Used by the mint CLI and by tests. */
export function generateSecretHex(): string {
  return randomBytes(MIN_SECRET_BYTES).toString('hex')
}
