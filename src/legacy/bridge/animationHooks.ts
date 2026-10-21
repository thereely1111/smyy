/**
 * 把 animation（并行动画通道）包成 core 认识的那套钩子
 *
 * 与 `@/legacy/bridge/analysisHooks` 同构：legacy/prototype/hooks.ts 只声明接口，
 * 实现放在这里，由 PrototypeShell 在挂载时注册。core.ts（生成物）依旧只认识
 * 注册表，不认识业务模块。
 */

import type { PrototypeAnimationHooks } from '@/legacy/prototype/hooks'
import {
  currentCase,
  failureText,
  knownAnims,
  liveAttach,
  liveRender,
  onUpdate,
  progressText,
  reset,
  reuse,
  start,
  status,
  takeAnims,
} from '@/stores/animation'

export function createAnimationHooks(): PrototypeAnimationHooks {
  return {
    start: (ref) => {
      void start(ref)
    },
    reuse,
    currentCase,
    status,
    progressText,
    failureText,
    takeAnims,
    knownAnims: () => knownAnims(),
    onUpdate,
    reset,
    /* 实时区（浮窗里的思考 / 工具明细）—— 转发到 live 模块的具名通道 */
    liveAttach,
    liveRender,
  }
}
