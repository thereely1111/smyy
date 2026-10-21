import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const bundle = await build({ entryPoints: ['src/engine/pythonWorkspace.ts'], bundle: true, write: false, format: 'esm', platform: 'node' })
const { flattenArtifacts, workspacePath, pythonSnapshot } = await import(`data:text/javascript;base64,${Buffer.from(bundle.outputFiles[0].text).toString('base64')}`)
test('Python workspace preserves backend paths and rejects traversal', () => {
  assert.equal(workspacePath('generated/q01/solve.py'), 'generated/q01/solve.py')
  for (const path of ['../x.py', '/x.py', 'a/../x.py', 'C:\\x.py', 'a//x.py', 'a/./x.py']) assert.throws(() => workspacePath(path))
})
test('Nested artifacts are included with editor contents replacing the entry', async () => {
  const tree = [{ name: 'src', path: 'custom/src', type: 'dir', children: [
    { name: 'main.py', path: 'custom/src/main.py', type: 'file' },
    { name: 'helper.py', path: 'custom/src/helper.py', type: 'file' },
  ] }, { name: 'data.csv', path: 'custom/data.csv', type: 'file' }]
  assert.equal(flattenArtifacts(tree).length, 3)
  const reads = []
  const result = await pythonSnapshot(tree, 'custom/src/main.py', 'print("edited")', async node => { reads.push(node.path); return node.path.endsWith('.py') ? 'import numpy' : 'a,b\n1,2' })
  assert.equal(result[0].content, 'print("edited")')
  assert.deepEqual(reads, ['custom/src/helper.py', 'custom/data.csv'])
})
test('Data read failures and size limits stop execution rather than silently dropping data', async () => {
  const node = { name: 'data.csv', path: 'data.csv', type: 'file' }
  await assert.rejects(pythonSnapshot([node], 'main.py', 'print(1)', async () => { throw new Error('HTTP 401') }), /401/)
  await assert.rejects(pythonSnapshot([{ ...node, size: 21 * 1024 * 1024 }], 'main.py', 'print(1)', async () => ''), /20 MB/)
})
