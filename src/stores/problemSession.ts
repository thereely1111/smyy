/**
 * 赛题 → 会话 / run 的登记处 —— 「一道赛题 = 一个会话 = 一个环境」的落地件
 *
 * 这是全模块最容易被做错、也最不能省的一件：
 * 会话级环境粒度下，**用户切问题 Tab 时绝不能换会话**，
 * 否则问题一写的文件问题二就读不到（B 组验收清单第 2 条过不去）。
 *
 * 约束：
 *   - threadId 前缀固定 `main-`（后端按该前缀识别前端新建会话）
 *   - runId 持久化在本地（只存 threadId 会导致刷新后点提交失败）
 *   - **threadId 不再自建映射**：沙箱与分析 / 动画 / 代码通道同处一个会话，
 *     一律经 `analysis/flow` 的 `threadIdOf(caseId)` 取 —— 自建映射会分叉会话，
 *     智能体就读不到 S1 落盘的分析产物
 */

import { createUuid } from '@/utils/uuid'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { threadIdOf } from '@/stores/analysis'

const RUN_KEY = 'smyy.problem_run_map'
const CUR_KEY = 'smyy.current_problem'

/** 生成一个前端新建会话的 threadId（前缀必须是 main-） */
export function newThreadId(): string {
  return `main-${createUuid()}`
}

/** 生成一次 run 的 runId */
export function newRunId(): string {
  return `run-${createUuid()}`
}

type StringMap = Record<string, string>

function readMap(key: string): StringMap {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) return parsed as StringMap
  } catch {
    // 损坏就丢弃，重新建映射比崩掉好
  }
  return {}
}

function writeMap(key: string, map: StringMap): void {
  try {
    localStorage.setItem(key, JSON.stringify(map))
  } catch {
    // 隐私模式下 localStorage 可能不可写 —— 不影响本次会话内存中的映射
  }
}

/** 读「当前赛题」—— 只是一个字符串，损坏就当作没选 */
function readCur(): string {
  try {
    return localStorage.getItem(CUR_KEY) ?? ''
  } catch {
    return ''
  }
}

function writeCur(problemId: string): void {
  try {
    if (problemId) localStorage.setItem(CUR_KEY, problemId)
    else localStorage.removeItem(CUR_KEY)
  } catch {
    // 同上：不可写也不影响本次会话
  }
}

export const useProblemSessionStore = defineStore('problemSession', () => {
  /** problemId → 最近一次 runId（resume 必须复用同一个） */
  const runOf = ref<StringMap>(readMap(RUN_KEY))

  /**
   * 当前选中的赛题（切题时才变）
   *
   * 持久化：刷新后仍能回到同一道题 —— 否则进代码台时
   * 产物目录会因为「不知道是哪道题」而空着，像是坏了。
   */
  const currentProblemId = ref<string>(readCur())

  const currentThreadId = computed(() =>
    currentProblemId.value ? threadIdOf(currentProblemId.value) : '',
  )
  const currentRunId = computed(() =>
    currentProblemId.value ? runOf.value[currentProblemId.value] ?? '' : '',
  )

  /**
   * 进入某道赛题的会话（**幂等**：已有就复用，没有才新建）
   *
   * 这就是「一题一会话」的全部实现 —— 调用方只需要调它，
   * 不要自己在组件里 newThreadId()，否则又变成「每问一个新会话」。
   *
   * threadId 直接向分析通道取（同一个 caseId 得同一个 thread），
   * 沙箱因此天然落在 S1/S2/S3 的那条会话里。
   */
  function enterProblem(problemId: string): string {
    currentProblemId.value = problemId
    writeCur(problemId)
    return threadIdOf(problemId)
  }

  /** 为当前赛题登记一个新的 runId（每轮**新消息**都要换新的） */
  function beginRun(problemId = currentProblemId.value): string {
    if (!problemId) return ''
    const rid = newRunId()
    runOf.value = { ...runOf.value, [problemId]: rid }
    writeMap(RUN_KEY, runOf.value)
    return rid
  }

  /**
   * 取 resume 用的 runId —— **复用**上一次的，不要新生成
   * （新建会导致后端认不出是哪次中断）
   */
  function runIdForResume(problemId = currentProblemId.value): string {
    return problemId ? runOf.value[problemId] ?? '' : ''
  }

  /** 换赛题（离开当前题的会话，但产物保留在磁盘上） */
  function leaveProblem(): void {
    currentProblemId.value = ''
    writeCur('')
  }

  return {
    runOf,
    currentProblemId,
    currentThreadId,
    currentRunId,
    enterProblem,
    beginRun,
    runIdForResume,
    leaveProblem,
  }
})
