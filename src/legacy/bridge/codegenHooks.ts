/**
 * 把 codegen/flow 包成 core 认识的那套钩子
 *
 * 与 `@/legacy/bridge/analysisHooks`、`@/legacy/bridge/animationHooks` 同构：
 * legacy/prototype/hooks.ts 只声明接口，实现放在这里，由 PrototypeShell 在挂载时注册。
 * core.ts（生成物）依旧只认识注册表，不认识业务模块。
 */

import type { PrototypeCodegenHooks } from '@/legacy/prototype/hooks'
import {
  liveAttach,
  liveRender,
  questionFailureText,
  questionProgressText,
  questionStatus,
  reset,
  reuseQuestion,
  skipQuestion,
  startQuestion,
  takeQuestion,
} from '@/stores/codegen'

export function createCodegenHooks(): PrototypeCodegenHooks {
  return {
    startQuestion: (ref, qi, model) => {
      void startQuestion(ref, qi, model)
    },
    reuseQuestion,
    questionStatus,
    questionProgressText,
    questionFailureText,
    skipQuestion,
    takeQuestion,
    reset,
    /* 实时区（等待气泡里的思考 / 工具明细）—— 转发到 live 模块的具名通道 */
    liveAttach,
    liveRender,
  }
}
