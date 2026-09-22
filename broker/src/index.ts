import { createBroker } from './server.js'
import { ConfigError, loadConfig } from './config.js'

function main(): void {
  let config
  try {
    config = loadConfig()
  } catch (error) {
    if (error instanceof ConfigError) {
      console.error(`[broker] ${error.message}`)
      process.exit(78) // EX_CONFIG
    }
    throw error
  }

  const broker = createBroker(config)

  broker
    .listen(config.port)
    .then((port) => {
      console.log(`[broker] listening on :${port}`)
      if (config.stateFile === null) {
        console.warn('[broker] BROKER_STATE_FILE is unset; revocations will not survive a restart')
      }
    })
    .catch((error: unknown) => {
      console.error('[broker] failed to start', error)
      process.exit(1)
    })

  const shutdown = (signal: string) => {
    console.log(`[broker] ${signal}, shutting down`)
    broker.close().then(
      () => process.exit(0),
      () => process.exit(1),
    )
  }
  process.on('SIGTERM', () => shutdown('SIGTERM'))
  process.on('SIGINT', () => shutdown('SIGINT'))
}

main()
