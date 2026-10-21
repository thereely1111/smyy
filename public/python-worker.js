/* Pyodide runs outside the UI thread. Only trusted user/agent code should be run:
   a worker prevents UI blocking but is not an origin or network security boundary. */
let runtime
let globals
let outputBytes = 0
const MAX_OUTPUT = 1024 * 1024
function output(stream, text) {
  if (outputBytes >= MAX_OUTPUT) return
  const chunk = (text + '\n').slice(0, MAX_OUTPUT - outputBytes)
  outputBytes += chunk.length
  postMessage({ type: 'output', stream, text: chunk })
  if (outputBytes >= MAX_OUTPUT) postMessage({ type: 'output', stream: 'stderr', text: '\n输出已达到 1 MB 上限\n' })
}
async function boot(indexURL) {
  if (!runtime) {
    postMessage({ type: 'status', text: '正在初始化 Python 3.11' })
    importScripts(indexURL + 'pyodide.js')
    runtime = await loadPyodide({ indexURL, stdout: () => {}, stderr: () => {}, stdin: () => { throw new Error('交互式 input() 暂不支持，请在代码中设置输入参数') } })
    runtime.setStdout({ batched: text => output('stdout', text) })
    runtime.setStderr({ batched: text => output('stderr', text) })
    const version = runtime.runPython('import sys; sys.version.split()[0]')
    if (!version.startsWith('3.11.')) throw new Error(`需要 Python 3.11，实际为 ${version}`)
    runtime.FS.mkdirTree('/workspace')
    runtime.FS.chdir('/workspace')
    runtime.runPython("import os; os.environ['MPLBACKEND'] = 'Agg'")
    globals = runtime.toPy({ '__name__': '__main__' })
    postMessage({ type: 'ready', version })
  }
}
function safePath(path) {
  if (!path || path.startsWith('/') || path.includes('\\') || path.split('/').some(part => part === '..' || part === '.') || path.includes('\0')) throw new Error(`不合法的工作区路径: ${path}`)
  return '/workspace/' + path
}
function listFiles(directory = '/workspace', files = []) {
  for (const name of runtime.FS.readdir(directory)) {
    if (name === '.' || name === '..' || name === '__pycache__') continue
    const full = directory + '/' + name
    const info = runtime.FS.stat(full)
    if (runtime.FS.isDir(info.mode)) listFiles(full, files)
    else files.push(full)
  }
  return files
}
function clearWorkspace(directory = '/workspace') {
  for (const name of runtime.FS.readdir(directory)) {
    if (name === '.' || name === '..') continue
    const full = directory + '/' + name
    if (runtime.FS.isDir(runtime.FS.stat(full).mode)) { clearWorkspace(full); runtime.FS.rmdir(full) }
    else runtime.FS.unlink(full)
  }
}
self.onmessage = async ({ data }) => {
  outputBytes = 0
  const started = performance.now()
  try {
    await boot(data.indexURL)
    if (data.type === 'init') { postMessage({ type: 'done', exitCode: 0, elapsedMs: 0, files: [] }); return }
    const before = new Map()
    if (data.type === 'run') {
      runtime.FS.chdir('/')
      clearWorkspace()
      for (const file of data.files) {
        const path = safePath(file.path)
        runtime.FS.mkdirTree(path.slice(0, path.lastIndexOf('/')))
        const bytes = typeof file.content === 'string' ? new TextEncoder().encode(file.content) : file.content
        runtime.FS.writeFile(path, bytes)
      }
      globals.destroy()
      globals = runtime.toPy({ '__name__': '__main__', '__file__': safePath(data.path) })
      const entry = safePath(data.path)
      const parent = entry.slice(0, entry.lastIndexOf('/'))
      runtime.FS.chdir(parent)
      runtime.runPython(`import sys, os\nsys.path[:] = [p for p in sys.path if not p.startswith('/workspace')]\nsys.path[:0] = [${JSON.stringify(parent)}, '/workspace']\nos.environ['MPLBACKEND'] = 'Agg'\nfor name, module in list(sys.modules.items()):\n    if str(getattr(module, '__file__', '')).startswith('/workspace/'):\n        del sys.modules[name]`)
    }
    for (const path of listFiles()) before.set(path, runtime.FS.readFile(path))
    postMessage({ type: 'status', text: '正在准备代码依赖' })
    // Imports from sibling modules must also load bundled scientific packages.
    const sources = data.type === 'run' ? data.files.filter(file => file.path.endsWith('.py') && typeof file.content === 'string').map(file => file.content).join('\n') : data.code
    await runtime.loadPackagesFromImports(sources, { messageCallback: text => postMessage({ type: 'status', text }), errorCallback: text => output('stderr', text) })
    postMessage({ type: 'status', text: '正在执行' })
    let exitCode = 0
    try {
      const result = await runtime.runPythonAsync(data.type === 'run' ? `exec(compile(${JSON.stringify(data.code)}, ${JSON.stringify(safePath(data.path))}, 'exec'))` : data.code, { globals })
      if (data.type === 'repl' && result !== undefined) output('stdout', String(result))
      result?.destroy?.()
    } catch (error) {
      // sys.exit(0) is a successful script exit, while all other errors keep the traceback.
      const exit = String(error).match(/\nSystemExit(?:: ([^\n]*))?\s*$/)
      const value = exit?.[1]?.trim()
      if (exit && (!value || value === 'None' || value === '0')) exitCode = 0
      else { exitCode = exit && /^-?\d+$/.test(value || '') ? Number(value) : 1; output('stderr', String(error)) }
    }
    // Export figures even when scripts use plt.show() with the noninteractive backend.
    runtime.runPython(`import sys\nif 'matplotlib.pyplot' in sys.modules:\n    import matplotlib.pyplot as plt\n    for number in plt.get_fignums():\n        plt.figure(number).savefig('/workspace/figure_%s.png' % number, bbox_inches='tight')\n    plt.close('all')`)
    const generated = []
    let total = 0
    for (const path of listFiles()) {
      const bytes = runtime.FS.readFile(path)
      const old = before.get(path)
      if (old && old.length === bytes.length && old.every((value, i) => value === bytes[i])) continue
      total += bytes.length
      if (total > 20 * 1024 * 1024) { output('stderr', '生成文件超过 20 MB，部分文件未导出'); break }
      generated.push({ path: path.slice('/workspace/'.length), bytes })
    }
    postMessage({ type: 'done', exitCode, elapsedMs: Math.round(performance.now() - started), files: generated })
  } catch (error) {
    output('stderr', String(error))
    postMessage({ type: 'done', exitCode: 1, elapsedMs: Math.round(performance.now() - started), files: [] })
  }
}
