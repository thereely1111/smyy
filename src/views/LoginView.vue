<!--
  登录页 —— 从 kuaike 的 `views/LoginView.vue` 同步过来

  框架沿用 kuaike 原版（登录 / 注册 / 找回三态、注册三种方式、验证码倒计时、
  回跳 redirect）；本地改了三处：
    1. 主题：kuaike 的靛紫 #6366f1 换成数模演易的青绿 --c-primary #12a594
    2. 文案：品牌与左栏叙事换成数模演易口径，阶段流用原型的六步
    3. 移除「体验账号」展示与一键填入（公网页面不暴露账号口令）
-->
<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useUserStore } from '@/stores/user'
import { authApi } from '@/api/auth'
import { AUTH_MODE } from '@/api/http'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const mode = ref<'login' | 'register' | 'forgot'>(
  route.query.mode === 'forgot' ? 'forgot' : route.query.mode === 'register' ? 'register' : 'login',
)
const username = ref('')
const password = ref('')
const confirm = ref('')
const err = ref('')
const usernameErr = ref('')
const passwordErr = ref('')
const ok = ref('')
const showPwd = ref(false)
const submitting = ref(false)

if (mode.value === 'forgot' && userStore.user?.username) username.value = userStore.user.username

/**
 * 左栏阶段流 —— 用原型工作台自己的六步（STEPS），
 * 不是 kuaike 那套「五阶段」，所以这里是六项，版式按六项排。
 */
const stageFlow = [
  { num: '壹', name: '壹 | 全局分析', desc: '理清题目背景与目标，拆解约束条件与已知数据。' },
  { num: '贰', name: '贰 | 模型假设', desc: '化繁为简，提出合理假设，完成核心变量的符号说明。' },
  { num: '叁', name: '叁 | 模型建立', desc: '选择恰当数学工具（优化、统计、微分方程等）构建核心框架。' },
  { num: '肆', name: '肆 | 模型求解', desc: '编写代码或调用算法，跑出核心数据与基础结果。' },
  { num: '伍', name: '伍 | 检验分析', desc: '进行误差分析、灵敏度检验，验证模型稳健性与合理性。' },
  { num: '陆', name: '陆 | 论文撰写', desc: '梳理图表与逻辑，产出结构完整、符合规范的学术论文。' },
]

// 注册方式（账密 / 手机号 / 邮箱）
const regType = ref<'account' | 'phone' | 'email'>('account')
const phone = ref('')
const email = ref('')
const code = ref('')
const codeBtnText = ref('获取验证码')
const codeSending = ref(false)
let codeTimer: ReturnType<typeof setInterval> | null = null

function switchMode(m: 'login' | 'register' | 'forgot'): void {
  mode.value = m
  err.value = ''
  ok.value = ''
}

function startCountdown(): void {
  if (codeTimer) clearInterval(codeTimer)
  let left = 60
  codeBtnText.value = `${left}s`
  codeTimer = setInterval(() => {
    left -= 1
    codeBtnText.value = left > 0 ? `${left}s` : '获取验证码'
    if (left <= 0 && codeTimer) {
      clearInterval(codeTimer)
      codeTimer = null
    }
  }, 1000)
}

/** 发送验证码：忘记密码走统一账号框，注册按当前 tab 取 principal */
async function sendCode(): Promise<void> {
  if (codeSending.value) return
  const isEmailTab = regType.value === 'email'
  const principal = mode.value === 'forgot' ? username.value.trim() : isEmailTab ? email.value.trim() : phone.value.trim()
  const valid =
    mode.value === 'forgot'
      ? /^(1\d{10}|[^\s@]+@[^\s@]+\.[^\s@]+)$/.test(principal)
      : isEmailTab
        ? /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(principal)
        : /^1\d{10}$/.test(principal)
  if (!valid) {
    err.value = isEmailTab ? '请输入正确的邮箱地址' : '请输入正确的手机号或邮箱'
    return
  }
  err.value = ''
  codeSending.value = true
  try {
    if (mode.value === 'forgot') await authApi.sendForgetCode(principal)
    else if (isEmailTab) await authApi.sendEmailCode(principal)
    else await authApi.sendPhoneCode(principal)
    startCountdown()
  } catch (e) {
    err.value = e instanceof Error ? e.message : '验证码发送失败，请稍后重试'
  } finally {
    codeSending.value = false
  }
}

async function submit(): Promise<void> {
  if (submitting.value) return
  err.value = ''
  ok.value = ''
  usernameErr.value = ''
  passwordErr.value = ''
  submitting.value = true
  try {
    if (mode.value === 'forgot') {
      if (password.value !== confirm.value) {
        err.value = '两次输入的新密码不一致'
        return
      }
      if (!code.value) {
        err.value = '请输入验证码'
        return
      }
      const e = await userStore.resetByCode(username.value, password.value, code.value)
      if (e) {
        err.value = e
        return
      }
      ok.value = '密码已重置，请用新密码登录'
      mode.value = 'login'
      password.value = ''
      confirm.value = ''
      code.value = ''
      return
    }

    if (mode.value === 'login') {
      const e = await userStore.login(username.value, password.value)
      if (e) {
        err.value = e
        return
      }
    } else {
      if (password.value !== confirm.value) {
        err.value = '两次输入的密码不一致'
        return
      }
      const isCaptchaReg = regType.value === 'phone' || regType.value === 'email'
      const principal =
        regType.value === 'email' ? email.value : regType.value === 'phone' ? phone.value : username.value
      const e = await userStore.register(
        principal,
        password.value,
        isCaptchaReg ? { registerType: regType.value, captcha: code.value } : {},
      )
      if (e) {
        // 账密注册：账号/密码格式错误分别落到对应输入框下方，其余走底部
        if (!isCaptchaReg && (e.includes('用户名') || e.includes('5-20位'))) usernameErr.value = e
        else if (!isCaptchaReg && (e.includes('密码') || e.includes('8-20位'))) passwordErr.value = e
        else err.value = e
        return
      }
    }
  } finally {
    submitting.value = false
  }
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
  void router.push(redirect && redirect !== '/case-admin' ? redirect : '/')
}
</script>

<template>
  <div class="auth">
    <!-- 品牌叙事面板 -->
    <aside class="pitch">
      <div class="pitch-grid"></div>
      <div class="pitch-glow glow-a"></div>
      <div class="pitch-glow glow-b"></div>

      <div class="pitch-brand" @click="router.push('/')">
        <span class="pitch-logo">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#12a594" stroke-width="2.2" stroke-linecap="round">
            <path d="M4 19V6M4 19h16M8 19v-6M12 19V9M16 19v-8M20 19v-4" />
          </svg>
        </span>
        <span class="pitch-name">数模演易</span>
      </div>

      <div class="pitch-body">
        <h1 class="pitch-title">把历年真题，变成你的练习场</h1>
        <p class="pitch-sub">
          读懂、拆解、求解、成文，AI 一路陪你走完。每一步的取舍仍在关键闸门由你拍板，
          它只负责把该算的算完、该写的写好。
        </p>

        <div class="pitch-flow">
          <div v-for="s in stageFlow" :key="s.num" class="pf-row">
            <span class="pf-num">{{ s.num }}</span>
            <div class="pf-meta">
              <div class="pf-name">{{ s.name }}</div>
              <div class="pf-desc">{{ s.desc }}</div>
            </div>
          </div>
        </div>
      </div>

      <div class="pitch-foot">你负责判断和决策，它负责推演和成文。</div>
    </aside>

    <!-- 表单面板 -->
    <main class="panel">
      <div class="card">
        <div class="card-head">
          <h2 class="card-title" :key="mode">
            <span v-if="mode === 'login'">欢迎回来</span>
            <span v-else-if="mode === 'register'">开始你的演易</span>
            <span v-else>重置密码</span>
          </h2>
          <p class="card-intro">
            <span v-if="mode === 'login'">登录个人账号，继续未完的那道题</span>
            <span v-else-if="mode === 'register'">注册一个账号，把「算」交给它</span>
            <span v-else>输入账号与新密码，即可恢复登录</span>
          </p>
        </div>

        <div v-if="mode !== 'forgot'" class="slider">
          <span class="slider-track" :style="{ transform: mode === 'login' ? 'translateX(0)' : 'translateX(100%)' }"></span>
          <button class="slider-btn" :class="{ on: mode === 'login' }" @click="switchMode('login')">登录</button>
          <button class="slider-btn" :class="{ on: mode === 'register' }" @click="switchMode('register')">注册</button>
        </div>

        <div v-if="mode === 'register'" class="reg-tabs">
          <button type="button" class="reg-tab" :class="{ on: regType === 'account' }" @click="regType = 'account'">账密注册</button>
          <button type="button" class="reg-tab" :class="{ on: regType === 'phone' }" @click="regType = 'phone'">手机号注册</button>
          <button type="button" class="reg-tab" :class="{ on: regType === 'email' }" @click="regType = 'email'">邮箱注册</button>
        </div>

        <div class="grid-form">
          <label v-if="mode !== 'register' || regType === 'account'" class="field">
            <span class="fl">账号</span>
            <input v-model="username" class="input" type="text" placeholder="请输入账号" @keyup.enter="submit" />
            <span v-if="usernameErr" class="msg err">{{ usernameErr }}</span>
          </label>

          <label v-if="mode === 'register' && regType === 'phone'" class="field">
            <span class="fl">手机号</span>
            <input v-model="phone" class="input" type="tel" placeholder="11 位手机号" @keyup.enter="submit" />
          </label>

          <label v-if="mode === 'register' && regType === 'email'" class="field">
            <span class="fl">邮箱</span>
            <input v-model="email" class="input" type="email" placeholder="you@example.com" @keyup.enter="submit" />
          </label>

          <label v-if="(mode === 'register' && (regType === 'phone' || regType === 'email')) || mode === 'forgot'" class="field">
            <span class="fl">验证码</span>
            <div class="pwd-wrap">
              <input v-model="code" class="input" placeholder="6 位验证码" @keyup.enter="submit" />
              <button type="button" class="code-btn" :disabled="codeBtnText !== '获取验证码' || codeSending" @click="sendCode">
                {{ codeSending ? '发送中…' : codeBtnText }}
              </button>
            </div>
          </label>

          <label class="field">
            <span class="fl">密码</span>
            <div class="pwd-wrap">
              <input
                v-model="password"
                class="input"
                :type="showPwd ? 'text' : 'password'"
                :placeholder="mode === 'forgot' ? '设置一个新密码' : mode === 'register' ? '请设置登录密码' : '请输入登录密码'"
                @keyup.enter="submit"
              />
              <span class="pwd-eye" @click="showPwd = !showPwd" role="button" aria-label="显示或隐藏密码">
                <svg v-if="!showPwd" viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M1 12s4-6.5 11-6.5S23 12 23 12s-4 6.5-11 6.5S1 12 1 12Z"/><circle cx="12" cy="12" r="2.8"/></svg>
                <svg v-else viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M1 12s4-6.5 11-6.5S23 12 23 12s-4 6.5-11 6.5S1 12 1 12Z"/><circle cx="12" cy="12" r="2.8"/><line x1="3" y1="3" x2="21" y2="21"/></svg>
              </span>
            </div>
            <span v-if="passwordErr" class="msg err">{{ passwordErr }}</span>
          </label>

          <label v-if="mode !== 'login'" class="field">
            <span class="fl">{{ mode === 'register' ? '确认密码' : '确认新密码' }}</span>
            <input v-model="confirm" class="input" type="password" placeholder="再次输入" @keyup.enter="submit" />
          </label>

          <p v-if="err" class="msg err">{{ err }}</p>
          <p v-else-if="ok" class="msg ok">{{ ok }}</p>
        </div>

        <button class="btn-submit" :disabled="submitting" @click="submit">
          {{ submitting ? '请稍候…' : mode === 'login' ? '登 录' : mode === 'register' ? '注册并进入' : '重置密码' }}
        </button>

        <p v-if="mode === 'login' && AUTH_MODE === 'mock'" class="mode-note">当前为本地演示模式，账号存在浏览器里</p>

        <div class="link-row">
          <template v-if="mode === 'login'">
            <a @click="switchMode('forgot')">忘记密码？</a>
          </template>
          <template v-else>
            <a @click="switchMode('login')">← 返回登录</a>
          </template>
        </div>
      </div>

      <p class="panel-legal">数模演易 · AI 驱动的数学建模协作工作台</p>
    </main>
  </div>
</template>

<style scoped lang="scss">
/* 主题：kuaike 的靛紫 #6366f1 → 数模演易青绿 --c-primary #12a594
   色值全部走 styles/tokens.scss 的令牌，不再写死靛紫，避免半成品换皮 */
.auth {
  height: 100vh;
  overflow: hidden;
  display: flex;
  align-items: stretch;
  background: var(--c-bg);
  font-family: var(--font-sans);
}
.auth * { box-sizing: border-box; }

/* ── 左 · 品牌叙事面板 ── */
.pitch {
  position: relative;
  flex: 1 1 52%;
  max-width: 720px;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px 60px;
  color: var(--c-primary-ink);
  background:
    radial-gradient(120% 95% at 100% 0%, rgba(46, 196, 176, 0.22), rgba(46, 196, 176, 0) 55%),
    radial-gradient(130% 100% at 0% 100%, rgba(223, 244, 241, 0.6), rgba(223, 244, 241, 0) 58%),
    linear-gradient(150deg, #effaf8 0%, #dff4f1 52%, #b8e8e2 100%);
  overflow: hidden;
}
.pitch-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(11, 46, 54, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(11, 46, 54, 0.07) 1px, transparent 1px);
  background-size: 56px 56px;
  mask-image: radial-gradient(90% 90% at 30% 20%, #000 30%, transparent 90%);
  -webkit-mask-image: radial-gradient(90% 90% at 30% 20%, #000 30%, transparent 90%);
  pointer-events: none;
}
.pitch-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(60px);
  pointer-events: none;
}
.glow-a { width: 340px; height: 340px; right: -120px; top: -80px; background: rgba(46, 196, 176, 0.3); }
.glow-b { width: 300px; height: 300px; left: -110px; bottom: -90px; background: rgba(18, 165, 148, 0.22); }

.pitch-brand { position: relative; display: flex; align-items: center; gap: 12px; cursor: pointer; width: fit-content; }
.pitch-logo {
  flex-shrink: 0;
  width: 40px; height: 40px;
  line-height: 0;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 12px;
  background: #ffffff;
  box-shadow: 0 6px 16px -6px rgba(18, 165, 148, 0.3);
}
.pitch-name { font-size: 19px; font-weight: 800; letter-spacing: 1px; color: var(--c-primary-ink); }

.pitch-body { position: relative; max-width: 460px; }
.pitch-title { font-size: 32px; line-height: 1.3; font-weight: 800; color: var(--c-ink); letter-spacing: 1px; }
.pitch-sub { margin: 16px 0 26px; font-size: 14px; line-height: 1.9; color: rgba(11, 46, 54, 0.78); }

.pitch-flow { display: flex; flex-direction: column; gap: 8px; }
.pf-row {
  display: flex; align-items: center; gap: 14px;
  padding: 11px 16px;
  border-radius: var(--radius);
  background: rgba(255, 255, 255, 0.62);
  border: 1px solid rgba(18, 165, 148, 0.24);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  transition: background 0.18s, transform 0.18s;
}
.pf-row:hover { background: rgba(255, 255, 255, 0.92); transform: translateX(3px); }
.pf-num {
  flex-shrink: 0;
  width: 28px; height: 28px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 9px;
  background: rgba(18, 165, 148, 0.14);
  font-size: 12.5px; font-weight: 800; color: var(--c-primary-ink);
}
.pf-name { font-size: 14px; font-weight: 700; color: var(--c-ink); }
.pf-desc { font-size: 11.5px; color: rgba(11, 46, 54, 0.66); margin-top: 2px; }
.pitch-foot { position: relative; font-size: 12px; letter-spacing: 2px; color: rgba(11, 46, 54, 0.55); }

/* ── 右 · 表单面板 ── */
.panel {
  flex: 1 1 48%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 40px 24px 24px;
  background: radial-gradient(1000px 520px at 50% 6%, rgba(223, 244, 241, 0.85), rgba(223, 244, 241, 0) 62%), #fbfefe;
}
.card {
  width: 400px;
  max-width: 92vw;
  max-height: calc(100vh - 88px);
  display: flex; flex-direction: column;
  background: var(--c-surface);
  border: 1px solid var(--c-line);
  border-radius: 20px;
  padding: 34px 36px 28px;
  box-shadow: 0 18px 44px -20px rgba(11, 46, 54, 0.28);
  animation: cardUp 0.55s cubic-bezier(0.22, 0.7, 0.25, 1);
}
.grid-form {
  min-height: 0;
  flex: 1 1 auto;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-width: none;
}
.grid-form::-webkit-scrollbar { display: none; }
@keyframes cardUp {
  from { opacity: 0; transform: translateY(22px); }
  to { opacity: 1; transform: translateY(0); }
}

.card-head { margin-bottom: 22px; }
.card-title { font-size: 27px; font-weight: 800; line-height: 1.3; color: var(--c-ink); animation: fadeIn 0.3s ease; }
.card-intro { margin-top: 8px; color: var(--c-muted); font-size: 13.5px; animation: fadeIn 0.3s ease; }
@keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }

.slider {
  position: relative;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  background: var(--c-primary-soft);
  border-radius: 12px;
  padding: 4px;
  margin-bottom: 22px;
}
.slider-track {
  position: absolute;
  top: 4px; bottom: 4px; left: 4px;
  width: calc((100% - 8px) / 2);
  border-radius: 9px;
  background: #ffffff;
  box-shadow: 0 3px 10px -4px rgba(18, 165, 148, 0.35);
  transition: transform 0.28s cubic-bezier(0.22, 0.7, 0.25, 1);
}
.slider-btn {
  position: relative; z-index: 1;
  border: none; background: transparent;
  padding: 9px 0;
  font-size: 13.5px; font-weight: 700; font-family: var(--font-sans);
  color: var(--c-muted);
  cursor: pointer;
  border-radius: 9px;
  transition: color 0.18s;
}
.slider-btn.on { color: var(--c-primary-ink); }

.reg-tabs {
  display: flex; align-items: center; gap: 4px;
  margin: -2px 0 16px; padding: 4px;
  background: var(--c-primary-soft);
  border: 1px solid #b8e8e2;
  border-radius: 12px;
}
.reg-tab {
  flex: 1; height: 34px;
  border: none; border-radius: 9px;
  background: transparent;
  color: var(--c-ink-2);
  font-size: 13px; font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.reg-tab.on {
  background: linear-gradient(135deg, var(--c-primary), var(--c-primary-ink));
  color: #fff;
  box-shadow: 0 6px 14px -8px rgba(18, 165, 148, 0.6);
}
.code-btn {
  position: absolute; right: 10px; top: 50%;
  transform: translateY(-50%);
  border: none; background: transparent;
  color: var(--c-primary-ink);
  font-size: 13px; font-weight: 600;
  cursor: pointer; padding: 0;
}
.code-btn:disabled { color: var(--c-muted); cursor: not-allowed; }

.field { display: block; margin-bottom: 16px; }
.fl { display: block; font-size: 12px; font-weight: 700; letter-spacing: 1px; color: var(--c-ink-2); margin-bottom: 8px; }
.input {
  width: 100%;
  padding: 13px 14px;
  border: 1px solid var(--c-line-strong);
  border-radius: var(--radius);
  background: #ffffff;
  font-size: 14.5px;
  color: var(--c-ink);
  outline: none;
  transition: border-color 0.16s, box-shadow 0.16s;
}
.input::placeholder { color: #a9bec2; }
.input:hover { border-color: #92ded5; }
.input:focus { border-color: var(--c-primary); box-shadow: 0 0 0 3px rgba(18, 165, 148, 0.16); }

.pwd-wrap { position: relative; }
.pwd-wrap .input { padding-right: 40px; }
.pwd-eye {
  position: absolute; right: 13px; top: 50%;
  transform: translateY(-50%);
  display: flex; align-items: center;
  color: var(--c-ink-2);
  cursor: pointer; user-select: none;
  opacity: 0.55;
  transition: opacity 0.15s;
}
.pwd-eye:hover { opacity: 1; }
.pwd-wrap .input::-ms-reveal,
.pwd-wrap .input::-ms-clear { display: none; }

.msg { font-size: 12.5px; line-height: 1.5; margin: -4px 0 12px; animation: fadeIn 0.25s ease; }
.msg.err { color: var(--c-error); }
.msg.ok { color: #2ba471; }

.btn-submit {
  width: 100%;
  margin-top: 6px;
  border: none;
  border-radius: var(--radius);
  padding: 15px 0;
  font-size: 15px; font-weight: 700; font-family: var(--font-sans);
  letter-spacing: 4px;
  color: #fff;
  background: linear-gradient(120deg, var(--c-primary), var(--c-primary-ink));
  cursor: pointer;
  box-shadow: 0 10px 22px -10px rgba(18, 165, 148, 0.6);
  transition: transform 0.18s, box-shadow 0.18s, filter 0.18s;
}
.btn-submit:disabled { opacity: 0.68; cursor: not-allowed; filter: none; }
.btn-submit:hover:not(:disabled) { filter: brightness(1.05); transform: translateY(-1px); }
.btn-submit:active { transform: translateY(0); }

.mode-note { margin-top: 8px; text-align: center; font-size: 11.5px; color: var(--c-muted); }

.link-row { margin-top: 18px; text-align: center; font-size: 12.5px; color: var(--c-muted); }
.link-row a { color: var(--c-primary-ink); font-weight: 700; cursor: pointer; }
.link-row a:hover { color: var(--c-primary); }

.panel-legal { margin-top: 22px; font-size: 11px; letter-spacing: 1px; color: #a9bec2; }

@media (max-width: 900px) {
  .pitch { display: none; }
  .panel { flex: 1 1 100%; min-height: 100vh; }
}
</style>
