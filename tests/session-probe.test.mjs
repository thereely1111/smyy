/**
 * 「会话还能不能接着用」判据的离线用例（零模型调用、零后端）。
 *
 * 覆盖 `utils/analysis/sessionProbe.ts` —— 它修的是 2026-10-13 用户报的毛病：
 * 「把上一道题删掉后，同一道题再输一遍报错：该会话已被删除，请新建会话」。
 *
 * 之所以必须单测：这条判据的两种错法都很安静 ——
 *   · 判得太松（照旧拿附件接口探活）⇒ 墓碑会话被判「可用」⇒ run 撞后端 410，
 *     用户看到「旧任务干扰新任务」，且要真删一次任务、真跑一次 run 才能发现；
 *   · 判得太紧（凡列表里没有就换）⇒ 把刚新建、后端还没登记的会话误换掉，
 *     每开一道新题白建一条死会话（暂停/删除打上去 404，2026-10-09 修过的老毛病）。
 */

import test from 'node:test'
import assert from 'node:assert/strict'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(import.meta.url)
const { build } = require(require.resolve('esbuild', { paths: [require.resolve('vite')] }))
const root = path.resolve(import.meta.dirname, '..')

const bundled = await build({
  stdin: {
    contents: `export { judgeSessionUsable, markFreshThread, isFreshThread } from '@/utils/analysis/sessionProbe';`,
    resolveDir: root,
    loader: 'ts',
  },
  bundle: true,
  platform: 'browser',
  format: 'esm',
  write: false,
  define: { 'process.env.NODE_ENV': '"production"' },
  plugins: [{
    name: 'test-alias',
    setup(api) {
      api.onResolve({ filter: /^@\// }, (a) => ({ path: path.join(root, 'src', a.path.slice(2)) + '.ts' }))
    },
  }],
})
const { judgeSessionUsable, markFreshThread, isFreshThread } =
  await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'))

const listed = (...ids) => ids.map((sessionId) => ({ sessionId }))

test('列表里在 → 可以接着用', () => {
  assert.equal(judgeSessionUsable(listed('main-a', 'main-b'), 'main-a'), true)
})

test('列表里没有（已删的墓碑会话 / 别人的会话）→ 不能接着用，调用方该换新的', () => {
  assert.equal(judgeSessionUsable(listed('main-b'), 'main-a'), false)
  assert.equal(judgeSessionUsable(listed(), 'main-a'), false)
})

test('列表请求失败（网络抖动 / 后端没起）→ 放行，别白换会话', () => {
  assert.equal(judgeSessionUsable(null, 'main-a'), true)
})

test('本模块刚新建、后端还没登记的会话 → 放行（列表里必然没有）', () => {
  const fresh = 'main-fresh-1'
  assert.equal(isFreshThread(fresh), false)
  markFreshThread(fresh)
  assert.equal(isFreshThread(fresh), true)
  assert.equal(judgeSessionUsable(listed(), fresh), true)
})

test('空 threadId 一律判不可用', () => {
  assert.equal(judgeSessionUsable(listed('main-a'), ''), false)
})

test('用户现场序列：删掉旧会话后同一道题该换新会话，换出的新会话不受影响', () => {
  const oldThread = 'main-old'
  /* ① 之前跑过：会话在列表里，复用没问题 */
  assert.equal(judgeSessionUsable(listed(oldThread), oldThread), true)
  /* ② 用户删了任务 → 后端置墓碑 → 列表里没它了 ⇒ 判死 */
  assert.equal(judgeSessionUsable(listed(), oldThread), false)
  /* ③ 换出全新会话 → 后端还没登记，白名单兜住 */
  const rotated = 'main-rotated'
  markFreshThread(rotated)
  assert.equal(judgeSessionUsable(listed(), rotated), true)
  /* ④ 它跑起来、被后端登记后，照旧判可用 */
  assert.equal(judgeSessionUsable(listed(rotated), rotated), true)
})
