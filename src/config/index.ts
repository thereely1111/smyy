/**
 * 前端运行期配置（沙箱 / 工作台）
 *
 * 后端：双后端分流（见 `vite.config.ts` 的 proxyOptions）——
 *   智能体侧 `/agui` `/api/agents` `/api/agui-events` `/api/notify` → `VITE_BACKEND_TARGET_AGENT`
 *   业务侧 `/api/auth` `/api/users` `/api/smyy` `/covers` `/case-files` → `VITE_BACKEND_TARGET`
 * 前端一律写相对路径，分流全在代理层，业务代码不感知。
 *
 * ⚠ 双 token：两端 JWT 密钥不同、同账号 userId 也不同（实测 51964be9… vs c93f6ee1…），
 *   一个 token 打两边必然 401 ⇒ `/agent-auth/*` 另签一份智能体 token，
 *   分派与刷新在 `src/api/http.ts`（channelOf / DUAL_TOKEN），登录编排在 `src/api/auth.ts`。
 *   生产同源部署时自动退化为单 token，无需额外配置。
 * （本仓库自带的 `agent-platform/` 已不再使用，见 `.env.local` 说明。）
 *
 * agentId 由「智能体中文名」经 `agentIdFromName` 推导 —— 等价于 Java
 * `UUID.nameUUIDFromBytes(dirName.getBytes(UTF_8))`，即后端 FileSystemAgentLoader
 * 把 `workspace/agents/<目录名>/` 映射成 agentId 的算法。
 *
 * ⇒ **不要硬编码 UUID**：改名只改 `.env.local` 的 VITE_SMYY_AGENT_NAME 即可，
 *   无需手算、也不用改代码。算法在 `src/utils/nameUuid.ts`。
 *   校验：`node scripts/sandbox/check-agent-id.mjs 数学建模解答助手`
 */

import { agentIdFromName } from '@/utils/nameUuid'

/** 对接模式：mock = 本地假会话；real = 走真后端 */
export type SandboxMode = 'mock' | 'real'

const rawMode = import.meta.env.VITE_SANDBOX_MODE as string | undefined
export const SANDBOX_MODE: SandboxMode = rawMode === 'mock' ? 'mock' : 'real'

/** 是否为 mock 模式（UI 上要显式打标，避免把假数据当真结果） */
export const IS_MOCK = SANDBOX_MODE === 'mock'

/** 智能体中文目录名 —— 对应后端 workspace/agents/<此名>/ */
export const AGENT_NAME: string =
  (import.meta.env.VITE_SMYY_AGENT_NAME as string | undefined)?.trim() || '数学建模解答助手'

/**
 * 推导出的 agentId（如 `14ef7e16-06c8-34ff-8845-c19866cb2689`）。
 *
 * 允许 `.env.local` 用 VITE_SMYY_AGENT_ID 覆盖（仅在需要指向一个「名字对不上
 * 后端目录」的临时实例时才用）；正常情况留空，由目录名推导。
 */
export const AGENT_ID: string =
  (import.meta.env.VITE_SMYY_AGENT_ID as string | undefined)?.trim() ||
  agentIdFromName(AGENT_NAME)

/**
 * ⚠ **没有第二个 agentId**（2026-10-13）。
 *
 * 曾经有个 `ANIM_AGENT_ID` 指向独立的「数模动画生成助手」，与解答助手并行跑动画。
 * 现役 java 后端里那个 agent **不存在**（10-10 按「合并进解答助手」口径移出），
 * 于是动画链路必然 404：`Agent not found: bfd1e86e-1cff-3219-b78b-be072dab8845`。
 *
 * 现在的口径：**S1（分析）/ S2（动画）/ S3（代码）都是同一个 `AGENT_ID`、
 * 同一条会话**（会话键→threadId 见 `stores/analysis` 的 `threadIdOf`），
 * 动画能力在解答助手的 `skills/mm-2-*`。所以这里不再有任何动画专用 agentId，
 * `.env.local` 里的 `VITE_SMYY_ANIM_AGENT_NAME` / `VITE_SMYY_ANIM_AGENT_ID`
 * 也已作废 —— 别再按名字推导第二个 agent 了。
 */

/** 产物树轮询间隔（ms）。0 = 不轮询，仅 run 结束或手动刷新时拉一次 */
export const ARTIFACTS_POLL_MS = Number(import.meta.env.VITE_SANDBOX_ARTIFACTS_POLL || 0)

/** 是否把工具结果结构化为 { stdout, stderr, exitCode } */
export const EXEC_STRUCTURED = (import.meta.env.VITE_SANDBOX_EXEC_STRUCTURED || 'true') !== 'false'

/** 会话产物保留天数，仅用于前端文案提示；0 = 不提示 */
export const RETENTION_DAYS = Number(import.meta.env.VITE_SANDBOX_RETENTION_DAYS || 0)

/** 附件单文件上限（MB），与后端 MAX_ATTACH_BYTES 保持一致 */
export const ATTACH_MAX_MB = Number(import.meta.env.VITE_SANDBOX_ATTACH_MAX_MB || 20)
