/**
 * 本地导出模块的回归（CDP，无需 token / 后端）：
 * 直接 import vite 服出来的 `src/utils/analysis/docExport.ts`，验 Markdown → Word-HTML。
 *
 * 为什么要有：这条是「后端没有 /export」时的兜底（kuaike 的 HTML 伪 Word 法），
 * 一旦 markdown-it 配置或 LaTeX 映射被改坏，用户点导出会得到一份没排版/满是 \sum 的文档。
 *
 * 运行：node scripts/port/probe-doc-export.mjs [baseUrl]
 */
import { spawn } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const CHROME = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 18723
const BASE = process.argv[2] || 'http://localhost:5181'
const profile = mkdtempSync(join(tmpdir(), 'cdp-docx-'))
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const chrome = spawn(CHROME, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', 'about:blank',
], { stdio: 'ignore' })

async function ws() {
  for (let i = 0; i < 60; i++) {
    try {
      const j = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()
      if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl
    } catch { /* retry */ }
    await sleep(250)
  }
  throw new Error('CDP 未就绪')
}

class Cdp {
  constructor(s) {
    this.ws = s; this.id = 0; this.p = new Map()
    s.addEventListener('message', (ev) => {
      const m = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
      if (m.id && this.p.has(m.id)) {
        const { res, rej } = this.p.get(m.id); this.p.delete(m.id)
        m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result)
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((res, rej) => {
      this.p.set(id, { res, rej })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => { if (this.p.has(id)) { this.p.delete(id); rej(new Error('timeout ' + method)) } }, 30000)
    })
  }
}

const SAMPLE = [
  '# 微电网调度模型',
  '',
  '## 一、符号说明',
  '',
  '| 符号 | 含义 |',
  '| --- | --- |',
  '| $P_t$ | 时段 $t$ 的购电功率 |',
  '',
  '目标函数：',
  '',
  '$$\\min \\sum_{t=1}^{T} (c_t P_t + \\lambda_t |P_t - P_{t-1}|)$$',
  '',
  '- 约束一：$P_t \\le P_{\\max}$',
  '- 约束二：$\\beta \\ge 0$',
  '',
  '> 引用一段说明。',
  '',
  '```python',
  'print(1)',
  '```',
].join('\n')

try {
  const sock = new WebSocket(await ws())
  await new Promise((r, j) => { sock.addEventListener('open', r); sock.addEventListener('error', j) })
  void new Cdp(sock) // 只为保持连接；真正的目标页在下面另开
  const t = await (await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(BASE + '/')}`, { method: 'PUT' })).json()
  const page = new WebSocket(t.webSocketDebuggerUrl)
  await new Promise((r, j) => { page.addEventListener('open', r); page.addEventListener('error', j) })
  const c = new Cdp(page)
  await c.send('Runtime.enable')
  await sleep(3500)

  const r = await c.send('Runtime.evaluate', {
    expression: `(async () => {
      const m = await import('/src/utils/analysis/docExport.ts');
      const html = m.wordHtmlOf(${JSON.stringify(SAMPLE)}, '微电网调度模型');
      return {
        hasFn: typeof m.wordHtmlOf === 'function',
        docName: m.docNameOf('artifacts/20261019_case/analysis/analysis_v1.0.md'),
        len: html.length,
        head: html.slice(0, 40),
        hasTable: html.includes('<table>'),
        hasTh: html.includes('<th'),
        thStyle: html.includes('border:1pt solid'),
        minTo: html.includes('min ∑'),
        leTo: html.includes('≤'),
        betaTo: html.includes('β'),
        fxBlock: html.includes('class=\\"fx\\"'),
        notRawLatex: !html.includes('\\\\sum') && !html.includes('\\\\le'),
        hasCode: html.includes('<pre>'),
        hasQuote: html.includes('<blockquote>'),
      };
    })()`,
    awaitPromise: true,
    returnByValue: true,
  })
  if (r.exceptionDetails) console.log('EVAL ERROR', JSON.stringify(r.exceptionDetails).slice(0, 500))
  const v = r.result?.value || {}
  const checks = [
    ['模块可用', v.hasFn],
    ['.md → .doc 文件名', v.docName === 'analysis_v1.0.doc', v.docName],
    ['输出像完整文档', typeof v.head === 'string' && v.head.startsWith('<!DOCTYPE html>')],
    ['表格带边框样式', v.hasTable && v.hasTh && v.thStyle],
    ['\\sum → ∑', v.minTo],
    ['\\le → ≤', v.leTo],
    ['\\beta → β', v.betaTo],
    ['$$ 行间公式单独成段', v.fxBlock],
    ['不残留裸 LaTeX 命令', v.notRawLatex],
    ['代码块 / 引用保留', v.hasCode && v.hasQuote],
  ]
  let bad = 0
  for (const [name, ok, detail] of checks) {
    if (!ok) bad++
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
  }
  console.log(`\nhtml 长度 ${v.len}`)
  console.log(bad ? `FAILED ${bad}` : 'ALL PASS')
} catch (e) {
  console.log('探针自身失败：' + (e instanceof Error ? e.message : String(e)))
} finally {
  chrome.kill()
}
