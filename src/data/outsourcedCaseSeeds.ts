import { BOOK, BOOK_E, PY_CODE, PY_CODE_E, RUN_OUT, RUN_OUT_E, SUPPORT, SUPPORT_E, ALL_ANIMS, ALL_ANIMS_E, ANIM_SHOT, ANIM_SHOT_E } from '@/legacy/prototype/core'

export interface AnimationSegmentSeed {
  key: string
  label: string
  fileName: string
  html: string
}

export interface SeedCaseContent {
  protoId: string
  title: string
  tag: string
  category: string
  sections: { guide: string; animation: string; animationSegments?: string; code: string; result: string }
}

function guideHtml(title: string, chapters: { t: string; html: string }[]): string {
  return `<h2>${title} · 实训指导书</h2>${chapters.map((c) => `<section><h3>${c.t}</h3>${c.html}</section>`).join('')}`
}

function animationHtml(list: string[][], shots: Record<string, string>): string {
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>可视化演示</title><style>body{margin:0;background:#101b22;color:#eaf4f5;font:15px system-ui,sans-serif}.wrap{max-width:1100px;margin:auto;padding:24px}.tabs{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:16px}.tabs button{border:1px solid #34515a;background:#172932;color:#d8e7e8;padding:9px 12px;border-radius:7px;cursor:pointer}.tabs button.on{background:#12a594;color:#062329}iframe{width:100%;height:min(68vh,700px);border:0;background:white;border-radius:8px}</style><div class="wrap"><div class="tabs" id="tabs"></div><iframe id="demo" title="可视化演示"></iframe></div><script>const items=${JSON.stringify(list)};const shots=${JSON.stringify(shots)};const tabs=document.getElementById('tabs');const frame=document.getElementById('demo');function show(i){tabs.querySelectorAll('button').forEach((b,n)=>b.classList.toggle('on',n===i));frame.src=shots[items[i][0]]||''}items.forEach((x,i)=>{const b=document.createElement('button');b.textContent=x[1];b.onclick=()=>show(i);tabs.appendChild(b)});show(0)</script></html>`
}

const supportText = (support: typeof SUPPORT) => JSON.stringify(support, null, 2)

async function loadText(path: string): Promise<string> {
  const response = await fetch(path)
  if (!response.ok) throw new Error(`读取案例文件失败：${path}`)
  return response.text()
}

export async function buildSemFileSeed(): Promise<SeedCaseContent> {
  const sources = [
    { key: 'global', label: '全局分析', path: '/cases/p5/anims/emod-0.html' },
    { key: 'problem1', label: '问题一', path: '/cases/p5/anims/emod-1.html' },
    { key: 'problem2', label: '问题二', path: '/cases/p5/anims/emod-2.html' },
    { key: 'problem3', label: '问题三', path: '/cases/p5/anims/emod-3.html' },
    { key: 'problem4', label: '问题四', path: '/cases/p5/anims/emod-4.html' },
  ]
  const segments: AnimationSegmentSeed[] = []
  for (const item of sources) {
    segments.push({
      key: item.key,
      label: item.label,
      fileName: `p5_sem-ad-optimization_animation_${item.key}_v1.html`,
      html: await loadText(item.path),
    })
  }
  return {
    protoId: 'p5', title: 'SEM 广告投放策略优化', tag: '2026国赛E题', category: '国赛专科',
    sections: {
      guide: guideHtml('SEM 广告投放策略优化', BOOK_E),
      animation: segments[0]?.html || '',
      animationSegments: JSON.stringify(segments),
      code: PY_CODE_E,
      result: `<h2>SEM 广告投放策略优化研究</h2><pre>${RUN_OUT_E}</pre><h3>支撑材料</h3><pre>${supportText(SUPPORT_E)}</pre>`,
    },
  }
}

export const outsourcedCaseSeeds: SeedCaseContent[] = [
  {
    protoId: 'p3', title: '微网与外部电网的电力调控策略', tag: '2026国赛C题', category: '国赛本科',
    sections: {
      guide: guideHtml('微网与外部电网的电力调控策略', BOOK),
      animation: animationHtml(ALL_ANIMS, ANIM_SHOT),
      code: PY_CODE,
      result: `<h2>微网与外部电网电力调控策略研究</h2><pre>${RUN_OUT}</pre><h3>支撑材料</h3><pre>${supportText(SUPPORT)}</pre>`,
    },
  },
  {
    protoId: 'p5', title: 'SEM 广告投放策略优化', tag: '2026国赛E题', category: '国赛专科',
    sections: {
      guide: guideHtml('SEM 广告投放策略优化', BOOK_E),
      animation: animationHtml(ALL_ANIMS_E, ANIM_SHOT_E),
      code: PY_CODE_E,
      result: `<h2>SEM 广告投放策略优化研究</h2><pre>${RUN_OUT_E}</pre><h3>支撑材料</h3><pre>${supportText(SUPPORT_E)}</pre>`,
    },
  },
]
