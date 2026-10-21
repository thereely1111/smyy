/**
 * 内置代码冒烟测试 —— 保证 src/data/builtinCode.ts 里每段代码都能跑通。
 *
 * 为什么必须测：这批代码是直接 `new Function` 执行的，写错一个变量名
 * 就变成「点了没反应」。放在 CI 前置检查里，比人肉点一遍可靠。
 *
 * 运行：node scripts/test-builtin-code.mjs
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

/** 把 builtinCode.ts 转成可 import 的模块 */
const src = readFileSync(resolve(here, '../../src/data/builtinCode.ts'), 'utf8')
const { outputText } = ts.transpileModule(src, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  fileName: 'builtinCode.ts',
})
const mod = await import('data:text/javascript;base64,' + Buffer.from(outputText).toString('base64'))
const { BUILTIN_SNIPPETS } = mod

/** 与 engine/localRunner.ts 等价的极简执行器（避免依赖 @/ 别名） */
function runCode(code) {
  const out = []
  const err = []
  const fakeConsole = {
    log: (...a) => out.push(a.map(String).join(' ')),
    info: (...a) => out.push(a.map(String).join(' ')),
    warn: (...a) => err.push(a.map(String).join(' ')),
    error: (...a) => err.push(a.map(String).join(' ')),
    debug: () => {},
    table: (...a) => out.push(a.map(String).join(' ')),
  }
  let exitCode = 0
  let failure = null
  try {
    // eslint-disable-next-line no-new-func
    new Function('console', 'checkTimeout', `"use strict";\n${code}\n`)(fakeConsole, () => {})
  } catch (e) {
    exitCode = 1
    failure = e
    err.push(String(e && e.message ? e.message : e))
  }
  return { stdout: out.join('\n'), stderr: err.join('\n'), exitCode, failure }
}

test('内置代码清单非空且字段完整', () => {
  assert.ok(BUILTIN_SNIPPETS.length >= 5, `至少应有 5 段，实际 ${BUILTIN_SNIPPETS.length}`)
  const ids = new Set()
  for (const s of BUILTIN_SNIPPETS) {
    assert.ok(s.id && !ids.has(s.id), `id 缺失或重复：${s.id}`)
    ids.add(s.id)
    assert.ok(s.title && s.summary && s.dataNote, `字段缺失：${s.id}`)
    assert.ok(Array.isArray(s.tags) && s.tags.length > 0, `tags 为空：${s.id}`)
    assert.ok(s.code && s.code.trim().length > 50, `代码过短：${s.id}`)
  }
})

for (const s of BUILTIN_SNIPPETS) {
  test(`可执行且退出码为 0：${s.title}`, () => {
    const r = runCode(s.code)
    if (r.failure) {
      assert.fail(`${s.id} 执行抛错：${r.stderr}`)
    }
    assert.equal(r.exitCode, 0, `${s.id} 退出码非 0：${r.stderr}`)
    assert.ok(r.stdout.length > 0, `${s.id} 没有任何输出 —— 点了会显示空白`)
  })
}

test('每段代码都不含未定义的全局（粗查 window/document）', () => {
  for (const s of BUILTIN_SNIPPETS) {
    assert.ok(!/\bdocument\b/.test(s.code), `${s.id} 引用了 document，本地通道里会抛错`)
    assert.ok(!/\bwindow\./.test(s.code), `${s.id} 引用了 window，本地通道里会抛错`)
  }
})

test('数字纪律：代码里不出现「赛题答案」口径的断言', () => {
  // 内置代码只做演示性验证，不应把某个数字写成「正确答案」
  for (const s of BUILTIN_SNIPPETS) {
    assert.ok(!/正确答案|标准答案|answer is/i.test(s.code), `${s.id} 疑似写入了答案口径`)
  }
})
