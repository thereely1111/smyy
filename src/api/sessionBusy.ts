/**
 * 「这条会话正忙」的判据 —— 前端据此**排队重试**，而不是把一句 409 直接甩给用户。
 *
 * 后端在 run 入口做同会话互斥（`PlatformAguiRuntimeContextResolver`）：已有活跃 run
 * 时抛 409「该会话有正在执行的任务，请等待其完成后再试」。而平台自己的 wakeup 续跑、
 * 看门狗补醒、HITL 挂起都算活跃 run —— 所以「分析还没跑完」这个长达 20–40 分钟里，
 * 用户在对话区说任何一句都会撞它。
 *
 * 2026-10-14 之前前端把它当普通失败渲染成「这句话没能发出去」，用户看到的是
 * 「对话到一定程度就不能说话了」。真实原因是**没排队**。
 */

import { ApiError } from '@/api/http'

/** 后端那句原文（注释里保留，便于对日志） */
export const SESSION_BUSY_TEXT = '该会话有正在执行的任务'

/**
 * 是不是「会话正忙」这一类失败。
 *
 * 两条判据取或：HTTP 409（权威，`httpStream` 抛的 `ApiError` 带 status），
 * 以及文案兜底（老后端/网关把状态码改掉的场合）。
 */
export function isSessionBusyError(e: unknown): boolean {
  if (e instanceof ApiError && e.status === 409) return true
  return e instanceof Error && e.message.includes(SESSION_BUSY_TEXT)
}
