/**
 * 工作台任务记录 ↔ 后端「会话扩展」的落盘（2026-10-12 新增，用户定调）
 *
 * 背景：任务与对话原先只活在 localStorage（`smyy.work.tasks`）里 —— 换浏览器、
 * 清一次站点数据、或换台机器，聊了半天的任务就没了。用户定调：**存到 smyy 库**。
 *
 * 为什么按「会话」存而不是按「用户」存：新后端的键值存储只有**会话级**
 * （`agent_session_extensions`，唯一键 `(agent_id, session_id)`，见 V33 迁移），
 * 没有用户级。而工作台的每条任务本来就对应一条平台会话（`threadId`），
 * 天然对齐 —— 不为了「按用户存」去改后端表结构。
 *
 * 口径：
 *   · 后端是**真相源**，localStorage 降级为「索引 + 离线缓存」；
 *   · 会话 id 还没有（任务刚建、还没跑过 run）时不写 —— 那时后端也没有这条会话；
 *   · 读写都不抛错给调用方：网络抖一下就退回本地那一份，页面照常用。
 *
 * 关联：`stores/tasks.ts` 负责节拍与合并；本文件只管协议的进出。
 */

import { AGENT_ID } from '@/config'
import { getExtension, upsertExtensionEntries } from '@/api/session'

/** 后端扩展里的键：一条任务一份 JSON 快照 */
export const WORKBENCH_KEY = 'smyy.workbench'

/** 快照版本 —— 以后字段变了，读到老版本按缺省值补，不整份丢弃 */
export const WORKBENCH_VERSION = 1

export interface WorkbenchSnapshot {
  v: number
  /** 秒级时间戳：合并时比它，后端更新才覆盖本地 */
  updatedAt: number
  title: string
  protoId: string
  fromDetail: boolean
  createdAt: number
  step: number
  /** 对话区 HTML 快照 */
  stream: string
  /** 右栏（闸门 + 产物）HTML 快照 */
  rail: string
  caseId: string
  paused: boolean
  /** 开题那一刻的题目文件名列表（题面由文件承担的任务才有；老快照缺 → []） */
  paperNames: string[]
}

/** 单条扩展值的大小上限 —— 后端是 TEXT，但别真往上灌几 MB */
export const MAX_REMOTE_STREAM = 120_000
export const MAX_REMOTE_RAIL = 40_000

function str(v: unknown, fallback = ''): string {
  return typeof v === 'string' ? v : fallback
}

function num(v: unknown, fallback: number): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : fallback
}

/** 把后端读回来的 JSON 收成一份可用的快照；认不出来返回 null（当成「后端没有」） */
function coerce(raw: unknown): WorkbenchSnapshot | null {
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const updatedAt = num(o.updatedAt, 0)
  if (!updatedAt) return null
  return {
    v: num(o.v, 0),
    updatedAt,
    title: str(o.title),
    protoId: str(o.protoId),
    fromDetail: o.fromDetail === true,
    createdAt: num(o.createdAt, updatedAt),
    step: num(o.step, 0),
    stream: str(o.stream),
    rail: str(o.rail),
    caseId: str(o.caseId),
    paused: o.paused === true,
    /* v 不升：老快照没有这个字段，回落空数组（applyRemote 不会拿空数组盖本地） */
    paperNames: Array.isArray(o.paperNames) ? o.paperNames.filter((x) => typeof x === 'string') : [],
  }
}

/**
 * 读回某会话的任务快照。
 *
 * 404（后端没有这份扩展）/ 网络异常一律当「没有」—— 调用方据此保留本地那份。
 * 注意后端 `GET .../extensions` 会做会话归属校验，不是自己的会话直接 404。
 */
export async function readWorkbench(sessionId: string): Promise<WorkbenchSnapshot | null> {
  if (!sessionId) return null
  try {
    const ext = await getExtension(AGENT_ID, sessionId)
    const entry = (ext?.entries ?? []).find((e) => e.key === WORKBENCH_KEY)
    if (!entry || !entry.value) return null
    return coerce(JSON.parse(entry.value) as unknown)
  } catch {
    return null
  }
}

/** 写入某会话的任务快照（覆盖已有 key；会话扩展不存在时后端会自动建） */
export function writeWorkbench(sessionId: string, snap: WorkbenchSnapshot): Promise<void> {
  if (!sessionId) return Promise.resolve()
  return upsertExtensionEntries(AGENT_ID, sessionId, [
    { key: WORKBENCH_KEY, value: JSON.stringify(snap) },
  ]).then(() => undefined)
}
