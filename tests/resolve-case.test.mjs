/**
 * resolveCase 题面判定的离线用例（零模型调用、零后端）。
 *
 * 修的是 2026-10-14 用户现场：用题目文件（C题.pdf）开的题，页面一刷新，
 * 附件暂存区（内存态）就空了 —— 题框里只有「C题」两个字，不足 20 字下限，
 * `resolveCase` 返回 null ⇒ 对话区一句话都发不出去，卡上还误导人去查网络。
 *
 * 修复＝把开题那一刻的题目文件名列表持久化进任务仓，刷新后作为第 5 参兜底喂回
 * `resolveCase` —— 同一份文件算出**同一个 caseId**，接得回原会话。
 *
 * 现场铁证：DB `agent_session_extension_entries` 里那条快照
 * `caseId="user-4mbvi2"` ＝ `shortHash('paper:C题.pdf')` —— 本文的回归用例逐字对上。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import fs from 'node:fs'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const root = path.resolve(import.meta.dirname, '..')

const bundled = await build({
  stdin: {
    contents: `export { resolveCase, resolveCaseIdFor, holdFiles, hasProblemFile } from '@/stores/analysis';`,
    resolveDir: root,
    loader: 'ts',
  },
  bundle: true,
  platform: 'browser',
  format: 'esm',
  write: false,
  define: { 'process.env.NODE_ENV': 'production', 'import.meta.env': '{}' },
  plugins: [
    {
      name: 'test-alias',
      setup(api) {
        /* @/config、@/utils/analysis/live 是**目录** —— 不能只拼 .ts，得再试 index.ts */
        api.onResolve({ filter: /^@\// }, (a) => {
          const base = path.join(root, 'src', a.path.slice(2))
          for (const cand of [`${base}.ts`, path.join(base, 'index.ts')]) {
            if (fs.existsSync(cand)) return { path: cand }
          }
          return { path: `${base}.ts` }
        })
      },
    },
  ],
})
const { resolveCase, resolveCaseIdFor, holdFiles, hasProblemFile } =
  await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'))

const TYPED = '某小区建有微网，含光伏与储能，每日需制定 144 个时段的购电计划，请建立优化模型求解。'

test('内置真题：从详情页进 → 用内置题面（2026-C）', () => {
  const c = resolveCase('p3', '随便什么标题', true)
  assert.equal(c?.caseId, '2026-C')
  assert.equal(c?.source, 'builtin')
})

test('用户自己写的题面（≥20 字）→ 用用户文本', () => {
  const c = resolveCase('', TYPED, false)
  assert.equal(c?.source, 'user')
  assert.equal(c?.text, TYPED)
  assert.match(c?.caseId ?? '', /^user-/)
})

test('题面太短又没有题目文件 → null（就该被拦）', () => {
  assert.equal(resolveCase('', 'C题', false), null)
})

test('★回归：只传题目文件（C题.pdf）→ caseId 逐字等于现场快照 user-4mbvi2', () => {
  const f = new File(['x'], 'C题.pdf', { type: 'application/pdf' })
  const r = holdFiles('home', [f], 'problem')
  assert.equal(r.accepted, 1)
  assert.equal(hasProblemFile('home'), true)
  const c = resolveCase('', 'C题', false, true)
  assert.equal(c?.caseId, 'user-4mbvi2')
  assert.equal(c?.text, '')
})

test('★回归：刷新后暂存区丢了 → 文件名兜底算出同一个 caseId（接得回原会话）', () => {
  const c = resolveCase('', 'C题', false, false, ['C题.pdf'])
  assert.equal(c?.caseId, 'user-4mbvi2')
  /* 实时暂存区在场时优先用实时的（两份名单一致 ⇒ caseId 一致） */
  const c2 = resolveCase('', 'C题', false, true, ['C题.pdf'])
  assert.equal(c2?.caseId, 'user-4mbvi2')
})

test('resolveCaseIdFor 透传兜底：开题存证 / 刷新还原都算得准', () => {
  assert.equal(resolveCaseIdFor('', 'C题', false, ['C题.pdf']), 'user-4mbvi2')
  assert.equal(resolveCaseIdFor('', 'C题', false, []), '')
})
