import { mkdir, copyFile, readFile, writeFile, stat, rename } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
// Pyodide 0.25.1 embeds CPython 3.11.3. Upgrade deliberately when changing Python versions.
const root = fileURLToPath(new URL('../../', import.meta.url))
const destination = path.join(root, 'public/python')
const version = '0.25.1'
const base = `https://fastly.jsdelivr.net/pyodide/v${version}/full/`
const requested = ['numpy', 'scipy', 'pandas', 'matplotlib', 'sympy']
await mkdir(destination, { recursive: true })
async function validFile(target, expected) {
  try {
    if (!(await stat(target)).size) return false
    return !expected || createHash('sha256').update(await readFile(target)).digest('hex') === expected
  } catch { return false }
}
async function download(name, expected, url = base + name) {
  const target = path.join(destination, name)
  if (await validFile(target, expected)) return
  let lastError
  for (let attempt = 0; attempt < 3; attempt++) {
    try {
      const response = await fetch(url, { signal: AbortSignal.timeout(600_000) })
      if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`)
      const bytes = new Uint8Array(await response.arrayBuffer())
      if (expected && createHash('sha256').update(bytes).digest('hex') !== expected) throw new Error(`${name}: SHA-256 mismatch`)
      await writeFile(target + '.part', bytes)
      await rename(target + '.part', target)
      console.log(`Python runtime: ${name}`)
      return
    } catch (error) { lastError = error; console.log(`Retry ${attempt + 1}: ${name} (${error.message})`) }
  }
  throw lastError
}
// The pinned npm package already carries the interpreter; avoid downloading it twice.
for (const name of ['pyodide.js', 'pyodide.asm.js', 'pyodide.asm.wasm', 'python_stdlib.zip', 'pyodide-lock.json']) {
  await copyFile(path.join(root, 'node_modules/pyodide', name), path.join(destination, name))
}
const lock = JSON.parse(await readFile(path.join(destination, 'pyodide-lock.json'), 'utf8'))
if (!lock.info.python.startsWith('3.11.')) throw new Error('Expected Python 3.11')
const packages = new Set()
function include(name) {
  if (packages.has(name)) return
  const entry = lock.packages[name]
  if (!entry) throw new Error(`Unknown Pyodide package: ${name}`)
  packages.add(name)
  for (const dependency of entry.depends ?? []) include(dependency)
}
requested.forEach(include)
// Bound concurrency to avoid overloading the mirror while keeping the initial setup short.
const queue = [...packages]
await Promise.all(Array.from({ length: 4 }, async () => {
  while (queue.length) {
    const name = queue.shift()
    const entry = lock.packages[name]
    await download(entry.file_name, entry.sha256)
  }
}))
await download('LICENSE', undefined, `https://fastly.jsdelivr.net/gh/pyodide/pyodide@${version}/LICENSE`)
const hashes = {}
for (const name of ['pyodide.js', 'pyodide.asm.js', 'pyodide.asm.wasm', 'python_stdlib.zip', 'pyodide-lock.json', ...[...packages].map(name => lock.packages[name].file_name)]) hashes[name] = createHash('sha256').update(await readFile(path.join(destination, name))).digest('hex')
await writeFile(path.join(destination, 'runtime.json'), JSON.stringify({ pyodide: version, python: lock.info.python, packages: [...packages], hashes }, null, 2))
console.log(`Python ${lock.info.python} ready (${packages.size} bundled packages)`)
