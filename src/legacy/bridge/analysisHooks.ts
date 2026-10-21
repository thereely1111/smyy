/**
 * 把 flow/render 两个模块包成 core 认识的那套钩子
 *
 * 为什么单独一个文件：legacy/prototype/hooks.ts 只声明接口，不实现；
 * 实现放在这里，由 PrototypeShell 在挂载时注册。这样 core.ts（生成物）
 * 依旧只认识注册表，不认识业务模块。
 */

import type { PrototypeAnalysisHooks } from '@/legacy/prototype/hooks'
import {
  artifactStamp,
  askWaitNoteText,
  askAgent,
  attachFailureText,
  available,
  beginProblem,
  codeNote,
  dropRejected,
  failureText,
  gateCancel,
  gateOf,
  gateSubmit,
  hasProblemFile,
  heldList,
  holdFiles,
  lateAttachments,
  liveActive,
  liveCounters,
  liveDoc,
  liveMount,
  liveRender,
  liveReset,
  problemTitle,
  progressText,
  rejectedList,
  removeHeld,
  rerun,
  reset,
  resetRuns,
  resolveCase,
  reuse,
  skip,
  start,
  status,
  step,
  uploadHeldNow,
} from '@/stores/analysis'
import {
  artifactMount,
  artifactPreview,
  artifactRefresh,
  artifactReset,
} from '@/utils/analysis/artifact'
import {
  reportDownload,
  reportMount,
  reportPreview,
  reportRefresh,
} from '@/utils/analysis/report'

export function createAnalysisHooks(): PrototypeAnalysisHooks {
  return {
    resolveCase,
    available,
    start: (ref) => {
      void start(ref)
    },
    reuse,
    status,
    progressText,
    /* 六步进度条 —— 接真流程（产物 + 台账），不再扫气泡文本 */
    step,
    /* 产物签名 —— 每次刷新产物树且内容有变化就 +1，用来驱动产物卡重画 */
    artifactStamp,
    /* 发言排队提示 —— 上一步还在后台跑时显示的等待文案 */
    askWaitNote: () => askWaitNoteText(),
    failureText,
    codeNote,
    liveDoc,
    skip,
    reset,
    resetRuns,
    /* 对话区自由输入 —— 不推进前端状态机、不要产物 */
    ask: (ref, text) => askAgent(ref, text),
    attachFailureText,
  holdFiles,
  heldList,
  hasProblemFile,
  problemTitle,
  removeHeld,
  rejectedList,
  dropRejected,
  attachNow: (scope) => uploadHeldNow(scope),
  lateAttachments,
  rerun,
  beginProblem,
  /* 实时对话通道 —— 转发到 live 模块（渲染逻辑都在那边） */
  liveMount,
  liveRender,
  liveActive,
  liveCounters,
  liveReset,
  /* 闸门（HITL）—— 表单画在右栏，值回送到挂起的那条 run */
  gate: () => gateOf(),
  gateSubmit: (values) => gateSubmit(values),
  gateCancel: () => gateCancel(),
  /* 产物卡 —— 转发到 artifact 模块（拉树、画墙、预览都在那边） */
  artifactMount,
  artifactRefresh,
  artifactReset,
  artifactPreview,
  /* 分析报告卡 —— 转发到 report 模块（挑主稿、调后端导出、下载都在那边） */
  reportMount,
  reportRefresh,
  reportDownload,
  reportPreview,
}
}
