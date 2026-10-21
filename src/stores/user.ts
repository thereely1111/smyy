/**
 * 登录态 store —— 从 kuaike 的 `stores/user.ts` 同步过来
 *
 * 保留原框架的全部机制（这是它值钱的地方，重写一遍必然丢细节）：
 *  - 模块级 restorePromise：会话恢复全局只做一次，路由守卫 await 同一个 Promise
 *  - 三级降级：/api/auth/me → refresh 换 token → 清空会话
 *  - mock / real 双模式：后端没起来也能演示
 *
 * 只改与企业无关的部分：
 *  - localStorage 前缀从 `textbook-platform:` 改成 `smyy:`
 *  - 公共体验账号的文案与管理员账号对齐 agent-platform 的播种账号
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

import { authApi } from '@/api/auth'
import { AUTH_MODE, ApiError, clearTokens, getAccessToken } from '@/api/http'

export interface UserInfo {
  username: string
  /** 昵称/显示名，注册时默认等于用户名 */
  name: string
  /** real 模式：后端用户 UUID */
  userId?: string
  /** real 模式：后端角色 */
  role?: string
  /** real 模式：统一认证返回的权限码 */
  permissions?: string[]
}

/** 本地注册的账号库（演示环境：明文存 localStorage，仅供 mock 模式） */
interface Account extends UserInfo {
  password: string
  phone?: string
  secretQ?: string
  secretA?: string
}

const USER_KEY = 'smyy:user'
const ACC_KEY = 'smyy:accounts'

/** 公共体验账号：内置固定账号，任何设备无需注册即可登录，保证人人可用 */
const PUBLIC_USERNAME = 'admin'
const PUBLIC_PASSWORD = '111111'
const PUBLIC_NAME = '体验账号'
const isPublicAccount = (u: string) => u.trim().toLowerCase() === PUBLIC_USERNAME.toLowerCase()

/**
 * 演示态公共体验账号（登录页「一键填入」用）：
 * real 模式对应 agent-platform 播种的默认管理员；mock 模式用内置账号。
 */
export const PUBLIC_ACCOUNT =
  AUTH_MODE === 'real'
    ? { username: 'admin', password: 'admin' }
    : { username: PUBLIC_USERNAME, password: PUBLIC_PASSWORD }

/** 会话恢复只做一次（main.ts 触发、路由守卫等待同一个 Promise） */
let restorePromise: Promise<void> | null = null

export const useUserStore = defineStore('user', () => {
  const user = ref<UserInfo | null>(loadUser())
  /** real 模式下的 access token（登录态的真实凭据） */
  const token = ref<string>(getAccessToken())

  function loadUser(): UserInfo | null {
    try {
      const raw = localStorage.getItem(USER_KEY)
      if (!raw) return null
      const u = JSON.parse(raw) as UserInfo
      if (u && u.username) {
        // real 模式只认后端账号（带 userId），避免本地演示账号被误判为已登录
        if (AUTH_MODE === 'real' && !u.userId) return null
        return u
      }
      return null
    } catch {
      return null
    }
  }

  function loadAccounts(): Account[] {
    try {
      const raw = localStorage.getItem(ACC_KEY)
      const arr = raw ? JSON.parse(raw) : []
      return Array.isArray(arr) ? arr : []
    } catch {
      return []
    }
  }

  function saveAccounts(list: Account[]): void {
    localStorage.setItem(ACC_KEY, JSON.stringify(list))
  }

  // real 模式：必须「有 access token 且有用户信息」才算已登录；mock 模式沿用本地账号
  const isLogged = computed(() => (AUTH_MODE === 'real' ? !!token.value && !!user.value : !!user.value))
  const displayName = computed(() => user.value?.name || user.value?.username || '')

  /** 本地账号登录（mock 模式） */
  function localLogin(username: string, password: string): string | null {
    const u = username.trim()
    if (!u) return '请输入账号'
    if (!password) return '请输入密码'
    if (isPublicAccount(u)) {
      if (password !== PUBLIC_PASSWORD) return '体验账号密码不正确'
      user.value = { username: PUBLIC_USERNAME, name: PUBLIC_NAME }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
      return null
    }
    const acc = loadAccounts().find((a) => a.username.toLowerCase() === u.toLowerCase())
    if (!acc) return '账号不存在，请先注册'
    if (acc.password !== password) return '密码不正确'
    user.value = { username: acc.username, name: acc.name }
    localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    return null
  }

  /**
   * 登录：成功返回 null，失败返回可直接展示的错误文案。
   * real 模式走后端 /api/auth/login（换 JWT 并落盘）；mock 模式走本地账号库。
   */
  async function login(username: string, password: string): Promise<string | null> {
    if (AUTH_MODE !== 'real') return localLogin(username, password)
    try {
      const tokens = await authApi.login(username.trim(), password)
      token.value = tokens.accessToken
      user.value = {
        username: tokens.username || username.trim(),
        name: tokens.username || username.trim(),
        userId: tokens.userId,
        role: tokens.role,
        permissions: tokens.permissions ?? [],
      }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
      return null
    } catch (e) {
      if (e instanceof ApiError) return e.message
      if (e instanceof Error && e.message) return e.message
      return '登录失败，请稍后重试'
    }
  }

  /**
   * 刷新页面后恢复会话：有 token 就用 /api/auth/me 校验，
   * 失败再尝试 refresh，都不行则清空登录态。
   *
   * 顺带把智能体会话续上（双 token）：两端 userId 不同，展示与本地映射
   * 一律用智能体侧那一份 —— 会话归属表在 8084 那套库里。
   */
  async function restore(): Promise<void> {
    if (AUTH_MODE !== 'real') return
    token.value = getAccessToken()
    if (!token.value) {
      user.value = null
      return
    }
    try {
      const info = await authApi.me()
      // 智能体 token 过期就静默续一次；续不上也不踢人，只是智能体功能会如实报错
      await authApi.ensureAgentSession()
      const cached = localStorage.getItem('user_info')
      const permissions = cached ? ((JSON.parse(cached).permissions as string[] | undefined) ?? []) : []
      user.value = {
        username: info.username,
        name: info.username,
        userId: authApi.agentUserId() || info.userId,
        role: info.role,
        permissions,
      }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
      return
    } catch {
      // access token 失效 → 尝试用 refresh_token 静默换新
    }
    try {
      const tokens = await authApi.refresh()
      token.value = tokens.accessToken
      const uname = tokens.username || user.value?.username || ''
      user.value = {
        username: uname,
        name: uname,
        userId: authApi.agentUserId() || tokens.userId,
        role: tokens.role,
        permissions: tokens.permissions ?? [],
      }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    } catch {
      clearTokens()
      token.value = ''
      user.value = null
      localStorage.removeItem(USER_KEY)
    }
  }

  /** 全局只恢复一次（main.ts 触发，路由守卫 await 同一个 Promise） */
  function ensureRestored(): Promise<void> {
    if (!restorePromise) {
      restorePromise = restore().catch(() => undefined)
    }
    return restorePromise
  }

  /** 注册：创建账号并自动登录；成功返回 null，失败返回错误文案 */
  async function register(
    username: string,
    password: string,
    opts: { phone?: string; secretQ?: string; secretA?: string; registerType?: string; captcha?: string } = {},
  ): Promise<string | null> {
    const isPhone = opts.registerType === 'phone'
    const isEmail = opts.registerType === 'email'
    const principal = username.trim()
    if (!principal) return '请输入账号'
    if (!password) return '请输入密码'

    if (isPhone) {
      if (!/^1\d{10}$/.test(principal)) return '请输入正确的 11 位手机号'
      if (!opts.captcha?.trim()) return '请输入验证码'
    } else if (isEmail) {
      if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(principal)) return '请输入正确的邮箱地址'
      if (!opts.captcha?.trim()) return '请输入验证码'
    } else {
      if (isPublicAccount(principal)) return '该账号为体验账号，请直接登录'
      // 与统一认证系统一致的用户名校验：5-20 位，支持字母（区分大小写）/数字/「_」，至少两种
      if (!/(?![0-9]+$)(?![a-zA-Z]+$)(?![_]+$)[\w]{5,20}$/g.test(principal)) {
        return '请输入 5-20 位的用户名，支持字母（区分大小写）、数字、「_」，至少包含两种'
      }
    }
    // 密码校验：8-20 位，字母/数字/「_」「.」，至少包含两种
    if (!/^(?![0-9]+$)(?![a-zA-Z]+$)(?![_.]+$)[\w.]{8,20}$/g.test(password)) {
      return '请输入 8-20 位字母、数字及符号（「.」和「_」），至少包含两种'
    }

    // mock 演示模式：沿用本地账号库（后端没启动也能演示）
    if (AUTH_MODE !== 'real') {
      if (isPhone || isEmail) return '演示模式暂不支持手机号 / 邮箱注册，请改用账密注册'
      if (opts.phone && !/^1\d{10}$/.test(opts.phone.trim())) return '请输入正确的 11 位手机号'
      const list = loadAccounts()
      if (list.some((a) => a.username.toLowerCase() === principal.toLowerCase())) return '该账号已注册，请直接登录'
      // 密保问题在 kuaike 的界面里就是隐藏预留字段（v-if="false"），
      // 原来的 store 却把它当必填，导致 mock 模式下注册永远失败。这里改为选填。
      const acc: Account = {
        username: principal,
        name: principal,
        password,
        phone: opts.phone?.trim() ?? '',
        secretQ: opts.secretQ?.trim() ?? '',
        secretA: opts.secretA?.trim() ?? '',
      }
      list.push(acc)
      saveAccounts(list)
      user.value = { username: acc.username, name: acc.name }
      localStorage.setItem(USER_KEY, JSON.stringify(user.value))
      return null
    }

    // real 模式：真实注册到后端，成功后自动登录
    try {
      const registerType = isPhone ? 'phone' : isEmail ? 'email' : 'username'
      await authApi.register(principal, password, { registerType, captcha: opts.captcha?.trim() })
      return await login(principal, password)
    } catch (e) {
      if (e instanceof ApiError) return e.message
      if (e instanceof Error && e.message) return e.message
      return '注册失败，请稍后重试'
    }
  }

  /** 退出登录：real 模式通知后端并清 token，mock 模式仅清本地账号 */
  async function logout(): Promise<void> {
    if (AUTH_MODE === 'real') {
      await authApi.logout()
      clearTokens()
      token.value = ''
    }
    user.value = null
    localStorage.removeItem(USER_KEY)
    // 退出后允许重新恢复会话（否则下次 ensureRestored 会拿到旧的已 resolve 的 Promise）
    restorePromise = null
  }

  /** 忘记密码：手机号/邮箱 + 验证码重置（未登录态，real） */
  async function resetByCode(principal: string, newPassword: string, captcha: string): Promise<string | null> {
    if (AUTH_MODE !== 'real') return '演示模式暂不支持密码重置'
    try {
      await authApi.forgotPassword(principal.trim(), newPassword, captcha.trim())
      return null
    } catch (e) {
      if (e instanceof ApiError) return e.message
      if (e instanceof Error && e.message) return e.message
      return '重置失败，请稍后重试'
    }
  }

  /** 修改密码（登录态）：只走后端 */
  async function changePassword(currentPassword: string, newPassword: string): Promise<string | null> {
    if (!user.value) return '未登录'
    try {
      await authApi.changePassword(currentPassword, newPassword)
      return null
    } catch (e) {
      return e instanceof ApiError ? e.message : '修改失败，请稍后重试'
    }
  }

  return {
    user,
    token,
    isLogged,
    displayName,
    login,
    restore,
    ensureRestored,
    logout,
    register,
    resetByCode,
    changePassword,
  }
})
