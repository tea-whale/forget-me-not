import { spawnSync } from 'node:child_process'
import { readFile, writeFile } from 'node:fs/promises'

// Rspack's output.clean removes stale artifacts. Avoid platform-specific shell syntax.
const result = spawnSync(process.execPath, ['node_modules/@rspack/cli/bin/rspack.js', 'build', '-c', 'rspack.config.ts'], {
  stdio: 'inherit',
  env: { ...process.env, NODE_ENV: 'production' },
})
if (result.error) throw result.error

// 把版本号写进页面，设置里能直接看到跑的是哪个包（手机上没有开发者工具）
const { version } = JSON.parse(await readFile(new URL('../package.json', import.meta.url), 'utf8'))
const htmlPath = new URL('../dist/index.html', import.meta.url)
const html = await readFile(htmlPath, 'utf8')
if (!html.includes('application-version')) {
  await writeFile(htmlPath, html.replace('</head>', `    <meta name="application-version" content="${version}" />\n  </head>`))
  console.log(`==> Stamped version ${version} into dist/index.html`)
}

process.exitCode = result.status ?? 1
