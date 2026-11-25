import { spawnSync } from 'node:child_process'

// Rspack's output.clean removes stale artifacts. Avoid platform-specific shell syntax.
const result = spawnSync(process.execPath, ['node_modules/@rspack/cli/bin/rspack.js', 'build', '-c', 'rspack.config.ts'], {
  stdio: 'inherit',
  env: { ...process.env, NODE_ENV: 'production' },
})
if (result.error) throw result.error
process.exitCode = result.status ?? 1
