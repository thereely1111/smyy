/**
 * 「跟着后端跑」停机判据的离线用例（零模型调用、零后端）。
 *
 * 覆盖 `utils/analysis/followPolicy.ts` —— 它修的是 2026-10-14 用户报的毛病：
 * 「后端智能体还在跑任务，前端却报『智能体这次没有返回分析结果』（原因：会话已空闲）」。
 *
 * 现场铁证：父会话 16:34:04 收流（把 S1a 派给子智能体后自己空闲）、16:39:07 才被
 * inbox → wakeup 通道唤醒，**空窗 303 秒**；同期 `题目解析智能体` 的框架任务
 * 一直是 RUNNING，平台自己的台账写着 `next_action=CONTINUE`。而旧规则是
 * 「run-status 连续空闲 3 轮（3 × 5 秒）即收工」⇒ 在交接空窗里下了失败定论。
 *
 * 判据输入是 `busy`（= `run-status.running || run-status.pendingWork`）：
 * P0-2 之后后端会把「子任务还在跑 / 结果待投递」也算进 `pendingWork`，
 * 于是这条规则在交接空窗里读到的就是 `busy=true`，根本不进空闲计数。
 *
 * 必须单测的理由：这条判据的两种错法都发生在**几十秒到几分钟的时序**里，
 * 只能真跑一次长任务才能看见（20–40 分钟 + 需真人过闸门），回归成本太高。
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
    contents: `export { followStopReason, FOLLOW_IDLE_ROUNDS, FOLLOW_IDLE_GRACE_MS, FOLLOW_PROBE_LOST_ROUNDS } from '@/utils/analysis/followPolicy';`,
    resolveDir: root,
    loader: 'ts',
  },
  bundle: true,
  platform: 'browser',
  format: 'esm',
  write: false,
  define: { 'process.env.NODE_ENV': 'production' },
  plugins: [{
    name: 'test-alias',
    setup(api) {
      api.onResolve({ filter: /^@\// }, (a) => ({ path: path.join(root, 'src', a.path.slice(2)) + '.ts' }))
    },
  }],
})
const { followStopReason, FOLLOW_IDLE_ROUNDS, FOLLOW_IDLE_GRACE_MS, FOLLOW_PROBE_LOST_ROUNDS } =
  await import('data:text/javascript;base64,' + Buffer.from(bundled.outputFiles[0].text).toString('base64'))

/** 默认一帧：后端还有在途工作 */
const tick = (over = {}) => ({
  ledgerOk: true,
  nextAction: 'CONTINUE',
  busy: true,
  idleStreak: 0,
  idleForMs: 0,
  probeFails: 0,
  ...over,
})

test('后端还有在途工作 → 永远不收工', () => {
  assert.equal(followStopReason(tick()), null)
  assert.equal(followStopReason(tick({ idleStreak: 99, idleForMs: 9_999_999 })), null)
})

test('★回归：父会话空闲但子智能体在跑（busy=true，实测 303 秒空窗）→ 不收工', () => {
  /* 现场形态：`run-status.running=false`（父会话收流了），但 `pendingWork=true`
     （`题目解析智能体` 的框架任务 RUNNING）⇒ 前端把两者并起来喂 `busy=true`。
     旧版只看 `running`，第 3 轮（15 秒）就判死。 */
  assert.equal(
    followStopReason(tick({ busy: true, nextAction: 'CONTINUE', idleStreak: 0, idleForMs: 0 })),
    null,
  )
})

test('台账 DONE / WAIT_HITL / IDLE → 立刻收工（智能体自己表态）', () => {
  for (const nextAction of ['DONE', 'WAIT_HITL', 'IDLE']) {
    assert.equal(followStopReason(tick({ nextAction, busy: true })), 'settled', nextAction)
  }
})

test('台账 CONTINUE + 后端全空闲 → 不收工（平台仍打算补醒续跑）', () => {
  assert.equal(
    followStopReason(tick({ busy: false, nextAction: 'CONTINUE', idleStreak: 61, idleForMs: 303_000 })),
    null,
  )
})

test('台账拿不到（老后端 / 跨账号 404）→ 退回时间阈值：空闲够久才收工', () => {
  const base = { busy: false, ledgerOk: false, nextAction: '' }
  assert.equal(followStopReason(tick({ ...base, idleStreak: FOLLOW_IDLE_ROUNDS, idleForMs: 0 })), null)
  assert.equal(
    followStopReason(tick({ ...base, idleStreak: FOLLOW_IDLE_ROUNDS, idleForMs: FOLLOW_IDLE_GRACE_MS - 1 })),
    null,
  )
  assert.equal(
    followStopReason(tick({ ...base, idleStreak: FOLLOW_IDLE_ROUNDS, idleForMs: FOLLOW_IDLE_GRACE_MS })),
    'idle',
  )
})

test('老后端没有 pendingWork（busy 只反映 running）+ 台账 UNKNOWN → 走时间阈值，不被当成「还在跑」', () => {
  assert.equal(
    followStopReason(tick({ busy: false, nextAction: 'UNKNOWN', idleStreak: 5, idleForMs: FOLLOW_IDLE_GRACE_MS })),
    'idle',
  )
})

test('探针问不出来 → 连续失联够多轮才按「跟丢了」收尾', () => {
  assert.equal(followStopReason(tick({ busy: null, probeFails: FOLLOW_PROBE_LOST_ROUNDS - 1 })), null)
  assert.equal(
    followStopReason(tick({ busy: null, probeFails: FOLLOW_PROBE_LOST_ROUNDS })),
    'lost',
  )
})

test('探针失联但台账已表态 → 仍按台账收工（不让读不到把已收尾的会话吊住）', () => {
  assert.equal(
    followStopReason(tick({ busy: null, nextAction: 'DONE', probeFails: 1 })),
    'settled',
  )
})
