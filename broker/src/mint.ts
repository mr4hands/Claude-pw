import { ConfigError, generateSecretHex, loadConfig } from './config.js'
import { mintToken } from './tokens.js'

/**
 * Mints a daemon token, or generates a fresh secret.
 *
 * Device tokens are deliberately NOT mintable here: they come from the pairing
 * flow, so the only way to add a device is to be at the daemon and scan its
 * code. That keeps a copy of the broker secret from being enough to enrol a
 * watch.
 *
 *   npm run mint -- --secret
 *   npm run mint -- --daemon [--account default] [--name "laptop"]
 */
function main(): void {
  const args = process.argv.slice(2)

  if (args.includes('--secret')) {
    console.log(generateSecretHex())
    return
  }

  if (!args.includes('--daemon')) {
    console.error('usage: mint -- --secret | --daemon [--account <id>] [--name <label>]')
    process.exit(2)
  }

  let config
  try {
    config = loadConfig()
  } catch (error) {
    if (error instanceof ConfigError) {
      console.error(`[mint] ${error.message}`)
      process.exit(78)
    }
    throw error
  }

  const account = valueOf(args, '--account') ?? 'default'
  const name = valueOf(args, '--name')
  const minted = mintToken(config.secret, {
    role: 'daemon',
    account,
    ...(name === undefined ? {} : { name }),
  })

  console.log(minted.token)
  console.error(`[mint] role=daemon account=${account} tokenId=${minted.payload.i}`)
}

function valueOf(args: string[], flag: string): string | undefined {
  const index = args.indexOf(flag)
  if (index === -1) return undefined
  return args[index + 1]
}

main()
