import { readFile, stat } from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
const root = fileURLToPath(new URL('../../public/python/', import.meta.url))
try {
  const metadata = JSON.parse(await readFile(path.join(root, 'runtime.json'), 'utf8'))
  if (!metadata.python.startsWith('3.11.')) throw new Error('Python version must be 3.11')
  const lock = JSON.parse(await readFile(path.join(root, 'pyodide-lock.json'), 'utf8'))
  const names = ['pyodide.js', 'pyodide.asm.js', 'pyodide.asm.wasm', 'python_stdlib.zip', ...metadata.packages.map(name => lock.packages[name].file_name)]
  for (const name of [...names, 'pyodide-lock.json']) {
    if (!(await stat(path.join(root, name))).size) throw new Error(`Empty runtime file ${name}`)
    const hash = createHash('sha256').update(await readFile(path.join(root, name))).digest('hex')
    if (hash !== metadata.hashes[name]) throw new Error(`Runtime integrity mismatch: ${name}`)
  }
  for (const name of ['numpy', 'scipy', 'pandas', 'matplotlib', 'sympy']) if (!metadata.packages.includes(name)) throw new Error(`Missing required package ${name}`)
  await stat(path.join(root, 'LICENSE'))
  console.log(`Bundled Python ${metadata.python} verified (${metadata.packages.length} packages)`)
} catch (error) {
  console.error('Python runtime is missing or incomplete. Run pnpm python:prepare before building.', error.message)
  process.exitCode = 1
}
