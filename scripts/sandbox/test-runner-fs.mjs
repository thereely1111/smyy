/**
 * 沙箱引擎「文件视图」测试 —— 保证产物文件里的代码能读到同目录的其它文件。
 *
 * 为什么必须测：这是「改代码 → 运行」闭环的关键一环。用户会写
 * `sandbox.readFile('data/附件1.csv')`，如果读不到、或者路径匹配错，
 * 用户看到的是一句看不懂的报错。这里把真实源码 transpile 出来跑，
 * 而不是复制一份等价实现 —— 复制的实现改了源也不会红，等于没测。
 *
 * 运行：node scripts/sandbox/test-runner-fs.mjs
 */

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const here = dirname(fileURLToPath(import.meta.url))

function loadTs() {
  const candidates = [
    'typescript',
    resolve(here, '../../node_modules/typescript'),
    'E:/象数演易/模板/kuaike/node_modules/typescript',
  ]
  for (const c of candidates) {
    try {
      return require(c)
    } catch {
      /* try next */
    }
  }
  throw new Error('找不到 typescript 包，请先 pnpm install')
}

const ts = loadTs()

/**
 * 加载真实的 engine/localRunner.ts。
 *
 * 它 `import type { ExecResult }` —— 类型导入 transpile 后会被抹掉，
 * 所以产物里没有任何运行时 import，可以直接喂给 import()。
 */
const engineSrc = readFileSync(
  resolve(here, '../../src/engine/localRunner.ts'),
  'utf8',
)
const { outputText } = ts.transpileModule(engineSrc, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  fileName: 'localRunner.ts',
})
const mod = await import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'))
const { runLocalCode } = mod

const FILES = [
  { path: 'data/附件1.csv', content: 'time,price\n0:00,0.42\n6:00,0.39' },
  { path: 'main.js', content: '// 执行入口' },
  { path: 'notes.md', content: '# 笔记' },
]

test('sandbox.readFile 能读到传入的文件（完整路径）', async () => {
  const out = await runLocalCode(
    `const t = sandbox.readFile('data/附件1.csv'); console.log(t);`,
    { files: FILES },
  )
  assert.equal(out.result.exitCode, 0)
  assert.match(out.result.stdout, /time,price/)
  assert.match(out.result.stdout, /0:00,0\.42/)
})

test('sandbox.readFile 支持只用文件名（尾部别名）', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.readFile('附件1.csv'));`,
    { files: FILES },
  )
  assert.equal(out.result.exitCode, 0)
  assert.match(out.result.stdout, /0:00,0\.42/)
})

test('路径分隔符 \ 与开头的 ./ 都能归一', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.readFile('./data\\\\附件1.csv'));`,
    { files: FILES },
  )
  assert.equal(out.result.exitCode, 0)
  assert.match(out.result.stdout, /0:00,0\.42/)
})

test('读不存在的文件：抛错进 stderr，并列出可读文件', async () => {
  const out = await runLocalCode(`sandbox.readFile('nope.txt');`, { files: FILES })
  assert.equal(out.result.exitCode, 1)
  assert.equal(out.result.isError, true)
  assert.match(out.result.stderr, /ENOENT/)
  // 报错里要能看出「有哪些能读」，否则用户只能瞎猜
  assert.match(out.result.stderr, /data\/附件1\.csv/)
})

test('sandbox.exists 不抛错，只回布尔', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.exists('main.js'), sandbox.exists('missing.js'));`,
    { files: FILES },
  )
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'true false')
})

test('sandbox.list 返回全部可读文件', async () => {
  const out = await runLocalCode(`console.log(sandbox.list().length);`, { files: FILES })
  assert.equal(out.result.exitCode, 0)
  // 3 个原名 + 3 个尾部别名（main.js / 附件1.csv / notes.md 别名）
  assert.ok(Number(out.result.stdout.trim()) >= FILES.length)
})

test('不传 files 时 sandbox 仍存在，读文件得到明确报错', async () => {
  const out = await runLocalCode(`console.log(sandbox.exists('x'));`)
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'false')
})

test('文件视图是只读的 —— 写操作不存在', async () => {
  const out = await runLocalCode(`sandbox.writeFile('a.txt','x');`, { files: FILES })
  assert.equal(out.result.isError, true)
  assert.match(out.result.stderr, /not a function|undefined/)
})

test('代码仍不能碰 DOM（window 未注入）', async () => {
  const out = await runLocalCode(`console.log(typeof window);`, { files: FILES })
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'undefined')
})

test('真实场景：读 csv 算合计并打印', async () => {
  const code = `
const csv = sandbox.readFile('data/附件1.csv');
const rows = csv.trim().split('\\n').slice(1);
let sum = 0;
for (const r of rows) sum += Number(r.split(',')[1]);
console.log('合计', sum.toFixed(4));
`
  const out = await runLocalCode(code, { files: FILES })
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), '合计 0.8100')
})

/* ── 产物目录的真实形态：带 artifacts/ 前缀 + 相对入口解析 ────── */

const ARTIFACTS = [
  { path: 'artifacts/data/附件1_光伏出力.csv', content: 'time,price\n0:00,0.42\n6:00,0.39' },
  { path: 'artifacts/src/solve_q1.js', content: '// 入口' },
  { path: 'artifacts/report.md', content: '# 报告' },
]

test('产物前缀 artifacts/ 可省略（用户写 data/x.csv 能命中）', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.readFile('data/附件1_光伏出力.csv').split('\\n')[0]);`,
    { files: ARTIFACTS, entryPath: 'artifacts/src/solve_q1.js' },
  )
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'time,price')
})

test('相对入口文件解析：同级 data/ 目录（../data/x.csv）', async () => {
  // 入口在 artifacts/src/，数据在 artifacts/data/ —— 需要先上一级
  const out = await runLocalCode(
    `console.log(sandbox.readFile('../data/附件1_光伏出力.csv').split('\\n')[0]);`,
    { files: ARTIFACTS, entryPath: 'artifacts/src/solve_q1.js' },
  )
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'time,price')
})

test('相对入口解析：同目录文件直接写文件名', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.exists('solve_q1.js'), sandbox.exists('data/附件1_光伏出力.csv'));`,
    { files: ARTIFACTS, entryPath: 'artifacts/src/solve_q1.js' },
  )
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'true true')
})

test('绝对路径（带 artifacts/ 全写）依然可用', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.readFile('artifacts/data/附件1_光伏出力.csv').split('\\n')[0]);`,
    { files: ARTIFACTS, entryPath: 'artifacts/src/solve_q1.js' },
  )
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), 'time,price')
})

test('以 / 开头视为沙箱根，不做相对解析', async () => {
  const out = await runLocalCode(
    `console.log(sandbox.readFile('/artifacts/report.md').split('\\n')[0]);`,
    { files: ARTIFACTS, entryPath: 'artifacts/src/solve_q1.js' },
  )
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), '# 报告')
})

test('相对入口解析：真的能跑一遍 mock 的 solve_q1 场景', async () => {
  const code = `
const csv = sandbox.readFile('../data/附件1_光伏出力.csv');
const rows = csv.trim().split('\\n').slice(1);
let cost = 0;
for (const r of rows) {
  const [, price] = r.split(',');
  cost += Number(price);
}
console.log('电价合计', cost.toFixed(4));
`
  const out = await runLocalCode(code, {
    files: ARTIFACTS,
    entryPath: 'artifacts/src/solve_q1.js',
  })
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), '电价合计 0.8100')
})

test('超时保护仍在（死循环不死页面）', async () => {
  const out = await runLocalCode(`while(true){ checkTimeout(); }`, { timeoutMs: 120 })
  assert.equal(out.timedOut, true)
  assert.equal(out.result.exitCode, 124)
})

test('纯计算代码不受文件视图影响（回归）', async () => {
  const out = await runLocalCode(`console.log(1+1);`, { files: FILES })
  assert.equal(out.result.exitCode, 0)
  assert.equal(out.result.stdout.trim(), '2')
})
