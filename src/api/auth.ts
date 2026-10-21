/**
 * 认证接口 —— 对接 agent-platform 的 AuthController（/api/auth/*）
 *
 * 复用沙箱模块已有的 http 客户端（`@/api/http`）：
 * 它已经是完整范式 —— JWT exp 本地预判、401 静默刷新、并发刷新去重、
 * httpStream 长连接。这里不再造第二个客户端，否则两套 token 状态会打架。
 *
 * 后端契约（2026-09-28 实测取证）：
 *   POST /api/auth/login    { username, password }  → { accessToken, refreshToken, userId, role, permissions[] }
 *   GET  /api/auth/me                                → { userId, username, role }
 *   POST /api/auth/refresh  { refreshToken }         → 同 login
 *   POST /api/auth/logout                            → 204
 *   目标 Java 后端 JWT 为 HS256，access token 默认有效期 30 分钟（可通过后端配置覆盖）。
 */

import {
  http,
  saveTokens,
  USER_ID_KEY,
  USER_INFO_KEY,
  AGENT_USER_ID_KEY,
  AGENT_AUTH_BASE,
  DUAL_TOKEN,
  hasAgentSession,
  isAccessTokenExpired,
  refreshAccessToken,
} from '@/api/http'

export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType?: string
  userId: string
  /** 后端登录响应里带用户名；refresh 响应可能不带 */
  username?: string
  role: string
  permissions?: string[]
}

export interface UserInfoResponse {
  userId: string
  username: string
  role: string
  createdAt?: string
}

/** 注册接口返回用户信息，不返回 access/refresh token。 */
export interface RegisterResponse {
  userId?: string | null
  username?: string | null
  role?: string | null
  createdAt?: string | null
  name?: string | null
  email?: string | null
  phone?: string | null
  permissions?: string[] | null
}

/** 登录/刷新成功后落盘：token 与用户信息分开存，会话恢复时先读后者 */
function persist(tokens: TokenResponse): void {
  saveTokens(tokens.accessToken, tokens.refreshToken)
  const username = tokens.username ?? ''
  localStorage.setItem(USER_ID_KEY, tokens.userId)
  localStorage.setItem(
    USER_INFO_KEY,
    JSON.stringify({ userId: tokens.userId, username, role: tokens.role, permissions: tokens.permissions ?? [] }),
  )
}

/**
 * 智能体侧（8084）独立登录 —— 双 token 的第二份。
 * 两端 JWT 密钥不同、同账号 userId 也不同，业务 token 打智能体接口恒 401，
 * 所以必须另签一份，且单独存放（互不覆盖）。
 */
async function agentLogin(username: string, password: string): Promise<TokenResponse> {
  const tokens = await http.post<TokenResponse>(
    `${AGENT_AUTH_BASE}/login`,
    { username, password },
    { skipAuth: true, timeoutMs: 10000 },
  )
  if (!tokens.accessToken) throw new Error('智能体侧未返回 token')
  saveTokens(tokens.accessToken, tokens.refreshToken, 'agent')
  if (tokens.userId) localStorage.setItem(AGENT_USER_ID_KEY, tokens.userId)
  return tokens
}

/** 把业务侧的 userId 换成智能体侧的（会话归属表在 8084 那套库里，用它才对得上） */
function preferAgentUserId(agentUserId: string): void {
  if (!agentUserId) return
  localStorage.setItem(USER_ID_KEY, agentUserId)
  try {
    const info = JSON.parse(localStorage.getItem(USER_INFO_KEY) ?? '{}') as Record<string, unknown>
    localStorage.setItem(USER_INFO_KEY, JSON.stringify({ ...info, userId: agentUserId }))
  } catch {
    // 旧缓存结构异常就只更新 USER_ID_KEY
  }
}

export const authApi = {
  /**
   * 账号密码登录，成功即落盘 token。
   * 业务侧（8080）是硬依赖；智能体侧（8084）尽力而为 —— 它挂了不该让用户登不进来，
   * 但那样智能体功能会带着「智能体侧登录已失效」如实报错，而不是静默无响应。
   */
  async login(username: string, password: string): Promise<TokenResponse> {
    const tokens = await http.post<TokenResponse>(
      '/api/auth/login',
      { username, password },
      { skipAuth: true, timeoutMs: 10000 },
    )
    persist(tokens)

    // 逃生舱：显式关掉双通道时，业务 token 就是全部
    if (!DUAL_TOKEN) return tokens

    let agentUserId = ''
    try {
      agentUserId = (await agentLogin(username, password)).userId
      preferAgentUserId(agentUserId)
    } catch (e) {
      /**
       * 环境里没有独立的智能体认证端点（生产同源部署）⇒ 复用业务 token。
       * 这不是降级兜底：同源时业务签的那份，智能体本来就认。
       */
      saveTokens(tokens.accessToken, tokens.refreshToken, 'agent')
      console.warn('[auth] 智能体侧独立登录不可用，已复用业务 token：', e)
    }
    return { ...tokens, userId: agentUserId || tokens.userId }
  },

  /**
   * 保证智能体会话可用（页面刷新后用）。
   * 有未过期 agent token 直接过；否则用 agent refresh 续一次（没有密码可重登）。
   */
  async ensureAgentSession(): Promise<boolean> {
    if (hasAgentSession() && !isAccessTokenExpired('agent')) return true
    return refreshAccessToken('agent')
  },

  /** 当前智能体侧 userId（没有则会话未建立） */
  agentUserId: (): string => localStorage.getItem(AGENT_USER_ID_KEY) ?? '',

  /** 当前登录用户信息（需 token；用于刷新页面后校验会话是否还有效） */
  me: () => http.get<UserInfoResponse>('/api/auth/me', undefined, { timeoutMs: 10000 }),

  /** 用 refresh_token 换新 token。http 层已封装自动刷新，这里供会话恢复手动调用 */
  async refresh(): Promise<TokenResponse> {
    const refreshToken = localStorage.getItem('refresh_token')
    if (!refreshToken) throw new Error('无 refresh_token')
    const tokens = await http.post<TokenResponse>(
      '/api/auth/refresh',
      { refreshToken },
      { skipAuth: true, timeoutMs: 10000 },
    )
    persist(tokens)
    return tokens
  },

  /** 登出：JWT 无状态，后端不吊销，仅作契约协调；失败也要清本地 */
  async logout(): Promise<void> {
    // 两套后端各登一次，任一失败都不影响本地退出
    await Promise.allSettled([
      http.post<void>('/api/auth/logout', undefined, { timeoutMs: 5000 }),
      http.post<void>(`${AGENT_AUTH_BASE}/logout`, undefined, { timeoutMs: 5000 }),
    ])
  },

  /* ── 以下端点来自 kuaike 登录框架，**尚未对 agent-platform 实测** ──────────
     登录四件套（login / me / refresh / logout）是实测过的；注册与找回密码
     这一组沿用 kuaike 的契约（同平台家族）。若 agent-platform 没实装，
     调用会抛后端返回的可读错误并原样显示在表单上 —— 属于显式失败，
     不是静默失败。要启用前建议先 `curl` 一次确认。 */

  /** 发送手机号验证码（注册用） */
  sendPhoneCode: (phone: string) =>
    http.get<number>(`/api/users/captcha/phone/${phone}`, { type: 'register' }, { skipAuth: true, timeoutMs: 10000 }),

  /** 发送邮箱验证码（注册用） */
  sendEmailCode: (email: string) =>
    http.get<number>(`/api/users/captcha/email/${email}`, { type: 'register' }, { skipAuth: true, timeoutMs: 10000 }),

  /** 忘记密码：发送重置验证码（手机号或邮箱，统一账号框） */
  sendForgetCode: (principal: string) =>
    http.get<number>(`/api/users/captcha/forgot/${principal}`, { type: 'forgot' }, { skipAuth: true, timeoutMs: 10000 }),

  /** 公开注册；principal 传用户名/手机号/邮箱，手机号与邮箱需带 captcha */
  register: (
    principal: string,
    password: string,
    opts: { registerType?: string; captcha?: string } = {},
  ) =>
    http.post<RegisterResponse>(
      '/api/users/register',
      { principal, password, registerType: opts.registerType, captcha: opts.captcha },
      { skipAuth: true, timeoutMs: 10000 },
    ),

  /** 登录态修改自己的密码 */
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<void>('/api/users/password', { oldPassword, newPassword }),

  /** 未登录态重置密码（手机号/邮箱 + 验证码） */
  forgotPassword: (principal: string, newPassword: string, captcha: string) =>
    http.post<void>(
      '/api/users/password/forgot',
      { principal, password: newPassword, captcha },
      { skipAuth: true, timeoutMs: 10000 },
    ),
}
