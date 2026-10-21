import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { runInNewContext } from 'node:vm'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const core = readFileSync(join(root, 'src/legacy/prototype/core.ts'), 'utf8')
const generator = readFileSync(join(root, 'scripts/port/build-port.mjs'), 'utf8')

function extractFunction(name) {
  const start = core.indexOf(`export function ${name}(`)
  assert.notEqual(start, -1)
  const end = core.indexOf('\n}', start) + 2
  return core.slice(start, end).replace('export function', 'function')
}

test('演示分享开关只更改内存状态并同步可访问性反馈', () => {
  const work = { id: 'demo', share: false }
  const state = { innerHTML: '' }
  const attrs = {}
  const classes = { toggle: (name, enabled) => { assert.equal(name, 'on'); attrs.on = enabled } }
  const button = { classList: classes, setAttribute: (key, value) => { attrs[key] = value }, parentNode: { querySelector: () => state } }
  const messages = []
  const toggleShare = runInNewContext(`${extractFunction('toggleShare')}; toggleShare`, { WORKS: [work], toast: (msg) => messages.push(msg) })

  toggleShare('demo', button)
  assert.equal(work.share, true)
  assert.equal(attrs['aria-checked'], 'true')
  assert.match(state.innerHTML, /演示分享已开/)
  assert.match(messages[0], /不会改变真实访问权限/)
  toggleShare('demo', button)
  assert.equal(work.share, false)
  assert.equal(attrs['aria-checked'], 'false')
  assert.match(state.innerHTML, /演示分享已关/)
  toggleShare('missing', button)
  assert.equal(messages.length, 2)
})

test('生成器恢复的开关模板与当前成果页保持一致', () => {
  const from = generator.indexOf('core = core.replace(mineSwitch, `')
  assert.notEqual(from, -1)
  const start = from + 'core = core.replace(mineSwitch, '.length
  const end = generator.indexOf('`)', start) + 1
  const generated = runInNewContext(generator.slice(start, end))
  assert.ok(core.replaceAll('\r\n', '\n').includes(generated))
  assert.match(generated, /role="switch" aria-checked/)
  assert.match(generated, /toggleShare\(\\'/)
})
test('生成器的开关样式与当前样式保持一致', () => {
  const css = readFileSync(join(root, 'src/styles/prototype.scss'), 'utf8')
  const start = generator.indexOf('const demoSwitchCss = `')
  assert.notEqual(start, -1)
  const valueStart = start + 'const demoSwitchCss = '.length
  const valueEnd = generator.indexOf('`', valueStart + 1) + 1
  assert.ok(valueEnd > valueStart)
  const generated = runInNewContext(generator.slice(valueStart, valueEnd))
  assert.ok(css.replaceAll('\r\n', '\n').includes(generated))
})