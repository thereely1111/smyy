/**
 * 统一 HTTP 客户端
 *
 * 设计沿用既有范式（跨项目验证过的「难写件」）：
 * - 自动携带 `Authorization: Bearer <access_token>`
 * - 本地预判 token 过期 → 先静默 refresh 再发业务请求
 * - 服务端 401 → refresh 后重放一次（并发请求共享同一次刷新，避免刷爆）
 * - refresh 也失败 → 清空本地会话，交由上层处理
 *
 * 双 token（2026-10-21）：智能体侧（8084）与业务侧（8080）是两套部署，
 * JWT 密钥不同、同账号的 userId 也不同 ⇒ 一个 token 打不通两边。
 * 按请求路径前缀判定通道，各带各的 token、各刷各的 refresh。
 *
 * ⚠️ 后端未配置 CORS，浏览器直连 :8080 会被跨域拦死。
 *    所有请求必须经 Vite 代理（vite.config.ts 的 `/api` `/agui`）以同源方式发出。
 */

export type AuthMode = 'mock' | 'real'

const rawMode = import.meta.env.VITE_AUTH_MODE as string | undefined
export const AUTH_MODE: AuthMode = rawMode === 'mock' ? 'mock' : 'real'

/** 业务侧通道（8080）/ 智能体侧通道（8084） */
export type TokenChannel = 'biz' | 'agent'

export const TOKEN_KEY = 'access_token'
export const REFRESH_TOKEN_KEY = 'refresh_token'
export const USER_ID_KEY = 'user_id'
export const USER_INFO_KEY = 'user_info'

/** 智能体侧独立存一份，与业务侧互不覆盖 */
export const AGENT_TOKEN_KEY = 'agent_access_token'
export const AGENT_REFRESH_TOKEN_KEY = 'agent_refresh_token'
export const AGENT_USER_ID_KEY = 'agent_user_id'

/**
 * 走智能体后端的路径前缀 —— 必须与 vite.config.ts 的 proxyOptions 逐条对齐，
 * 漏一条就会带着业务 token 打过去、恒 401。
 */
const AGENT_PATH_PREFIXES = ['/agui', '/api/agents', '/api/agui-events', '/api/notify'] as const

/**
 * 智能体侧认证端点前缀。
 * 统一走 `/agent-auth`（dev 由 vite proxy、生产由 nginx 重写到智能体的 `/api/auth`）。
 * 目标环境没配这条规则时（比如生产同源部署），请求会 404 或被 SPA 兜底成 HTML，
 * 登录编排据此自动回落到「复用业务 token」—— 所以不需要按 dev/prod 分支，也不需要额外构建参数。
 * `VITE_AGENT_AUTH_BASE` 可覆盖。
 */
export const AGENT_AUTH_BASE =
  (import.meta.env.VITE_AGENT_AUTH_BASE as string | undefined) || '/agent-auth'

/**
 * 是否启用独立的 agent token 通道。
 * 默认开：登录时尽力给智能体侧单签一份，签不出来就复用业务 token。
 * 这样「两套后端」与「同源一套后端」两种拓扑都不用改配置。
 * `VITE_DUAL_TOKEN=0` 是逃生舱（强制全走业务 token）。
 */
export const DUAL_TOKEN = (import.meta.env.VITE_DUAL_TOKEN as string | undefined) !== '0'

/** 该通道实际使用的存储键（DUAL_TOKEN 关闭时全部指向业务键） */
const keyOf = (channel: TokenChannel): string =>
  channel === 'agent' && DUAL_TOKEN ? AGENT_TOKEN_KEY : TOKEN_KEY
const refreshKeyOf = (channel: TokenChannel): string =>
  channel === 'agent' && DUAL_TOKEN ? AGENT_REFRESH_TOKEN_KEY : REFRESH_TOKEN_KEY

/** 按路径判定该带哪套 token */
export function channelOf(path: string): TokenChannel {
  const hit = AGENT_PATH_PREFIXES.some(
    (p) => path === p || path.startsWith(`${p}/`) || path.startsWith(`${p}?`),
  )
  return hit ? 'agent' : 'biz'
}


/** 非 2xx 响应统一抛出的错误，`message` 已尽量取后端可读文案 */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

// ── Token 读写 ─────────────────────────────────────────────────────────────
export const getAccessToken = (channel: TokenChannel = 'biz'): string =>
  localStorage.getItem(keyOf(channel)) ?? ''
export const getRefreshToken = (channel: TokenChannel = 'biz'): string =>
  localStorage.getItem(refreshKeyOf(channel)) ?? ''

export function saveTokens(accessToken: string, refreshToken: string, channel: TokenChannel = 'biz'): void {
  localStorage.setItem(keyOf(channel), accessToken)
  if (refreshToken) localStorage.setItem(refreshKeyOf(channel), refreshToken)
}

/** 清智能体侧的 token（不影响业务侧登录态） */
export function clearAgentTokens(): void {
  localStorage.removeItem(AGENT_TOKEN_KEY)
  localStorage.removeItem(AGENT_REFRESH_TOKEN_KEY)
  localStorage.removeItem(AGENT_USER_ID_KEY)
}

export function clearTokens(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(REFRESH_TOKEN_KEY)
  localStorage.removeItem(USER_ID_KEY)
  localStorage.removeItem(USER_INFO_KEY)
  clearAgentTokens()
}

/** 是否已建立智能体会话（有 agent token 即算） */
export const hasAgentSession = (): boolean => !!getAccessToken('agent')

/** 解析 JWT 的 exp（秒）为毫秒时间戳；无法解析返回 null（交给服务端 401 兜底） */
export function getAccessTokenExpiryMillis(channel: TokenChannel = 'biz'): number | null {
  const token = getAccessToken(channel)
  if (!token) return null
  try {
    const payload = JSON.parse(atob(token.split('.')[1])) as { exp?: number }
    if (typeof payload?.exp === 'number') return payload.exp * 1000
  } catch {
    // token 结构异常 —— 按未知处理，交给 401 兜底
  }
  return null
}

/** 已存 token 且已过期（无 token 或无法解析时返回 false） */
export function isAccessTokenExpired(channel: TokenChannel = 'biz'): boolean {
  const exp = getAccessTokenExpiryMillis(channel)
  if (exp == null) return false
  return Date.now() >= exp
}

// ── 401 / 过期自动刷新 ──────────────────────────────────────────────────────
/** 正在进行的 refresh（按通道去重），多个 401 共享同一次刷新 */
const refreshInFlight: Record<TokenChannel, Promise<boolean> | null> = { biz: null, agent: null }

/** 刷新端点：业务侧走 `/api/auth/refresh`，智能体侧走它自己的认证前缀 */
const REFRESH_PATH: Record<TokenChannel, string> = {
  biz: '/api/auth/refresh',
  agent: `${AGENT_AUTH_BASE}/refresh`,
}

async function doRefresh(channel: TokenChannel): Promise<boolean> {
  const refreshToken = getRefreshToken(channel)
  if (!refreshToken) return false

  /**
   * 智能体通道先打自己的端点；环境里没有这条路由（生产同源部署）时回落到业务端点。
   * 同源场景下 agent 的 refresh_token 本来就是业务那份的副本，打业务端点必然通。
   */
  const paths = channel === 'agent' ? [REFRESH_PATH.agent, REFRESH_PATH.biz] : [REFRESH_PATH.biz]

  for (const path of paths) {
    try {
      const tokens = (await rawFetch(path, {
        method: 'POST',
        body: { refreshToken },
        skipAuth: true,
        timeoutMs: 10000,
      }).then((res) => {
        if (!res.ok) throw new ApiError(res.status, 'refresh 失败')
        return res.json()
      })) as {
        accessToken: string
        refreshToken?: string
        userId?: string
        username?: string
        role?: string
      }
      if (!tokens.accessToken) continue
      saveTokens(tokens.accessToken, tokens.refreshToken ?? refreshToken, channel)

      // 智能体侧的 userId 与业务侧可能不同，单独存，供展示与会话归属对齐
      if (channel === 'agent') {
        if (tokens.userId) localStorage.setItem(AGENT_USER_ID_KEY, tokens.userId)
        return true
      }
      if (tokens.userId) {
        localStorage.setItem(USER_ID_KEY, tokens.userId)
        localStorage.setItem(
          USER_INFO_KEY,
          JSON.stringify({
            userId: tokens.userId,
            username: tokens.username ?? '',
            role: tokens.role ?? '',
          }),
        )
      }
      return true
    } catch {
      // 换下一个候选端点
    }
  }
  return false
}

/** 用 refresh_token 换新 access token；失败返回 false（调用方负责清会话） */
export function refreshAccessToken(channel: TokenChannel = 'biz'): Promise<boolean> {
  const inflight = refreshInFlight[channel]
  if (inflight) return inflight
  const task = doRefresh(channel).finally(() => {
    refreshInFlight[channel] = null
  })
  refreshInFlight[channel] = task
  return task
}

/**
 * refresh 失败时清空本地 token。
 * ⚠️ 只清**当前通道**：智能体侧失效不该把用户踢出业务登录（否则整站掉线）。
 */
export async function ensureFreshToken(channel: TokenChannel = 'biz'): Promise<boolean> {
  const ok = await refreshAccessToken(channel)
  if (!ok) {
    if (channel === 'agent') clearAgentTokens()
    else clearTokens()
  }
  return ok
}

/** 通道对应的 401 文案：两者要让用户知道是哪一侧掉了 */
function unauthorized(channel: TokenChannel): ApiError {
  return channel === 'agent' && DUAL_TOKEN
    ? new ApiError(401, '智能体侧登录已失效，请重新登录')
    : new ApiError(401, '登录已过期，请重新登录')
}

// ── 请求核心 ────────────────────────────────────────────────────────────────
export interface RequestOptions {
  method?: string
  body?: unknown
  params?: Record<string, string>
  /** 超时毫秒数；不传则一直等（留给 SSE 之类长连接） */
  timeoutMs?: number
  /** 跳过 Authorization 头（登录/刷新等公开端点） */
  skipAuth?: boolean
  signal?: AbortSignal
  /** multipart 上传：传 FormData 时不要设 Content-Type，浏览器自己带 boundary */
  formData?: FormData
}

function buildHeaders(path: string, hasBody: boolean, skipAuth?: boolean): Record<string, string> {
  const headers: Record<string, string> = {}
  if (!skipAuth) {
    const token = getAccessToken(channelOf(path))
    if (token) headers.Authorization = `Bearer ${token}`
  }
  if (hasBody) headers['Content-Type'] = 'application/json'
  return headers
}

/** 后端统一错误体为 { code, message }，同时兼容 FastAPI 风格的 detail */
async function extractErrorMessage(res: Response): Promise<string> {
  const text = await res.text()
  try {
    const json = JSON.parse(text) as { message?: unknown; detail?: unknown }
    if (typeof json.message === 'string') return json.message
    if (typeof json.detail === 'string') return json.detail
  } catch {
    // 非 JSON（例如网关 HTML 错误页），回落到原文
  }
  return text || res.statusText || `请求失败（HTTP ${res.status}）`
}

async function rawFetch(path: string, options: RequestOptions = {}): Promise<Response> {
  const { method = 'GET', body, params, skipAuth, timeoutMs, signal, formData } = options
  const url = new URL(path, window.location.origin)
  if (params) {
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v))
  }

  const controller = new AbortController()
  let timedOut = false
  const timer = timeoutMs
    ? window.setTimeout(() => {
        timedOut = true
        controller.abort()
      }, timeoutMs)
    : undefined
  if (signal) {
    if (signal.aborted) controller.abort()
    else signal.addEventListener('abort', () => controller.abort(), { once: true })
  }

  try {
    return await fetch(url.toString(), {
      method,
      // multipart 时不设 Content-Type，由浏览器生成 boundary
      headers: formData
        ? skipAuth
          ? {}
          : buildHeaders(path, false, skipAuth)
        : buildHeaders(path, body !== undefined, skipAuth),
      body: formData ?? (body !== undefined ? JSON.stringify(body) : undefined),
      signal: controller.signal,
    })
  } catch (e) {
    // 调用方自己取消的不做包装，原样抛出
    if (!timedOut && e instanceof DOMException && e.name === 'AbortError') throw e
    throw timedOut
      ? new ApiError(408, '请求超时，请确认后端已启动')
      : new ApiError(0, '无法连接后端服务，请确认后端已启动（默认 :8080）')
  } finally {
    if (timer) window.clearTimeout(timer)
  }
}

/** 认证端点不参与刷新重试（它们本身就是用来换取 token 的） */
const isAuthPath = (path: string): boolean =>
  path.startsWith('/api/auth/') || path.startsWith('/agent-auth/')

/**
 * 发请求 + 认证兜底：按路径选通道 → 过期预刷新 → 401 刷新后重放一次。
 * request / requestText / httpStream 共用这一份，别再各写一遍。
 */
async function fetchWithAuth(path: string, options: RequestOptions): Promise<Response> {
  const channel = channelOf(path)
  const needsAuth = !options.skipAuth && !isAuthPath(path)

  if (needsAuth && isAccessTokenExpired(channel)) {
    if (!(await ensureFreshToken(channel))) throw unauthorized(channel)
  }
  let res = await rawFetch(path, options)
  if (res.status === 401 && needsAuth) {
    if (!(await ensureFreshToken(channel))) throw unauthorized(channel)
    res = await rawFetch(path, options)
  }
  return res
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const res = await fetchWithAuth(path, options)
  if (!res.ok) throw new ApiError(res.status, await extractErrorMessage(res))
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

/** POST 但期望返回文本（如产物内容接口可能直接回纯文本） */
async function requestText(path: string, options: RequestOptions = {}): Promise<string> {
  const res = await fetchWithAuth(path, options)
  if (!res.ok) throw new ApiError(res.status, await extractErrorMessage(res))
  return res.text()
}

/**
 * SSE / 流式请求：返回原始 Response，由调用方自行读流。
 * 与普通请求的区别：不解析 JSON、不设超时（长连接）；仍自动带 Bearer，并在 401 时静默刷新一次。
 * 用于 AG-UI 的 `POST /agui/run/{agentId}`（后端对该路径同样强制 Bearer）。
 */
export async function httpStream(
  path: string,
  body: unknown,
  signal?: AbortSignal,
): Promise<Response> {
  const res = await fetchWithAuth(path, { method: 'POST', body, signal })
  if (!res.ok) throw new ApiError(res.status, await extractErrorMessage(res))
  return res
}

/**
 * SSE / 流式 GET 请求：返回原始 Response，由调用方自行读流。
 * 用于订阅后端 `GET /api/notify/stream`（user 级常驻通知流）。
 * 行为对齐 httpStream：自动带 Bearer、401 静默刷新一次、不设超时（长连接）。
 */
export async function httpStreamGet(
  path: string,
  signal?: AbortSignal,
): Promise<Response> {
  const res = await fetchWithAuth(path, { method: 'GET', signal })
  if (!res.ok) throw new ApiError(res.status, await extractErrorMessage(res))
  return res
}

export const http = {
  get: <T>(path: string, params?: Record<string, string>, options?: Omit<RequestOptions, 'method' | 'body' | 'params' | 'formData'>) =>
    request<T>(path, { ...options, method: 'GET', params }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body' | 'formData'>) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body' | 'formData'>) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, 'method' | 'body' | 'formData'>) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T = void>(path: string, options?: Omit<RequestOptions, 'method' | 'body' | 'formData'>) =>
    request<T>(path, { ...options, method: 'DELETE' }),
  /** multipart 上传 */
  upload: <T>(path: string, formData: FormData, options?: Omit<RequestOptions, 'method' | 'body' | 'formData'>) =>
    request<T>(path, { ...options, method: 'POST', formData }),
  /** 期望纯文本响应 */
  getText: (path: string, params?: Record<string, string>, options?: Omit<RequestOptions, 'method' | 'body' | 'params' | 'formData'>) =>
    requestText(path, { ...options, method: 'GET', params }),
}

export { rawFetch }
