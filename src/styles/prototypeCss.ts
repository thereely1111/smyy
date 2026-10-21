/**
 * 原型样式的按需挂载
 *
 * 为什么不用「直接 import 一份全局 scss」：
 *   原型那 44K 样式是单页应用的全局样式，里面有 `.card` `.chat` `.panel`
 *   `.stream` `.chip` `.note` `.msg` `.hint` `.meta` 等通用类名 —— 实测与
 *   smyy 沙箱模块的类名有 17 个重叠。全局引入会静默改掉沙箱页面的观感，
 *   而且这种回归「看起来没报错」，最难查。
 *
 * 做法：用 Vite 的 `?inline` 拿到编译后的 CSS 字符串，自己在需要的时候
 *   插一个 <style>，离开时删掉。零 CSS 转换（不写选择器前缀那种会把样式
 *   改坏的活），完全可逆。
 *
 * 代价：45K CSS 会作为字符串进主 bundle。相对「样式互相污染」，这个代价划算。
 */

import prototypeCss from './prototype.scss?inline'

let styleEl: HTMLStyleElement | null = null

/** 挂载原型样式（幂等：重复调用只插一次） */
export function mountPrototypeCss(): void {
  if (styleEl) return
  styleEl = document.createElement('style')
  styleEl.setAttribute('data-smyy', 'prototype-css')
  styleEl.textContent = prototypeCss
  document.head.appendChild(styleEl)
}

/** 卸载原型样式，恢复其他模块的样式环境 */
export function unmountPrototypeCss(): void {
  styleEl?.remove()
  styleEl = null
}
