import { resolve } from 'node:path'

export interface Config {
  readonly brokerUrl: string
  readonly token: string
  readonly workdir: string
  readonly permissionMode: 'default' | 'acceptEdits' | 'plan' | 'bypassPermissions'
  readonly reconnectBaseMs: number
  readonly reconnectMaxMs: number
  readonly catalogRefreshMs: number
}

export class ConfigError extends Error {}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const brokerUrl = (env['BROKER_URL'] ?? '').trim()
  if (brokerUrl === '') throw new ConfigError('BROKER_URL is not set.')
  if (!/^wss?:\/\//.test(brokerUrl)) {
    throw new ConfigError(`BROKER_URL must start with ws:// or wss://, got "${brokerUrl}".`)
  }

  const token = (env['DAEMON_TOKEN'] ?? '').trim()
  if (token === '') {
    throw new ConfigError(
      'DAEMON_TOKEN is not set. Mint one on the broker:\n' +
        '  npm run mint -- --daemon --account default',
    )
  }

  const mode = (env['PERMISSION_MODE'] ?? 'default').trim()
  if (mode !== 'default' && mode !== 'acceptEdits' && mode !== 'plan' && mode !== 'bypassPermissions') {
    throw new ConfigError(`PERMISSION_MODE must be default, acceptEdits, plan or bypassPermissions.`)
  }
  if (mode === 'bypassPermissions') {
    // Not refused — it is a legitimate choice — but it silently makes the
    // watch decorative, so it should never be a surprise.
    console.warn(
      '[daemon] PERMISSION_MODE=bypassPermissions: tool calls are auto-approved and will NEVER reach your watch.',
    )
  }

  return {
    brokerUrl: brokerUrl.replace(/\/+$/, ''),
    token,
    workdir: resolve((env['WORKDIR'] ?? '.').trim() || '.'),
    permissionMode: mode,
    reconnectBaseMs: 1_000,
    reconnectMaxMs: 30_000,
    catalogRefreshMs: 30_000,
  }
}
