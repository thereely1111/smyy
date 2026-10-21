/**
 * 原型逻辑的启动引导
 *
 * 原型的应用脚本在 <script> 里随文档自动执行两段 IIFE（启动初始化、轮播拖拽接线），
 * 那时 DOM 已经在了。搬进 ESM 后会变成 import 即执行 —— 挂载时 Vue 的 DOM 还不存在，
 * `$('d-tabs').querySelectorAll` 直接抛异常、整页白屏。所以那两段已改成导出函数，
 * 由这里在挂载后按序调用。
 *
 * 为什么还要「全局监听记账」：
 *   原型原码里有 4 处 window/document 级监听（resize、ESC 的 keydown，
 *   以及拖拽过程中临时挂的 mousemove / mouseup）。它们写在函数体里，
 *   每跑一次 init 就注册一次。用户去登录页再回来，组件重挂载会再跑一次，
 *   于是同一件事被注册两遍。这里是通用的收口办法：只在启动的那一瞬间
 *   接管 addEventListener，把这段期间注册的监听记下来，卸载时逐一摘掉。
 *   这样不必改动任何一行原型原码。
 */

import {
  installPrototypeGlobals,
  initPrototype,
  installCarouselDrag,
} from './core'

type Listener = [EventTarget, string, EventListenerOrEventListenerObject, boolean | AddEventListenerOptions | undefined]

const tracked: Listener[] = []

/** 只把 fn 执行期间注册的监听记账，执行完立刻还原，不干扰 Vue 自己的监听 */
function withListenerTracking(fn: () => void): void {
  const targets: EventTarget[] = [window, document]
  const saved: Array<[EventTarget, EventTarget['addEventListener']]> = []
  for (const t of targets) {
    const original = t.addEventListener
    saved.push([t, original])
    t.addEventListener = function (this: EventTarget, ...args: unknown[]) {
      tracked.push([t, args[0] as string, args[1] as EventListener, args[2] as boolean | undefined])
      return (original as (...a: unknown[]) => void).apply(t, args)
    } as EventTarget['addEventListener']
  }
  try {
    fn()
  } finally {
    for (const [t, original] of saved) t.addEventListener = original
  }
}

/** 摘掉本次启动期间注册的全部全局监听 */
function releaseGlobalListeners(): void {
  for (const [t, type, handler, opts] of tracked) {
    try {
      t.removeEventListener(type, handler, opts as EventListenerOptions | undefined)
    } catch {
      /* 已经随 DOM 一起销毁的，忽略 */
    }
  }
  tracked.length = 0
}

let booted = false

/**
 * 启动原型逻辑。必须在模板渲染进 DOM 之后调用（Vue 的 onMounted）。
 * 重复挂载时会重新执行 —— 因为新 DOM 上的事件需要重新接，
 * 旧的监听由 releaseGlobalListeners 收掉。
 */
export function bootPrototype(): void {
  withListenerTracking(() => {
    installPrototypeGlobals()
    initPrototype()
    installCarouselDrag()
  })
  booted = true
}

/** 卸载：收掉全局监听（DOM 本身交给 Vue 销毁） */
export function shutdownPrototype(): void {
  releaseGlobalListeners()
  booted = false
}

export const isPrototypeBooted = (): boolean => booted
