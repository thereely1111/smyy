/**
 * 原型 → smyy 移植生成器（机械步骤全部脚本化，不手抄）
 *
 * 前置：先跑 `node scripts/port/split-prototype.mjs`，它会把原型切成片段并做
 *       「拼回逐字节一致」自检。本脚本消费它的产物。
 *
 * 本脚本产出（全部可重复生成、幂等）：
 *   1. src/styles/prototype.scss      原型 44K 主样式 + 变量别名层
 *   2. src/legacy/prototype/core.ts   原型应用脚本（去掉 1.02M 截图 base64）
 *   3. public/cases/p3/anim-shots/0..4.png  5 张演示缩略图（从 base64 解码成真图片）
 *   4. public/cases/p3/anims/mod-0..4.html  5 个自包含演示模块（可 iframe 直挂）
 *
 *   注：静态资源已按案例分目录（public/cases/<protoId>/{anims,anim-shots}/），
 *   详见 public/cases/README.md。本脚本只产出 p3（默认兜底）那一份。
 *
 * 设计取舍（为什么 core.ts 是「逐字搬运 + ts-nocheck」）：
 *   原型是 1.2M 字符的原生 JS，162 个函数、52 个数据常量，全部靠
 *   `$('#id')` 直接操作 DOM、靠 inline onclick 调全局函数。
 *   一次性改写成 Vue SFC 既不现实、也会静默改变行为（D1 决策是「以原型为准」）。
 *   所以本阶段采用「外壳 Vue 化 + 逻辑逐字搬运」：
 *     - Vue 拥有模板与生命周期（真 Vue 项目结构）
 *     - 原型逻辑作用于同一套 id/class 契约，行为逐字不变
 *     - core.ts 加 @ts-nocheck 是为了让 vue-tsc 保持绿；它随后会被逐函数 Vue 化
 *   这不是终态，是可控的第一步；`~待 Vue 化` 标记标出了后续要拆的函数。
 *
 * 断言（任一不过即 ABORT，不落盘）：
 *   A1 应用脚本内不含 `</script>`（否则分片本身就切错了）
 *   A2 core.ts 去掉截图块后，与「原文 − 截图块」逐字节一致（只允许桥接层追加）
 *   A3 截图块键数 == 演示模块数 == 5
 *   A4 每个外置模块都含 id="DATA"、主脚本已可执行、且 `:host` 已全部替换
 */

import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'

const SPLIT = process.argv[2] || path.join(os.tmpdir(), 'smyy-port')
/* 2026-10-19：ROOT 原先写死作者机器上的 `E:/数模演易/smyy`，换机后这个路径不存在 ——
   连下面的 typescript require 都会 MODULE_NOT_FOUND。这里改成「旧路径在就用旧的，
   不在就按脚本自身位置推仓库根」，两台机器都能跑。 */
const LEGACY_ROOT = 'E:/数模演易/smyy'
const ROOT = fs.existsSync(path.join(LEGACY_ROOT, 'package.json'))
  ? LEGACY_ROOT
  : path.resolve(import.meta.dirname, '../..')
const SRC = path.join(ROOT, 'src')

function fail(msg) {
  console.error('\n❌ ABORT: ' + msg)
  process.exit(1)
}
/* 产物先收集、末尾统一落盘（2026-10-12 改）。
   不在算完一处写一处：末尾要拿「本次生成结果」与「磁盘现状」做逐字节自检
   （忽略行尾差异），对不上就整体拒绝写入 —— 半写状态比不写更糟。 */
const pending = []
function write(rel, text) {
  pending.push([rel, text])
  return text
}

/* ── A0 的由来与替代（2026-10-12） ────────────────────────────────────────
   背景：core.ts / prototype.scss 里原本有一部分内容是提交时「直接改产物」进去的，
   本脚本没有对应规则 —— 首页卡片封面图、轮播 pointer 拖拽（e7b2995）、
   封面图样式、轮播横向手势样式。当天实测：重跑本脚本会让这些内容**静默回退**，
   而脚本自带的 A1–A5 断言全部照绿 —— 典型的「看着成功、其实退化」。

   处置（本轮）：这四组规则已全部补进本脚本（见 §P1-封面图数据 / P1-卡片渲染 /
   P1-轮播手势 / P1-样式），并把原先那个「靠放行标记」的一次性守门换成
   **每次生成都做的产物一致性自检**（见文件末尾 flush）——生成结果与磁盘产物
   忽略行尾后必须逐字节相同，否则整体拒绝写盘。比标记更硬：以后任何人再往
   产物里手工塞内容，这里都会当场拦住并指出首个差异位置。 */

console.log('读取分片产物：', SPLIT)
const index = JSON.parse(fs.readFileSync(path.join(SPLIT, 'index.json'), 'utf8'))
const segText = (r) => fs.readFileSync(path.join(SPLIT, 'app', r.file), 'utf8')

const shotRow = index.app.find((r) => r.name === 'ANIM_SHOT')
if (!shotRow) fail('分片产物里没有 ANIM_SHOT')

// ── A1：应用脚本内不能出现 </script>（否则当初分片就切错了） ─────────────────
const wholeApp = index.app.map(segText).join('')
if (wholeApp.includes('</script>')) fail('A1 失败：应用脚本内出现 </script>，分片边界不可信')
console.log('✅ A1：应用脚本内无 </script>')

// ── 解析 ANIM_SHOT：5 张 PNG ───────────────────────────────────────────────
const shotSrc = segText(shotRow)
const shotEntries = [...shotSrc.matchAll(/'([^']+)'\s*:\s*'data:image\/([a-z]+);base64,([A-Za-z0-9+/=]+)'/g)]
if (shotEntries.length === 0) fail('没能从 ANIM_SHOT 里解析出图片条目')
console.log(`ANIM_SHOT：${shotEntries.length} 张`)
for (const [, k, ext, b64] of shotEntries) {
  const buf = Buffer.from(b64, 'base64')
  if (buf.length < 1024) fail(`截图 ${k} 解出来只有 ${buf.length} 字节，不像图片`)
  if (buf[0] !== 0x89 || buf.subarray(1, 4).toString() !== 'PNG') fail(`截图 ${k} 不是 PNG（魔数不符）`)
  write(`public/cases/p3/anim-shots/${k}.png`, buf)
}

// ── A3：截图数 == 模块数 ───────────────────────────────────────────────────
if (index.anim.length !== shotEntries.length) {
  fail(`A3 失败：截图 ${shotEntries.length} 张，演示模块 ${index.anim.length} 个，对不上`)
}
console.log(`✅ A3：截图与演示模块数量一致（${index.anim.length}）`)

// ── 演示模块标题（从 ALL_ANIMS 取，用于 <title>） ──────────────────────────
const animsRow = index.app.find((r) => r.name === 'ALL_ANIMS')
let ANIM_TITLES = []
if (animsRow) {
  try {
    const lit = segText(animsRow).replace(/^\s*var\s+ALL_ANIMS\s*=\s*/, '').replace(/;\s*$/, '')
    const arr = new Function('return ' + lit)()
    if (Array.isArray(arr)) ANIM_TITLES = arr.map((a) => String(a[1] || ''))
  } catch (e) {
    fail('解析 ALL_ANIMS 失败：' + e.message)
  }
}

// ── 1. 原型样式 + 变量别名层 ──────────────────────────────────────────────
let rawCss = fs.readFileSync(path.join(SPLIT, 'style-0.css'), 'utf8')
// 由原型重建时一并保留成果页开关的可访问触控与焦点样式。
const originalSwitchCss = `.sw{
  width:44px;height:24px;border-radius:999px;background:#C8DDDF;position:relative;transition:.22s;flex-shrink:0;cursor:pointer
}
.sw:after{
  content:"";position:absolute;top:3px;left:3px;width:18px;height:18px;border-radius:50%;background:#fff;
  box-shadow:0 1px 3px rgba(0,0,0,.2);transition:.22s
}
.sw.on{background:var(--green-500)}
.sw.on:after{left:23px}`
const demoSwitchCss = `.sw{
  width:44px;height:44px;padding:0;border:0;background:transparent;position:relative;flex-shrink:0;cursor:pointer;
  transition:transform .2s ease
}
.sw:before{
  content:"";position:absolute;top:10px;left:0;width:44px;height:24px;border-radius:999px;
  background:#C8DDDF;transition:background .22s ease
}
.sw:after{
  content:"";position:absolute;top:13px;left:3px;width:18px;height:18px;border-radius:50%;background:#fff;
  box-shadow:0 1px 3px rgba(0,0,0,.2);transition:left .22s ease
}
.sw.on:before{background:var(--blue-600)}
.sw.on:after{left:23px}
.sw:hover{transform:scale(1.05)}
.sw:active{transform:scale(.96)}
.sw:focus-visible{outline:2px solid var(--blue-600);outline-offset:2px;border-radius:6px}
@media(prefers-reduced-motion:reduce){.sw,.sw:before,.sw:after{transition:none}}`
if (!rawCss.includes(originalSwitchCss)) fail('原型分享开关样式已变化')
rawCss = rawCss.replace(originalSwitchCss, demoSwitchCss)
const alias = `
/* ==================================================================
   变量别名层（本次迁移新增）

   原型的组件样式用的是 --blue-700 / --blue-100 这套命名，颜色本身已经是青绿；
   smyy 的设计令牌是 --c-primary 这套命名。两边必须接上，否则会出现
   「青绿底色配靛紫按钮」这种半成品换皮 —— 别名层就是那道接缝。
   ================================================================== */
:root {
  --blue-900: var(--c-primary-ink);
  --blue-800: var(--c-primary-ink);
  --blue-700: var(--c-primary);
  --blue-600: var(--c-blue);
  --blue-500: var(--c-primary);
  --blue-400: #4fd0c0;
  --blue-300: #92ded5;
  --blue-200: #b8e8e2;
  --blue-100: var(--c-primary-soft);
  --blue-50: #effaf8;

  --amber-500: var(--c-warn);
  --red-500: var(--c-error);
  --green-500: #2ba471;

  --bg: var(--c-bg);
  --card: var(--c-surface);
  --ink: var(--c-ink);
  --ink-2: var(--c-ink-2);
  --line: var(--c-line);
}
`
// 沙箱桥接样式（本轮新增）
const sandboxBridgeCss = `
/* ==================================================================
   沙箱桥接样式（本次迁移新增）

   回执消息里那份产物清单。为什么不放在 Vue 组件的 <style scoped> 里：
   这条消息是 core.ts 在运行时插进对话区的，编译期没有 scoped 属性，
   scoped 样式对它一律不生效。
   ================================================================== */
.sb-files { margin: 10px 0 0; padding: 0; list-style: none; display: flex; flex-direction: column; gap: 6px; }
.sb-files li { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border: 1px solid var(--line); border-radius: 8px; background: var(--card); }
.sb-files li code { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: var(--ink); }
.sb-files li span { flex-shrink: 0; color: var(--ink-3); font-size: 11px; }

/* ==================================================================
   真实分析通道样式（本轮新增）

   两处：① 每一条消息顶部的「本节由智能体实时生成 / 本节为内置剧本」来源标注；
        ② 读题等待气泡里的进度行（已用时、工具调用次数）。
   同样不能放进 Vue 的 scoped 样式 —— 这些节点是 core.ts 运行时插入的。
   ================================================================== */
.src-note { margin: 4px 0 10px; padding: 6px 10px; border-left: 3px solid var(--c-primary, #12a594); border-radius: 0 6px 6px 0; background: var(--blue-50, #effaf8); color: var(--ink-2, #3d6570); font-size: 12px; line-height: 1.6; }
.src-note b { color: var(--ink, #0b2e36); }
.an-meter { margin: 4px 0 0; color: var(--ink-3, #57727c); font-size: 12px; font-variant-numeric: tabular-nums; }

/* ==================================================================
   真实动画通道样式（本轮新增）

   演示模块一律用 iframe 挂载（内置外置页 / 真实生成的 blob HTML 都是），
   而 .anim-frame 此前**没有任何样式** —— 全屏层里那个 iframe 一直是
   浏览器默认的 300×150。这里把尺寸补上，两处挂载点各自约束：
     ① 抽屉舞台 .stage 内：撑满宽度，按视口给一个够看的高度
     ② 全屏层 .afs-stage 内：绝对定位铺满
   ================================================================== */
.stage .anim-frame { display: block; width: 100%; height: min(62vh, 560px); border: 0; border-radius: 9px; background: #F3FAFA; }
.afs-stage .anim-frame { position: absolute; inset: 0; display: block; width: 100%; height: 100%; border: 0; border-radius: 0; background: #F3FAFA; }

/* ==================================================================
   附件条样式（本轮新增）

   附件条是 core.ts 运行时插进输入框内部（textarea 与工具条之间）的容器 ——
   不改模板结构，也就不碰原型那 162 个函数依赖的 id。三种状态要一眼分得开：
   待上传（文件还只在浏览器里）、上传中（正在送进会话）、已上传（智能体已经
   读得到）。用户要能从颜色直接看出「到底传上去没有」。
   ================================================================== */
.attach-strip { display: flex; flex-wrap: wrap; gap: 6px; margin: 0; }
.attach-strip:empty { display: none; }
.composer > .attach-strip { padding: 2px 22px 6px; }
.at-chip { display: inline-flex; align-items: center; gap: 6px; max-width: 280px; padding: 4px 6px 4px 9px; border: 1px solid var(--line); border-radius: 999px; background: var(--card); color: var(--ink-2, #3d6570); font-size: 12px; cursor: pointer; }
.at-chip i { display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: var(--c-warn, #e0a04a); flex-shrink: 0; }
.at-chip.up i { background: var(--c-primary, #12a594); }
.at-chip.ing i { background: var(--blue-400, #1e9fc4); animation: atPulse 1.05s ease-in-out infinite; }
.at-chip.sel { border-color: var(--blue-400, #1e9fc4); box-shadow: 0 0 0 2px rgba(30,159,196,.18); }
@keyframes atPulse { 0%,100% { opacity: 1 } 50% { opacity: .3 } }
.at-chip b { font-weight: 400; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.at-chip .st { flex-shrink: 0; color: var(--ink-3, #57727c); font-size: 11px; }
/* 题目文件与数据附件要一眼分得开 —— 前者是题面本身，后者只是补充材料 */
.at-chip.prob { border-color: rgba(224,160,74,.55); background: rgba(224,160,74,.08); }
.at-chip.prob em { flex-shrink: 0; padding: 0 5px; border-radius: 999px; background: rgba(224,160,74,.20); color: var(--c-gold-dark, #8a6313); font-style: normal; font-size: 10px; line-height: 15px; }
.at-chip.bad { border-style: dashed; border-color: var(--line); background: transparent; color: var(--ink-3, #57727c); }
.at-chip.bad i { background: var(--ink-3, #57727c); }
.at-chip.bad .st { color: var(--c-gold-dark, #8a6313); }
.at-chip button { flex-shrink: 0; width: 16px; height: 16px; padding: 0; border: 0; border-radius: 50%; background: transparent; color: var(--ink-3, #57727c); cursor: pointer; line-height: 16px; font-size: 13px; }
.at-chip button:hover { background: var(--blue-100, #effaf8); color: var(--ink, #0b2e36); }

/* ==================================================================
   S2 进度浮窗样式 —— ⛔ 已随浮窗整体移除（2026-10-15，按用户要求）。
   右栏「可视化演示」是唯一进度出口，浮窗的 .an-float / .anf-* 全部删除。
   ================================================================== */
/* 代码等待气泡的实时区同理（它是 .txt 的兄弟，空着时不占位置） */
.bubble .lv-stream.code-live:empty { display: none; margin-top: 0; }

/* ==================================================================
   工作台左侧任务栏 + 顶栏左对齐（2026-10-08 改版）

   改版缘由：任务列表原来挂在**首页**（一条常驻条 + 展开列表），但首页是入口页，
   在那儿该看到的是「开始做题」，不是运维面板。现在整块搬进工作台，做成左侧竖列 ——
   切任务、看阶段、暂停/删除都在一处，首页只留顶栏那个「工作台」入口。

   节点由 Vue 渲染（v-for / v-if），样式仍写在这里：原型那 44K 样式是全局的，
   放同一处才好一起调，也避免和 Vue 的 scoped 样式两套口径。
   ================================================================== */
.wside { flex: 0 0 236px; display: flex; flex-direction: column; min-width: 0; border-right: 1px solid var(--line, #d8e8e8); background: var(--card, #fff); }
.wside .ws-head { display: flex; align-items: center; gap: 8px; padding: 13px 14px 9px; color: var(--ink, #0b2e36); font-size: 13px; font-weight: 700; }
.wside .ws-head b { padding: 1px 7px; border-radius: 999px; background: var(--blue-100, #effaf8); color: var(--c-primary, #0b6e68); font-size: 11px; font-weight: 700; font-variant-numeric: tabular-nums; }
.wside .ws-list { flex: 1; overflow-y: auto; padding: 0 8px 12px; }
.ws-empty { padding: 4px 8px; color: var(--ink-3, #57727c); font-size: 12px; line-height: 1.75; }
.ws-row { display: flex; align-items: flex-start; gap: 9px; padding: 9px 9px 8px; margin-bottom: 3px; border: 1px solid transparent; border-radius: 10px; cursor: pointer; transition: .15s; }
.ws-row:hover { background: var(--bg, #f3fafa); }
.ws-row.on { border-color: var(--blue-200, #bfe6df); background: var(--blue-50, #effaf8); }
.ws-dot { flex: 0 0 auto; width: 7px; height: 7px; margin-top: 6px; border-radius: 50%; background: var(--line, #d8e8e8); }
.ws-dot.live { background: var(--c-primary, #12a594); animation: wsPulse 1.4s ease-in-out infinite; }
.ws-dot.paused { background: var(--c-gold, #e0a04a); }
@keyframes wsPulse { 0%, 100% { opacity: 1 } 50% { opacity: .35 } }
.ws-row .ws-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.ws-row .ws-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink, #0b2e36); font-size: 12.5px; font-weight: 600; }
.ws-row .ws-meta { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-3, #57727c); font-size: 11px; font-variant-numeric: tabular-nums; }
.ws-acts { flex: 0 0 auto; display: flex; flex-direction: column; gap: 4px; }
.ws-act { padding: 2px 8px; border: 1px solid var(--line, #d8e8e8); border-radius: 999px; background: #fff; color: var(--ink-3, #57727c); font-size: 11px; line-height: 17px; cursor: pointer; white-space: nowrap; }
.ws-act:hover { border-color: var(--c-primary, #12a594); color: var(--c-primary, #0b6e68); }
.ws-act.hot { border-color: var(--c-warn, #c0392b); color: var(--c-warn, #c0392b); background: rgba(192, 57, 43, .06); }
.ws-act[disabled] { opacity: .5; cursor: default; }

/* 顶栏：状态徽标与操作按钮**贴最右**（撑开元素已挪到进度条之后，见 5e）。
   三条保命规则：
     · 进度条与题名可以让出空间（min-width 0 + 进度条 self-scroll），
       六格进度条渲染出来时不会把右边这一排挤出可视区；
     · 状态与操作不参与收缩 —— 它们被挤变形比空一行更难看；
     · 收起产物仍在最末（它不是常显的，抽屉关上时是 display:none）。 */
.wtop .steps { flex: 0 1 auto; min-width: 0; }
.wtop .wt { min-width: 0; }
.wtop .env-badge, .wtop .stag, .wtop .flow-tools { flex-shrink: 0; }

/* ==================================================================
   闸门卡片（HITL，2026-10-09 新增）

   平台的 ui_form_advanced / ui_gate 是 external 工具：模型一调，**run 就挂起**
   等用户填表。卡片画在等待气泡（.msg.ai .bubble）里 —— 节点由 core.ts 运行时
   插入，所以样式必须放这里，放不进 Vue 的 scoped 样式。
   风格走站点那套浅色卡片语言，不像素级复刻平台深色 FormCard。
   ================================================================== */
.gate-card { margin: 10px 0 0; padding: 12px 14px; border: 1px solid var(--blue-200, #bfe6df); border-radius: 12px; background: var(--blue-50, #effaf8); }
.gate-card.done { border-color: var(--line, #d8e8e8); background: var(--bg, #f3fafa); }
.gate-card.done .gate-f, .gate-card.done .gate-acts { display: none; }
.gate-card .gate-t { display: flex; align-items: center; gap: 8px; color: var(--ink, #0b2e36); font-size: 13px; font-weight: 700; }
.gate-card .gate-t i { flex: 0 0 auto; width: 7px; height: 7px; border-radius: 50%; background: var(--c-gold, #e0a04a); }
.gate-card.done .gate-t i { background: var(--c-primary, #12a594); }
.gate-card .gate-m { margin: 6px 0 0; color: var(--ink-2, #3d6570); font-size: 12px; line-height: 1.65; }
.gate-card .gate-f { margin: 10px 0 0; display: flex; flex-direction: column; gap: 10px; }
.gate-card .gate-field > span { display: block; margin-bottom: 5px; color: var(--ink, #0b2e36); font-size: 12px; font-weight: 600; }
.gate-card .gate-field > span em { margin-left: 3px; color: var(--c-warn, #c0392b); font-style: normal; }
.gate-card .gate-field input[type=text], .gate-card .gate-field input[type=number],
.gate-card .gate-field textarea, .gate-card .gate-field select {
  width: 100%; box-sizing: border-box; padding: 7px 9px; border: 1px solid var(--line, #d8e8e8);
  border-radius: 8px; background: #fff; color: var(--ink, #0b2e36); font-size: 12.5px; font-family: inherit;
}
.gate-card .gate-field textarea { min-height: 62px; resize: vertical; line-height: 1.6; }
.gate-card .gate-field input:focus, .gate-card .gate-field textarea:focus, .gate-card .gate-field select:focus {
  outline: none; border-color: var(--c-primary, #12a594); box-shadow: 0 0 0 2px rgba(18, 165, 148, .15);
}
/* 单选 / 多选组：**竖向铺满、圆点与首行对齐**（2026-10-13 排版重做）。
   原来那版是胶囊（border-radius:999px）+ align-items:center，选项文字一换行就成
   「圆点吊在文字中间、胶囊拉成两行大圆角」的怪样；竖向排列还让长短选项左缘一致，
   一眼看得出是同一组。 */
.gate-card .gate-opts { display: flex; flex-direction: column; gap: 6px; }
.gate-card .gate-opt { display: flex; align-items: flex-start; gap: 8px; padding: 8px 11px; border: 1px solid var(--line, #d8e8e8); border-radius: 10px; background: #fff; color: var(--ink-2, #3d6570); font-size: 12.5px; line-height: 1.65; cursor: pointer; transition: border-color .15s, background .15s, box-shadow .15s; }
.gate-card .gate-opt:hover { border-color: var(--blue-200, #bfe6df); background: var(--blue-50, #effaf8); }
.gate-card .gate-opt > span { flex: 1 1 auto; min-width: 0; }
.gate-card .gate-opt input { flex: 0 0 auto; margin: 3px 0 0; width: 14px; height: 14px; accent-color: var(--c-primary, #12a594); }
.gate-card .gate-opt.on { border-color: var(--c-primary, #12a594); background: rgba(18, 165, 148, .08); color: var(--c-primary, #0b6e68); font-weight: 600; box-shadow: inset 3px 0 0 0 var(--c-primary, #12a594); }
.gate-card .gate-acts { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
.gate-card .gate-note { margin: 8px 0 0; color: var(--ink-3, #57727c); font-size: 11.5px; line-height: 1.6; }
`
// 开题气泡：把「这次交了什么文件」如实列出来（2026-10-12 新增）
const startBriefCss = `
/* ==================================================================
   开题气泡 —— 「开始演易」这条用户消息里的文件清单（2026-10-12 新增）

   这条消息由 core.ts 在运行时插进对话区，编译期没有 scoped 属性，
   scoped 样式对它一律不生效 ⇒ 样式必须放这里。
   用户气泡是深青绿底，chip 沿用原型既有的 .fchip（.msg.me 那几条覆盖
   会自动把它变成半透明白），这里只补清单容器与「未提交文件」那行。
   ================================================================== */
.ub-title { font-weight: 700; }
.ub-files { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 9px; }
.ub-none { margin-top: 7px; font-size: 12.2px; opacity: .78; }
`
/* ── P1-右栏（闸门 + 产物）样式（2026-10-12 新增）─────────────────────────
   用户定的工作台版式：**左对话 / 右（闸门 + 产物）**，左栏任务列表收成窄条。

   三块东西要一起说清：
     · .wside  折叠成 56px 的状态点条，鼠标悬停（或点标题）展开成 236px；
     · .wrail  右栏常驻 420px，顶部两个 tab（闸门 / 产物），各自一个 pane；
     · 产物 pane 里，**原型的 aside#drawer 原样搬了进来** —— 上层那些
       openDrawer / openArt / openArtifactView / closeDrawer 一行没改，
       它们照旧按 id 找 #drawer / #d-title2 / #drawer-body，只是这个抽屉现在
       不再是从右边滑出来的浮层，而是右栏里的一块。所以这里必须把那几条
       原型样式（width:0 / .on{width:520px} / .wide{width:720px}）覆盖掉，
       选择器要压过 .drawer.on.wide（原型是 (0,3,0)，这里用 id 提权到 (1,2,0)）。

   为什么要折叠左栏：三栏并排时，236px 的任务列表把对话区挤得只剩一半，
   而「哪条任务在跑」看状态点就够，细节（副标题、暂停/删除）展开再看不迟。 */
const workRailCss = `
/* ============ 工作台：左栏折叠 ============ */
.wside { flex: 0 0 56px; overflow: hidden; transition: flex-basis .22s ease; }
.wside:hover, .wside.pin { flex-basis: 236px; }
.wside .ws-head { flex-direction: column; align-items: center; gap: 2px; padding: 12px 4px 8px; cursor: pointer; user-select: none; }
.wside:hover .ws-head, .wside.pin .ws-head { flex-direction: row; align-items: center; justify-content: flex-start; gap: 8px; padding: 13px 14px 9px; }
.wside .ws-head span, .wside .ws-head b { opacity: 0; pointer-events: none; transition: opacity .14s; }
.wside:hover .ws-head span, .wside:hover .ws-head b,
.wside.pin .ws-head span, .wside.pin .ws-head b { opacity: 1; pointer-events: auto; }
.wside .ws-head .ws-fold { display: block; width: 7px; height: 7px; border-radius: 50%; background: var(--line, #d8e8e8); }
.wside:hover .ws-head .ws-fold, .wside.pin .ws-head .ws-fold { display: none; }
.wside .ws-row { justify-content: center; padding: 9px 0 8px; }
.wside .ws-row .ws-main, .wside .ws-acts { display: none; }
.wside:hover .ws-row, .wside.pin .ws-row { justify-content: flex-start; padding: 9px 9px 8px; }
.wside:hover .ws-row .ws-main, .wside.pin .ws-row .ws-main { display: flex; }
.wside:hover .ws-acts, .wside.pin .ws-acts { display: flex; }
.wside .ws-empty { display: none; }
.wside:hover .ws-empty, .wside.pin .ws-empty { display: block; }

/* ============ 右栏骨架 ============ */
.wrail { flex: 0 0 420px; min-width: 0; display: flex; flex-direction: column; border-left: 1px solid var(--line, #d8e8e8); background: var(--card, #fff); transition: flex-basis .22s ease; }
/* 收起态：**留一条 34px 的窄条** —— 那个「展开」按钮得有个地方待着。
   早先是 flex-basis:0 + overflow:hidden，按钮跟着右栏一起被裁掉，
   用户点过一次「收起」就再也展不开（2026-10-13 报障）。 */
.wrail.min { flex: 0 0 34px; overflow: hidden; border-left-color: transparent; }
.wrail.min .rail-tabs { padding: 10px 0; justify-content: center; border-bottom: 0; }
.wrail.min .rail-tabs > .grow, .wrail.min .rail-tab, .wrail.min .rail-pane { display: none; }
.wrail.min .rail-min { border-color: var(--c-primary, #12a594); color: var(--c-primary, #0b6e68); }
.rail-tabs { flex: 0 0 auto; display: flex; align-items: center; gap: 4px; padding: 10px 12px 0; border-bottom: 1px solid var(--line, #d8e8e8); }
.rail-tab { position: relative; padding: 7px 12px 9px; border: 0; border-bottom: 2px solid transparent; margin-bottom: -1px; background: transparent; color: var(--ink-3, #57727c); font-size: 13px; font-weight: 700; cursor: pointer; }
.rail-tab:hover { color: var(--c-primary, #0b6e68); }
.rail-tab.on { color: var(--c-primary, #0b6e68); border-bottom-color: var(--c-primary, #12a594); }
.rail-tab.hot::after { content: ""; position: absolute; top: 6px; right: 4px; width: 6px; height: 6px; border-radius: 50%; background: var(--c-gold, #e0a04a); animation: wsPulse 1.4s ease-in-out infinite; }
.rail-tab .rb { margin-left: 5px; padding: 0 6px; border-radius: 999px; background: var(--blue-100, #effaf8); color: var(--c-primary, #0b6e68); font-size: 11px; font-weight: 700; font-variant-numeric: tabular-nums; }
.rail-min { width: 24px; height: 24px; padding: 0; border: 1px solid var(--line, #d8e8e8); border-radius: 6px; background: #fff; color: var(--ink-3, #57727c); font-size: 12px; line-height: 22px; cursor: pointer; }
.rail-min:hover { border-color: var(--c-primary, #12a594); color: var(--c-primary, #0b6e68); }
.rail-pane { display: none; flex: 1 1 auto; min-height: 0; flex-direction: column; }
.rail-pane.on { display: flex; }

/* 闸门区：卡片沿用对话气泡那套 .bubble .txt 的观感（内容本来就是同一份 HTML） */
.rail-gate-list { flex: 1 1 auto; overflow-y: auto; padding: 14px 12px 18px; background: var(--bg, #f3fafa); }
.rail-gate-list:empty::after { content: "当前没有要你确认的事。智能体走到需要你拿主意的地方，卡片会出现在这里。"; display: block; padding: 18px 6px; color: var(--ink-3, #57727c); font-size: 12.5px; line-height: 1.9; }
.rail-gate-card { margin-bottom: 12px; }
.rail-gate-card .bubble { max-width: none; width: 100%; }
.rail-gate-card .bubble .txt { font-size: 13.5px; line-height: 1.75; padding: 13px 14px; }
.rail-gate-card.decided { opacity: .72; }
.rail-gate-card.decided .bubble .txt { background: var(--bg, #f3fafa); border-color: var(--line, #d8e8e8); }
.rail-gate-card.decided .txt h4::after { content: "已确认"; margin-left: 8px; padding: 1px 7px; border-radius: 999px; background: rgba(18,165,148,.12); color: var(--c-primary, #0b6e68); font-size: 11px; font-weight: 700; vertical-align: 1px; }

/* 产物区：上半清单、下半预览（原型那个 #drawer 就在下面） */
.rail-art-list { flex: 0 0 auto; max-height: 42%; overflow-y: auto; padding: 12px; }
.rail-art-list:empty { display: none; }
.ra-empty { margin: 0; color: var(--ink-3, #57727c); font-size: 12.5px; line-height: 1.9; }
.ra-row { display: flex; align-items: center; gap: 10px; width: 100%; margin-bottom: 8px; padding: 10px 12px; border: 1px solid var(--line, #d8e8e8); border-radius: 11px; background: #fff; text-align: left; cursor: pointer; transition: .16s; }
.ra-row:hover { border-color: var(--blue-200, #bfe6df); background: var(--blue-50, #effaf8); }
.ra-row.on { border-color: var(--c-primary, #12a594); background: var(--blue-50, #effaf8); }
.ra-ic { flex: 0 0 auto; width: 26px; height: 26px; border-radius: 8px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 13px; }
.ra-t { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ra-t b { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink, #0b2e36); font-size: 12.5px; font-weight: 700; }
.ra-t s { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ink-3, #57727c); font-size: 11px; text-decoration: none; }
.ra-go { flex: 0 0 auto; color: var(--c-primary, #0b6e68); font-size: 11.5px; font-weight: 700; }
#rail-pane-art { position: relative; }
#rail-pane-art.preview .rail-art-list { display: none; }
#rail-pane-art .drawer { width: auto; display: none; flex: 1 1 auto; min-height: 0; border-left: 0; transition: none; }
#rail-pane-art.preview .drawer { display: flex; }
#rail-pane-art .drawer.on, #rail-pane-art .drawer.on.wide { width: auto; }
#rail-pane-art .drawer .dh { padding: 12px 14px; }
#rail-pane-art .drawer .db { padding: 14px; }
/* 顶栏那个「收起产物」是抽屉时代的残留 —— 右栏常驻后它没有意义了 */
.wtop #btn-drawer-close { display: none !important; }

/* 窄屏：右栏改成盖在对话上的浮层，不挤走对话区 */
@media(max-width:1180px){
  .wbody { position: relative; }
  .wrail { position: absolute; top: 0; right: 0; bottom: 0; width: min(420px, 88vw); z-index: 30; box-shadow: -14px 0 34px rgba(11, 46, 54, .16); }
  /* 窄屏那套是浮层：收起时也得留出 34px，否则展开按钮同样够不着 */
  .wrail.min { transform: translateX(calc(100% - 34px)); }
}
`
/* ── P1-封面图样式 / 轮播手势样式（2026-10-12 补规则） ─────────────────────
   这两处此前是**直接改产物**加的（commit b5cda42 首页封面图、e7b2995 轮播拖拽），
   本脚本没有规则 ⇒ 重跑即静默回退。锚点取自原型 style-0.css 原文（逐字节），
   命中次数必须恰好 1 处；封面图那段是**新增**样式，插在原型原有规则之间。 */
rawCss = (() => {
  const swaps = [
    [
      '.car-view{overflow:hidden;padding:6px 2px 12px;margin:0 -2px}',
      '.car-view{overflow:hidden;padding:6px 2px 12px;margin:0 -2px;touch-action:pan-y}',
      '轮播容器横向手势（touch-action:pan-y）',
    ],
    [
      '.car-track{display:flex;gap:16px;transition:transform .55s cubic-bezier(.4,0,.2,1);cursor:grab}',
      '.car-track{display:flex;gap:16px;transition:transform .55s cubic-bezier(.4,0,.2,1);cursor:grab;user-select:none}',
      '轮播轨道拖拽期禁止选中文本（user-select:none）',
    ],
  ]
  let css = rawCss
  for (const [old, next, label] of swaps) {
    const hits = css.split(old).length - 1
    if (hits !== 1) fail(`${label}：原型样式锚点命中 ${hits} 处（应为 1）`)
    css = css.replace(old, next)
  }
  const coverAnchor = '.pcard .cover svg{position:absolute;right:14px;top:14px;opacity:.9}'
  const coverCss = `.pcard .cover-image{
  position:absolute;inset:0;width:100%;height:100%;display:block;object-fit:cover;
  z-index:1;transition:transform .25s ease
}
.pcard:hover .cover-image{transform:scale(1.04)}
`
  const hits = css.split(coverAnchor).length - 1
  if (hits !== 1) fail(`封面图样式插入点命中 ${hits} 处（应为 1）`)
  return css.replace(coverAnchor, coverCss + coverAnchor)
})()
console.log('✅ P1：首页封面图与轮播手势样式已并入（3 处）')

write('src/styles/prototype.scss', `/* 原型主样式 —— 由 scripts/port/build-port.mjs 从 数模演易原型v2.html 生成，勿手改 */\n${rawCss}\n${alias}\n${sandboxBridgeCss}\n${startBriefCss}\n${workRailCss}`)

// ── 2. core.ts：逐字搬运（剔除 1.02M 截图，换成图片 URL 表） ────────────────
/* ── P1-封面图数据：给首页 11 道题补上实拍封面（2026-10-12 补规则） ─────────
   原型卡片只有 COVER_ICON 那套 SVG 占位图；站点上线后给每题配了一张实拍封面，
   当时直接改 core.ts 产物加的 ⇒ 重跑即丢。这里只按题目 id 往 PROBLEMS 段注入
   coverImage 字段，题面 / 标签 / 浏览量等数据仍逐字节来自分片，题面改版不受影响。 */
const COVER_IMAGES = {
  p1: 'herb-drying-cover-v1.png',
  p2: 'radio-interference-localization-cover-v1.png',
  p3: 'microgrid-power-dispatch-cover-v1.png',
  p4: 'frequency-conflict-resolution-cover-v1.png',
  p5: 'sem-ad-optimization-cover-v1.png',
  p6: 'smartphone-battery-cover-v1.png',
  p7: 'space-elevator-moon-cover-v1.png',
  p8: 'dancing-stars-insight-cover-v1.png',
  p9: 'sports-management-cover-v1.png',
  p10: 'passive-solar-shading-cover-v1.png',
  p11: 'generative-ai-tradeoffs-cover-v1.png',
}
function injectCoverImages(seg) {
  let n = 0
  const out = seg.replace(
    /(\{id:'(p\d+)'[\s\S]*?cover:'[a-z]+'(?:,demo:true)?)\}/g,
    (whole, head, id) => {
      const file = COVER_IMAGES[id]
      if (!file) return whole
      n += 1
      /* 2026-10-13 补规则：磁盘产物（commit 6eaf517「首页题库封面自动生成」）把
         coverImage 指到**后端生成的** /covers/<slug>-cover.png，并把旧的静态封面
         降级成 coverFallback（<img> 的 onerror 兜底，见下面的 pcardHTML 规则）。
         本脚本此前只写 /hero-assets/… 一条 —— 重跑会把封面功能抹回去，故补上。 */
      const slug = file.replace(/-v1\.png$/, '')
      if (slug === file) fail(`封面文件名 ${file} 不是 …-v1.png 形式，推不出 /covers/ 的 slug`)
      return head + ",coverImage:'/covers/" + slug + ".png',coverFallback:'/hero-assets/" + file + "'}"
    },
  )
  /* 基准取「PROBLEMS 里实际有几道题」，不能取映射表自己的长度 ——
     后者少一条就跟着少一条，断言恒成立，等于没断言。 */
  const mapped = Object.keys(COVER_IMAGES).length
  const total = (seg.match(/\{id:'p\d+'/g) || []).length
  if (mapped !== total) fail(`封面图映射表 ${mapped} 条，而 PROBLEMS 有 ${total} 道题 —— 两边对不上`)
  if (n !== total) fail(`封面图注入 ${n} 处（应为 ${total}）`)
  return out
}

const shotMap = `var ANIM_SHOT = {\n${shotEntries.map(([, k]) => `  '${k}': './cases/p3/anim-shots/${k}.png'`).join(',\n')}\n};\n`
const parts = []
for (const r of index.app) {
  if (r.name === 'ANIM_SHOT') { parts.push(shotMap); continue }
  parts.push(r.name === 'PROBLEMS' ? injectCoverImages(segText(r)) : segText(r))
}
console.log(`✅ P1：首页封面图数据已注入（${Object.keys(COVER_IMAGES).length} 道题）`)
let core = parts.join('')

// ANIM_SHOT 由 base64 换成 URL 表后，长度必然不同 —— 用「原文 − 截图块」做基准断言
const baseline = index.app.filter((r) => r.name !== 'ANIM_SHOT').map(segText).join('')
// 逐字搬运后，除截图块位置外必须完全一致
const rebuilt = []
for (const r of index.app) rebuilt.push(r.name === 'ANIM_SHOT' ? '\u0000SHOT\u0000' : segText(r))
if (rebuilt.join('').replace(/\u0000SHOT\u0000/g, '') !== baseline) fail('A2 失败：剔除截图块后与原文不一致')
console.log('✅ A2：剔除截图块后与原文逐字节一致')

// 把每个顶层 function 导出（供桥接层挂到 window，还原原型的 inline onclick 契约）
const fnNames = index.app.filter((r) => r.kind === 'fn').map((r) => r.name)
for (const n of fnNames) {
  const re = new RegExp(`(^|\\n)function\\s+${n.replace(/\$/g, '\\$')}\\s*\\(`, 'g')
  const hits = (core.match(re) || []).length
  if (hits !== 1) fail(`导出 ${n} 时命中 ${hits} 次（应为 1）`)
  core = core.replace(re, `$1export function ${n}(`)
}
// askFocus 在本轮被删除（见下方「关注点闸门」），不能再作为全局函数还原到 window
const globalFnNames = fnNames.filter((n) => n !== 'askFocus')
const dataNames = index.app.filter((r) => r.kind === 'data' || r.kind === 'head').map((r) => r.name)
for (const n of dataNames) {
  if (n === '(head)' || n === 'ANIM_SHOT') continue
  const re = new RegExp(`(^|\\n)var\\s+${n.replace(/\$/g, '\\$')}\\s*=`, 'g')
  const hits = (core.match(re) || []).length
  if (hits !== 1) fail(`导出 ${n} 时命中 ${hits} 次（应为 1）`)
  core = core.replace(re, `$1export let ${n} =`)
}
// 截图 URL 表也要导出
core = core.replace('var ANIM_SHOT = {', 'export const ANIM_SHOT: Record<string, string> = {')

// 我的成果是静态案例：恢复本地演示开关，但不能暗示真实公开权限。
const mineRowStart = "    var on=!!w.share;\n    return '<div class=\"wcard'"
if (!core.includes(mineRowStart)) fail('示例成果行结构已变化，需复核分享入口')
const mineSwitch = /        '<div class="switch-box" onclick="event\.stopPropagation\(\)">'\+[\s\S]*?        '<\/div>'\+/
if (!mineSwitch.test(core)) fail('示例成果分享开关结构已变化')
core = core.replace(mineSwitch, `        '<div class="switch-box" onclick="event.stopPropagation()">'+
          '<span class="st" id="share-state-'+w.id+'"><b>'+(on?'演示分享已开':'演示分享已关')+'</b>仅本次访问有效</span>'+
          '<button type="button" class="sw'+(on?' on':'')+'" role="switch" aria-checked="'+on+'" aria-label="演示分享：'+esc(w.title)+'" aria-describedby="share-state-'+w.id+'" onclick="event.stopPropagation();toggleShare(\\''+w.id+'\\',this)"></button>'+
        '</div>'+`)
const mineToggle = /export function toggleShare\(id,el\)\{[\s\S]*?\n\}/
if (!mineToggle.test(core)) fail('示例成果分享处理已变化')
core = core.replace(mineToggle, `export function toggleShare(id,el){
  var w=WORKS.filter(function(x){return x.id===id})[0];
  if(!w || !el) return;
  w.share=!w.share;
  el.classList.toggle('on',w.share);
  el.setAttribute('aria-checked',String(w.share));
  var box=el.parentNode.querySelector('.st');
  if(box) box.innerHTML='<b>'+(w.share?'演示分享已开':'演示分享已关')+'</b>仅本次访问有效';
  toast('演示状态已'+(w.share?'开启':'关闭')+'，不会改变真实访问权限');
}`)
// 原 mountAnim 依赖已被外置的 <template>，重命名留档，改由桥接层用 iframe 实现
const MA = 'function mountAnim(k){'
if (!core.includes(MA)) fail('没找到 mountAnim 的原始声明，无法重命名')
core = core.replace(MA, '/* ~待 Vue 化：原实现依赖内联 <template>，演示模块已外置为独立页面，\n   此处保留原码留档，实际挂载见文件末尾的 mountAnim（iframe 版）。 */\nfunction mountAnimLegacy(k){')

// ── go()：唯一一处「改行为」的地方（视图闸门 + 路由同步） ────────────────────
// 原型是单页四视图，go(v) 只切 DOM 类名。搬进站点后必须补上「登录闸门」和
// 「URL 同步」两件事，但**不能**去重写原型那套流程逻辑 —— 所以只在这一处开口，
// 且闸门与路由都由外部注入（见 src/legacy/prototype/hooks.ts），core 不认识路由。
const GO_GUARD_ANCHOR = `!state.workTitle){ toast('先在首页输入题目'); return; }\n  state.view=v;`
if ((core.match(new RegExp(GO_GUARD_ANCHOR.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) || []).length !== 1) {
  fail('go() 的闸门锚点不是恰好 1 处')
}
core = core.replace(
  GO_GUARD_ANCHOR,
  `!state.workTitle){ toast('先在首页输入题目'); return; }\n  if(!guardView(v)) return;   /* ~迁移改动：受保护视图先过登录闸门 */\n  state.view=v;`,
)
const GO_SYNC_ANCHOR = `if(v==='mine') renderMine();`
if ((core.match(new RegExp(GO_SYNC_ANCHOR.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) || []).length !== 1) {
  fail('go() 的路由同步锚点不是恰好 1 处')
}
core = core.replace(GO_SYNC_ANCHOR, `if(v==='mine') renderMine();\n  syncViewRoute(v);   /* ~迁移改动：把视图同步进 URL，刷新不丢位置 */`)
console.log('✅ go() 已接入登录闸门与路由同步（仅此一处改动行为）')

// ── renderRunActs()：加一个「进入沙箱运行」按钮（本轮新增） ──────────────────
// 原来那两个按钮（页面内演示运行 + 环境抽屉）原样保留 —— 演示剧本不丢，改的是
// 「多给一条通向真沙箱的路」而不是替换。新按钮的去留由 hooks.sandboxReady()
// 决定：题目在沙箱里没有环境时它返回空串，按钮根本不出现，
// 不会留下「点了没反应」的死入口。
const RUN_ACTS_ANCHOR = `'<button class="abtn gh" onclick="openEnvDrawer()">查看环境信息</button>'+`
if ((core.match(new RegExp(RUN_ACTS_ANCHOR.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) || []).length !== 1) {
  fail('renderRunActs() 的按钮锚点不是恰好 1 处')
}
core = core.replace(RUN_ACTS_ANCHOR, RUN_ACTS_ANCHOR + '\n    sandboxRunBtn(qn)+')
console.log('✅ renderRunActs() 已接入「进入沙箱运行」按钮')

// ── 真实分析通道：四处开口（本轮新增） ──────────────────────────────────────
// 原则同沙箱那处：**不是重写原型流程**，只在「内容从哪来」这一个点上开口。
// 每一处都写成「先试真实、拿不到就用原来那份」，并且全部带「恰好 1 处」断言。

/** 锚点的统一检查：出现次数必须正好是 n */
function needUnique(haystack, needle, label) {
  const hits = haystack.split(needle).length - 1
  if (hits !== 1) fail(`${label}：锚点命中 ${hits} 处（应为 1）`)
}

// ① artBody()：有真实产物时，抽屉里优先展示它（原内置文档保留为兜底）
const ARTBODY_ANCHOR = `function artBody(type){\n  if(type==='analysis'){`
needUnique(core, ARTBODY_ANCHOR, 'artBody()')
core = core.replace(
  ARTBODY_ANCHOR,
  `function artBody(type){\n  /* ~接入改动：有智能体产出的分析时优先展示（没有则走下面的内置文档） */\n  var live=analysisLiveBody(type);\n  if(live) return live;\n  if(type==='analysis'){`,
)

// ② doGlobal()：全局分析这一步
const DOGLOBAL_ANCHOR = `  var k=pushMsg(m0,mkey());`
needUnique(core, DOGLOBAL_ANCHOR, 'doGlobal()')
core = core.replace(
  DOGLOBAL_ANCHOR,
  `  /* ~接入改动：有真实通道的题目优先用智能体生成的全局分析 */\n  var m0Real=analysisSlice('global',0);\n  if(m0Real) m0=m0Real;\n  var k=pushMsg(m0,mkey());`,
)

// ③ doAnalysis()：每一问的分析这一步（node 就是「这一步的内容」）
const DOANALYSIS_ANCHOR = `    renderSteps(q.step);\n    var k=pushMsg(q.analysis,mkey());\n    autoOpenArt(q.analysis,k);\n    phase='needPicks'; setPhase('needPicks');\n    applyChips(q.analysis.chips);`
needUnique(core, DOANALYSIS_ANCHOR, 'doAnalysis()')
core = core.replace(
  DOANALYSIS_ANCHOR,
  `    renderSteps(q.step);\n    /* ~接入改动：拿得到真实分析就用它，拿不到就是 q.analysis 这份内置剧本 */\n    var node=analysisSlice('question',qi)||q.analysis;\n    var k=pushMsg(node,mkey());\n    autoOpenArt(node,k);\n    phase='needPicks'; setPhase('needPicks');\n    applyChips(node.chips||q.analysis.chips);`,
)

// ④ doPicks()：候选模型这一步（节点字面量前插一层「真实优先」）
const DOPICKS_ANCHOR = `    var node={step:q.step,key:q.key+'_r'+pickRound,`
needUnique(core, DOPICKS_ANCHOR, 'doPicks()')
core = core.replace(
  DOPICKS_ANCHOR,
  `    /* ~接入改动：候选模型优先取 solution_map 里的；取不到用内置剧本 */\n    var node=analysisPicksNode(q)||{step:q.step,key:q.key+'_r'+pickRound,`,
)

// ④b doAnalysis() 定义：进这一问之前先过一道「深挖闸门」
//     不改函数体，只在它外面套一层 —— 闸门内部条件不成立时直接放行
const DOANALYSIS_DEF_ANCHOR = `export function doAnalysis(){`
needUnique(core, DOANALYSIS_DEF_ANCHOR, 'doAnalysis() 定义')
core = core.replace(
  DOANALYSIS_DEF_ANCHOR,
  `export function doAnalysis(){\n` +
    `  /* ~接入改动：全局那次没真正分析过这一问时，先为它单独跑一次深挖（可跳过） */\n` +
    `  return analysisBootQuestion(qi,doAnalysisReal);\n}\n` +
    `function doAnalysisReal(){`,
)

// ⑤ pushLab()：代码那一步补一句来源说明（真实代码 / 内置剧本要说清是哪一种）
const PUSHLAB_ANCHOR = `    html:'<h4>问题'+q.cn+'的代码已生成 · 环境已就绪</h4>'+`
needUnique(core, PUSHLAB_ANCHOR, 'pushLab()')
core = core.replace(PUSHLAB_ANCHOR, PUSHLAB_ANCHOR + `codeLabNote()+`)

// ⑤b pushLab()：快捷语按「真实 / 内置」分流（真实代码在用时不给「跑完了」这种页内运行口径）
const PUSHLAB_CHIP_ANCHOR = `  applyChips(['跑完了，看结果'],'也可以直接回一句「跑完了」');`
needUnique(core, PUSHLAB_CHIP_ANCHOR, 'pushLab() 的快捷语')
core = core.replace(
  PUSHLAB_CHIP_ANCHOR,
  `  /* ~接入改动：真实代码在用时页内不跑，快捷语改成「交给沙箱 / 本地环境」的口径 */\n` +
    `  if(codeRealOf(qi)) applyChips([],'要跑这段代码就交给沙箱或本地环境；也可以直接问我关于这份源码的问题');\n` +
    `  else applyChips(['跑完了，看结果'],'也可以直接回一句「跑完了」');`,
)

// ⑤c pushLab() 开头：真实代码到手后先灌进 q._codeText（import 自动补装跟着走真实值）
const PUSHLAB_HEAD_ANCHOR = `export function pushLab(){\n  var q=QUIZ[qi];`
needUnique(core, PUSHLAB_HEAD_ANCHOR, 'pushLab() 开头')
core = core.replace(
  PUSHLAB_HEAD_ANCHOR,
  `export function pushLab(){\n  var q=QUIZ[qi];\n` +
    `  /* ~接入改动：这一问拿到过智能体生成的源码就换上它（依赖检测、抽屉正文都跟着走真实值）*/\n` +
    `  codeApplyToQuiz(qi);`,
)

// ⑥ startWork()：进工作台时先让智能体读一遍题（一次 run 覆盖四问）
const STARTWORK_ANCHOR = `  thinking(doGlobal);\n}\n/* 第一层：全局分析（演示一） */`
needUnique(core, STARTWORK_ANCHOR, 'startWork()')
core = core.replace(
  STARTWORK_ANCHOR,
  `  /* ~接入改动：换了一道题，先摘掉上一题已上传的附件。\n` +
    `     只摘「已上传」，不动还没上传的 —— 首页是「先选文件、再点开始演易」，\n` +
    `     这个动作发生在开题那一刻，把待上传的清掉就白选了 */\n` +
    `  attachBeginProblem();\n` +
    `  /* ~接入改动：新起一道题，把三条真实通道的上一题结果清掉（动画/代码是本题专属）*/\n` +
    `  realChannelsReset();\n` +
    `  /* ~接入改动：有真实通道的题目先读题再走流程；其余题目行为不变。\n` +
    `     动画在后台生成（不阻塞对话），进度在右下角小浮窗里 */\n` +
    `  analysisBoot(state.workTitle,!!fromDetail,function(){ thinking(doGlobal); animationBoot(); });\n}\n/* 第一层：全局分析（演示一） */`,
)
console.log('✅ 真实分析通道已接入（artBody / doGlobal / doAnalysis / doPicks / pushLab / startWork）')

// ── 「开始演易」这条动作要作为一条用户消息上屏（2026-10-10，P2 第 10 条）───────
// 两件事收在同一个锚点，因为它们是同一行代码上的两个毛病：
//   ① **detailId 是全局残留**：看完题目详情页再回首页输入自己的题，它还是上一题的 id，
//      于是「这道题是不是内置演示题」判错，C 题那段内置题面会顶掉用户刚输入的题。
//      只有 fromDetail 为真时它才代表本题 —— 口径与 analysisProtoId() 保持一致。
//   ② 对话区第一条用户消息原来写的是系统回执「（题目已上传）」。可用户点的是
//      「开始演易」，留言就该是「开始演易」，否则对话读起来像没有用户参与，
//      也接不上后面「用户说了什么 → 智能体怎么答」这条线。
const STARTWORK_BUBBLE_ANCHOR =
  `  var p=PROBLEMS.filter(function(x){return x.id===state.detailId})[0];\n` +
  `  var isDemo=!!(p&&p.demo);\n` +
  `  var up=isDemo\n`
needUnique(core, STARTWORK_BUBBLE_ANCHOR, 'startWork() 的 isDemo 判定')
core = core.replace(
  STARTWORK_BUBBLE_ANCHOR,
  `  /* ~接入改动：fromDetail 为假时 detailId 是上一道题的残留，不能拿它判「是不是演示题」*/\n` +
  `  var p=fromDetail?PROBLEMS.filter(function(x){return x.id===state.detailId})[0]:null;\n` +
  `  var isDemo=!!(p&&p.demo);\n` +
  `  var up=isDemo\n`,
)

// ── 开题气泡如实列出「这次交了什么文件」（2026-10-12 新增）──────────────────
// 原来那条非演示分支无条件写「（已上传题目）<br>题面」：用户只在输入框打了字、
// 一个文件没交时它是假话；真交了文件也不说是哪几个，事后对不上。
// 这里按首页入口的**实际附件清单**分行列出（清单来源就是随后 start() 真正会
// 上传的那份 heldList('home')，所以不会说假话），每个文件沿用「已接收」那套
// chip 样式，并标出它算题目还是附件；一个文件都没有时如实写「未提交文件」。
// 只替换非演示分支的 up —— 演示分支那串内置题面文案很长，整段去动容易漏括号。
const STARTWORK_BRIEF_ANCHOR = `export function startWork(title,fromDetail){`
needUnique(core, STARTWORK_BRIEF_ANCHOR, 'startWork() 函数头')
core = core.replace(
  STARTWORK_BRIEF_ANCHOR,
  `/** 开题气泡的正文：题面 + 这次交了什么文件（~接入改动 2026-10-12） */\n` +
  `function submitBriefHTML(title){\n` +
  `  var h='<span class="ub-title">'+esc(title||'（未命名题目）')+'</span>';\n` +
  `  var H=getAnalysisHooks();\n` +
  `  var list=(H&&H.heldList)?(H.heldList('home')||[]):[];\n` +
  `  var prob=[],data=[];\n` +
  `  for(var i=0;i<list.length;i++){\n` +
  `    if(list[i].kind==='problem') prob.push(list[i].name); else data.push(list[i].name);\n` +
  `  }\n` +
  `  if(prob.length||data.length){\n` +
  `    h+='<div class="ub-files">'+submitChipsHTML('题目',prob)+submitChipsHTML('附件',data)+'</div>';\n` +
  `  }else{\n` +
  `    h+='<div class="ub-none">未提交文件</div>';\n` +
  `  }\n` +
  `  return h;\n` +
  `}\n` +
  `function submitChipsHTML(kind,names){\n` +
  `  var h='';\n` +
  `  for(var i=0;i<names.length;i++){\n` +
  `    h+='<span class="fchip">'+IC_FILE+'<span><b>'+esc(names[i])+'</b><span> · '+kind+'</span></span></span>';\n` +
  `  }\n` +
  `  return h;\n` +
  `}\n` +
  STARTWORK_BRIEF_ANCHOR,
)

// 首条用户消息前面补上「用户干了什么」。**不改** up 那个三元表达式（里面含很长的
// 内置题面文案），只在 pushMsg 之前加一行 —— 动三元容易漏括号，core 是生成物，
// 漏一个括号会让整页白屏（vue-tsc 会报 TS1005，别跳过类型检查）。
const STARTWORK_PUSH_ANCHOR = `  pushMsg({who:'me',html:up},mkey());`
needUnique(core, STARTWORK_PUSH_ANCHOR, 'startWork() 的首条用户消息 pushMsg')
core = core.replace(
  STARTWORK_PUSH_ANCHOR,
  `  /* ~接入改动：这条用户消息记的是「用户干了什么」+「交了什么文件」，不是一条系统回执 */\n` +
  `  if(!isDemo) up=submitBriefHTML(state.workTitle);\n` +
  `  up=(fromDetail?'在平台复现一遍':'开始演易')+'<br>'+up;\n` +
  STARTWORK_PUSH_ANCHOR,
)
console.log('✅ 开题那条用户消息已如实记动作（开始演易 / 在平台复现一遍）+ 交了什么文件 + detailId 残留已收口')

// ── 附件真上传：一处开口（本轮新增） ────────────────────────────────────────
// 原型那两个「上传题目数据文件」按钮，选中文件后只做两件事：toast 报个文件名、
// 把「（已上传题目文件）xxx」塞进输入框 —— 文件本身读完就丢，从来没上传过。
// 这里把「选中之后干什么」换掉，交给桥接层去校验、暂存、开题（或在工作台里
// 选完）时传到本题会话。按钮与输入框结构一律不动。
const PICKFILE_ANCHOR =
  `      var names=[]; for(var i=0;i<fs.length;i++) names.push(fs[i].name);\n` +
  `      toast('已选择 '+fs.length+' 个文件：'+names.join('、'),\n` +
  `        '<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="#7FE0A8" stroke-width="2.4" stroke-linecap="round"><path d="M20 6L9 17l-5-5"/></svg>');\n` +
  `      if(scope==='home'){\n` +
  `        var ta=$('home-input');\n` +
  `        if(!ta.value.trim()) ta.value='（已上传题目文件）'+names[0];\n` +
  `      }else{\n` +
  `        var ta2=$('work-input');\n` +
  `        if(!ta2.value.trim()) ta2.value='（已上传附件数据）'+names[0];\n` +
  `      }`
needUnique(core, PICKFILE_ANCHOR, 'pickFile()')
/* 本轮改动：pickFile 多收一个 kind（'problem' = 题目文件 / 'data' = 数据附件）。
   kind 决定文件落进哪个槽 —— 题面可以从题目文件来，附件只做补充材料。
   闭包捕获，不用全局变量：选择框是模态的，但全局变量会在「两次点击」之间走样。 */
const PICKFILE_HEAD = `export function pickFile(scope){`
needUnique(core, PICKFILE_HEAD, 'pickFile() 函数头')
core = core.replace(PICKFILE_HEAD, `export function pickFile(scope,kind){`)
core = core.replace(
  PICKFILE_ANCHOR,
  `      var names=[]; for(var i=0;i<fs.length;i++) names.push(fs[i].name);\n` +
    `      /* ~接入改动：真的把文件交出去（校验 + 暂存，开题或在工作台里选完即上传）。\n` +
    `         原型这里是把文件名写进输入框冒充「已上传」，文件读完就丢 —— 这次换掉 */\n` +
    `      attachPick(scope,fs,names,kind||'data');`,
)
console.log('✅ 附件真上传已接入（pickFile / pickData / pickProblem / startWork）')

// ── 首页「开始演易」的准入：题目文件也算一份题面（本轮新增） ────────────────
// 原来只有输入框有字才放行，于是「传了题目文件、没打字」这一步被挡在门外。
// 现在两种都认：手写的题面，或上传的题目文件（后者把文件名当标题递进去，
// 真正的题面由题目文件本身承担 —— 见 flow.resolveCase 的第 4 个参数）。
const SENDFROMHOME_ANCHOR =
  `export function sendFromHome(){\n` +
  `  var v=$('home-input').value.trim();\n` +
  `  if(!v){ toast('请输入题目内容或上传题目文件'); return; }\n` +
  `  startWork(v);\n` +
  `}`
needUnique(core, SENDFROMHOME_ANCHOR, 'sendFromHome()')
core = core.replace(
  SENDFROMHOME_ANCHOR,
  `export function sendFromHome(){\n` +
    `  var v=$('home-input').value.trim();\n` +
    `  /* ~接入改动：只传了题目文件也算「可以开始演易」*/\n` +
    `  var paper=attachProblemTitle('home');\n` +
    `  if(!v&&!paper){ toast('请先在输入框写下题目，或点「上传题目」把题目文件传上来'); return; }\n` +
    `  /* ~接入改动（2026-10-14）：与 analysisRef 同一判据的准入闸 —— 题面太短又没有\n` +
    `     题目文件就拦下，别开出一条注定跑不动的任务（对话区连一句话都发不出去）。 */\n` +
    `  var H=getAnalysisHooks();\n` +
    `  var ref=(H&&H.resolveCase)?H.resolveCase('', v||paper, false, attachHasProblem('home')):null;\n` +
    `  if(!ref){ toast('这道题还开不了工：把题干写全一点（不少于 20 字），或传一份题目文件'); return; }\n` +
    `  startWork(v||paper);\n` +
    `}`,
)
console.log('✅ 首页准入已放开（题面文字 或 题目文件 任一即可开始演易）')

// ── 删掉「关注点」闸门（本轮新增） ─────────────────────────────────────────
// 原型在工作台入口会先弹一张「你最关心这道题的哪个方面？」（askFocus）的卡片，
// 让用户先挑一个角度。它对后面的分析与模型选择其实没有影响，属于无谓的人工介入，
// 用户要求删除 —— 入口那一步直接进分析。
// 注意：摘的是**引用与定义**，不是「留着函数不调用」—— 留着的话导出表与全局还原表里
// 会多一个没人调用的死口子（fnNames 里还有它，运行时会 ReferenceError）。
const FOCUS_DROP = [
  {
    from: `{t:'理解正确，继续',cls:'pri',ico:IC_OK,run:function(){ askFocus(); }},`,
    to: `{t:'理解正确，继续',cls:'pri',ico:IC_OK,run:function(){ doAnalysis(); }},`,
    label: 'askFocus ①：理解确认卡的主按钮改走分析',
  },
  {
    from: `    onSkip:function(){ askFocus(); }`,
    to: `    onSkip:function(){ doAnalysis(); }`,
    label: 'askFocus ②：理解确认卡的跳过改走分析',
  },
  {
    from: `  needFocus:'等待：选择关注重点',\n`,
    to: ``,
    label: 'askFocus ③：阶段文案表里的条目',
  },
  {
    from: `  if(phase==='needFocus') return doPicks();\n`,
    to: ``,
    label: 'askFocus ④：续跑分派里的分支',
  },
  {
    from: `export function askFocus(){
  pushFlowGate({
    key:'focus-'+qi+'-'+SEQ,
    label:'全局分析关注点', pref:'focus', phase:'needFocus', step:0,
    html:'<h4>你最关心这道题的哪个方面？</h4><p>先选一个最想优先看懂的角度，我会在后续解释中把它讲得更具体。</p>',
    options:[
      {t:'数据',value:'data',run:function(){ doAnalysis(); }},
      {t:'模型',value:'model',run:function(){ doAnalysis(); }},
      {t:'结果',value:'result',run:function(){ doAnalysis(); }},
      {t:'论文表达',value:'paper',run:function(){ doAnalysis(); }}
    ],
    onSkip:function(){ doAnalysis(); }
  });
}
`,
    to: `/* ~接入改动：「关注点」闸门（askFocus）按用户要求整段删除，入口直接进分析 */\n`,
    label: 'askFocus ⑤：函数定义整段',
  },
]
for (const p of FOCUS_DROP) needUnique(core, p.from, p.label)
for (const p of FOCUS_DROP) core = core.replace(p.from, p.to)
console.log('✅ 「关注点」闸门（askFocus）已整段删除（定义 + 2 处入口引用 + 阶段文案 + 续跑分派）')

// ── S2 真实动画通道：四处开口（本轮新增） ──────────────────────────────────
// 原则同前面几处：**不是重写原型流程**，只在「内容从哪来」这一个点上开口。

// ① regAnims()：真实通道接管期间不登记内置演示（真实档位由 animationBoot 登记）
const REGANIMS_ANCHOR = `export function regAnims(m){\n  if(!m||!m.arts) return;`
needUnique(core, REGANIMS_ANCHOR, 'regAnims()')
core = core.replace(
  REGANIMS_ANCHOR,
  `export function regAnims(m){\n` +
    `  /* ~接入改动：真实动画接管期间不登记内置演示（免得真实与内置混在一张列表里）*/\n` +
    `  if(animRealActive()){ animRealRegister(); return; }\n` +
    `  if(!m||!m.arts) return;`,
)

// ② artBody('animation')：真实通道试过就在这一档给「真实 / 失败」两种口径
const ARTBODY_ANIM_ANCHOR = `  if(type==='animation'){\n    var qs=animList();`
needUnique(core, ARTBODY_ANIM_ANCHOR, "artBody('animation')")
core = core.replace(
  ARTBODY_ANIM_ANCHOR,
  `  if(type==='animation'){\n` +
    `    /* ~接入改动：真实通道试过就交给它（含「生成中 / 没生成出来」的实话）*/\n` +
    `    var realAnim=animRealDrawerHTML();\n    if(realAnim) return realAnim;\n` +
    `    var qs=animList();`,
)

// ③ drawerAnim()：舞台里挂真实动画（blob URL iframe），内置档位照旧用截图
const DRAWERANIM_ANCHOR = `  ANIM_CUR=k;\n  var st=$('d-stage'); if(st) st.innerHTML=fsBtnHTML(k)+animShotHTML(k);   /* 截图占位 + 全屏按钮 */`
needUnique(core, DRAWERANIM_ANCHOR, 'drawerAnim()')
core = core.replace(
  DRAWERANIM_ANCHOR,
  `  ANIM_CUR=k;\n` +
    `  /* ~接入改动：真实档位用 blob URL 挂 iframe（与全屏层同一隔离口径）*/\n` +
    `  var stageShot=animRealURL(k)?animRealFrameHTML(k):animShotHTML(k);\n` +
    `  var st=$('d-stage'); if(st) st.innerHTML=fsBtnHTML(k)+stageShot;   /* 截图占位 + 全屏按钮 */\n` +
    `  wireAnimTabs();   /* ~接入改动：真实列表是渲染后才有的节点，事件在这里补绑 */`,
)

// ── S3 真实代码通道：六处开口（本轮新增） ──────────────────────────────────

// ① choosePick()：选完模型先让智能体按它写这一问的源码包
const CHOOSEPICK_ANCHOR =
  `    var k=pushMsg({who:'ai',html:o.reply.html,arts:o.reply.arts},mkey());\n` +
  `    applyChips([],'下一步：设定参数并在当前页面运行代码');\n` +
  `    setTimeout(function(){ openArtOfType({arts:o.reply.arts},k,'code'); },350);\n` +
  `    setTimeout(askParameter,1000);`
needUnique(core, CHOOSEPICK_ANCHOR, 'choosePick() 尾部')
core = core.replace(
  CHOOSEPICK_ANCHOR,
  `    /* ~接入改动：真实代码通道 —— 选完模型先让智能体按这个方案写这一问的源码包；\n` +
    `       拿到真实代码就不再播那段内置剧本的回复（诚实性：不把内置说成生成）*/\n` +
    `    var k;\n` +
    `    if(codegenReady()){\n` +
    `      k=pushMsg({who:'ai',html:codegenReplyNode(o,qi),arts:[AR('code',(o.name||'')+' · 生成的源码','智能体按题面生成')]},mkey());\n` +
    `      applyChips([],codegenRunHint());\n` +
    `      codeBoot(qi,{name:o.name,desc:o.desc},function(){\n` +
    `        codeApplyToQuiz(qi);\n` +
    `        setTimeout(function(){ openArtOfType({arts:[AR('code',(o.name||'')+' · 生成的源码','智能体按题面生成')]},k,'code'); },350);\n` +
    `        setTimeout(askParameter,1000);\n` +
    `      });\n` +
    `    }else{\n` +
    `      k=pushMsg({who:'ai',html:o.reply.html,arts:o.reply.arts},mkey());\n` +
    `      applyChips([],'下一步：设定参数并在当前页面运行代码');\n` +
    `      setTimeout(function(){ openArtOfType({arts:o.reply.arts},k,'code'); },350);\n` +
    `      setTimeout(askParameter,1000);\n` +
    `    }`,
)

// ② artBody('code')：有真实源码包时展示它（内置那份保留为兜底）
const ARTBODY_CODE_ANCHOR = `  if(type==='code'){`
needUnique(core, ARTBODY_CODE_ANCHOR, "artBody('code')")
core = core.replace(
  ARTBODY_CODE_ANCHOR,
  `  if(type==='code'){\n` +
    `    /* ~接入改动：这一问拿到过真实源码包就展示它 */\n` +
    `    var realCode=codeRealBody(qi);\n    if(realCode) return realCode;`,
)

// ③ openDrawer()：正文渲染后补绑真实代码的按钮（复制/下载是渲染后才有的节点）
const OPENDRAWER_ANCHOR = `  $('drawer-body').innerHTML=artBody(type);`
needUnique(core, OPENDRAWER_ANCHOR, 'openDrawer()')
core = core.replace(
  OPENDRAWER_ANCHOR,
  OPENDRAWER_ANCHOR + `\n  if(type==='code') wireRealCodeActions();   /* ~接入改动：真实代码的复制/下载在这里接上 */`,
)

// ④ renderRunActs()：真实代码在用时页内不给模拟运行
const RUNACTS_ANCHOR = `export function renderRunActs(qn){\n  var q=QUIZ[qn]||{};`
needUnique(core, RUNACTS_ANCHOR, 'renderRunActs()')
core = core.replace(
  RUNACTS_ANCHOR,
  `export function renderRunActs(qn){\n` +
    `  /* ~接入改动：智能体生成的代码只生成不运行 —— 页内那个「▶ 运行代码」打的是内置剧本的输出，\n` +
    `     挂在真实源码下面等于把示意值说成实算结果，所以换成沙箱 / 本地环境的通路 */\n` +
    `  if(codeRealOf(qn)) return codeLabActs(qn);\n` +
    `  var q=QUIZ[qn]||{};`,
)

// ⑤ skipStep() 的 lab 分支：跳过时明说这批数字是示意值
const SKIPLAB_ANCHOR = `    pushMsg({who:'me',html:'跳过这一步，直接用预设结果继续'},mkey());\n    thinking(function(){ pushAfterRunOK(qi,QUIZ[qi]); }); return;`
needUnique(core, SKIPLAB_ANCHOR, 'skipStep() 的 lab 分支')
core = core.replace(
  SKIPLAB_ANCHOR,
  `    pushMsg({who:'me',html:'跳过这一步，直接用预设结果继续'},mkey());\n` +
    `    /* ~接入改动：真实代码在用时，往下走的是内置演示结果，得说清那是示意值 */\n` +
    `    if(codeRealOf(qi)) pushMsg({who:'ai',html:codeSampleNotice()},mkey());\n` +
    `    thinking(function(){ pushAfterRunOK(qi,QUIZ[qi]); }); return;`,
)

// ⑥ lab 阶段的自由回复：真实代码在用时不能再指「上一条消息里的运行按钮」
const LABNUDGE_ANCHOR = `  if(phase==='lab'){\n    if(/跑完|运行完|出来了|做完了|好了|完成/.test(v)){`
needUnique(core, LABNUDGE_ANCHOR, 'lab 阶段的自由回复')
core = core.replace(
  LABNUDGE_ANCHOR,
  `  if(phase==='lab'){\n` +
    `    /* ~接入改动：真实代码只生成不运行，页内没有运行按钮可指 */\n` +
    `    if(codeRealOf(qi)) return nudge('<h4>这段代码只生成不运行</h4><p>这一问的源码由解题代码智能体按题面生成，页内不给模拟运行。要跑就交给沙箱，或在本地环境里执行 —— 结果出来后贴回来，我接着帮你解读。</p>'+NEXT_TAIL);\n` +
    `    if(/跑完|运行完|出来了|做完了|好了|完成/.test(v)){`,
)
console.log('✅ 真实动画/代码通道已接入（regAnims / artBody×2 / drawerAnim / choosePick / openDrawer / renderRunActs / skipStep / pushLab）')

// ── 产物卡（Step3）：通用文本抽屉（本轮新增） ──────────────────────────────

/* core 里那个抽屉只认内置键：openDrawer(type,title,sub) 的内容由 artBody(type) 现算。
   产物是**任意文件**，内容已在 TS 侧取好、只把 HTML 交进来，所以另开一个入口，
   复用同一个抽屉外壳（含标题栏、遮罩、右上角关闭按钮）。 */
const ARTIFACT_VIEW_ANCHOR = `export function openArt(type,key){`
needUnique(core, ARTIFACT_VIEW_ANCHOR, 'openArt()')
core = core.replace(
  ARTIFACT_VIEW_ANCHOR,
  `/* ~接入改动（2026-10-09）：通用文本抽屉 —— 产物卡点开走这里。\n` +
    `   不复用 openDrawer(type)：那个按内置键去算 artBody，产物是任意文件名。 */\n` +
    `export function openArtifactView(title,sub,html){\n` +
    `  state.drawerOpen=true; state.drawerType='artifact';\n` +
    `  var d=$('drawer'); if(!d) return;\n` +
    `  d.classList.add('on'); d.classList.remove('wide');\n` +
    `  var t=$('d-title2'); if(t) t.innerHTML=esc(title||'产物')+'<span class="sub">'+esc(sub||'')+'</span>';\n` +
    `  var b=$('drawer-body'); if(b) b.innerHTML=html||'';\n` +
    `  var c=$('btn-drawer-close'); if(c) c.style.display='';\n` +
    `}\n` +
    ARTIFACT_VIEW_ANCHOR,
)
console.log('✅ 产物卡：通用文本抽屉已接入（openArtifactView）')

// ── 顶层 IIFE 改成具名导出 ────────────────────────────────────────────────
// 原型末尾有两处随文档自动执行的 IIFE（启动初始化、轮播拖拽接线）：
//   (function init(){ ... })()            挂输入框事件、渲染轮播/筛选/网格
//   (function(){ ... })()                 给 #car-view / #car-track 接拖拽
// 在 <script> 里它们跑的时候 DOM 已经在了；搬进 ESM 之后会在 import 那一刻执行，
// 那时 Vue 还没挂载，$('d-tabs') 是 null → 抛异常、整个应用白屏。
// 所以改成导出函数，由 PrototypeShell 在 onMounted 里按序调用。
const openers = [...baseline.matchAll(/\n\(function/g)].map((m) => m.index)
const closers = [...baseline.matchAll(/\n\}\)\(\);/g)].map((m) => m.index)
if (openers.length !== 2) fail(`顶层 IIFE 开头有 ${openers.length} 处（预期 2）`)
if (closers.length !== 2) fail(`顶层 IIFE 结尾有 ${closers.length} 处（预期 2）`)

const CAROUSEL_HEAD = '(function(){\n  var v=document.getElementById(\'car-view\');'
if (!core.includes(CAROUSEL_HEAD)) fail('没找到轮播拖拽的匿名 IIFE 开头')
core = core.replace(CAROUSEL_HEAD, 'export function installCarouselDrag(){\n  var v=document.getElementById(\'car-view\');')
const IIFE_OPEN = '(function init(){'
if (!core.includes(IIFE_OPEN)) fail('没找到 init IIFE 开头')
core = core.replace(IIFE_OPEN, 'export function initPrototype(){')
// 收口：两个 `})();` 都换成 `}`（前一个是轮播、后一个是 init，从后往前替换）
const closerIdx = [...core.matchAll(/\n\}\)\(\);/g)].map((m) => m.index)
if (closerIdx.length !== 2) fail(`改完后 IIFE 结尾仍有 ${closerIdx.length} 处（预期 2）`)
for (const at of [closerIdx[1], closerIdx[0]]) {
  core = core.slice(0, at) + '\n}' + core.slice(at + '\n})();'.length)
}
console.log('✅ 两处顶层 IIFE 已改为具名导出（initPrototype / installCarouselDrag）')

// ── P1-卡片渲染：封面图优先于 SVG 占位图（2026-10-12 补规则） ──────────────
// pcardHTML 原来只渲染 COVER_ICON 里的 SVG。站点版改成「有实拍封面就上图，
// 没有才退回 SVG」，图片懒加载 + 绝对定位铺满（样式见 prototype.scss 的 .cover-image）。
const PCARD_HEAD = `export function pcardHTML(p){\n  return '<button class="pcard" onclick="openDetail(\\''+p.id+'\\')">'+`
needUnique(core, PCARD_HEAD, 'pcardHTML() 函数头')
core = core.replace(
  PCARD_HEAD,
  `/**\n` +
    ` * 卡片封面。\n` +
    ` *\n` +
    ` * \`coverImage\` 指向**后端生成的** \`/covers/<slug>-cover.png\`（\`ProblemCoverController\` 落盘）。\n` +
    ` * ⚠ 该控制器至今不存在 ⇒ 这些请求**恒 404**：\`onerror\` 兜住了视觉，代价是每张卡白打一次\n` +
    ` * 请求、后端日志被它们刷满（真故障会被淹没）。故当前**优先取本地静态封面** \`coverFallback\`\n` +
    ` * （\`/hero-assets/…\`，11 张都在）；后端真做出封面生成后，把下面 \`var src=\` 的候选次序换回来。\n` +
    ` *\n` +
    ` * 两者都没有才落在内联 SVG 图标上 —— 不这么兜的话，任何一张缺失就是碎图。\n` +
    ` */\n` +
    `export function pcardHTML(p){\n` +
    `  var cover;\n` +
    `  var src = p.coverFallback || p.coverImage;\n` +
    `  if(src){\n` +
    `    var spare = src === p.coverImage ? p.coverFallback : p.coverImage;\n` +
    `    var onerr = spare\n` +
    `      ? ' onerror="this.onerror=null;this.src=\\''+esc(spare)+'\\'"'\n` +
    `      : ' onerror="this.style.display=\\'none\\'"';\n` +
    `    cover = '<img class="cover-image" src="'+esc(src)+'" alt="'+esc(p.title)\n` +
    `      +' 预览图" loading="lazy"'+onerr+'>';\n` +
    `  } else {\n` +
    `    cover = COVER_ICON[p.cover]||COVER_ICON.chart;\n` +
    `  }\n` +
    `  return '<button class="pcard" onclick="openDetail(\\''+p.id+'\\')">'+`,
)
const PCARD_COVER_OLD = `    '<div class="cover">'+(COVER_ICON[p.cover]||COVER_ICON.chart)+'</div>'+`
needUnique(core, PCARD_COVER_OLD, 'pcardHTML() 封面容器')
core = core.replace(PCARD_COVER_OLD, `    '<div class="cover">'+cover+'</div>'+`)
console.log('✅ P1：首页卡片封面图渲染已接入（pcardHTML）')

// ── P1-轮播手势：Pointer Events 版拖拽（2026-10-12 补规则） ────────────────
// 原型那版只认鼠标（mousedown/mousemove/mouseup + 48px 阈值翻页），另有一组
// touchstart/touchend（40px 阈值）。站点版（commit e7b2995）统一改成 Pointer
// Events：鼠标与触摸走同一条路径，拖拽跟手（rubber 阻尼）、松手带惯性（fling），
// 且**只在移动超过 4px 时才 setPointerCapture** —— 否则 pointerup 会被重定向到
// car-view，浏览器不在 pcard 上生成 click，卡片的 inline onclick 会失效。
// 整段替换而非逐行改：这是一段自成一体的交互实现，混着改看不清边界。
const CAROUSEL_POINTER_IMPL = `export function installCarouselDrag(){
  var v=document.getElementById('car-view');
  var tr=document.getElementById('car-track');
  if(!v||!tr) return;
  var down=false, sx=0, lastX=0, lastTime=0, velocity=0, moved=false;
  var dragDx=0;
  var rubber=0.28;
  var wrapIndex=function(i){
    if(carMax<=0) return 0;
    return ((i%(carMax+1))+(carMax+1))%(carMax+1);
  };
  var renderDrag=function(dx){
    var raw=carIdx*carStep-dx;
    var min=0, max=carMax*carStep;
    if(raw<min) raw=raw+(min-raw)*rubber;
    if(raw>max) raw=raw-(raw-max)*rubber;
    tr.style.transform='translateX('+(-raw)+'px)';
  };
  var finishDrag=function(){
    var distance=dragDx;
    var target=Math.round((carIdx*carStep-distance)/carStep);
    var fling=Math.abs(velocity)>0.45;
    if(fling && Math.abs(distance)<carStep*0.5) target=carIdx+(velocity<0?1:-1);
    carIdx=wrapIndex(target);
    tr.classList.remove('grabbing');
    carRender();
    if(state.view==='home') startCarousel();
  };
  v.addEventListener('mouseenter',stopCarousel);
  v.addEventListener('mouseleave',function(){ if(!down&&state.view==='home') startCarousel(); });
  v.addEventListener('pointerdown',function(e){
    if(e.pointerType==='mouse'&&e.button!==0) return;
    down=true; moved=false; sx=lastX=e.clientX; lastTime=Date.now(); velocity=0; dragDx=0;
    tr.classList.add('grabbing'); stopCarousel();
    /* 不在 pointerdown 时就 setPointerCapture —— 那会让 pointerup/mouseup 重定向到
       car-view，浏览器因此不在 pcard 按钮上生成 click，inline onclick 失效。
       改为仅在真正开始拖拽（移动超 4px）时才捕获，纯点击不受影响。 */
  });
  v.addEventListener('pointermove',function(e){
    if(!down) return;
    var now=Date.now(), dx=e.clientX-sx, dt=now-lastTime;
    if(Math.abs(dx)>4){
      if(!moved){ try{ v.setPointerCapture(e.pointerId); }catch(err){} }
      moved=true;
    }
    if(dt>0) velocity=(e.clientX-lastX)/dt;
    lastX=e.clientX; lastTime=now; dragDx=dx;
    renderDrag(dx);
    if(moved) e.preventDefault();
  });
  var release=function(e){
    if(!down) return;
    if(e.pointerId!==undefined){
      try{ v.releasePointerCapture(e.pointerId); }catch(err){}
    }
    down=false;
    finishDrag();
  };
  v.addEventListener('pointerup',release);
  v.addEventListener('pointercancel',release);
  /* 拖动过程中松手会触发卡片的 click，这里拦截掉 */
  tr.addEventListener('click',function(e){
    if(moved){ e.preventDefault(); e.stopPropagation(); moved=false; }
  },true);
  window.addEventListener('resize',function(){ carMeasure(); carRender(); });
}`
const carouselRe = /export function installCarouselDrag\(\)\{[\s\S]*?\n\}/
const carouselHits = (core.match(new RegExp(carouselRe.source, 'g')) || []).length
if (carouselHits !== 1) fail(`轮播拖拽实现命中 ${carouselHits} 处（应为 1）`)
core = core.replace(carouselRe, () => CAROUSEL_POINTER_IMPL)
console.log('✅ P1：轮播拖拽已换成 Pointer Events 版（跟手阻尼 · 松手惯性 · 触屏同路）')

// ══════════════════════════════════════════════════════════════════════════
// 内置演示剧本：写死内容整体删除（2026-10-08 用户定调）
//
// 背景：工作台的对话内容要 **100% 来自智能体**。原型里那套针对 2026 国赛 C 题的
// 写死演示 —— 开场白（INTRO_NODE）、四问的分析 / 候选模型 / 结果 / 排查建议
// （QUIZ 的 B 类字段）、页内「运行代码」的假输出（SIM_RUN_OUT）、论文节点的
// 写死快捷语（PAPER_CHIPS）—— 整体清掉；同时把所有「拿不到真产物就用剧本顶」
// 的回落点改成**如实说明**（不再有任何一条路能读到写死内容）。
//
// 怎么删才稳：这几个常量在分片产物里各是一段独立文本，用**整段精确替换**而不是
// 跨行正则 —— 段文本是逐字节取来的，可以断言命中次数，不怕原型改版、也不会误伤
// 搬运区其它内容（A2 的逐字节断言只比对 index.app 重建结果，不受 core 后续替换影响）。
//
// 保留什么：QUIZ 的骨架字段 n / cn / step / key / file / codeName。它们是流程的
// 契约 —— 跳步、步骤条、产物文件名、沙箱交接单都按它们取值。字段类型写成 any，
// 因为原型那套 `q.analysis` / `q.picks` 访问点还在源码里（本轮把它们改成
// 「拿不到就如实说明」），留一个假形状反而更危险。
const SCRUB_SEGS = [
  {
    name: 'QUIZ',
    label: '四问的分析 / 候选模型 / 结果 / 排查建议',
    text:
      'export let QUIZ =[\n' +
      '/* ~接入改动（2026-10-08）：内置演示剧本已删除，这里只保留流程骨架字段。\n' +
      '   分析 / 候选模型 / 结果 / 排查建议一律由智能体实时生成。 */\n' +
      "{n:1,cn:'一',step:1,key:'q1',file:'result1.xlsx',codeName:'main.py'},\n" +
      "{n:2,cn:'二',step:2,key:'q2',file:'result2.xlsx',codeName:'model_q2.py'},\n" +
      "{n:3,cn:'三',step:3,key:'q3',file:'result3.xlsx',codeName:'model_q3.py'},\n" +
      "{n:4,cn:'四',step:4,key:'q4',file:'result4-2.xlsx、result4-3.xlsx',codeName:'model_q4.py'}\n" +
      '] as any[];\n',
  },
  { name: 'INTRO_NODE', label: '进工作台的开场白（2026 C 题整段讲解）', text: 'export let INTRO_NODE ={step:0} as any;\n' },
  { name: 'SIM_RUN_OUT', label: '页内「运行代码」的假输出', text: 'export let SIM_RUN_OUT ={} as any;\n' },
  { name: 'PAPER_CHIPS', label: '论文节点的写死快捷语', text: 'export let PAPER_CHIPS =[] as any[];\n' },
]
for (const s of SCRUB_SEGS) {
  const row = index.app.find((r) => r.name === s.name)
  if (!row) fail(`清空内置演示剧本：分片产物里找不到 ${s.name}`)
  /* 注意：这段在 core 里已经过「顶层声明导出」处理，原始 `var X =` 已变成
     `export let X =` —— 锚点必须按 core 里的现形取，否则一处都命中不了 */
  const original = segText(row).replace(
    new RegExp('(^|\\n)var\\s+' + s.name + '\\s*='),
    '$1export let ' + s.name + ' =',
  )
  needUnique(core, original, `清空 ${s.name}（${s.label}）`)
  core = core.replace(original, s.text)
  console.log(`   ✂ 已清空：${s.name} —— ${s.label}`)
}

/** 正则版锚点：命中次数必须正好 1（跨行的锚点用不了 needUnique） */
function needRe(re, label) {
  const hits = core.match(new RegExp(re.source, 'g'))
  if (!hits || hits.length !== 1) fail(`${label}：正则命中 ${hits ? hits.length : 0} 处（应为 1）`)
}

const SCRUB_PATCHES = [
  {
    // ① 开场白：不再有内置剧本这条默认值（`auto:` 那一版属 doGlobal）
    label: 'doGlobal()：开场白不再取内置剧本',
    re: /  var m0=INTRO_NODE;\n  if\(!isDemo\)\{\n    m0=\{who:'ai',step:0,\n      html:'<div class="tip-line"[\s\S]{0,400}?arts:INTRO_NODE\.arts,auto:INTRO_NODE\.auto,chips:INTRO_NODE\.chips\};\n  \}\n/,
    to: `  /* ~接入改动（2026-10-08）：开场白只认智能体的全局分析产出，内置剧本已删 */\n`,
  },
  {
    label: 'startWork()：开场白不再取内置剧本',
    re: /  var m0=INTRO_NODE;\n  if\(!isDemo\)\{\n    m0=\{who:'ai',step:0,\n      html:'<div class="tip-line"[\s\S]{0,400}?arts:INTRO_NODE\.arts,chips:INTRO_NODE\.chips\};\n  \}\n/,
    to: `  /* ~接入改动（2026-10-08）：开场白只认智能体的全局分析产出，内置剧本已删 */\n`,
  },
  {
    // ② 全局分析：拿不到真产物就如实说，不再拿剧本顶
    label: 'doGlobal()：全局分析只认实时产出',
    re: /  \/\* ~接入改动：有真实通道的题目优先用智能体生成的全局分析 \*\/\n  var m0Real=analysisSlice\('global',0\);\n  if\(m0Real\) m0=m0Real;\n  var k=pushMsg\(m0,mkey\(\)\);\n  autoOpenArt\(m0,k\);/,
    to:
      `  /* ~接入改动（2026-10-08）：全局分析只认智能体的实时产出 —— 内置剧本已删，\n` +
      `     拿不到就如实说明，不拿写死内容冒充 */\n` +
      `  var m0Real=analysisSlice('global',0);\n` +
      `  if(!m0Real){\n` +
      `    pushMsg({who:'ai',html:analysisMissingHTML('全局分析')},mkey());\n` +
      `    setTimeout(askUnderstanding,500);\n` +
      `    return;\n` +
      `  }\n` +
      `  var k=pushMsg(m0Real,mkey());\n` +
      `  autoOpenArt(m0Real,k);`,
  },
  {
    // ③ 每一问的分析
    label: 'doAnalysisReal()：这一问的分析只认实时产出',
    re: /    \/\* ~接入改动：拿得到真实分析就用它，拿不到就是 q\.analysis 这份内置剧本 \*\/\n    var node=analysisSlice\('question',qi\)\|\|q\.analysis;\n    var k=pushMsg\(node,mkey\(\)\);\n    autoOpenArt\(node,k\);\n    phase='needPicks'; setPhase\('needPicks'\);\n    applyChips\(node\.chips\|\|q\.analysis\.chips\);/,
    to:
      `    /* ~接入改动（2026-10-08）：这一问的分析只认智能体的实时产出 —— 内置剧本已删 */\n` +
      `    var node=analysisSlice('question',qi);\n` +
      `    if(!node){\n` +
      `      pushMsg({who:'ai',html:analysisMissingHTML('问题'+q.cn+'的分析')},mkey());\n` +
      `      phase='needPicks'; setPhase('needPicks');\n` +
      `      applyChips([]);\n` +
      `      return;\n` +
      `    }\n` +
      `    var k=pushMsg(node,mkey());\n` +
      `    autoOpenArt(node,k);\n` +
      `    phase='needPicks'; setPhase('needPicks');\n` +
      `    applyChips(node.chips||[]);`,
  },
  {
    // ④ 候选模型
    label: 'doPicks()：候选模型只认实时产出',
    re: /    \/\* ~接入改动：候选模型优先取 solution_map 里的；取不到用内置剧本 \*\/\n    var node=analysisPicksNode\(q\)\|\|\{step:q\.step,key:q\.key\+'_r'\+pickRound,\n      html:'<h4>'\+esc\(q\.picksTitle\)\+'<\/h4>'\+q\.picksIntro\+\n        '<p class="next">[\s\S]{0,200}?<\/p>',\n      picks:q\.picks\};\n    pushMsg\(node,mkey\(\)\);/,
    to:
      `    /* ~接入改动（2026-10-08）：候选模型只认智能体给的 solution_map —— 内置剧本已删 */\n` +
      `    var node=analysisPicksNode(q);\n` +
      `    if(!node){\n` +
      `      pushMsg({who:'ai',html:analysisMissingHTML('问题'+q.cn+'的候选模型')},mkey());\n` +
      `      phase='needPicks'; setPhase('needPicks');\n` +
      `      return;\n` +
      `    }\n` +
      `    pushMsg(node,mkey());`,
  },
  {
    // ⑤ 页内「运行代码」：打的是假输出，随剧本一起摘掉
    label: 'renderRunActs()：摘掉页内「运行代码」按钮',
    re: /  var q=QUIZ\[qn\]\|\|\{\};\n  return '<div class="run-acts">'\+\n    '<button class="abtn pri" onclick="runLocalCode\('\+qn\+',\\''\+esc\(q\.codeName\|\|''\)\+'\\'\)">▶ 运行代码（'\+esc\(q\.codeName\|\|'main\.py'\)\+'）<\/button>'\+/,
    to:
      `  /* ~接入改动（2026-10-08）：页内「运行代码」打的是内置剧本的假输出，已随剧本删除；\n` +
      `     要真跑就交给沙箱或本地环境（有真实代码时，上面那行 codeLabActs 已经接管） */\n` +
      `  return '<div class="run-acts">'+`,
  },
  {
    // ⑥ 结果正文：q.result 已为空，换成如实说明（防 undefined 崩）
    label: 'actNext()：结果正文不再取内置剧本',
    re: /    pushMsg\(\{who:'ai',html:q\.result\.html\+'<p class="next">下一步：'\+nx\+'<\/p>'\},mkey\(\)\);/,
    to:
      `    /* ~接入改动（2026-10-08）：结果正文只认智能体产物，内置剧本已删 */\n` +
      `    var resHtml=(q.result&&q.result.html)?q.result.html:analysisMissingHTML('问题'+q.cn+'的运行结果');\n` +
      `    pushMsg({who:'ai',html:resHtml+'<p class="next">下一步：'+nx+'</p>'},mkey());`,
  },
]
for (const p of SCRUB_PATCHES) {
  needRe(p.re, p.label)
  core = core.replace(p.re, p.to)
  console.log(`   ✂ 已改：${p.label}`)
}

// 跨行但含大量引号的锚点用字符串版（正则转义反而更容易写错）
const SCRUB_STR_PATCHES = [
  {
    label: 'skipStep()：跳过「运行」不再推内置演示结果',
    from:
      `    pushMsg({who:'me',html:'跳过这一步，直接用预设结果继续'},mkey());\n` +
      `    /* ~接入改动：真实代码在用时，往下走的是内置演示结果，得说清那是示意值 */\n` +
      `    if(codeRealOf(qi)) pushMsg({who:'ai',html:codeSampleNotice()},mkey());\n` +
      `    thinking(function(){ pushAfterRunOK(qi,QUIZ[qi]); }); return;`,
    to:
      `    pushMsg({who:'me',html:'跳过这一步'},mkey());\n` +
      `    /* ~接入改动（2026-10-08）：内置剧本的运行结果已删，跳过就没结果可推 —— 如实说明 */\n` +
      `    pushMsg({who:'ai',html:analysisMissingHTML('问题'+QUIZ[qi].cn+'的运行结果')},mkey()); return;`,
  },
  {
    // 下面三处读的是已删字段（picks / fixHints）。这几条路径当前走不到，
    // 但留着的话一旦被触发就是 TypeError 白屏 —— 兜底成空数组，成本为零。
    label: 'actRepick()：picks 已删，计数兜底',
    from: `      '<p>好的，我们回到<b>候选模型</b>这一步。下面 '+q.picks.length+' 个方案的取舍我再列一遍，`,
    to: `      '<p>好的，我们回到<b>候选模型</b>这一步。下面 '+((q.picks||[]).length)+' 个方案的取舍我再列一遍，`,
  },
  {
    label: 'askPickAgain()：picks 已删，传参兜底',
    from: `    picks:q.picks};`,
    to: `    picks:q.picks||[]};`,
  },
  {
    label: 'doFix()：fixHints 已删，列表兜底',
    from: `        '<ul>'+q.fixHints[kind].map(function(h){ return '<li>'+esc(h)+'</li>'; }).join('')+'</ul>'+`,
    to: `        '<ul>'+(((q.fixHints||[])[kind]||[]).map(function(h){ return '<li>'+esc(h)+'</li>'; }).join(''))+'</ul>'+`,
  },
]
for (const p of SCRUB_STR_PATCHES) {
  needUnique(core, p.from, p.label)
  core = core.replace(p.from, p.to)
  console.log(`   ✂ 已改：${p.label}`)
}
console.log('✅ 内置演示剧本的写死内容与回落链路已整体删除（QUIZ / INTRO_NODE / SIM_RUN_OUT / PAPER_CHIPS + 7 处回落点）')

// ── 工作台任务仓：开题登记 + 对话区按任务分桶（2026-10-08 新增） ─────────────
// 原则同前面几处：不改原型流程，只在「这段对话写进哪个容器」这一个点上开口。
// 为什么是全局改道而不是逐个锚点：这 6 处全是同一个语义（当前任务的对话区），
// 漏一处就会出现「消息写到了另一个任务的对话里」这种最难查的串台。
const STARTWORK_TASK_ANCHOR = `  state.workTitle=title||'新题目';\n`
needUnique(core, STARTWORK_TASK_ANCHOR, 'startWork() 的题面赋值')
core = core.replace(
  STARTWORK_TASK_ANCHOR,
  `  state.workTitle=title||'新题目';\n` +
    `  workSessionBegin(!!fromDetail);   /* ~接入改动：登记一条工作台任务（任务仓） */\n` +
    `  railGateReset();   /* ~接入改动（2026-10-12）：开新题时右栏不能留着上一条任务的闸门 */\n`,
)

const STREAM_IN_HITS = (core.match(/\$\('stream-in'\)/g) || []).length
if (STREAM_IN_HITS !== 3) fail(`core 里 $('stream-in') 出现 ${STREAM_IN_HITS} 处（预期 3：pushMsg / thinking / startWork）`)
core = core.replace(/\$\('stream-in'\)/g, 'workHost()')
console.log(`✅ 工作台任务仓已接入（startWork 登记 + core ${STREAM_IN_HITS} 处 + bridge 3 处对话区改走 workHost()）`)

// ── 意图路由 + 指令卡按钮（2026-10-09 新增）─────────────────────────────────
// 用户要的是「像对话 AI 一样自由聊，但只聊这道题」。判据本身放在
// utils/analysis/intent.ts（纯函数、可单测），这里只把它接进原型那套 DOM 两处：
//   ① userTurn 的三类分流（流程指令 / 题目相关问答 / 无关话题）；
//   ② applyChips 的按钮兜底（调用方没给按钮时，按当前阶段补规范指令按钮）。
// 按钮发的是**规范指令文本**，与用户手打关键字走同一条判据 —— 两处行为永远一致。
const USERTURN_ANCHOR =
  `  /* ① 随口提问：回答完停在原地，并提示可以继续 */\n` +
  `  if(phase!=='adjust' && isQuestion(v) && !wantsAdvance(v)){\n` +
  `    replySide(offTopicAnswer(v)+NEXT_TAIL,null);\n` +
  `    return;\n` +
  `  }`
needUnique(core, USERTURN_ANCHOR, 'userTurn 的「随口提问」分流')
core = core.replace(
  USERTURN_ANCHOR,
  `  /* ① 意图路由（~接入改动 2026-10-09）：把这句话分成三类 ——\n` +
    `        · 无关话题 → 一句话拒答并拉回题目（不烧模型调用）\n` +
    `        · 题目相关问答 → 交给智能体答（限题目相关，细筛在它那边）\n` +
    `        · 流程指令 → 落到下面既有的按阶段分流里\n` +
    `     判据是纯函数（utils/analysis/intent.ts）；指令卡按钮发的是同一套规范\n` +
    `     指令文本，所以点按钮与手打关键字走的是同一条路。 */\n` +
    `  var it=parseIntent(v);\n` +
    `  if(phase!=='adjust' && it.kind==='offtopic'){\n` +
    `    replySide(OFFTOPIC_REPLY+NEXT_TAIL,null);\n` +
    `    return;\n` +
    `  }\n` +
    `  /* 原型那句「要推进流程」的老口径（wantsAdvance）只对**不像问句**的话生效：\n` +
    `     带问号 / 疑问词的，即使命中「模型 / 分析 / 推荐 / 数据」这类流程词，也先当\n` +
    `     题目相关问答 —— 否则「这道题该用什么模型？」会被当成「开始分析」推进。 */\n` +
    `  if(phase!=='adjust' && it.kind==='question' && !(wantsAdvance(v)&&!isQuestion(v))){\n` +
    `    intentAsk(v);\n` +
    `    return;\n` +
    `  }`,
)
console.log('✅ 意图路由已接入 userTurn（流程指令 / 题目问答 / 无关话题 三类分流）')

const APPLYCHIPS_ANCHOR =
  `export function applyChips(list,hint){\n` +
  `  var box=$('work-chips'); if(!box) return;\n` +
  `  var arr=(list||[]).slice();`
needUnique(core, APPLYCHIPS_ANCHOR, 'applyChips 的入参归一')
core = core.replace(
  APPLYCHIPS_ANCHOR,
  APPLYCHIPS_ANCHOR +
    `\n  /* ~接入改动（2026-10-09）：调用方没给按钮时，按当前阶段补一组「规范指令」按钮。\n` +
    `     点按钮 = 发一句规范指令文本，与用户手打关键字走同一条判据（见 userTurn）。 */\n` +
    `  if(!arr.length) arr=intentChips(phase,qi);`,
)
console.log('✅ 指令卡按钮已接入 applyChips（没给按钮时按阶段补规范指令）')

// ~待 Vue 化 标记：这些函数直接操作 DOM，是后续拆成组件的目标

// ══════════════════════════════════════════════════════════════════════════
// 写死对话整体删除（2026-10-12 用户定调）
//
// 背景：工作台对话区此后**只有一种内容来源** —— 平台后端那条 AG-UI 对话。
// 原型那套「六步剧本 + 六张写死选择闸门 + 随口一问的写死答案」整体清掉：
//   · 写死闸门族（flowGateHTML / pushFlowGate / chooseFlow / skipFlow / …）——
//     用户截图里那张「我已理解这道题的核心 + 理解正确/补充说明/其他/跳过」就是它
//   · 六张写死闸门 askUnderstanding / askModelCompare / askParameter /
//     askResultAngle / askSatisfaction / askPaperStyle（文案全是 2026 C 题口径）
//   · offTopicAnswer 的 8 段写死回答、NEXT_TAIL 的「下一步…」尾句
//   · userTurn 里那批「已补充三张图 / 已切换 Gurobi」的假回复
//   · 内置环境与假运行（ENV_* / runLocalCode / SIM_RUN_OUT / renderEnvBody）
//
// 怎么删才稳：原型作者把这些函数写在了一起，所以按「首个顶层声明 → 下一个要保留的
// 顶层声明」**整段切掉**，不用花括号配对 —— core 里有大量含花括号的字符串与正则，
// 配平法会误判。每段两端都断言唯一。
//
// 删完必须过一道**符号审计**（段末）：任何一个被删的名字在 core 里还有引用，
// 直接 ABORT 并一次列全 —— core.ts 顶上写着 @ts-nocheck，编译器不会替我们抓这个。
const DEAD_FNS = [
  // 写死闸门族
  'rememberFlow', 'otherInputHTML', 'flowGateHTML', 'toggleOtherInput', 'cancelOtherInput',
  'classifyCustomFlow', 'submitOtherInput', 'pushFlowGate', 'restoreFlowGate', 'chooseFlow',
  'skipFlow', 'continueAfterFlow', 'openQuestionPause', 'resumeQuestionPause', 'backToPreviousDecision',
  // 六步剧本的推进器与选择器
  'jumpStep', 'skipStep', 'picksHTML', 'submitCustomModel', 'usedActs',
  'applyChips', 'intentChips', 'quickSay', 'pushFlowAnswer', 'nudge',
  // 六张写死闸门 + 写死问答
  'isQuestion', 'wantsAdvance', 'offTopicAnswer', 'askUnderstanding', 'modelCompareHTML',
  'showModelCompare', 'askModelCompare', 'askPickAgain', 'questionHasParams', 'askParameter',
  'askResultAngle', 'askSatisfaction', 'finishResultDecision', 'askPaperStyle', 'figurePickHTML',
  'toggleAllFigures', 'askFigureSelection', 'readFigurePicks', 'confirmFigureSelection', 'buildPaperNode',
  // 脚本化流程本体
  'replySide', 'doGlobal', 'doAnalysis', 'doAnalysisReal', 'doPicks', 'choosePick', 'pushLab',
  'actNext', 'advanceQuestion', 'actAdjust', 'actRepick', 'doFix', 'fixRound', 'doPaper',
  'showResultReading',
  // 内置环境与假运行
  'simOutForQ', 'getOrCreateEnv', 'detectImports', 'ensurePackages', 'runLocalCode',
  'pushAfterRunOK', 'pushAfterRunFail', 'renderRunActs', 'renderEnvBody', 'openEnvDrawer',
  'refreshEnvDrawer', 'getCurrentEnv', 'copyEnvFreeze', 'copyEnvCmd', 'renderEnvBadge',
  'ensureEnvForCurrentQ', 'renderEnvReadyCard', 'bindQuizCodeText',
  // 写死数据
  'FIGURE_CAND', 'ENV_BASE_PKGS', 'ENV_OPT_PKGS', 'ENV_PY_VERSION', 'ENV_POOL', 'CUR_ENV_ID',
  'ENV_RUN_TYPING', 'SIM_RUN_OUT', 'NEXT_TAIL',
]

/** 整段切掉 [from, to)：两端各断言唯一，to 那段整体保留 */
function cutRange(from, to, label) {
  const i = core.indexOf(from)
  if (i < 0) fail(`${label}：找不到起点（${from.slice(0, 48)}…）`)
  if (core.indexOf(from, i + 1) >= 0) fail(`${label}：起点不唯一`)
  const j = core.indexOf(to, i)
  if (j < 0) fail(`${label}：找不到终点（${to.slice(0, 48)}…）`)
  if (core.indexOf(to, j + 1) >= 0) fail(`${label}：终点不唯一`)
  core = core.slice(0, i) + core.slice(j)
}

const CUT_RANGES = [
  ['export function rememberFlow(label,restore){', 'export function renderSteps(cur){',
    '写死闸门族（flowGateHTML / pushFlowGate / chooseFlow / skipFlow / openQuestionPause…）'],
  ['export function jumpStep(i){', 'export function artHTML(a,key){',
    '步骤条点击跳步 + skipStep（跳过当前步的剧本推进）'],
  ['export function picksHTML(node){', 'export function actsHTML(node,key){',
    '候选模型卡 + 自定义模型输入（选择改由后端真闸门承担）'],
  ['export function usedActs(key){', 'export function fileChipsHTML(names){',
    '按钮「已点过」置灰（随写死闸门一起删）'],
  ['export function applyChips(list,hint){', 'export function thinking(cb){',
    '输入框上方的写死快捷语条'],
  ['export function doGlobal(){', 'export function sendInWork(){',
    'doGlobal（全局分析那一步的脚本推进）'],
  ['export function quickSay(t){', 'export function isQuestion(v){',
    'quickSay / pushFlowAnswer / nudge（写死快捷语的发送与提示）'],
  ['export function isQuestion(v){', 'export function userTurn(v){',
    '六张写死闸门 + offTopicAnswer 的 8 段写死回答 + 写死判据'],
  ['export function userTurn(v){', 'export function openArtifactView(title,sub,html){',
    '脚本化流程本体（userTurn / doAnalysis / doPicks / choosePick / pushLab / actNext / doPaper / 内置环境与假运行）'],
]
for (const [from, to, label] of CUT_RANGES) {
  cutRange(from, to, label)
  console.log(`   \u2702 已切段：${label}`)
}

// userTurn 换成「一句话发出去」——不再有任何写死的分支树
const NEW_USERTURN =
  `/* ~接入改动（2026-10-12）：对话区不再有写死的分支树。\n` +
  `   你说一句，就原样交给智能体：它自己决定是回答、是接着做，还是反过来问你。\n` +
  `   它问你时走平台的真闸门（ui_form_advanced / ui_gate）—— 前端画的写死选择卡已删。 */\n` +
  `export function userTurn(v){\n` +
  `  intentAsk(v);\n` +
  `}\n`
needUnique(core, 'export function openArtifactView(title,sub,html){', 'userTurn 的落点（openArtifactView）')
core = core.replace('export function openArtifactView(title,sub,html){', NEW_USERTURN + 'export function openArtifactView(title,sub,html){')

// msgHTML 不再渲染候选模型卡（picksHTML 已删）
const MSGHTML_PICKS = `    (m.picks?picksHTML(m):'')+\n`
needUnique(core, MSGHTML_PICKS, 'msgHTML 里对 picksHTML 的调用')
core = core.replace(MSGHTML_PICKS, '')

// sendInWork 不再插一条假的「AI 正在思考」气泡 —— 真实事件流自己会显示进度
const SENDINWORK_ANCHOR = `  pushMsg({who:'me',html:esc(v).replace(/\\n/g,'<br>')},mkey());\n  thinking(function(){ userTurn(v); });`
needUnique(core, SENDINWORK_ANCHOR, 'sendInWork 的落点')
core = core.replace(
  SENDINWORK_ANCHOR,
  `  pushMsg({who:'me',html:esc(v).replace(/\\n/g,'<br>')},mkey());\n` +
    `  /* ~接入改动（2026-10-12）：不再插一条 820ms 的假思考气泡 —— 后端事件流\n` +
    `     自己会画思考折叠条与转圈，多插一条只会显得卡顿 */\n` +
    `  userTurn(v);`,
)

// 进工作台：不再等分析回来再走脚本（doGlobal），只把动画那条真通道挂上
const STARTWORK_BOOT = `  analysisBoot(state.workTitle,!!fromDetail,function(){ thinking(doGlobal); animationBoot(); });`
needUnique(core, STARTWORK_BOOT, 'startWork 的读题入口')
core = core.replace(
  STARTWORK_BOOT,
  `  /* ~接入改动（2026-10-12）：进工作台只发**一条** run（智能体全权驱动）。\n` +
     `     过去这里 cb 里还要接着走（全局分析 / 题意确认 / 逐问分析）那套脚本，\n` +
    `     现在没有了：分析、四问、论文全部由智能体自己推进，前端只画事件流与产物卡。\n` +
    `     动画那条真通道照旧在后台挂上（它是平台能力，不是对话）。 */\n` +
    `  analysisBoot(state.workTitle,!!fromDetail,function(){ animationBoot(); });`,
)
console.log('\u2705 userTurn / msgHTML / sendInWork / startWork 已改道（对话只认后端事件流）')

// 去掉「过程用时」那条写死尾注：NEXT_TAIL 已随脚本删除，正文里不再拼「下一步…」
for (const n of DEAD_FNS) {
  const re = new RegExp(`\\+${n}\\b`, 'g')
  if (re.test(core)) fail(`正文里仍拼接 ${n}`)
}

// 符号审计：被删的名字一个都不许剩
{
  /* 收尾补丁：被删函数在别处还有残留调用，一并摘掉。
     为什么单独列一遍：这些调用点写在原型 HTML 里（步骤条的可点击态、论文抽屉里那颗
     「确认，进入下一问」按钮），不在前面那几段的范围里，但一点就 "is not a function"。 */
  const LEFTOVER_LINES = [
    ["(can?' clickable':'')", '步骤条不再标可点击态', false],
    ['onclick="jumpStep(', '步骤条去掉 onclick=jumpStep', false],
    ['onclick="quickSay(', '论文抽屉里调已删 quickSay 的按钮', true],
    ['export let NEXT_TAIL =', 'NEXT_TAIL 声明', false],
  ]
  for (const [anchor, label, many] of LEFTOVER_LINES) {
    const hits = []
    let from = 0
    for (;;) { const i = core.indexOf(anchor, from); if (i < 0) break; hits.push(i); from = i + 1 }
    if (!hits.length) fail(`${label}：锚点一处都没命中`)
    if (!many && hits.length !== 1) fail(`${label}：锚点命中 ${hits.length} 处（应为 1）`)
    for (let k = hits.length - 1; k >= 0; k--) {
      const at = hits[k]
      const s = core.lastIndexOf('\n', at) + 1
      let e = core.indexOf('\n', at)
      if (e < 0) e = core.length; else e += 1
      core = core.slice(0, s) + core.slice(e)
    }
    console.log(`   ✂ ${label}（${hits.length} 处）`)
  }
  const leftovers = []
  for (const n of DEAD_FNS) {
    const hits = core.match(new RegExp('\\b' + n.replace(/\$/g, '\\$') + '\\b', 'g'))
    if (hits && hits.length) leftovers.push(`${n}\u00d7${hits.length}`)
  }
  if (leftovers.length) fail(`写死对话删除不彻底，core 里仍有引用：${leftovers.join('、')}`)
  console.log(`\u2705 符号审计：${DEAD_FNS.length} 个被删符号在 core 里 0 残留`)
}

/* 全局注册表要跟着缩：装到 window 上的名字里，已删的那些不能留（留着就是 undefined，
   模板里的 inline onclick 一点就 "is not a function"） */
const liveFnNames = globalFnNames.filter((n) => !DEAD_FNS.includes(n))
console.log(`\u2705 window 注册表：${globalFnNames.length} → ${liveFnNames.length} 个函数`)

const TODO_VUE = [
  'go', 'openDetail', 'setDetailTab', 'openWorkFromDetail', 'closeDrawer', 'openDrawer',
  'switchAnim', 'openAnimFs', 'closeAnimFs', 'switchAfsTab',
  'renderCarousel', 'renderGrid', 'renderFilters', 'renderMine', 'renderDetailPanels',
  'startWork', 'sendFromHome', 'sendInWork', 'pushMsg', 'renderSteps',
]

const header = `/* eslint-disable */
// @ts-nocheck
import { guardView, syncViewRoute, sandboxReady, gotoSandboxRun, gotoSandboxArtifacts, getAnalysisHooks, getAnimationHooks, getCodegenHooks, getWorkHooks } from './hooks'
/* 外包案例库的读取口：hasPublishedCase 供首页卡片「可进入演示」角标，outsourcedCase 供
   详情页 renderDetailPanels，outsourcedCaseError 供详情页空态提示。core 是 ESM，裸标识符
   不会回落到 window，必须显式引入 —— 缺了就是 ReferenceError，并中断整条渲染链：
   缺 hasPublishedCase 时 pcardHTML 抛错，首页轮播/筛选/网格全空。（~迁移改动 2026-10-16） */
import { outsourcedCase, hasPublishedCase, outsourcedCaseError } from '@/stores/outsourcedCases'
/* 意图路由（2026-10-09）：纯粹的判据模块 —— 不碰 DOM、不认识路由与 store，
   所以直接 import，不占注册表。core 只用它三样：分流、按钮文案、无关话题的答复。 */
/* ==================================================================
   ⚠⚠ 2026-10-19：本脚本已**无法复现**磁盘上的 core.ts
   ------------------------------------------------------------------
   磁盘产物比本脚本能生成的多约 1050 行 / 89 处（成果页双 Tab + PDF/Word/ZIP 下载、
   外包案例库接入、E 题 p5 数据、动画 rail、微网回放…），因此 core.ts 已转为
   **手工维护的产物**：改动直接改产物文件，不要再指望重跑本脚本把它们长出来。
   本脚本结尾的自检已改为「告警 + 跳过写入」，跑一次最坏结果是「什么都没发生」；
   只有显式 --force 才会用生成结果覆盖（那会丢掉上面那一批）。
   ================================================================== */
/* ==================================================================
   原型逻辑层 · Stage 1（由 scripts/port/build-port.mjs 自动生成，勿手改）

   内容：数模演易原型v2.html 里那块 1,175,971 字符的应用脚本，**逐字搬运**。
   改动只有六类，都有据可查：
     1. 剔除 1,015,738 字符的截图 base64（占 86%），换成 public/cases/p3/anim-shots/*.png
        的 URL 表 —— 图片本该是文件，不该塞在 JS 里。
     2. 每个顶层声明加上 export，供桥接层还原到 window。
     3. 两处随文档自动执行的 IIFE 改成具名导出（initPrototype / installCarouselDrag）：
        它们在 <script> 里跑时 DOM 已就位，进 ESM 后会在 Vue 挂载前执行并抛异常。
     4. go() 里加两行：登录闸门 + 路由同步（标了「~迁移改动」）。
     5. core 3 处 + bridge 3 处的 \`$('stream-in')\` 改成 \`workHost()\`：对话区按任务
        分桶（见桥接层的「工作台任务仓」），前台任务的容器挂在 #stream-in 里，
        换台只搬容器本身。桥接层内部那几处（workHost / workHostShow / workHostEl）
        保留 \`$('stream-in')\` —— 那读的正是「槽位」，不是「当前任务的容器」。
     6. header 多一行 import：把 utils/analysis/intent.ts（意图路由，纯函数）引进来 ——
        对话区据此把「流程指令 / 题目相关问答 / 无关话题」分开；指令卡按钮也用它。
     其余 161 个函数、全部数据，逐字未改。

   为什么加 @ts-nocheck：这是未类型化的原生 JS（162 个函数全部隐式 any），
   强类型化要么改写全部代码、要么引入 2000 处 any 注解，两者都会让
   「行为与原型逐字一致」这件事失去保障。所以本文件先豁免类型检查，
   由 scripts/port/probe-*.mjs 做真机断言，随后逐函数 Vue 化。

   ⚠ 标了「~待 Vue 化」的函数直接操作 DOM，是后续拆成 Vue 组件的目标；
     在那之前，它们与 Vue 模板共用同一套 id / class 契约（见 PrototypeShell.vue）。
   ================================================================== */

`

// ── 工作台右栏：闸门改道 + 产物预览下沉（2026-10-12 新增，用户定版）──────────
// 版式：**左对话 / 右（闸门选择 + 产物浏览）**，闸门不再出现在对话流里。
// 这里只改「闸门画在哪」「产物预览画在哪」两个落点，原型流程一步都不动：
//   ① pushFlowGate()：原来借 pushMsg 把闸门塞进对话区 → 改走 bridge 的 railGateOpen
//      （右栏不在时它内部回退 pushMsg —— 单跑原型 / 脚本照旧跑得动）；
//   ② chooseFlow / skipFlow / submitCustomFlow / confirmFigureSelection 这四处
//      给按钮加 .used 之后，让右栏那张卡跟着定格成「已确认」；
//   ③ 三个抽屉函数（openDrawer / openArtifactView / closeDrawer）：右栏里
//      「产物清单」与「产物预览」是上下两块，切预览态要让右栏跟一声。
// 「收起产物」那个顶栏按钮是抽屉时代的残留，样式表里已隐藏（见 workRailCss）。
/* 写死闸门（pushFlowGate / chooseFlow / skipFlow / submitOtherInput / confirmFigureSelection）
   已随「写死对话」整体删除，所以下面这两处「把闸门改道进右栏」的补丁没有落点了：
   右栏的闸门现在由 **平台真闸门** 走 syncGate → railGateOpen 那条路（见下）。 */

/* 同理：judge「已选项」置灰的四处调用点都在已删函数里 */

const DRAWER_OPEN_ANCHOR = `  state.drawerOpen=true; state.drawerType=type;\n  var d=$('drawer');\n  d.classList.add('on');`
needUnique(core, DRAWER_OPEN_ANCHOR, 'openDrawer() 的开抽屉落点')
core = core.replace(DRAWER_OPEN_ANCHOR, `  railArtView(true);   /* ~接入改动（2026-10-12）：右栏切到产物预览态 */\n` + DRAWER_OPEN_ANCHOR)

const DRAWER_ART_ANCHOR = `  state.drawerOpen=true; state.drawerType='artifact';\n`
needUnique(core, DRAWER_ART_ANCHOR, 'openArtifactView() 的开抽屉落点')
core = core.replace(DRAWER_ART_ANCHOR, DRAWER_ART_ANCHOR + `  railArtView(true);   /* ~接入改动：右栏切到产物预览态 */\n`)

const DRAWER_CLOSE_ANCHOR = `  $('drawer').classList.remove('on','wide');`
needUnique(core, DRAWER_CLOSE_ANCHOR, 'closeDrawer() 的收抽屉落点')
core = core.replace(DRAWER_CLOSE_ANCHOR, DRAWER_CLOSE_ANCHOR + `\n  railArtView(false);   /* ~接入改动：右栏回到产物清单 */`)
console.log('✅ 工作台右栏已接入（闸门改道右栏 4 处 + 抽屉与右栏产物区联动 3 处）')

const bridge = `
/* ⚠️ 这个 bridge 是模板字符串（反引号是它的结束符）：
 *    - 代码里要写反引号必须转义成 \\\`，注释里干脆别写 —— 顺手写一个就会把
 *      模板截断，报的是「Unexpected identifier」这种对不上号的语法错
 *    - 内联处理器里不要写 \\' ，会被提前解义成 ' 而生成坏代码；参数走 data-*
 * ==================================================================
   迁移桥接层（非原型原码，本次新增）
   ================================================================== */

/** 演示模块已外置成 public/cases/<protoId>/anims/mod-N.html → 改用 iframe 挂载，
 *  CSS 与全局变量天然隔离（原型当年是靠 Shadow DOM + 代理 document 达到同样效果）。
 *  为什么不用原来的 Shadow DOM 方案：外置成独立页面后浏览器本身就提供了隔离，
 *  再套一层 Shadow DOM 只会让「按 F12 排查」变难。 */
export function mountAnim(k) {
  unmountAnim()
  const holder = $('afs-stage')
  if (!holder) return false
  const ifr = document.createElement('iframe')
  ifr.className = 'anim-frame'
  ifr.setAttribute('data-anim', String(k))
  /* ~接入改动：真实档位（智能体生成的动画）用 blob URL，内置档位照旧走 public/cases/<protoId>/anims/ */
  ifr.setAttribute('title', (animRealTitle(k) || animMeta(k)) + ' 可视化演示')
  ifr.setAttribute('src', animRealURL(k) || './cases/' + ANIM_DIR_CUR + '/anims/' + ANIM_PREFIX_CUR + k + '.html')
  holder.appendChild(ifr)
  ANIM_HOST = ifr
  return true
}

/* ── 沙箱衔接（非原型原码，本次新增）─────────────────────────────────────

   对话里代码生成好之后，用户可以选择把它丢进真沙箱去跑；跑完的产物再回到
   这条对话里。这两件事原型都没有，所以放在桥接层，规则与上面一致：
     - 按钮跟原型其它按钮一样走 inline onclick → 必须挂到 window（见下）
     - 跳转不在这里做 —— core 不认识路由，通过 hooks 交给接入方
     - 去程/回程的凭据格式见 data/runHandoff.ts
   注意：下面刻意不写内联处理器里的引号（让按钮从 data-* 读参数），
   因为这个 bridge 本身是模板字符串，写 \\' 会被提前解成 ' 而生成出坏代码。 */

/** 「进入沙箱运行」按钮 —— 这道题在沙箱里没有环境时返回空串，按钮不出现 */
export function sandboxRunBtn(qn) {
  if (!sandboxReady()) return ''
  var q = QUIZ[qn] || {}
  return '<button class="abtn pri" onclick="openSandboxRun(' + qn + ')">进入沙箱运行（' + esc(q.codeName || 'main.py') + '）</button>'
}

/** inline onclick 的两个入口 —— 原型那套 HTML 只在 window 上找名字 */
export function openSandboxRun(qn) { gotoSandboxRun(qn) }
export function openSandboxArtifacts(sandboxId) { gotoSandboxArtifacts(sandboxId) }

/** 沙箱跑完的回执 → 一条对话消息：退出码 + 产物清单 + 回沙箱的入口 */
export function pushSandboxResult(rcp) {
  if (!rcp) return
  var files = rcp.files || []
  var fmt = function (n) {
    if (!n) return ''
    if (n < 1024) return n + ' B'
    if (n < 1048576) return (n / 1024).toFixed(1) + ' KB'
    return (n / 1048576).toFixed(1) + ' MB'
  }
  var list = files.length
    ? '<ul class="sb-files">' + files.map(function (f) {
        return '<li><code>' + esc(f.path) + '</code><span>' + fmt(f.size) + '</span></li>'
      }).join('') + '</ul>'
    : '<p>这一趟没有产出文件。</p>'
  var code = (rcp.exitCode === null || rcp.exitCode === undefined) ? '未知' : rcp.exitCode
  pushMsg({ who: 'ai', html:
    '<h4>沙箱运行完成 · ' + esc(rcp.command || 'main.py') + '</h4>' +
    '<p>退出码 <b>' + esc(String(code)) + '</b> · 产出 <b>' + files.length + '</b> 个文件。它们留在沙箱会话里，清单已经同步回这条对话。</p>' +
    list +
    '<div class="run-acts"><button class="abtn pri" data-sb="' + esc(rcp.sandboxId || '') + '" onclick="openSandboxArtifacts(this.dataset.sb)">在沙箱中查看产物</button></div>' +
    '<p class="next">下一步：告诉我要怎么解读这批结果，或者直接说「继续」推进流程。</p>'
  }, mkey())
}

/* ── 真实分析通道（非原型原码，本轮新增）─────────────────────────────────

   把 2026 国赛 C 题那条「分析 → 候选模型」从**写死的剧本**换成**智能体实时生成**。
   三道纪律：
     - 内置剧本一行不改：取不到真实内容时原样走它，它是永久的兜底
     - 页内标注来源：真实 / 内置要看得出来，不把生成内容说成预设内容
     - 只在这一层动手：core 段里的原型逻辑仍逐字未改（除几处标了「~接入改动」的开口）

   与沙箱那套的区别：沙箱解决「怎么跑代码」，这里解决「分析和候选从哪来」。 */

/** 四问的中文序号，用来拼「问题一」这种说法 */
function analysisCnAt(i){ return (QUIZ[i]&&QUIZ[i].cn)||String(i+1); }

/**
 * 这一步挂哪几张产物卡。
 *
 * 内置剧本每一步给两张：一张分析卡（点开右侧文档）+ 一张可视化演示卡，
 * 并靠 auto:'animation' 自动弹出演示抽屉。真实通道顶掉的是**分析正文**，
 * 不是这两件事：
 *   - 分析卡必须留着 —— 它是打开真实产物文档的唯一入口，正文由
 *     artBody('analysis') 换成智能体的文档（见 analysisLiveBody）
 *   - 演示卡原样保留 —— 演示是平台能力，不算被替换
 *   - 自动弹出照旧 —— 那是既有流程行为
 */
function analysisArts(scope,qi,base){
  var kept=(base&&base.length)
    ? base.filter(function(a){ return a.type==='animation'; })
    : [];
  var title=scope==='global'?'全局分析 · 智能体生成':('问题'+analysisCnAt(qi)+' · 问题分析');
  var sub=scope==='global'
    ? '题意 · 数据 · 约束 · 四问关系（按题面生成）'
    : '目标 · 约束 · 易错点（按题面生成）';
  return [AR('analysis',title,sub)].concat(kept);
}

/**
 * 这次工作台是「从详情页进来的」还是「从首页输入题目进来的」。
 *
 * 进工作台时由 analysisBoot 记下来，后面每一步（尤其单问深挖那几次 run）
 * 都要用它 —— 它决定题面取内置的还是取用户刚输入的那段。
 */
let analysisFromDetail=false;

/**
 * 开题那一刻的题目文件名列表（题面由文件承担的任务才有，刷新后由任务仓还原）。
 *
 * 附件暂存区是内存态、刷新即丢 —— 不把文件名记下来，「用题目文件开的题」刷新后
 * 就解不出题面（resolveCase 返回 null），对话区一句话都发不出去。文件名不变 ⇒
 * 算出的 caseId 不变 ⇒ 还能接回原会话。
 */
let analysisPaperNames=[];

/** 本道题在题库里的题号；只有从详情页进来时它才代表本题 */
function analysisProtoId(){ return analysisFromDetail?(state.detailId||''):''; }

/**
 * 当前这道题该用哪份题面（内置真题用内置题面，用户自己输入的题目用用户文本）。
 *
 * ⚠️ state.detailId 只在「从详情页进入」时才是本道题的题号。它是个全局状态，
 *    看完 C 题详情页再回首页输入自己的题时，它还留着 'p3' —— 无条件传进去
 *    会让内置 C 题题面顶掉用户刚输入的题面。所以只认 fromDetail 这一种情况。
 */
function analysisRef(title,fromDetail){
  var H=getAnalysisHooks(); if(!H) return null;
  var fd=!!fromDetail;
  /* 第 4 参 = 「题面由题目文件承担」：只有首页入口才可能走到这种情况
     （从详情页进来用的是题库里的内置题面）。flow 据此决定 caseId 与题面正文。
     第 5 参 = 刷新后的兜底：开题那一刻记下的题目文件名（暂存区已空时用它）。 */
  return H.resolveCase(fd?analysisProtoId():'', title||state.workTitle||'', fd, !fd&&attachHasProblem('home'), fd?[]:(analysisPaperNames||[]));
}

/**
 * 附件口径的实话。
 *
 * 三条：真传上去了就说「已上传、可读」；没传上去要把原因说出来（别让用户以为传了）；
 * 一个都没有就照旧写明「没有随请求提交」。含糊不得 —— 智能体那边「缺件即停」的
 * 硬约束靠的就是这个口径。
 */
function analysisAttachNote(ref){
  var H=getAnalysisHooks();
  var held=(H&&H.heldList)?H.heldList('home'):[];
  var up=0, wait=0, probUp=0, dataUp=0;
  for(var i=0;i<held.length;i++){
    var isP=held[i].kind==='problem';
    if(held[i].uploaded){ if(isP) probUp++; else dataUp++; }
    else wait++;
  }
  up=probUp+dataUp;
  var fail=(H&&H.attachFailureText)?(H.attachFailureText()||''):'';
  var bits=[];
  if(wait) bits.push('正在把你选的 '+wait+' 个文件传到本题会话…');
  if(probUp) bits.push('题目文件'+(probUp>1?'（'+probUp+' 份）':'')+'已上传到本题会话，智能体按它来理解题目。');
  if(dataUp) bits.push('另有 '+dataUp+' 个数据附件已上传到本题会话工作区，智能体可以直接读取。');
  if(ref&&!ref.text) bits.push('这次没有在输入框写题面，题目内容以题目文件为准 —— 智能体会先读它。');
  if(fail) bits.push('有文件没能传上去：'+fail+'。');
  if(!up&&!wait&&!fail) bits.push('本次没有附件随请求提交；题面里若点名了附件，智能体会按「未读取」如实登记，不会凭文件名推测里面的数据。');
  return '<p class="src-note">'+esc(bits.join(''))+'</p>';
}

/**
 * 读题气泡的**头部**：标题 + 附件口径 + 进度 + 跳出口。
 *
 * ⚠️ **实时对话区不在这里** —— 它由旁边那个 .lv-stream div 单独承载。
 * 为什么拆成两个兄弟节点：头部每隔 700ms 整体重写一次（进度在变），如果实时区
 * 也在这个字符串里，每重写一次就把智能体刚画上去的思考/工具块冲掉一次。
 *
 * ⚠️ **进度行里不带秒数**（2026-10-13 改）：analysisHeadHTML 现在只出**模具**，
 * .an-meter 是空的，秒数由 paintText 走 textContent 补。原因：头部每 700ms
 * 重写一次，而秒数每秒都在变 —— 秒数留在字符串里，等值守卫就永远不成立，
 * 头部连同里面的「跳过等待」按钮每秒被重建一次（按钮的监听器随即失效）。
 */
function analysisHeadHTML(ref){
  return '<h4>正在读题并生成分析</h4>'+
    '<p>智能体正在对<b>'+esc(ref.title||'这道题')+'</b>做题意分析与建模路线设计，一次跑完四个问题；跑完我会把真实分析接到下面的流程里。</p>'+
    analysisAttachNote(ref)+
    '<p class="an-meter"></p>'+
    '<div class="run-acts"><button class="abtn gh an-skip">跳过等待</button></div>';
}

/** 头部进度行这一刻的文字（秒数在这里、不在模具里） */
function analysisMeterText(){
  var H=getAnalysisHooks();
  var st=H?H.status():'idle';
  if(st!=='running') return '正在准备工作区…';
  return (H&&H.progressText&&H.progressText())||'';
}

/**
 * 只改文本节点，**不重建 DOM**。
 *
 * 秒数这类「每秒都在变、但结构没变」的内容走这里：整块 innerHTML 重写会把
 * 子节点全部换掉（转圈动画重头转、按钮监听器失效），而 textContent 是原地更新。
 */
function paintText(root,sel,text){
  if(!root) return;
  var n=root.querySelector(sel);
  if(n&&n.textContent!==text) n.textContent=text;
}

/**
 * 头部只重写模具、不重写进度：模具与时间无关 ⇒ 只有「工具调用次数 / 最近工具 /
 * 附件口径 / 状态」这些**真变了**的时候才动 DOM。
 */
function syncHead(el,ref){
  if(!el) return;
  var head=el.querySelector('.an-head');
  if(!head) return;
  var mold=analysisHeadHTML(ref||{});
  if(head.getAttribute('data-mold')!==mold){
    head.innerHTML=mold;
    head.setAttribute('data-mold',mold);
  }
  paintText(head,'.an-meter',analysisMeterText());
}

/** 实时对话区的挂载点 —— 智能体的思考 / 工具调用 / 正文都画进这里 */
function liveBoxHTML(){ return '<div class="lv-stream"></div>'; }

/* ==================================================================
   产物卡（Step3，2026-10-09）

   跑完分析后往对话区追一张「智能体产物」卡：列出本会话 + 全部子会话
   artifacts/ 下的**全部**文件（先不过滤），点一张进右侧抽屉看文本。

   两个纪律：
     · **不写内联 onclick**（bridge 是模板字符串，内联处理器里的引号会在生成期
       被提前解义），参数一律走 data-* + 事件委托；
     · **事件委托挂在外层容器上**：卡片墙是整块 innerHTML 重画的（点一次「刷新」
       就重画一次），直接绑在卡片上的监听器会随节点一起消失，挂容器不会。
   ================================================================== */

/** 产物卡的容器（内部由 analysis 模块整块重画） */
function artifactCardBoxHTML(){ return '<div class="art-card"></div>'; }

/** 产物卡的事件委托：刷新按钮 / 点卡片看内容 */
function bindArtifactCard(box,H,caseId){
  box.addEventListener('click',function(ev){
    var t=ev.target;
    /* 从点击处往上找带 data-* 的那个节点（卡片是 button，刷新按钮也在里面） */
    while(t && t!==box && !(t.getAttribute && (t.hasAttribute('data-path')||t.hasAttribute('data-art-refresh')))) t=t.parentNode;
    if(!t || t===box || !t.getAttribute) return;
    if(t.getAttribute('data-art-refresh')!==null){ H.artifactRefresh(box,caseId); return; }
    var p=t.getAttribute('data-path'); if(!p) return;
    H.artifactPreview(p,box,caseId).then(function(r){
      if(r) openArtifactView(r.title,r.sub,r.html);
    }).catch(function(){});
  });
}

/**
 * 往对话区追一张产物卡。
 *
 * 刷新时机 = **跑完这一下 + 用户手动点刷新**（2026-10-09 用户拍板）。
 * 刻意不轮询：智能体写产物实测要二三十分钟，逐秒打接口只是白烧请求；
 * 现在还没写完的，卡片上会如实写「可能还在写，过会儿点刷新再看」。
 */
function artifactCardBoot(caseId){
  var H=getAnalysisHooks();
  if(!H||!H.artifactMount) return;
  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+artifactCardBoxHTML()+'</div></div>';
  workHost().appendChild(el);
  var box=el.querySelector('.art-card');
  if(!box) return;
  bindArtifactCard(box,H,caseId);
  H.artifactMount(box,caseId);
  H.artifactRefresh(box,caseId);
  scrollBottomIfStick();
}

/* ==================================================================
   分析报告卡（P2，2026-10-09）

   「以 docx 为主，动画以 html」：分析跑完后，把产物里的分析主稿整理成 Word
   交到对话区（挑文件 + 调后端导出 + 下载全在 analysis 模块里）。动画产物是
   HTML，本来就能在浏览器里直接跑，不进这条路径。

   与产物卡同构，刻意**各占一条气泡**：产物卡是「清单」，报告卡是「这一份成品」，
   混在一起用户分不清哪个是能直接交出去的东西。
   ================================================================== */

/** 报告卡的容器（内部由 analysis 模块整块重画） */
function reportCardBoxHTML(){ return '<div class="rep-card"></div>'; }

/** 报告卡的事件委托：重新生成 / 下载 Word / 预览原稿 / 重试 */
function bindReportCard(box,H,caseId){
  box.addEventListener('click',function(ev){
    var t=ev.target;
    while(t && t!==box && !(t.getAttribute && t.hasAttribute('data-report'))) t=t.parentNode;
    if(!t || t===box || !t.getAttribute) return;
    var act=t.getAttribute('data-report');
    if(act==='download'){ H.reportDownload(box,caseId); return; }
    if(act==='preview'){
      H.reportPreview(box,caseId).then(function(r){
        if(r) openArtifactView(r.title,r.sub,r.html);
      }).catch(function(){});
      return;
    }
    /* refresh 与 retry 同一动作：重新走一遍「找主稿 → 导出」 */
    H.reportRefresh(box,caseId);
  });
}

/** 往对话区追一张分析报告卡 */
function reportCardBoot(caseId){
  var H=getAnalysisHooks();
  if(!H||!H.reportMount) return;
  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+reportCardBoxHTML()+'</div></div>';
  workHost().appendChild(el);
  var box=el.querySelector('.rep-card');
  if(!box) return;
  bindReportCard(box,H,caseId);
  H.reportMount(box,caseId);
  H.reportRefresh(box,caseId);
  scrollBottomIfStick();
}

/**
 * 运行结束后把气泡**定格**成一条运行记录。
 *
 * 为什么不再 el.remove()：这条通道以前把等待气泡当纯进度条，跑完就删；
 * 但需求要的是「把平台那边的对话内容留在站点对话里」—— 一删，智能体刚说的话、
 * 刚跑的工具就全没了。现在只在「真的收到过事件」时定格，其余情形（刷新后直接
 * 接回产物、压根没跑 run）仍按原来的方式删掉，不给对话区留空壳。
 *
 * @returns true = 已定格；false = 已删除
 */
function sealRunBubble(el,liveBox,H){
  /* 认这个气泡自己的实时容器 —— 任务仓把对话分区之后，「当前容器」可能已经是
     别条任务的了，问全局状态会答错 */
  var keep=H.liveActive?H.liveActive(liveBox):false;
  /* 气泡里挂着闸门卡就不能删：智能体只发了思考/工具、还没写正文时 liveActive 为假，
     这时把气泡删掉等于把用户唯一能填表的入口删掉 —— 那条 run 会永远挂着 */
  if(!keep && el.querySelector('.gate-card')) keep=true;
  if(!keep){ el.remove(); return false; }
  if(H.liveRender) H.liveRender(liveBox);
  var head=el.querySelector('.an-head');
  /* ~接入改动（2026-10-08）：分析过程不再上屏（思考 / 工具调用只留一个转圈），
     所以定格后这条气泡里只剩智能体说的话 —— 头部那几行读题进度收尾后没有意义，
     直接清掉，免得一条「正文」气泡还顶着「正在读题并生成分析」的标题 */
  if(head) head.innerHTML='';
  return true;
}

/* ==================================================================
   智能滚动（2026-10-09）

   读题气泡每 700ms 重画一次进度，早先那里是无条件 scrollBottom() —— 于是
   智能体一边输出、用户一边被拽回底部，前面的内容根本看不了。现在改成
   「粘底才跟随」：本来就贴着底部才跟着走；往上翻过就停手，用户自己滑回
   底部后自动恢复跟随。

   判据只看一件事：scrollTop 有没有**变小**（用户往上滚）。不用
   「距底部多少像素」判，是因为内容变高同样会触发 scroll 事件，那种情况
   不是用户操作，把它算进去会让跟随莫名其妙地关掉。
   ================================================================== */
function stickEl(){
  var st=$('stream');
  if(!st) return null;
  if(!st.__smyyStickBound){
    st.__smyyStickBound=true;
    st.addEventListener('scroll',function(){
      var top=st.scrollTop;
      if(typeof st.__smyyTop==='number' && top<st.__smyyTop-4) st.__smyyStick=false;
      else if(st.scrollHeight-top-st.clientHeight<120) st.__smyyStick=true;
      st.__smyyTop=top;
    },{passive:true});
  }
  return st;
}
function scrollBottomIfStick(){
  var st=stickEl();
  if(!st) return;
  if(st.__smyyStick===false) return;   /* 用户正在看前面的内容，别动他的视口 */
  st.scrollTop=st.scrollHeight;
  st.__smyyStick=true;
  st.__smyyTop=st.scrollTop;
}

/* ==================================================================
   闸门卡片（HITL，2026-10-09）

   平台的 ui_form_advanced / ui_gate 是 external 工具：模型一调，run 就挂起等人
   填表。表单形状由 analysis 模块解析成 GateSpec（平铺字段描述），这里只负责
   画成卡片、把用户填的值送回挂起的那条 run —— 填完按同一 (threadId, runId) 续跑，
   所以对话区里仍是同一条气泡、同一段实时输出。

   两个纪律：
     · **不写内联 onclick**（bridge 是模板字符串，内联处理器里的引号会被提前解义），
       一律用 data-* + addEventListener；
     · 提交/取消后卡片**留在对话里**当记录（加 .done），不再被 700ms 轮询重画掉。
   ================================================================== */

/** 字段初始值 → 文本 */
function gateInitText(f){
  var v=f&&f.initialValue;
  if(v===undefined||v===null) return '';
  if(Array.isArray(v)) return '';
  return String(v);
}
/** 这个选项是不是初始选中的（多选看数组，单选看相等） */
function gateInitChecked(f,option){
  var v=f&&f.initialValue;
  if(Array.isArray(v)) for(var i=0;i<v.length;i++) if(String(v[i])===String(option)) return true;
  return v!==undefined&&v!==null&&String(v)===String(option);
}

/**
 * 一个字段的 HTML。所有控件都带 data-key，提交时按它取值。
 *
 * 第二参 gateId ＝ 所属卡片的 id（interruptId），**只有单选组用得上**：同组 radio 必须
 * 同名才互斥，而 data-key 不是 name、浏览器不认 —— 少了这一层，单选组就变成
 * 「三个都能点亮」（2026-10-13 用户报障）。名字带上卡片 id，页面上同时存在多张卡
 * 时也不会互相串台。
 *
 * ⚠ 本函数整段位于生成器的**模板字符串**里：注释与字符串里一律不能出现反引号，
 *   否则模板串会被提前截断（写这段时踩过一次）。
 */
function gateFieldHTML(f,gateId){
  var head='<span>'+esc(f.label||f.key)+(f.required?'<em>*</em>':'')+'</span>';
  var attrs=' data-key="'+esc(f.key)+'"'+(f.required?' data-req="1"':'');
  var i,o;
  if(f.kind==='radio-group'||f.kind==='checkbox-group'){
    var type=f.kind==='checkbox-group'?'checkbox':'radio';
    var nameAttr=type==='radio'?' name="'+esc((gateId||'gate')+'-'+f.key)+'"':'';
    var opts='';
    for(i=0;i<f.options.length;i++){
      o=f.options[i];
      opts+='<label class="gate-opt'+(gateInitChecked(f,o)?' on':'')+'"><input type="'+type+'"'+nameAttr+attrs+
        ' value="'+esc(o)+'"'+(gateInitChecked(f,o)?' checked':'')+'><span>'+esc(o)+'</span></label>';
    }
    return '<label class="gate-field">'+head+'<div class="gate-opts">'+opts+'</div></label>';
  }
  if(f.kind==='select'){
    var list='<option value="">请选择</option>';
    for(i=0;i<f.options.length;i++){
      o=f.options[i];
      list+='<option value="'+esc(o)+'"'+(gateInitText(f)===o?' selected':'')+'>'+esc(o)+'</option>';
    }
    return '<label class="gate-field">'+head+'<select'+attrs+'>'+list+'</select></label>';
  }
  if(f.kind==='textarea'){
    return '<label class="gate-field">'+head+'<textarea rows="3"'+attrs+
      ' placeholder="'+esc(f.placeholder||'')+'">'+esc(gateInitText(f))+'</textarea></label>';
  }
  var input='<input type="'+(f.kind==='number'?'number':'text')+'"'+attrs+
    (f.kind==='number'?' data-num="1"':'')+
    (typeof f.min==='number'?' min="'+f.min+'"':'')+
    (typeof f.max==='number'?' max="'+f.max+'"':'')+
    (typeof f.step==='number'?' step="'+f.step+'"':'')+
    ' value="'+esc(gateInitText(f))+'" placeholder="'+esc(f.placeholder||'')+'">';
  return '<label class="gate-field">'+head+input+'</label>';
}

/** 整张卡片的 HTML */
function gateCardHTML(g){
  var fields='';
  for(var i=0;i<g.fields.length;i++) fields+=gateFieldHTML(g.fields[i],g.interruptId);
  return '<div class="gate-card" data-gate="'+esc(g.interruptId)+'">'+
    '<div class="gate-t"><i></i>'+esc(g.title)+'</div>'+
    (g.message?'<p class="gate-m">'+esc(g.message)+'</p>':'')+
    '<div class="gate-f">'+fields+'</div>'+
    '<div class="gate-acts">'+
      '<button class="abtn gh" data-act="cancel">取消</button>'+
      '<button class="abtn pri" data-act="submit">'+esc(g.submitLabel)+'</button>'+
    '</div>'+
    '<p class="gate-note">填完点提交，智能体会接着往下做 —— 它是在等你这一步。</p>'+
  '</div>';
}

/** 从卡片上读出用户填的值（多选是数组，数字转 Number） */
function gateReadValues(card){
  var out={}, nodes=card.querySelectorAll('[data-key]');
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i], k=el.getAttribute('data-key');
    if(!k) continue;
    if(el.type==='checkbox'){
      if(!Array.isArray(out[k])) out[k]=[];
      if(el.checked) out[k].push(el.value);
    }else if(el.type==='radio'){
      if(el.checked) out[k]=el.value;
    }else if(el.getAttribute('data-num')==='1'){
      out[k]=el.value===''?'':Number(el.value);
    }else{
      out[k]=el.value;
    }
  }
  return out;
}

/** 必填项为空就拦下来 —— 提交一份缺字段的答复，智能体只能再来问一次 */
function gateMissing(card){
  var nodes=card.querySelectorAll('[data-req="1"]');
  for(var i=0;i<nodes.length;i++){
    var el=nodes[i], k=el.getAttribute('data-key');
    if(el.type==='checkbox'){
      var picked=card.querySelectorAll('input[type=checkbox][data-key="'+k+'"]:checked');
      if(!picked.length) return k;
    }else if(el.type==='radio'){
      if(!card.querySelector('input[type=radio][data-key="'+k+'"]:checked')) return k;
    }else if(!String(el.value||'').trim()){
      return k;
    }
  }
  return '';
}

/** 提交/取消后把卡片定格：留在右栏当记录，不再被轮询重画 */
function gateFreeze(card,note){
  card.className='gate-card done';
  card.setAttribute('data-locked','1');
  var old=card.querySelector('.gate-note');
  if(old) old.textContent=note;
  /* ~接入改动（2026-10-12）：右栏那张卡片跟着定格成「已确认」，并从待办数里划掉 */
  railGateDecide(null,card);
}

/** 一个字段的选项描边跟着勾选状态重画（change 与「撤选」两处共用这一份） */
function paintGateOpts(card,key){
  if(!key) return;
  var group=card.querySelectorAll('input[data-key="'+key+'"]');
  for(var i=0;i<group.length;i++){
    var lab=group[i].closest('.gate-opt');
    if(lab) lab.className='gate-opt'+(group[i].checked?' on':'');
  }
}

/** 该单选字段当前选中的值（组内没有 checked 的就返回空串） */
function gateGroupPick(card,key){
  var group=card.querySelectorAll('input[data-key="'+key+'"]');
  for(var i=0;i<group.length;i++){
    if(group[i].type==='radio'&&group[i].checked) return group[i].value;
  }
  return '';
}

function bindGateCard(card,g,scope,qi,H){
  /* 选中态描边（不用 :has()，老一点的浏览器也能对上） */
  card.addEventListener('change',function(ev){
    var t=ev.target;
    if(!t||!t.getAttribute) return;
    var key=t.getAttribute('data-key');
    paintGateOpts(card,key);
    if(key) card.__pick[key]=gateGroupPick(card,key);
    card.__optGuard=null;
  });
  /* 本组当前选中的值 —— 「点第二下撤选」靠它判断，不靠浏览器在 mousedown/click
     哪个时刻改 checked（各家实现不一致，实测点圆点与点文字的反应就不一样）。
     绑定时先按初始值灌一遍，否则「初始就有选中项」的卡片点它撤不掉。 */
  card.__pick={};
  card.__optGuard=null;
  (function(){
    var all=card.querySelectorAll('input[data-key]');
    for(var i=0;i<all.length;i++){
      var k=all[i].getAttribute('data-key');
      if(k&&all[i].type==='radio') card.__pick[k]=gateGroupPick(card,k);
    }
  })();
  /* 每次真实点击都重置「本次点击已处理」标记 */
  card.addEventListener('mousedown',function(){ card.__optGuard=null; });
  card.addEventListener('click',function(ev){
    var t=ev.target;
    if(!t||!t.tagName) return;
    /* 单选组：再点一次已选中的那项 = 撤销这一选（原生 radio 给不了「都不选」这个状态，
       用户 2026-10-13 报「选项选错了退不回去」）。preventDefault 是必须的 ——
       不压住默认动作，checked 会立刻被设回 true。
       两处防抖：① 点 label 里的文字时浏览器会把这次点击再转发给 input，同一串
       点击里第二次进来直接压住（__optGuard 认的是那整个 label）；② 撤选后本组
       选中值要同步清空，否则下次想再点亮这一项会被当成「又点了已选中项」。 */
    var lab=t.closest?t.closest('.gate-opt'):null;
    if(lab){
      var inp=lab.querySelector('input');
      if(inp&&inp.type==='radio'){
        var key=inp.getAttribute('data-key')||'';
        if(card.__optGuard===lab){ ev.preventDefault(); return; }
        if(card.__pick[key]===inp.value){
          /* 撤选要**做两遍**：radio 的勾选由浏览器的默认动作完成，而「点圆点本身」时
             Chrome 在 click 之前就把 checked 设好了，preventDefault 压不住那一步 ——
             实测点圆点：handler 里明明设了 false，事件跑完它又是 true。所以除了当场
             撤销，再在下一个 tick（默认动作已完成）撤一次，两种时序都覆盖到。 */
          var undo=function(){ inp.checked=false; paintGateOpts(card,key); };
          ev.preventDefault();
          undo();
          window.setTimeout(undo,0);
          card.__pick[key]='';
          card.__optGuard=lab;
          return;
        }
      }
    }
    if(t.tagName!=='BUTTON') return;
    var act=t.getAttribute('data-act');
    if(!act) return;
    if(act==='cancel'){
      gateFreeze(card,'已取消这次确认。可以重新发一次题，或在工作台里继续追问。');
      if(scope==='global') H.gateCancel(); else H.gateCancelQuestion(qi);
      return;
    }
    if(act!=='submit') return;
    var missing=gateMissing(card);
    if(missing){ toast('有必填项还没填：'+missing,IC_WARN); return; }
    var values=gateReadValues(card);
    gateFreeze(card,'已提交，智能体继续处理…');
    if(scope==='global') H.gateSubmit(values); else H.gateSubmitQuestion(qi,values);
  });
}

/**
 * 对账式挂闸门：每 700ms 看一眼「这条气泡所属的那次 run 有没有挂起的闸门」。
 * 有就画卡（已画过同一张就跳过），没有就把还没提交过的卡片收掉。
 *
 * 2026-10-12 改道：卡片落到**右栏闸门区**（railGateHost()），对话流里不再出现。
 * 右栏不在（单跑原型 / 脚本，没有 Vue 外壳）时回退老路径 —— 挂进等待气泡。
 */
function syncGate(host,scope,qi,H){
  var rail=railGateHost();
  var box=(rail||host).querySelector('.gate-card');
  if(box&&box.getAttribute('data-locked')==='1') return;   /* 已提交/已取消，留在右栏当记录 */
  var g=null;
  if(scope==='global'){ if(H.gateGlobal) g=H.gateGlobal(); }
  else if(H.gateQuestion) g=H.gateQuestion(qi);
  if(!g){ if(box) box.remove(); railGateBadge(); return; }
  if(box){
    if(box.getAttribute('data-gate')===g.interruptId) return;
    box.remove();
  }
  var wrap=document.createElement('div');
  wrap.innerHTML=gateCardHTML(g);
  var card=wrap.firstChild;
  if(rail){
    wrap.className='rail-gate-card';
    rail.appendChild(wrap);
    rail.scrollTop=rail.scrollHeight;
  }else{
    /* 回退老路径：挂进气泡的**内容流**（.txt），不能挂在 .msg 上 —— 那个是
       flex 行（头像 + 气泡），挂上去卡片会跑到气泡**右边**去 */
    (host.querySelector('.txt')||host).appendChild(card);
  }
  bindGateCard(card,g,scope,qi,H);
  if(rail){ railGateBadge(); railExpand(); railTabSoft('gate'); }
  scrollBottomIfStick();
}

/**
 * 六步进度条：由**产物与平台台账**往前走（2026-10-14 改）。
 *
 * 上一版（2026-10-12）读的是这条气泡渲染出来的**文本**：认 \`questions/qNN\`、
 * 认正文里的「问题三」。那是错的 —— 智能体写一句进度话术「q02-q04 是否跨日
 * SOC 链」，进度条就跳到「问题四」，而逐问（S1b）一个字都还没写；用户的原话是
 * 「进度条没有链接任务流程，每次对话就会推动一次」。
 *
 * 现在判据换成真东西：产物树（\`truth/*\`、\`questions/q0N/*\`、论文稿）＋平台台账的
 * 「阶段=S…」，由 analysis 模块在**跟随会话**时算好（\`syncStep\`），这里只取数。
 *
 * 两条纪律不变：
 *   · **认不出就不动** —— 没有证据时保持原样，不按时间硬推、不猜；
 *   · **只前进不后退** —— 倒退会让用户以为前面干的活白干了。
 */
function syncSteps(){
  var H=getAnalysisHooks();
  if(!H||!H.step) return;
  var n=Number(H.step())||0;
  if(n>curStep) renderSteps(n);
}

/**
 * 进工作台时的读题闸门。
 *
 * 一次 run 覆盖四个问题，所以只在入口等一次；后面每一步读的都是同一份缓存，
 * 不会再等第二次。失败 / 超时 / 用户点跳过，都直接放行走内置剧本。
 */
export function analysisBoot(title,fromDetail,cb){
  analysisFromDetail=!!fromDetail;   /* 后面每一步都要用它决定题面来源 */
  var H=getAnalysisHooks();
  if(!H){ cb(); return; }
  var ref=analysisRef(title,fromDetail);
  if(!ref){ cb(); return; }

  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+
    '<div class="an-head">'+analysisHeadHTML(ref)+'</div>'+
    liveBoxHTML()+
    '</div></div>';
  workHost().appendChild(el);
  scrollBottom();
  syncHead(el,ref);   /* 进度行首帧：秒数走 textContent，不进模具 */

  var liveBox=el.querySelector('.lv-stream');
  if(H.liveMount) H.liveMount(liveBox);

  var done=false, timer=null;
  function finish(){
    if(done) return; done=true;
    if(timer) clearInterval(timer);
    sealRunBubble(el,liveBox,H);
    cb();
    /* ~接入改动（2026-10-09，P2；2026-10-10 调序）：先追「分析报告」卡（成品，能直接
       下载 Word）再追「智能体产物」卡（清单）。理由与站点一贯的口径一致：成品先行、
       明细在后 —— 产物清单可能很长，成品压在它下面要滚半天才看得见。 */
    reportCardBoot(ref.caseId);
    /* ~接入改动（2026-10-09）：分析跑完（或接回已有产物）后追一张产物清单卡。
       放在 cb() 之后 —— cb 里还会往对话区推分析/候选模型那几条，这些卡排在它们后面。
       会话坐标在挂载那一刻冻结（ref.caseId），切到别的任务后点刷新也不会读错会话。 */
    artifactCardBoot(ref.caseId);
  }
  /* 事件委托，**不绑在按钮节点上**（2026-10-13）：头部现在有了等值守卫、不再
     每秒重建，但状态一变还是可能重写 —— 绑节点上的监听器会随之一并消失。
     挂在外层容器上就与重写无关了。 */
  el.addEventListener('click',function(ev){
    var t=ev.target;
    if(t&&t.closest&&t.closest('.an-skip')) H.skip();
  });

  /* 刷新后产物可能已经在会话里 —— 先试读；读到就不烧模型调用 */
  H.reuse(ref).then(function(ok){
    if(done) return;
    if(ok) finish(); else H.start(ref);
  }).catch(function(){ if(!done) H.start(ref); });

  timer=setInterval(function(){
    var st=H.status();
    /* 头部：只重写模具（与时间无关），秒数走 paintText；实时区原样累加 */
    if(!done) syncHead(el,ref);
    if(H.liveRender) H.liveRender(liveBox);
    syncGate(el,'global',0,H);
    syncSteps();
    /* 跟随期间新落盘的产物要跟手出现在清单里（内部按签名去重，没变就不打接口） */
    refreshArtCard();
    attachStripSync('home');
    attachStripSync('work');
    /* 粘底才滚 —— 用户往上翻看前面的内容时不许把他拽回来 */
    scrollBottomIfStick();
    if(st==='done'||st==='failed'){
      if(st==='failed'){
        /* 内置剧本已删（2026-10-08）：拿不到就如实说原因，不再拿写死内容顶上 */
        var why=H.failureText()||'';
        pushMsg({who:'ai',html:'<h4>智能体这次没有返回分析结果</h4>'+
          '<p class="src-note">这一步的内容由智能体实时生成，本次没拿到。你可以在下方输入框里追问，或重新发一次题。</p>'+
          (why?'<p class="src-note">原因：'+esc(why)+'</p>':'')},mkey());
      }
      finish();
    }
  },700);
}

/* ── 单问深挖（本轮新增）──────────────────────────────────────────────────

   全局那次 run 是「一次覆盖四问」，每一问只分到一节。走到某一问时再让智能体
   **只盯这一问**做一次：真机实测同一道题、同一个问题，单独做的篇幅是全局里
   那一节的 20 倍以上（30 行 → 730 行）。

   什么时候**不**跑：全局那次已经真的分析过这一问（不是占位），或者这一问
   之前已经深挖过 —— 有内容就别重复烧模型调用。 */

/** 单问深挖气泡的**头部**（实时区同样由旁边的 .lv-stream 承载；进度行同样不带秒数） */
function analysisQHeadHTML(qi){
  var cn=analysisCnAt(qi);
  return '<h4>正在深挖问题'+esc(cn)+'</h4>'+
    '<p>这次只让智能体做<b>问题'+esc(cn)+'</b>这一问，所以它写得比全局那次细得多 —— 同一道题单独做一问，篇幅通常是全局里那一节的二十倍以上。</p>'+
    '<p class="src-note">这一问会久一些（真机实测约 20 分钟）。不想等就点下面的「跳过等待」—— 那只让这个气泡停止等待，智能体那边的活不受影响，回头再看。</p>'+
    '<p class="an-meter"></p>'+
    '<div class="run-acts"><button class="abtn gh an-qskip">跳过等待</button></div>';
}

/** 单问头部进度行这一刻的文字 */
function analysisQMeterText(qi){
  var H=getAnalysisHooks();
  var st=H?H.questionStatus(qi):'idle';
  if(st!=='running') return '正在准备工作区…';
  return (H&&H.questionProgressText&&H.questionProgressText(qi))||'正在准备工作区…';
}

/** 单问头部：模具等值守卫 + 秒数走 textContent（与 syncHead 同构，按问取数） */
function syncQHead(el,qi){
  if(!el) return;
  var head=el.querySelector('.an-head');
  if(!head) return;
  var mold=analysisQHeadHTML(qi);
  if(head.getAttribute('data-mold')!==mold){
    head.innerHTML=mold;
    head.setAttribute('data-mold',mold);
  }
  paintText(head,'.an-meter',analysisQMeterText(qi));
}

/**
 * 进某一问前的深挖闸门。
 *
 * 与 analysisBoot 同构（等待可跳过、失败如实说原因、超时回落、结束时定格），
 * 差别只有一处：它是**按问**的，而且会先判断「这一问到底值不值得再跑一次」。
 */
export function analysisBootQuestion(qi,cb){
  var H=getAnalysisHooks();
  if(!H){ cb(); return; }
  /* 全局那次就没跑通（未登录 / 后端不可用 / 用户点了跳过）→ 这一问别再重试一遍，
     否则四问会各失败一次，白等四轮 */
  if(H.status()==='failed'){ cb(); return; }
  /* 全局那次已经真分析过这一问 → 直接用，不重复烧调用 */
  if(H.globalHasQuestion(qi)){ cb(); return; }
  /* 这一问之前深挖过（刷新后重走流程会走到这里）→ 也直接用 */
  if(H.hasQuestionRun(qi)){ cb(); return; }
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref){ cb(); return; }

  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+
    '<div class="an-head">'+analysisQHeadHTML(qi)+'</div>'+
    liveBoxHTML()+
    '</div></div>';
  workHost().appendChild(el);
  scrollBottom();
  syncQHead(el,qi);   /* 进度行首帧：秒数走 textContent */

  var liveBox=el.querySelector('.lv-stream');
  if(H.liveMount) H.liveMount(liveBox);

  var done=false, timer=null;
  function finish(){
    if(done) return; done=true;
    if(timer) clearInterval(timer);
    sealRunBubble(el,liveBox,H);
    cb();
  }
  /* 事件委托：头部重写不影响监听器（同 analysisBoot） */
  el.addEventListener('click',function(ev){
    var t=ev.target;
    if(t&&t.closest&&t.closest('.an-qskip')) H.skipQuestion(qi);
  });

  /* 刷新后产物可能已经在会话里 —— 先试读，读到就不烧模型 */
  H.reuseQuestion(ref,qi).then(function(ok){
    if(done) return;
    if(ok) finish(); else H.startQuestion(ref,qi);
  }).catch(function(){ if(!done) H.startQuestion(ref,qi); });

  timer=setInterval(function(){
    var st=H.questionStatus(qi);
    if(!done) syncQHead(el,qi);
    if(H.liveRender) H.liveRender(liveBox);
    syncGate(el,'question',qi,H);
    syncSteps();
    /* 粘底才滚 —— 这一问要跑 20 分钟，用户更难忍受被反复拽到底部 */
    scrollBottomIfStick();
    if(st==='done'||st==='failed'){
      if(st==='failed'){
        /* 内置剧本已删（2026-10-08）：如实说这一问没拿到，不拿写死内容顶 */
        var why=H.questionFailureText(qi)||'';
        pushMsg({who:'ai',html:'<h4>问题'+esc(analysisCnAt(qi))+'这次没等到分析结果</h4>'+
          '<p class="src-note">这一问的内容同样由智能体实时生成，本次没拿到；其余问题不受影响。</p>'+
          (why?'<p class="src-note">原因：'+esc(why)+'</p>':'')},mkey());
      }
      finish();
    }
  },700);
}

/**
 * 「这一步没拿到智能体内容」的如实说明。
 *
 * 内置演示剧本已整体删除（2026-10-08），所以拿不到真产物时**不能装作有** ——
 * 说清楚这一步归谁负责、用户可以怎么办，比塞一段写死内容诚实得多。
 */
function analysisMissingHTML(what){
  return '<h4>还没有拿到'+esc(what)+'</h4>'+
    '<p class="src-note">这一步的内容由智能体实时生成，本次没有返回可用的结果。'+
    '你可以在下方输入框里追问，或者重新发一次题（重开会话会重新生成一遍）。</p>';
}

/** 取某一步的真实分析；未就绪返回 null（调用方据此如实提示，不再回落内置剧本） */
export function analysisSlice(scope,qi){
  var H=getAnalysisHooks();
  if(!H||H.status()!=='done') return null;
  var opts={step:0,cnAt:analysisCnAt}, base=[];
  /* ~接入改动（2026-10-08）：内置剧本里的演示卡已删，base 恒为空 ——
     演示卡由产物自己带（analysisArts 会给真产物前插一张分析卡） */
  if(scope!=='global'){
    var q=QUIZ[qi]; if(!q) return null;
    opts.step=q.step;
  }
  var t=H.takeContent(scope,qi,opts);
  if(!t||!t.html) return null;
  var arts=analysisArts(scope,qi,base);
  var node={who:'ai',step:opts.step,html:t.html,arts:arts,chips:t.chips};
  /* 演示自动弹出是既有流程行为，真实通道下照旧 */
  var hasAnim=arts.some(function(a){ return a.type==='animation'; });
  if(hasAnim) node.auto='animation';
  return node;
}

/** 候选模型：候选取自 solution_map，形状与内置 picks 一致（所以下游不用改） */
export function analysisPicksNode(q){
  var H=getAnalysisHooks();
  if(!H||H.status()!=='done') return null;
  var picks=H.takePicks(qi,{step:q.step,cnAt:analysisCnAt});
  if(!picks||!picks.length) return null;
  return {step:q.step,key:q.key+'_r'+pickRound,
    html:'<h4>'+esc(q.picksTitle)+'</h4>'+H.picksNote()+
      '<p>下面这些方案都能走通，差别在严谨程度与可扩展性。我不替你拍板，点一张卡片右侧的「选它」：</p>'+
      '<p class="next">下一步：可以先看看要不要对比；确定后就点一张卡片右侧的「选它」。</p>',
    picks:picks};
}

/** 代码那一步的来源说明：分析由智能体生成，代码这一步仍走内置通道 */
export function analysisCodeNote(){
  var H=getAnalysisHooks(); if(!H) return '';
  return H.codeNote()||'';
}

/** 产物抽屉里的真实文档；没有真实产物返回空串，抽屉回落到原有内容 */
export function analysisLiveBody(type){
  var H=getAnalysisHooks(); if(!H) return '';
  return H.liveDoc(type)||'';
}

/* ── 附件真上传（非原型原码，本轮新增）─────────────────────────────────────

   把首页与工作台那两个「上传」按钮接成真上传。分工：
     -    校验与暂存交给 flow（只有它知道 caseId 与会话）
     - 首页入口：选完先留着，点「开始演易」时随 start() 传到本题会话
     - 工作台入口：题目已经定了，选完当场传

   附件条插在 composer **内部**（textarea 与工具条之间），看起来就是输入框
   自带的一排附件 —— 不改模板结构，也就不碰原型那 162 个函数依赖的 id。
   三种状态各自可见：待上传（黄点，文件还只在浏览器里）、上传中（蓝点闪，
   正在送进会话）、已上传（绿点，智能体读得到）。传完还会 toast 一次。

   注意：这里的内联处理器一律从 data-* 取参数、不写引号（bridge 是模板字符串，
   写 \\' 会被提前解义成 ' 而生成出坏代码）。 */

/** 附件条的挂载点：composer 内部、textarea 之后、工具条之前 */
function attachStripEl(scope,create){
  var input=$(scope==='home'?'home-input':'work-input');
  if(!input) return null;
  var box=input.closest('.composer')||input.parentNode;
  var el=document.getElementById('attach-strip-'+scope);
  if(!el&&create&&box){
    el=document.createElement('div');
    el.id='attach-strip-'+scope;
    el.className='attach-strip';
    var bar=box.querySelector('.cbar');
    if(bar) box.insertBefore(el,bar); else box.appendChild(el);
  }
  return el;
}

/** 键盘删除：输入框为空时按退格删最后一个；点中高亮的那条按退格/删除删它 */
function attachBindKeys(scope){
  var input=$(scope==='home'?'home-input':'work-input');
  if(!input||input.__attachKeys) return;
  input.__attachKeys=true;
  input.addEventListener('keydown',function(e){
    if(e.key!=='Backspace'&&e.key!=='Delete') return;
    var H=getAnalysisHooks();
    if(!H||!H.heldList) return;
    var list=H.heldList(scope)||[];
    if(!list.length) return;
    var box=attachStripEl(scope,false);
    var picked=box?box.querySelector('.at-chip.sel'):null;
    if(picked){
      var pb=picked.querySelector('button');
      e.preventDefault();
      attachRemoveKey(scope,pb?pb.getAttribute('data-k'):'');
      return;
    }
    /* 只吃「输入框为空」时的退格 —— 有内容时那是正常的删字 */
    if(e.key==='Backspace'&&!input.value){
      e.preventDefault();
      attachRemoveKey(scope,list[list.length-1].key);
    }
  });
}

/** 附件条的签名：内容或状态一变就得重画，没变就别动 DOM（免得闪、免得打断点击） */
function attachStripSig(list,bad){
  var s='';
  for(var i=0;i<list.length;i++){
    var st=list[i].uploaded?'1':(list[i].uploading?'u':'0');
    s+=list[i].key+'|'+st+';';
  }
  for(var k=0;k<(bad||[]).length;k++) s+='!'+bad[k].key+';';
  return s;
}

/** 点一下 chip 选中（蓝框），再按退格/删除就删它；点第二条时取消前一条 */
function attachChipTap(chip,ev){
  if(ev&&ev.target&&ev.target.tagName==='BUTTON') return;
  if(!chip) return;
  var box=chip.parentNode;
  var on=String(chip.className||'').indexOf(' sel')>=0;
  if(box){
    var all=box.querySelectorAll('.at-chip');
    for(var i=0;i<all.length;i++){
      all[i].className=String(all[i].className).replace(' sel','');
    }
  }
  if(!on) chip.className=chip.className+' sel';
}

/** 画附件条：三种状态各有各的点色与文案，鼠标停上去还说清「什么时候传」 */
function attachStripRender(scope){
  var el=attachStripEl(scope,true);
  if(!el) return;
  attachBindKeys(scope);
  var H=getAnalysisHooks();
  var list=(H&&H.heldList)?(H.heldList(scope)||[]):[];
  var bad=(H&&H.rejectedList)?(H.rejectedList(scope)||[]):[];
  var html='';
  for(var i=0;i<list.length;i++){
    var f=list[i];
    var isProb=f.kind==='problem';
    var cls=f.uploaded?' up':(f.uploading?' ing':'');
    if(isProb) cls=cls+' prob';
    var st=f.uploaded?'已上传':(f.uploading?'上传中…':'待上传');
    var tip;
    if(isProb){
      if(f.uploaded) tip='题目文件已在本会话，智能体按它来理解题目';
      else if(f.uploading) tip='正在上传题目文件…';
      else if(scope==='home') tip='题目文件：点「开始演易」时自动上传，智能体按它来理解题目';
      else tip='题目文件：本题会话还没建立，稍后会自动上传';
    }
    else if(f.uploaded) tip='已保存到本题会话，智能体可以直接读取';
    else if(f.uploading) tip='正在上传到本题会话…';
    else if(scope==='home') tip='点「开始演易」时自动上传到本题会话';
    else tip='本题会话还没建立，稍后会自动上传';
    html+='<span class="at-chip'+cls+'" title="'+esc(tip)+'"><i></i>'+
      (isProb?'<em>题目</em>':'')+
      '<b>'+esc(f.name)+'</b>'+
      '<span class="st">'+st+'</span>'+
      '<button data-k="'+esc(f.key)+'" title="移除（也可按退格键）" onclick="attachStripRemove(this)">×</button></span>';
  }
  /* 没过校验的要留在条上 —— 只弹一句 toast 的话，用户选完看到"没反应"，
     会以为已经交出去了，接着就来问「智能体怎么读不到」 */
  for(var b=0;b<bad.length;b++){
    html+='<span class="at-chip bad" title="'+esc(bad[b].reason+'（这份文件不会被上传；点 × 去掉这条提示）')+'"><i></i>'+
      '<b>'+esc(bad[b].name)+'</b>'+
      '<span class="st">未通过校验</span>'+
      '<button data-k="'+esc(bad[b].key)+'" title="去掉这条提示" onclick="attachStripRemove(this)">×</button></span>';
  }
  el.innerHTML=html;
  el.setAttribute('data-sig',attachStripSig(list,bad));
  /* 点选绑定放在这里 —— innerHTML 每次重画都会换掉节点，事件得跟着重绑 */
  var chips=el.querySelectorAll('.at-chip');
  for(var j=0;j<chips.length;j++){
    (function(c){ c.addEventListener('click',function(ev){ attachChipTap(c,ev); }); })(chips[j]);
  }
}

/* 上传是 flow 内部完成的（start() 里先登记会话再传），core 收不到通知 ——
   用上一次的快照对账：等「待上传归零、且已上传变多」的那一刻提示一次。
   不然用户盯着「待上传」永远猜不到到底是传了还是没传。 */
var attachSeen={};

/** 对账：真的传上去了才吭声，一次只吭一声 */
function attachNoteSync(scope,list){
  var up=0,ing=0,pend=0;
  for(var i=0;i<list.length;i++){
    if(list[i].uploaded) up++;
    else if(list[i].uploading) ing++;
    else pend++;
  }
  var prev=attachSeen[scope];
  attachSeen[scope]={up:up,pend:pend};
  if(!prev) return;
  if(prev.pend>0&&pend===0&&ing===0&&up>prev.up){
    /* 题目文件与数据附件分开说 —— 前者是题面，别把两者混成一句「附件」 */
    var prob=0;
    for(var j=0;j<list.length;j++){ if(list[j].uploaded&&list[j].kind==='problem') prob++; }
    var parts=[];
    if(prob) parts.push('题目文件已上传');
    if(up-prob>0) parts.push((up-prob)+' 个附件已上传');
    toast(parts.join('、')+'，智能体可以直接读取',IC_OK);
  }
}

/**
 * 按需重画。
 *
 * 上传是在分析通道内部完成的（start() 里先登记会话再传），core 这边不会收到
 * 通知 —— 所以由读题气泡那个定时器顺带对一次账：签名变了才重画。
 */
function attachStripSync(scope){
  var H=getAnalysisHooks();
  var list=(H&&H.heldList)?(H.heldList(scope)||[]):[];
  var bad=(H&&H.rejectedList)?(H.rejectedList(scope)||[]):[];
  var el=attachStripEl(scope,false);
  if(!el){ if(list.length||bad.length) attachStripRender(scope); }
  else if(el.getAttribute('data-sig')!==attachStripSig(list,bad)) attachStripRender(scope);
  attachNoteSync(scope,list);
}

/** 按 key 移除一个附件：按钮点、键盘删都走这条 */
function attachRemoveKey(scope,key){
  var H=getAnalysisHooks();
  if(!H||!H.removeHeld||!key) return;
  attachSeen[scope]=null;   /* 主动撤掉的不该触发「已上传」提示 */
  var after=function(){ attachStripRender(scope); };
  var p=H.removeHeld(scope,key);
  if(p&&p.then) p.then(after,after); else after();
}

/** 附件条上的「移除」：没通过的只撤提醒；待上传的直接丢，已上传的连会话里的文件一起删 */
function attachStripRemove(btn){
  var chip=btn;
  while(chip&&String(chip.className||'').indexOf('at-chip')<0){ chip=chip.parentNode; }
  var box=chip&&chip.parentNode;
  var scope=(box&&box.id==='attach-strip-work')?'work':'home';
  var key=btn&&btn.getAttribute?btn.getAttribute('data-k'):'';
  /* 没通过校验的那条只是提醒，撤掉它不该碰真附件 */
  if(chip&&String(chip.className||'').indexOf('bad')>=0){
    var HB=getAnalysisHooks();
    if(HB&&HB.dropRejected&&key){ HB.dropRejected(scope,key); attachStripRender(scope); }
    return;
  }
  attachRemoveKey(scope,key);
}

/** 换了一道题：摘掉上一题已上传的附件，并把附件条重画 */
function attachBeginProblem(){
  var H=getAnalysisHooks();
  if(H&&H.beginProblem) H.beginProblem();
  attachSeen.home=null;
  attachSeen.work=null;
  attachStripRender('home');
  attachStripRender('work');
}

/* ── 题目文件（本轮新增）─────────────────────────────────────────────────

   题面有两个来源：输入框里手写的文字，和用户上传的题目文件。后者让
   「只传文件、一个字不打」也能开跑 —— 题面正文由文件承担，站点只负责
   把它送进会话，并告诉智能体「题面在这个文件里，先读它」。 */

/** 题目文件的显示名（去掉扩展名）—— 只传了题目文件时，用它当工作台标题 */
function attachProblemTitle(scope){
  var H=getAnalysisHooks();
  if(!H||!H.problemTitle) return '';
  return H.problemTitle(scope)||'';
}

/** 这个入口有没有题目文件（决定「开始演易」放不放行、题面从哪来）*/
function attachHasProblem(scope){
  var H=getAnalysisHooks();
  return !!(H&&H.hasProblemFile&&H.hasProblemFile(scope));
}

/** 「上传题目」按钮：文件进题目槽 */
export function pickProblem(scope){ pickFile(scope,'problem'); }

/** 「上传附件」按钮：文件进附件槽 */
export function pickData(scope){ pickFile(scope,'data'); }

/** 选完文件：校验 + 暂存；工作台里再当场传到本题会话。
 *  kind 决定这份文件算题面（'problem'）还是补充材料（'data'）—— 两边的
 *  提示文案与附件条样式都据此分开，用户点哪个按钮就该得到哪套反馈。 */
function attachPick(scope,files,names,kind){
  var H=getAnalysisHooks();
  if(!H||!H.holdFiles){ toast('当前环境没有接入附件通道，文件不会被上传'); return; }
  kind=kind||'data';
  var r=H.holdFiles(scope,files,kind)||{accepted:0,rejected:[]};
  var bad=r.rejected||[];
  var what=kind==='problem'?'题目文件':'附件';
  var msg='已选中 '+files.length+' 个'+what+'：'+names.join('、');
  if(bad.length) msg=msg+'；有 '+bad.length+' 个没收下（'+bad.join('；')+'）——没收下的不会被上传，已在附件条上标出。';
  if(!r.accepted) msg=msg+'没有一个能上传。';
  else if(kind==='problem'&&scope==='home') msg=msg+'。现在就可以点「开始演易」了。';
  else if(kind==='problem') msg=msg+'。开题后智能体按它理解题目。';
  else if(scope==='home') msg=msg+'。点「开始演易」时自动上传。';
  toast(msg,bad.length?IC_WARN:IC_OK);
  attachStripRender(scope);
  if(scope!=='work'||!r.accepted||!H.attachNow) return;
  attachStripSync('work');   /* 先亮出「上传中」—— 不然这一段是看不见的 */
  H.attachNow('work').then(function(failed){
    attachStripSync('work');
    if(failed&&failed.length) toast(failed.join('；'),IC_WARN);
    attachLateNotice();
  },function(){ attachStripSync('work'); });
}

/** 有没有「传上去了、但这一轮读不到」的附件 */
function attachLateList(){
  var H=getAnalysisHooks();
  return (H&&H.lateAttachments)?(H.lateAttachments()||[]):[];
}

/**
 * 刚传的附件没赶上这一轮的实话 + 重跑入口。
 *
 * 这一轮 run 的提示词在进工作台时就发出去了，里面没有这份附件 —— 智能体
 * 那一轮确实读不到。只显示一个绿色的「已上传」会让用户以为它已经看到，
 * 所以这里把话说清楚，并给一个动作把它补进去。
 */
function attachLateNotice(){
  var H=getAnalysisHooks();
  var late=attachLateList();
  if(!late.length) return;
  var names=[]; for(var i=0;i<late.length;i++) names.push(late[i].name);
  var running=!!(H&&H.status&&H.status()==='running');
  pushMsg({who:'ai',html:'<h4>这份附件这一轮读不到</h4>'+
    '<p>「'+esc(names.join('、'))+'」已经传到本题会话工作区，但这一轮的题目与分析请求<b>在它上传之前就发出去了</b>，智能体手上没有它的清单。</p>'+
    '<p class="src-note">'+(running
      ? '这一轮还在跑 —— 等它结束，再点下面的按钮把它带上重跑一次。'
      : '点下面的按钮，带上它重新分析一次。')+'</p>'+
    '<div class="run-acts"><button class="abtn attach-rerun" onclick="attachRerun(this)">带上刚上传的附件重新分析</button></div>'},mkey());
  scrollBottom();
}

/** 「带上刚上传的附件重新分析」：丢结果、留附件，重新发起一次 */
function attachRerun(btn){
  var H=getAnalysisHooks();
  if(!H||!H.rerun){ toast('当前环境没有接入分析通道',IC_WARN); return; }
  try { H.rerun(); }
  catch(e){ toast(e&&e.message?e.message:'现在还不能重跑',IC_WARN); return; }
  if(btn){ btn.disabled=true; btn.textContent='已重新发起…'; }
  toast('重新分析已开始，这次会带上刚上传的附件',IC_OK);
  analysisBoot(state.workTitle,!!analysisFromDetail,function(){ animationBoot(); });
}

/* ── S2 真实动画通道（非原型原码，本轮新增）───────────────────────────────

   把「分析 → 可视化演示」从**内置演示**换成分析动画智能体实时生成：
   在后台生成（不阻塞当前步骤），右下角小浮窗报进度；生成完的每一档
   登记进 ANIM_BANK，内容用 blob URL 挂 iframe（与全屏层同一隔离口径）。

   诚实性三条：
     - 接管期间**不登记内置演示**（regAnims 改走 animRealRegister），
       免得真实与内置混在一张列表里分不清；
     - 失败不说成成功：抽屉里如实写「这一档没有真实产物」；
     - 换题 / 重试把上一题留下的 blob URL 全部 revoke。 */

var ANIM_REAL_ON=false;         /* 真实动画是否接管（接管期间内置演示让位） */
var ANIM_REAL_TRIED=false;      /* 这道题是否试过真实通道（区分「没试过」与「试过但失败」） */
var ANIM_REAL_URLS={};          /* k → blob URL */
/* ⛔ 2026-10-15 浮窗移除后，只留这条轮询句柄（done/failed 的登记副作用用） */
var ANIM_POLL_TIMER=null;

/** 真实动画是否已接管 */
function animRealActive(){ return ANIM_REAL_ON; }

/** 已就绪的真实档位；未就绪返回空数组 */
function animRealItems(){
  var A=getAnimationHooks();
  if(!A||A.status()!=='done') return [];
  return A.takeAnims()||[];
}

/** 按站点键找一档 */
function animRealItem(k){
  var list=animRealItems();
  for(var i=0;i<list.length;i++) if(String(list[i].k)===String(k)) return list[i];
  return null;
}

function animRealTitle(k){ var it=animRealItem(k); return it?it.title:''; }

/** 这一档的 blob URL（懒创建；没有这一档返回空串） */
function animRealURL(k){
  var it=animRealItem(k);
  if(!it||!it.html) return '';
  if(!ANIM_REAL_URLS[k]){
    try{ ANIM_REAL_URLS[k]=URL.createObjectURL(new Blob([it.html],{type:'text/html'})); }
    catch(e){ return ''; }
  }
  return ANIM_REAL_URLS[k];
}

/** 撤掉所有 blob URL（换题 / 重试 / 重新开始） */
function animRealRevoke(){
  for(var k in ANIM_REAL_URLS){
    if(!Object.prototype.hasOwnProperty.call(ANIM_REAL_URLS,k)) continue;
    try{ URL.revokeObjectURL(ANIM_REAL_URLS[k]); }catch(e){}
  }
  ANIM_REAL_URLS={};
}

/** 把真实档位登记进 ANIM_BANK（regAnims 在接管期间改走这里） */
function animRealRegister(){
  var list=animRealItems();
  for(var i=0;i<list.length;i++){
    var it=list[i], hit=null;
    for(var j=0;j<ANIM_BANK.length;j++) if(String(ANIM_BANK[j].k)===String(it.k)) hit=ANIM_BANK[j];
    if(hit){ hit.title=it.title; hit.sub=it.sub; }
    else ANIM_BANK.push({k:String(it.k),title:it.title,sub:it.sub});
  }
  refreshAnimDrawer();     /* 抽屉开着就立刻换成真实内容 */
}

/** 舞台里的真实动画：iframe 挂 blob URL（与全屏层同一隔离口径） */
function animRealFrameHTML(k){
  var url=animRealURL(k);
  if(!url) return '';
  return '<iframe class="anim-frame" data-anim="'+esc(String(k))+'" title="'+esc(animRealTitle(k)||'分析动画')+'" src="'+esc(url)+'"></iframe>';
}

/** 真实列表里那排标签的事件：渲染后才有，用 data-wired 防重复绑定 */
function wireAnimTabs(){
  var box=$('drawer-body');
  if(!box) return;
  var all=box.querySelectorAll('[data-dq]');
  for(var i=0;i<all.length;i++){
    var b=all[i];
    if(b.getAttribute('data-wired')) continue;
    b.setAttribute('data-wired','1');
    b.addEventListener('click',(function(k){
      return function(){ drawerAnim(k); };
    })(b.getAttribute('data-dq')));
  }
}

/**
 * 动画抽屉的正文。
 *
 * 返回空串表示「真实通道没试过」→ 调用方（artBody）走内置那份列表。
 * 试过就一律由这里作答：生成中 / 没生成出来 / 已就绪三种口径分明，
 * 绝不拿内置演示冒充真实结果。
 */
function animRealDrawerHTML(){
  if(!ANIM_REAL_TRIED) return '';
  var A=getAnimationHooks();
  var head='<div class="card-h">'+ART.animation.icon.replace('#fff','#17A2C4')+'<h3>可视化演示</h3>';
  var list=animRealItems();
  if(!list.length){
    var st=A?A.status():'idle';
    var running=st==='running';
    var tip=running?'正在生成分析动画…':'这一档没有拿到真实动画';
    var why=(!running&&A&&A.failureText)?(A.failureText()||''):'';
    return '<div class="card">'+head+'<span class="tag-c" style="font-size:11px">'+(running?'生成中':'未生成')+'</span></div>'+
      '<div class="card-b">'+
        '<div class="stage dim"><div class="stage-empty">'+esc(tip)+'<span>动画在后台生成，完成一档就出现在这里</span></div></div>'+
        '<p class="src-note">这里的动画由分析动画智能体按本题题面生成，不是平台内置演示。'+(why?('这次没拿到：'+esc(why)+'。'):'')+'</p>'+
      '</div></div>';
  }
  var cur=String(ANIM_CUR), has=false;
  for(var i=0;i<list.length;i++) if(String(list[i].k)===cur) has=true;
  if(!has) cur=String(list[0].k);
  ANIM_CUR=cur;
  var cards=list.map(function(it){
    var k=String(it.k);
    return '<button class="acard'+(k===cur?' open':'')+'" id="dq-'+esc(k)+'" data-dq="'+esc(k)+'" style="margin-bottom:8px">'+
      '<span class="ai" style="background:linear-gradient(120deg,#12A594,#1E9FC4)">'+esc(k)+'</span>'+
      '<span class="at"><b>'+esc(it.title)+'</b><span>'+esc(it.sub)+'</span></span></button>';
  }).join('');
  return '<div class="card">'+head+'<span class="tag-c" style="font-size:11px">智能体生成 · '+list.length+' 档</span></div>'+
    '<div class="card-b">'+
      '<div class="stage" id="d-stage">'+fsBtnHTML(cur)+animRealFrameHTML(cur)+'</div>'+
      '<p class="src-note">这些动画由分析动画智能体按本题题面生成（与上面那份分析同源），不是平台内置演示。</p>'+
      '<div style="margin-top:14px">'+cards+'</div>'+
    '</div></div>';
}

/* ⛔ 进度浮窗已移除（2026-10-15，按用户要求）
   ─────────────────────────────────────────────────────────────
   原来右下角有一个常驻浮窗（「正在生成分析动画 / 已用时 X 秒 · 已出 N 档 /
   动画在后台生成… / 先收起」，含一块思考·工具明细实时区）。现已整体去掉：
   右栏「可视化演示」tab 是唯一的进度出口，浮窗与之重复。

   连带影响（有意为之）：浮窗那块的 .anf-live 是动画链路的实时区通道宿主，
   不建浮窗 ⇒ 不再 liveAttach ⇒ 流式思考/工具明细不再上屏（本来也只画在浮窗里）。
   产物流转、右栏「出到哪档看到哪档」全部照旧。

   下面的状态轮询**必须保留**：它不是为浮窗服务的，还承担两个副作用 ——
   done 时把已收到的档位登记给右栏（animRealRegister）、failed 时刷新抽屉。 */

/** 动画状态轮询（原 animFloatWatch，已去掉一切浮窗 UI） */
function animStatusWatch(){
  var A=getAnimationHooks();
  if(!A) return;
  if(ANIM_POLL_TIMER) clearInterval(ANIM_POLL_TIMER);
  ANIM_POLL_TIMER=setInterval(function(){
    var st=A.status();
    if(st==='done'){
      clearInterval(ANIM_POLL_TIMER); ANIM_POLL_TIMER=null;
      ANIM_REAL_ON=true;
      animRealRegister();
    }else if(st==='failed'){
      clearInterval(ANIM_POLL_TIMER); ANIM_POLL_TIMER=null;
      ANIM_REAL_ON=false;
      refreshAnimDrawer();
    }
  },900);
}

/**
 * 进工作台后由 analysisBoot 的回调**在后台**发起（对话不等它）。
 *
 * 前置条件是 S1 真的跑通了：上游没有分析产物，动画无从谈起 —— 这时直接返回，
 * 内置演示照旧（不是失败，是设计）。
 */
export function animationBoot(){
  var A=getAnimationHooks();
  var S=getAnalysisHooks();
  if(!A||!S||S.status()!=='done') return;
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref) return;
  ANIM_REAL_TRIED=true;
  ANIM_REAL_ON=true;          /* 接管期间内置演示不登记 */
  animRealRevoke();           /* 上一题留下的 blob URL 全部撤掉 */
  A.reuse(ref).then(function(ok){
    if(ok){ ANIM_REAL_ON=true; animRealRegister(); }
    else A.start(ref);
  }).catch(function(){ A.start(ref); });
  animStatusWatch();
}

/* ── S3 真实代码通道（非原型原码，本轮新增）───────────────────────────────

   把「选定模型 → 代码」从**内置剧本代码**换成解题代码智能体实时生成：
   选定后弹等待气泡（可跳过），完成后真实源码包交给抽屉展示。

   与 S2 的关键差别：智能体的契约是**只生成不运行**（executed_problem_code:false）。
   所以页内那个「▶ 运行代码」不给 —— 它打的是内置剧本的输出，挂在真实源码下面
   等于把示意值说成实算结果。只留「复制 / 下载 / 交给沙箱」。 */

/** 这道题该用哪份题面（与 S2 同一口径） */
function codegenRef(){ return analysisRef(state.workTitle,analysisFromDetail); }

/** 真实代码通道这次是否可用：S1 跑通 + 有题面 + 钩子已注册 */
function codegenReady(){
  var C=getCodegenHooks(), S=getAnalysisHooks();
  if(!C||!S||S.status()!=='done') return false;
  return !!codegenRef();
}

/** 这一问的真实代码包；没有返回 null */
function codeRealOf(qn){
  var C=getCodegenHooks();
  if(!C) return null;
  try{ return C.takeQuestion(qn); }catch(e){ return null; }
}

/** 选定模型后的回复（真实模式：不照搬内置剧本那段话术） */
function codegenReplyNode(o,qi){
  return '<h4>已采用方案 · '+esc(o.name)+'</h4>'+
    '<p>我按这个方案给<b>问题'+esc(analysisCnAt(qi))+'</b>写一份完整源码包：主程序 + 运行说明 + 依赖清单。</p>'+
    '<p class="next">下一步：代码回来之后设定参数，把源码交给沙箱或本地环境去跑。</p>';
}

/** 真实模式下选完模型的快捷语提示 */
function codegenRunHint(){ return '代码生成中，稍等；也可以直接回一句「继续」'; }

/** 代码生成的等待气泡：进度 + 跳出口 + 「只生成不运行」的实话（进度行不带秒数） */
function codeWaitHTML(qi){
  var cn=analysisCnAt(qi);
  return '<h4>正在为问题'+esc(cn)+'生成代码</h4>'+
    '<p>解题代码智能体按你刚选的模型在写这一问的完整源码包（主程序 + 运行说明 + 依赖清单），所以会久一些。</p>'+
    '<p class="src-note">这条通道只生成代码、不在页面里运行 —— 拿到之后可以复制、下载，或交给沙箱去跑。</p>'+
    '<p class="an-meter"></p>'+
    '<div class="run-acts"><button class="abtn gh code-skip">不等了，先跳过</button></div>';
}

/** 代码等待气泡进度行这一刻的文字 */
function codeMeterText(qi){
  var C=getCodegenHooks();
  var st=C?C.questionStatus(qi):'idle';
  if(st!=='running') return '正在准备工作区…';
  return (C&&C.questionProgressText&&C.questionProgressText(qi))||'正在准备工作区…';
}

/** 等待气泡状态区：模具等值守卫 + 秒数走 textContent */
function syncCodeWait(el,qi){
  if(!el) return;
  var box=el.querySelector('.txt');
  if(!box) return;
  var mold=codeWaitHTML(qi);
  if(box.getAttribute('data-mold')!==mold){
    box.innerHTML=mold;
    box.setAttribute('data-mold',mold);
  }
  paintText(box,'.an-meter',codeMeterText(qi));
}

/**
 * 选完模型后发起这一问的代码生成，并在对话里等到有结果（或用户跳过）。
 * 完成后回调 cb —— 由 cb 决定接下来怎么走（设定参数 / 打开抽屉）。
 */
export function codeBoot(qi,model,cb){
  cb=cb||function(){};
  var C=getCodegenHooks();
  var ref=codegenRef();
  if(!C||!ref){ cb(); return; }

  var el=document.createElement('div');
  el.className='msg ai';
  /* 实时区是 .txt 的**兄弟**，不是它的孩子：状态文案（codeWaitHTML）每 700ms
     整块重写一次，实时区不能跟着被换掉 —— 事件流状态挂在节点上（live 模块的
     WeakMap），节点一换这轮就只剩最后一次重画之后的明细了。 */
  el.innerHTML='<div class="bubble"><div class="txt">'+codeWaitHTML(qi)+'</div>'+
    '<div class="lv-stream code-live"></div></div>';
  workHost().appendChild(el);
  scrollBottom();
  syncCodeWait(el,qi);   /* 进度行首帧：秒数走 textContent，且**不会**碰实时区 */

  var liveBox=el.querySelector('.code-live');
  /* 先把实时区交出去再发起：startQuestion 内部会 begin 这个通道，晚交就接不到开头那批事件 */
  if(C.liveAttach) C.liveAttach(qi,liveBox);

  var done=false,timer=null;
  function finish(){
    if(done) return; done=true;
    if(timer) clearInterval(timer);
    if(C.liveAttach) C.liveAttach(qi,null);   /* 气泡摘掉，通道一并注销 */
    el.remove();
    cb();
  }
  /* 事件委托：状态区重写不影响监听器 */
  el.addEventListener('click',function(ev){
    var t=ev.target;
    if(t&&t.closest&&t.closest('.code-skip')) C.skipQuestion(qi);
  });

  /* 刷新后产物可能已经在会话里 —— 先试读；读到就不烧模型调用 */
  C.reuseQuestion(ref,qi,model).then(function(ok){
    if(done) return;
    if(!ok) C.startQuestion(ref,qi,model);
  }).catch(function(){ if(!done) C.startQuestion(ref,qi,model); });

  timer=setInterval(function(){
    var st=C.questionStatus(qi);
    if(!done) syncCodeWait(el,qi);
    /* 实时区：不参与上面的重写，只按 live 模块那套刷新。
       可见性交给 CSS 的 :empty —— 不在这里切 display，避免「有内容却被藏着」的空窗。 */
    if(liveBox&&C.liveRender) C.liveRender(liveBox);
    scrollBottom();
    if(st==='done'||st==='failed'){
      if(st==='failed'){
        /* 内置剧本已删（2026-10-08）：拿不到代码就如实说，不拿写死内容顶 */
        var why=C.questionFailureText(qi)||'';
        pushMsg({who:'ai',html:'<h4>问题'+esc(analysisCnAt(qi))+'这次没拿到代码</h4>'+
          '<p class="src-note">这一问的源码由解题代码智能体生成，本次没拿到；你可以在下方输入框里追问，或稍后重试。</p>'+
          (why?'<p class="src-note">原因：'+esc(why)+'</p>':'')},mkey());
      }else{
        pushMsg({who:'ai',html:'<h4>问题'+esc(analysisCnAt(qi))+'的代码已生成</h4>'+
          '<p>源码包来自解题代码智能体（按 <b>'+esc(model.name||'')+'</b> 生成的这一问），右侧「代码」抽屉里可以看全文、复制、下载。它<b>只生成不运行</b>。</p>'+
          '<p class="next">下一步：把源码交给沙箱或本地环境去跑。</p>'},mkey());
      }
      finish();
    }
  },700);
}

/** 把真实源码灌进这一问（代码检测 import 时就跟着走真实值） */
function codeApplyToQuiz(qi){
  var pkg=codeRealOf(qi);
  var q=QUIZ[qi];
  if(pkg&&pkg.mainText&&q) q._codeText=pkg.mainText;
}

/** 代码那一步的来源说明：真实 / 内置分开写 */
function codeLabNote(){
  if(codeRealOf(qi)) return '<p class="src-note">这一问的源码由解题代码智能体按本题题面生成，<b>只生成不运行</b>；要跑请交给沙箱或本地环境。</p>';
  return analysisCodeNote();
}

/** 抽屉里的代码正文：真实源码 + 配套文件清单 */
function codeRealBody(qn){
  var pkg=codeRealOf(qn);
  if(!pkg||!pkg.mainText) return '';
  var files=pkg.files||[];
  var list=files.length
    ? '<ul class="sb-files">'+files.map(function(f){
        return '<li><code>'+esc(f.path)+'</code><span>'+String((f.content||'').length)+' 字</span></li>';
      }).join('')+'</ul>'
    : '<p class="side-note">这个包只有主程序一个文件。</p>';
  var lines=String(pkg.mainText).split('\\n').length;
  return '<div class="card"><div class="card-h">'+ART.code.icon.replace('#fff','#1E9FC4')+'<h3>'+esc(pkg.mainName||'main.py')+'</h3><div class="grow"></div>'+
      '<button class="btn-ghost" data-act="copy">复制</button>'+
      '<button class="btn-ghost" data-act="download">下载</button></div>'+
    '<p class="src-note">这份源码由解题代码智能体按本题题面生成（模型：'+esc(pkg.model||'')+'），<b>只生成不运行</b> —— 页内不给模拟运行，要跑请交给沙箱或本地环境。</p>'+
    '<div class="card-b" style="padding:0"><div class="code-box">'+
      '<div class="code-top"><span class="f">'+esc(pkg.mainName||'main.py')+' · Python · '+lines+' 行</span></div>'+
      '<pre class="code">'+hl(pkg.mainText)+'</pre>'+
    '</div></div></div>'+
    '<div class="card"><div class="card-h"><h3>配套文件</h3></div><div class="card-b">'+list+'</div></div>';
}

/** 抽屉渲染完成后补绑：复制 / 下载（这些节点渲染后才有，绑不了内联处理器） */
export function wireRealCodeActions(){
  var box=$('drawer-body');
  if(!box) return;
  var c=box.querySelector('[data-act="copy"]');
  if(c) c.addEventListener('click',function(){ codeCopyText(); });
  var d=box.querySelector('[data-act="download"]');
  if(d) d.addEventListener('click',function(){ codeDownloadText(); });
}

function codeCopyText(){
  var pkg=codeRealOf(qi);
  if(!pkg) return;
  if(navigator.clipboard&&navigator.clipboard.writeText){
    navigator.clipboard.writeText(pkg.mainText||'').then(
      function(){ toast('源码已复制'); },
      function(){ toast('复制失败，请手动选中源码'); });
  }else toast('这个浏览器不支持一键复制，请手动选中源码');
}

function codeDownloadText(){
  var pkg=codeRealOf(qi);
  if(!pkg) return;
  try{
    var a=document.createElement('a');
    a.href=URL.createObjectURL(new Blob([pkg.mainText||''],{type:'text/plain;charset=utf-8'}));
    a.download=pkg.mainName||'main.py';
    document.body.appendChild(a); a.click();
    document.body.removeChild(a);
    var url=a.href;
    setTimeout(function(){ try{ URL.revokeObjectURL(url); }catch(e){} },2000);
    toast('已下载 '+(pkg.mainName||'main.py'));
  }catch(e){ toast('下载失败，请改用复制'); }
}

/** 重新开始一道题：把动画 / 代码两条通道里属于上一题的东西清掉 */
export function realChannelsReset(){
  animRealRevoke();
  ANIM_REAL_ON=false;
  ANIM_REAL_TRIED=false;
  if(ANIM_POLL_TIMER){ clearInterval(ANIM_POLL_TIMER); ANIM_POLL_TIMER=null; }
  var C=getCodegenHooks(); if(C&&C.reset) C.reset();
  var A=getAnimationHooks(); if(A&&A.reset) A.reset();
}

/* ==================================================================
   工作台任务仓（2026-10-08 新增）

   为什么：工作台的状态（state.workTitle / detailId / 对话区 DOM）以前只在内存里，
   一次刷新、一次 Vite 热更新重载就全没了 —— 重载后 applyRoute 会调 go('work')，
   而 go() 第一行是 if(!state.workTitle){ toast(...); return }，于是视图停在首页、
   地址栏却还挂着 #/work，看着就是「聊着聊着跳回首页」。

   这里做两件事：
     ① 开题时向任务仓登记一条（题面 / 题号 / 是否来自详情页）；
     ② 对话区**按任务分桶** —— 每个任务一个容器，前台那个挂在 #stream-in 里，
        其余任务的容器留在内存（节点不销毁）。所以切走再切回来，两边内容都在，
        也不会出现「A 的异步回调把消息写进 B 的对话」。

   数据落盘在 stores/tasks.ts（Vue 侧）；这里只通过 hooks 问两个问题：
   「现在前台是哪条任务」「开一条新任务」。core 不认识 localStorage。
   ================================================================== */
var WORK_HOSTS={};      /* taskId -> 该任务的对话区容器 */
var WORK_HOST_ID='';    /* 当前挂在 #stream-in 里的那条任务的 id */

function workKey(){
  var W=getWorkHooks();
  return (W&&W.currentId)?(W.currentId()||''):'';
}

/** 把某条任务的容器切到前台（换台） */
function workHostShow(key){
  var slot=$('stream-in'); if(!slot||!key) return null;
  var el=WORK_HOSTS[key];
  if(!el){
    el=document.createElement('div');
    el.className='stream-in';              /* 宽度约束沿用原型那条 .stream-in */
    el.setAttribute('data-work-task',key);
    WORK_HOSTS[key]=el;
  }
  if(el.parentNode!==slot){
    /* 清 slot 只是把「上一条任务」的容器摘下来，节点与它的子树都还在 WORK_HOSTS 里，
       被摘下的那条任务继续往自己的 DOM 写也写得到，切回来原样可见 */
    slot.innerHTML='';
    slot.appendChild(el);
  }
  WORK_HOST_ID=key;
  return el;
}

/**
 * 当前任务的对话区容器；任务仓还没起来时退回 #stream-in（单容器老行为，
 * 单独跑原型逻辑 / 脚本不受影响）。core 里所有「往对话区追加」的调用点都走这里。
 */
function workHost(){
  var slot=$('stream-in');
  if(!slot) return null;
  var key=workKey();
  if(!key) return slot;
  var el=WORK_HOSTS[key];
  if(el&&el.parentNode===slot) return el;
  return workHostShow(key);
}

/** 给页面外壳用：当前任务的对话区容器（沙箱快照 / 离开前快照要读写的那个） */
function workHostEl(){ var el=workHost(); return el||$('stream-in'); }

/** 任务被移除时把它的容器也丢掉，别把 DOM 攒在内存里 */
function workSessionDrop(key){
  if(!key) return;
  var el=WORK_HOSTS[key];
  if(el&&el.parentNode) el.parentNode.removeChild(el);
  delete WORK_HOSTS[key];
  if(WORK_HOST_ID===key) WORK_HOST_ID='';
}

/** 开题：登记一条任务（由 startWork 调用） */
function workSessionBegin(fromDetail){
  var W=getWorkHooks(); if(!W) return;
  /* 题目文件名只在开题这一刻收（那时暂存区一定是对的）：收进任务仓持久化，
     刷新后 workSessionResume 用它还原 —— 同一份文件算出同一个 caseId，接得回原会话。
     内置真题（从详情页进）不该有文件题面，一律不收。 */
  var names=[];
  if(!fromDetail){
    var H=getAnalysisHooks(), held=(H&&H.heldList)?H.heldList('home'):[];
    for(var i=0;i<held.length;i++) if(held[i].kind==='problem') names.push(held[i].name);
  }
  analysisPaperNames=names;
  W.begin({
    title: state.workTitle||'',
    protoId: fromDetail?(state.detailId||''):'',
    fromDetail: !!fromDetail,
    paperNames: names
  });
}

/**
 * 把一条任务贴回工作台（页面外壳在「返回工作台」或「刷新后落到 #/work」时调用）。
 *
 * 只恢复上下文与视图，**不重新发起任何 run** —— 上一轮跑没跑完由既有产物决定
 * （analysisBoot 那套 reuse 依旧管用）。容器还在内存里（同一页面内切走切回）就
 * 原样接上，不拿快照覆盖正在跑的内容；不在（刷新过）才用快照铺回来。
 */
function workSessionResume(t){
  if(!t||!t.title) return false;
  state.workTitle=t.title;
  state.detailId=t.protoId||null;
  analysisFromDetail=!!t.fromDetail;   /* 后面每一步取题面都要用它，别留 false */
  /* 还原开题那一刻的题目文件名 —— 暂存区刷新即丢，题面由文件承担的任务靠它接回原会话 */
  analysisPaperNames=Array.isArray(t.paperNames)?t.paperNames:[];
  var wt=$('w-title');
  if(wt) wt.textContent=state.workTitle.length>46?state.workTitle.slice(0,46)+'…':state.workTitle;
  var tag=$('w-tag');
  if(tag) tag.textContent=t.fromDetail?'2026 真题复现':'新题目';
  var host=workHostShow(t.id);
  if(host&&!host.childNodes.length&&t.stream) host.innerHTML=t.stream;

  /* ~接入改动（2026-10-12）：进度条与附件条是**视图级**的骨架，只有 startWork() 画过。
     刷新 / 换任务走的是这一条路，必须补一次 —— 否则顶上那块永远是空的（实测那 67K
     原型 CSS 一直在，丢的就是 #w-steps 与 #attach-strip-work 这两块 DOM）。
     步骤取**本条任务记下的第几步**，不能一律第 0 步 —— 明明停在问题二却高亮
     「1 全局分析」，那是说假话。 */
  if(typeof t.step==='number'&&t.step>=0) curStep=t.step;
  /* ~接入改动（2026-10-12）：右栏闸门区也按任务贴回 —— 闸门现在只活在右栏，
     它不在 t.stream 里，不单独恢复的话刷新/切任务回来右栏会空掉。 */
  railGateLoad(t.id,t.rail);
  workChromeSync();

  /* 分析通道是模块级单例：换到这一条之前先把上一条的全局结果与四问深挖摘掉，
     否则渲染层会把**上一条任务**的分析贴进这一条的对话里。摘完再试接回本条的
     既有产物（reuse 命中就不再烧模型调用；没命中就维持空态，等用户推进流程）。 */
  var H=getAnalysisHooks();
  if(H){
    if(H.resetRuns) H.resetRuns();
    var ref=analysisRef(state.workTitle,!!t.fromDetail);
    if(ref&&H.reuse) H.reuse(ref);
  }

  go('work');
  scrollBottom();
  return true;
}

/** 把原型那一整套全局函数还原到 window。
 *
 *  为什么必须这么做：原型的 HTML 里写的是 inline onclick="go('home')" 这类
 *  内联处理器，浏览器只在 window 上找这些名字。Vue 模板沿用同一套 HTML
 *  （这样行为逐字不变），所以函数得回到 window 上。
 *  这也是原型原本的运行时形态（它的函数本来就是全局的）。 */
export function installPrototypeGlobals() {
  const w = window as unknown as Record<string, unknown>
  const fns: Record<string, unknown> = {
${liveFnNames.map((n) => `    ${JSON.stringify(n)}: ${n},`).join('\n')}
    'openSandboxRun': openSandboxRun,
    'openSandboxArtifacts': openSandboxArtifacts,
    'attachStripRemove': attachStripRemove,
    'attachRerun': attachRerun,
    'pickProblem': pickProblem,
    'pickData': pickData,
  }
  Object.assign(w, fns)
  w.__smyyPrototype = { installed: true, count: ${liveFnNames.length} }
}

export const PROTOTYPE_FN_COUNT = ${liveFnNames.length}

/* ── 意图路由（非原型原码，2026-10-09 新增）────────────────────────────────

   对话区三件事：把用户的话判成「流程指令 / 题目相关问答 / 无关话题」，在合适的
   阶段给出规范指令按钮，以及把随手一问交给智能体。

   判据本身在 utils/analysis/intent.ts（纯函数，core 通过 header 那行 import 用）；
   这里只放「接进原型那套 DOM」的两小段。 */


/**
 * 把用户随口问的一句交给智能体，答案实时画进对话区。
 *
 * 与读题 / 深挖气泡同构（留一个 .lv-stream、定时重画），差别是它**不推进流程**：
 * 没有跳过后回落、没有超时、不登记产物。拿不到答案就如实说原因，不拿写死内容顶。
 */
/**
 * 把用户的一句话交给智能体 —— 工作台对话区**唯一**的发送口（2026-10-12 重写）。
 *
 * 与读题气泡同构：留一个 .lv-stream 装事件流、700ms 重画一次；平台真闸门
 * （ui_form_advanced / ui_gate）由 syncGate 挂进这条气泡。
 *
 * 它**不推进任何前端流程** —— 前端已经没有流程可推了。用户说「继续」，智能体
 * 自己知道该继续什么；用户只是问一句，它就只答一句。
 */
export function intentAsk(v){
  var H=getAnalysisHooks();
  /* 两态拆开（2026-10-14 用户现场：后端活得好好的，卡的其实是题面）——
     「连不上」只留给通路真的断了（没注册钩子）；题面解不出来要说清是题面的事，
     别再拿「检查登录、后端在不在跑」把人支去查网络。 */
  if(!H||!H.ask){
    pushMsg({who:'ai',html:'<h4>现在连不上智能体</h4>'+
      '<p class="src-note">这条通路要连上智能体才能回答：检查一下登录状态、后端是不是在跑。这一句没有发出去。</p>'},mkey());
    return;
  }
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref){
    var t=(state.workTitle||'').slice(0,40);
    pushMsg({who:'ai',html:'<h4>这道题还没有可用的题面</h4>'+
      '<p class="src-note">当前题面「'+esc(t)+'」太短，也拿不到题目文件 —— 这道题的题面原本由题目文件承担，文件在页面刷新后就丢了。回首页把题干写全，或把那份题目文件重新传一次（推荐，能接回原会话）；内置真题请走题库详情页进入。这一句没有发出去。</p>'},mkey());
    return;
  }
  var el=document.createElement('div');
  el.className='msg ai';
  el.innerHTML='<div class="bubble"><div class="txt">'+
    '<div class="an-head"><h4>智能体正在处理</h4>'+
    '<p class="src-note">这一句已经发给它了 —— 它可能直接回答，也可能反过来问你。</p>'+
    /* 排队行：后端忙时这一句会先排队（同一会话只允许一个活跃 run），
       用户必须看得见它在等什么 —— 否则「输入框吞了消息」和「发不出去」体感一样。
       文案由 analysis 模块给（\`askWaitNote\`），这里只负责显示/隐藏。 */
    '<p class="src-note ask-wait" style="display:none"></p></div>'+
    liveBoxHTML()+
    '</div></div>';
  workHost().appendChild(el);
  scrollBottom();

  var liveBox=el.querySelector('.lv-stream');
  if(H.liveMount) H.liveMount(liveBox);
  var done=false, waitEl=el.querySelector('.ask-wait'), timer=setInterval(function(){
    if(H.liveRender) H.liveRender(liveBox);
    syncGate(el,'global',0,H);
    syncSteps();
    /* 排队说明（等后台收尾时才有）—— 等值守卫：内容不变就不碰 DOM */
    if(waitEl&&H.askWaitNote){
      var note=String(H.askWaitNote()||'');
      if(waitEl.textContent!==note){ waitEl.textContent=note; waitEl.style.display=note?'':'none'; }
    }
    /* 这一轮里智能体可能刚写/重写了产物（内部按签名去重，没变就不打接口） */
    refreshArtCard();
    scrollBottomIfStick();
  },700);
  function stop(){ done=true; if(timer){ clearInterval(timer); timer=null; } }
  function say(html){ stop(); pushMsg({who:'ai',html:html},mkey()); }

  H.ask(ref,v).then(function(res){
    if(done) return;
    stop();
    if(H.liveRender) H.liveRender(liveBox);
    /* 有正文就已经画在气泡里了；只有真的一个字都没有才把整条气泡收掉 */
    if(res&&res.ok) return;
    var note=(res&&res.note)||'原因未明';
    if(res&&res.text){ say('<p class="src-note">上面那半段没能收完 —— '+esc(note)+'</p>'); return; }
    el.remove();
    say('<h4>这句话没能发出去</h4><p class="src-note">'+esc(note)+'</p>');
  }).catch(function(e){
    if(done) return;
    stop();
    el.remove();
    say('<h4>这句话没能发出去</h4><p class="src-note">'+esc((e&&e.message)||'调用失败')+'</p>');
  }).then(function(){
    /* ~接入改动（2026-10-14）：这一轮结束后把产物卡重拉一次。
       智能体在这一轮里可能刚写了新版本（逐问分析、重做、继续往下做），
       卡片不重拉就永远停在开题那一刻的清单，用户会误以为「重做没生效」。 */
    refreshArtCard();
  });
}

/**
 * 把对话区那张产物卡重拉一次。
 *
 * 为什么需要：产物卡只在「开题那次 run 跑完」挂载并刷过一次（\`artifactCardBoot\`），
 * 之后的推进全靠对话驱动 —— 没有这一步，智能体重做/续做写下的新版本在清单里看不见。
 * 卡里的树本来就是「本会话 + 全部子会话」的合并，重拉一次就够，不做轮询
 * （2026-10-09 用户拍板：跑完自动刷一次 + 手动刷新）。
 *
 * 只认当前任务那张卡：没有卡（还没走到分析那一步）就什么都不做。
 *
 * 2026-10-14：加了**产物签名去重** —— 跟随会话期间 700ms 就会被叫一次，
 * 不加去重就等于每 700ms 真去打一次产物接口。签名由 analysis 模块维护
 * （产物树内容一变就 +1），没变就直接返回。 */
var LAST_ART_STAMP=-1;
function refreshArtCard(){
  var H=getAnalysisHooks();
  if(!H||!H.artifactRefresh) return;
  var stamp=H.artifactStamp?Number(H.artifactStamp())||0:0;
  if(stamp===LAST_ART_STAMP) return;
  var host=workHostEl();
  if(!host) return;
  var box=host.querySelector<HTMLElement>('.art-card');
  if(!box) return;
  var ref=analysisRef(state.workTitle,analysisFromDetail);
  if(!ref) return;
  LAST_ART_STAMP=stamp;
  H.artifactRefresh(box,ref.caseId);
}

/* ── 工作台右栏：闸门 + 产物（2026-10-12 新增，用户定版）────────────────────
   版式：**左对话 / 右（闸门 + 产物）**。两条人工决策链路都改道到这里 ——
   原型流程闸门（pushFlowGate → railGateOpen）与平台 HITL 闸门（syncGate →
   railGateHost）。对话流里从此不再出现闸门卡片；用户选择/跳过后仍会留一条
   用户消息，所以对话的叙事没有断。

   四个必须守住的点：
     · 右栏不在（单跑原型 / 脚本 / 不在工作台视图）时一律回退老路径 ——
       不能因为新增一块 UI 就让原型逻辑跑不动；
     · 闸门区**按任务**：DOM 是真相，只在换任务时用任务仓里的快照铺回来
       （RAIL_GATE_TASK 记住当前贴的是哪条，避免同一条任务被旧快照覆盖）；
     · 产物清单靠**对账**重画（签名变了才动 innerHTML）—— 每 1.5 秒重写
       会把用户的点击打断；
     · 一律不写内联 onclick：本段是模板字符串，引号会在生成期被解义。 */
var RAIL_GATE_TASK='';    /* 右栏闸门区当前贴的是哪条任务 */
var RAIL_ART_SIG='';      /* 产物清单上次渲染的签名 */
var RAIL_PIN=0;           /* 用户手动切过 tab 的保护期（时间戳） */

/** 当前任务的右栏闸门容器；不在工作台视图 / 右栏不在时返回 null（调用方回退老路径） */
function railGateHost(){
  if(typeof state==='undefined'||!state||state.view!=='work') return null;
  return $('rail-gate-list')||null;
}

/** 闸门待办数：没定格的卡片才计数（同一张画两次也只算一张） */
function railGateBadge(){
  var list=$('rail-gate-list'), n=0;
  if(list){
    var all=list.querySelectorAll('.rail-gate-card');
    for(var i=0;i<all.length;i++){ if(!all[i].classList.contains('decided')) n++; }
  }
  var b=$('rail-gate-badge');
  if(b){ b.textContent=n?String(n):''; b.style.display=n?'':'none'; }
  var t=$('rail-tab-gate');
  if(t) t.classList.toggle('hot',n>0);
}

/** 把一个闸门节点画进右栏；右栏不在时回退原型那条「塞进对话区」的老路 */
function railGateOpen(node,gate){
  var list=railGateHost();
  if(!list) return pushMsg(node,gate.key);
  var el=document.createElement('div');
  el.className='rail-gate-card';
  /* 复用对话气泡那套 .bubble .txt 的观感 —— 内容本来就是同一份 HTML */
  el.innerHTML='<div class="bubble"><div class="txt">'+(node.html||'')+'</div></div>';
  list.appendChild(el);
  list.scrollTop=list.scrollHeight;
  railGateBadge();
  railExpand();
  railTabSoft('gate');
  return node.key;
}

/** 闸门定了（选了 / 跳了 / 交了）：把它那张卡定格成「已确认」 */
function railGateDecide(key,card){
  var el=card||(key?$('gate-'+key):null);
  if(!el||!el.closest) return;
  var wrap=el.closest('.rail-gate-card');
  if(wrap) wrap.classList.add('decided');
  railGateBadge();
}

/** 开新题：右栏的旧闸门不能留着（那是上一条任务的） */
function railGateReset(){
  RAIL_GATE_TASK='';
  var list=$('rail-gate-list');
  if(list) list.innerHTML='';
  railGateBadge();
}

/** 切任务时把这条任务的闸门区贴回来；同一条任务不动 DOM（它才是真相） */
function railGateLoad(taskId,html){
  var list=$('rail-gate-list');
  if(!list) return;
  var id=taskId||'';
  if(RAIL_GATE_TASK===id) return;
  RAIL_GATE_TASK=id;
  list.innerHTML=html||'';
  railGateBadge();
}

/* ── 右栏 tab ──────────────────────────────────────────────────────────── */
function railTabSet(name){
  var w=$('wrail'); if(!w) return;
  var tabs=w.querySelectorAll('.rail-tab');
  for(var i=0;i<tabs.length;i++){
    tabs[i].classList.toggle('on',tabs[i].getAttribute('data-rail')===name);
  }
  var g=$('rail-pane-gate'), a=$('rail-pane-art');
  if(g) g.classList.toggle('on',name==='gate');
  if(a) a.classList.toggle('on',name==='art');
}
/** 用户手动切的 tab：给 8 秒保护期，别被自动切换立刻抢走 */
function railTab(name){ RAIL_PIN=Date.now()+8000; railTabSet(name); }
/** 系统自动切（来了闸门 / 打开了产物）：保护期内不动用户的视角 */
function railTabSoft(name){ if(Date.now()<RAIL_PIN) return; railTabSet(name); }
/** 闸门来了就把右栏展开 —— 收着的时候卡片等于没画，用户根本看不到 */
function railExpand(){
  var w=$('wrail');
  if(!w||!w.classList.contains('min')) return;
  w.classList.remove('min');
  var b=$('rail-min');
  if(b) b.textContent='›';
}
function railToggle(){
  var w=$('wrail'); if(!w) return;
  w.classList.toggle('min');
  var b=$('rail-min');
  if(b) b.textContent=w.classList.contains('min')?'‹':'›';
}
function railBind(){
  var w=$('wrail');
  if(!w||w.getAttribute('data-bound')==='1') return;
  w.setAttribute('data-bound','1');
  var tabs=w.querySelectorAll('.rail-tab');
  for(var i=0;i<tabs.length;i++){
    (function(b){ b.addEventListener('click',function(){ railTab(b.getAttribute('data-rail')); }); })(tabs[i]);
  }
  var m=w.querySelector('.rail-min');
  if(m) m.addEventListener('click',railToggle);
}

/* ── 右栏产物浏览 ──────────────────────────────────────────────────────── */
/** 产物预览盖住清单 —— 原型那三个抽屉函数在 core 里被加了钩子调这里 */
function railArtView(on){
  var p=$('rail-pane-art'); if(!p) return;
  p.classList.toggle('preview',!!on);
  if(on) railTabSoft('art');
}
function railArtBadge(n){
  var b=$('rail-art-badge');
  if(b){ b.textContent=n?String(n):''; b.style.display=n?'':'none'; }
}
function bindRailArtRows(){
  var box=$('rail-art-list'); if(!box) return;
  var rows=box.querySelectorAll('.ra-row');
  for(var i=0;i<rows.length;i++){
    (function(r){
      r.addEventListener('click',function(){
        var card=document.getElementById(r.getAttribute('data-card')||'');
        /* 点右栏这一行 = 点对话区那张产物卡（onclick 里就是 openArt），不另写一套打开逻辑 */
        if(card) card.click();
        else toast('这张产物已经不在当前对话里了',IC_WARN);
      });
    })(rows[i]);
  }
}
/**
 * 产物清单：把当前任务对话区里的 .arts .acard 抄一份到右栏。
 * 只扫 .arts 里的卡 —— 左侧那些步骤卡也是 .acard，混进来就全错了。
 */
function railArtSync(){
  railBind();
  var box=$('rail-art-list'); if(!box) return;
  var host=workHostEl();
  var cards=host?host.querySelectorAll('.arts .acard'):[];
  var sig='', items=[];
  for(var i=0;i<cards.length;i++){
    var c=cards[i];
    var nameEl=c.querySelector('.at b'), subEl=c.querySelector('.at span'), icEl=c.querySelector('.ai');
    var it={
      id:c.id||'',
      name:nameEl?(nameEl.textContent||''):'',
      sub:subEl?(subEl.textContent||''):'',
      icon:icEl?(icEl.textContent||''):'',
      bg:icEl?(icEl.style.background||''):'',
      on:c.classList.contains('open')
    };
    sig+=it.id+'|'+it.name+'|'+it.sub+'|'+(it.on?'1':'0')+';';
    items.push(it);
  }
  if(sig!==RAIL_ART_SIG){
    RAIL_ART_SIG=sig;
    var h='';
    if(!items.length){
      h='<p class="ra-empty">还没有产物。分析 / 动画 / 代码跑完后，产物会列在这里，点一下就打开。</p>';
    }else{
      for(var j=0;j<items.length;j++){
        var x=items[j];
        h+='<button type="button" class="ra-row'+(x.on?' on':'')+'" data-card="'+esc(x.id)+'">'
          +'<i class="ra-ic" style="background:'+esc(x.bg||'#12a594')+'">'+esc(x.icon||'')+'</i>'
          +'<span class="ra-t"><b>'+esc(x.name||'产物')+'</b><s>'+esc(x.sub||'')+'</s></span>'
          +'<span class="ra-go">查看</span></button>';
      }
    }
    box.innerHTML=h;
    bindRailArtRows();
  }else{
    /* 只有「当前打开的是哪一张」会变 —— 轻量刷高亮，不重画整块 */
    var rows=box.querySelectorAll('.ra-row');
    for(var k=0;k<rows.length&&k<items.length;k++) rows[k].classList.toggle('on',items[k].on);
  }
  railArtBadge(items.length);
}

/** 给 Vue 外壳用的：右栏对账一次（绑定 + 产物清单） */
function railSync(){ railBind(); railArtSync(); }

/**
 * 补齐工作台的「视图级骨架」：顶部六步进度条 + 输入框上方那条附件条（2026-10-12）。
 *
 * 为什么会缺：#view-work 的模板里，进度条 #w-steps 是个**空 div**，附件条
 * #attach-strip-work **根本不在模板里** —— 两块都只有 startWork() 现场画得出来
 * （renderSteps / attachBeginProblem）。而刷新、从别的视图点回工作台、从沙箱返回后
 * 组件重建，走的都是 workSessionResume()，它只贴对话快照就 go('work')，于是这两块
 * 永远缺席：顶上一片空、布局豁一块，用户会当成「样式丢了」。
 * （2026-10-12 实测：那份 67K 原型 CSS 刷新前后逐字节一致 —— 丢的自始至终是这两块
 *  DOM，不是样式表。）
 *
 * 所以把「进工作台要补的骨架」收成这一个幂等函数，由 workSessionResume 每次调用：
 *   · 进度条按当前 curStep 重画（调用方已把它同步成这条任务记下的第几步）——
 *     换任务时它跟着换，不再是上一条任务的残留；
 *   · 附件条按当前暂存清单重画：没有待上传的文件时它就是空节点，与 startWork 同口径
 *     （刷新后暂存区本来就是空的 —— 那些文件早已在会话里，不是被删了）。
 */
function workChromeSync(){
  renderSteps(Math.max(0,Math.min(STEPS.length-1,curStep)));
  attachStripRender('work');
  railSync();
}

/* bridge 里手写的函数默认「既不挂 window 也不 export」—— 它们只服务 core 内部。
   下面这几个是给 Vue 外壳用的：外壳要贴回一条任务、要拿当前对话区容器去存取快照，
   不该再手写一套 DOM 恢复逻辑（那会和这里的容器分桶规则打架）。 */
export { workSessionResume, workHostEl, workSessionDrop, workChromeSync, railSync }
`
/* ── A5：语法自检 ────────────────────────────────────────────────────────
   core.ts 是生成物，**一个括号不配平就是整页白屏**。前面那些断言看不出语法错
   （A2 只比字节），vue-tsc 也只会丢一句没头没尾的 `TS1005: ')' expected`。
   2026-10-10 就踩过一次：给首条用户消息包括号时漏了闭合，构建照过、断言全绿，
   浏览器里 PrototypeShell.vue「Failed to fetch dynamically imported module」。
   所以这里落盘前用 TypeScript 的 transpile 把整个文件过一遍 —— 廉价、早、定位准。 */
{
  const { createRequire } = await import('node:module')
  const ts = createRequire(path.join(ROOT, 'package.json'))('typescript')
  const full = header + core + bridge
  const diags = (ts.transpileModule(full, {
    compilerOptions: { target: ts.ScriptTarget.ES2020, module: ts.ModuleKind.ESNext },
    reportDiagnostics: true,
  }).diagnostics || []).filter((d) => d.category === ts.DiagnosticCategory.Error)
  if (diags.length) {
    const d = diags[0]
    const line = d.file && typeof d.start === 'number'
      ? d.file.getLineAndCharacterOfPosition(d.start).line + 1 : '?'
    fail(`core.ts 语法错 ${diags.length} 处，首个在第 ${line} 行：TS${d.code} ` +
      ts.flattenDiagnosticMessageText(d.messageText, ' '))
  }
  console.log(`✅ A5：core.ts 通过语法自检（${full.length} 字符，0 处语法错）`)
}

write('src/legacy/prototype/core.ts', header + core + bridge)

// ── 4. 外置演示模块 → 可独立打开的页面 ────────────────────────────────────
for (let i = 0; i < index.anim.length; i += 1) {
  const m = index.anim[i]
  let h = fs.readFileSync(path.join(SPLIT, 'anim', m.file), 'utf8')

  // :host 是 Shadow DOM 的宿主选择器；独立页面里没有影子根，换成 html 即可
  const hostCount = (h.match(/:host/g) || []).length
  h = h.replace(/:host/g, 'html')

  // 主脚本原本是 type="text/plain"，由原型的 runAnimScript 取出来 eval；
  // 独立页面里必须让它真的执行
  const plain = '<script type="text/plain" data-anim-main="1">'
  if (!h.includes(plain)) fail(`模块 ${i} 找不到 text/plain 主脚本`)
  h = h.replace(plain, '<script data-anim-main="1">')

  const title = ANIM_TITLES[i] || `演示 ${i}`

  // A4 断言
  if (!h.includes('id="DATA"')) fail(`A4 模块 ${i} 缺少 id="DATA"`)
  if (h.includes('type="text/plain"')) fail(`A4 模块 ${i} 仍有未执行的 text/plain 脚本`)
  if (h.includes(':host')) fail(`A4 模块 ${i} 仍残留 :host`)

  const doc = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>${title}</title>
<style>html,body{margin:0;height:100%;overflow:hidden}</style>
</head>
<body>
${h}
</body>
</html>
`
  write(`public/cases/p3/anims/mod-${i}.html`, doc)
  console.log(`     （模块 ${i}：替换了 ${hostCount} 处 :host，标题「${title}」）`)
}

// ── 5. 站点外壳 → PrototypeShell.vue ──────────────────────────────────────
// 模板**逐字取用原型外壳**（不手抄，避免转写错字）。全部 diff 只有 nav-right
// 那一块：两个「登录」按钮原本是 onclick=toast('登录为演示功能') 的占位。
const shellRaw = fs.readFileSync(path.join(SPLIT, 'region-shell.html'), 'utf8')
const hs = shellRaw.indexOf('<header class="nav">')
if (hs < 0) fail('外壳里找不到 <header class="nav">')
let shellBody = shellRaw.slice(hs).trimEnd()
const mineCopy = '<h1>我的成果</h1>'
const mineDescription = '你自己生成的解答都在这里，与历史题库分开管理。每个成果可单独控制是否公开给其他同学查看。'
if (!shellBody.includes(mineCopy) || !shellBody.includes(mineDescription)) fail('示例成果文案已变化')
shellBody = shellBody.replace(mineCopy, '<h1>示例成果</h1>')
  .replace(mineDescription, '这里展示预置演示案例，不是你的真实成果。分享开关仅演示交互，仅本次访问有效，不会公开文件。')

const NAV_RIGHT_RE = /<div class="nav-right">[\s\S]*?<\/div>/
const navHits = shellBody.match(new RegExp(NAV_RIGHT_RE.source, 'g')) || []
if (navHits.length !== 1) fail(`外壳里 .nav-right 有 ${navHits.length} 处（预期 1）`)
shellBody = shellBody.replace(
  NAV_RIGHT_RE,
  `<div class="nav-right">
      <template v-if="!userStore.isLogged">
        <button class="btn-ghost" @click="openLogin">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
          学生入口
        </button>
        <button class="btn-login" @click="openLogin">登录</button>
      </template>
      <template v-else>
        <span class="nav-user" :title="userStore.displayName">{{ userStore.displayName }}</span>
        <button class="btn-ghost" @click="onLogout">退出</button>
      </template>
    </div>`,
)
if (/toast\('登录为演示功能'\)/.test(shellBody)) fail('外壳里仍残留「登录为演示功能」占位')

// ── 5b. 沙箱入口注入（本轮新增，双轨并存） ──────────────────────────────────
// 原型那套演示工作台原样保留，这里只**新增**两个通往真沙箱的入口，
// 不碰 openWorkFromDetail / sendFromHome / demoProblem 这三个既有动作。
// 换行符跟外壳走：原型源是 CRLF 还是 LF 不确定，写死 \n 会造出混合行尾。
const EOL = shellBody.includes('\r\n') ? '\r\n' : '\n'

const NAV_WORK_BTN = `<button id="nv-work" style="display:none" onclick="go('work')">工作台</button>`
const navWorkHits = shellBody.match(new RegExp(NAV_WORK_BTN.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g')) || []
if (navWorkHits.length !== 1) fail(`nav-links 里「工作台」按钮有 ${navWorkHits.length} 处（预期 1）`)
/* 2026-10-08：顶栏「工作台」按钮改走 Vue 的 openWorkTask()。
   原来它是 onclick="go('work')"，而 go() 第一行是 if(!state.workTitle){toast();return} ——
   刷新后工作台状态是空的，点它只会弹个 toast、人还停在首页。首页那条常驻入口（今天已删）
   当初就是为绕开这个而加的。现在让按钮也走「从任务仓贴回来」那条路，首页不留任何入口
   也能回到正在进行的任务。 */
shellBody = shellBody.replace(
  NAV_WORK_BTN,
  `<button id="nv-work" style="display:none" @click="openWorkTask()">工作台</button>`,
)
if (shellBody.includes(`onclick="go('work')"`)) fail('顶栏「工作台」按钮没换成 Vue 绑定')
// 2026-09-30：顶栏「沙箱」入口已撤 —— 它的去处（/sandbox 环境列表）随
// 「删掉沙箱预留题目数据」一并移除，工作台待改由站点内全屏层 .sb-layer 承载。
// 原型的「工作台」按钮原样保留。

const DETAIL_DL_BTN = `<button class="w" onclick="toast('指导书下载为演示功能')">`
// 2026-09-30：详情页「进入沙箱环境」入口已撤（同上）—— 它的去处
//（/sandbox/work/:id 工作台）已从 router 移除。

// ── 5c. 上传按钮拆分（本轮新增） ────────────────────────────────────────────
// 原来首页与工作台各只有一个「上传」按钮，题目文件与数据附件混在一个口子里，
// 语义上分不开：站点既不知道哪个文件是题面，也就无法回答「没输文字、只传了
// 题目文件，能不能开跑」。拆成「上传题目」「上传附件」两个按钮，各自带
// data-pick 标记（探针用），文件按类型分别落到 flow 的题目槽与附件槽。
const PICK_BTN_ICON_DOC =
  `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 3v4.5a1.5 1.5 0 0 0 1.5 1.5H20"/><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M9 13h6M9 17h4"/></svg>`
const PICK_BTN_ICON_UP =
  `<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/></svg>`

const pickPair = (scope, indent) => {
  const btn = indent.replace(/ {2}$/, '') // 按钮本身比它的内容少缩进两格
  /* 第一个按钮前**不要再补缩进** —— 正则是从 <button 起匹配的，原行首那几格
     空格留在原地，再补一次就成了双倍缩进 */
  return (
    `<button class="btn-ghost" data-pick="problem" onclick="pickProblem('${scope}')">` + EOL +
    indent + PICK_BTN_ICON_DOC + EOL +
    indent + `上传题目` + EOL +
    btn + `</button>` + EOL +
    btn + `<button class="btn-ghost" data-pick="data" onclick="pickData('${scope}')">` + EOL +
    indent + PICK_BTN_ICON_UP + EOL +
    indent + `上传附件` + EOL +
    btn + `</button>`
  )
}

for (const scope of ['home', 'work']) {
  const re = new RegExp(`<button class="btn-ghost" onclick="pickFile\\('${scope}'\\)">[\\s\\S]*?</button>`)
  const hits = shellBody.match(new RegExp(re.source, 'g')) || []
  if (hits.length !== 1) fail(`「${scope}」输入框的上传按钮有 ${hits.length} 处（预期 1）`)
  const indent = scope === 'home' ? ' '.repeat(12) : ' '.repeat(16)
  shellBody = shellBody.replace(re, pickPair(scope, indent))
}

const hintHits = shellBody.match(/<span class="hint">[^<]*<\/span>/g) || []
if (hintHits.length !== 2) fail(`composer 里的 hint 有 ${hintHits.length} 处（预期 2）`)
shellBody = shellBody.replace(
  `<span class="hint">支持 PDF / Word / 图片 / Excel 数据附件</span>`,
  `<span class="hint">题目支持 PDF / Word / 图片；附件支持 Excel / CSV 等数据文件</span>`,
)
shellBody = shellBody.replace(
  `<span class="hint">可继续补充数据或题目要求</span>`,
  `<span class="hint">可继续补充题目材料或数据文件</span>`,
)
if (/onclick="pickFile\('/.test(shellBody)) fail('拆完按钮后仍残留 pickFile() 内联调用')
console.log('✅ 上传按钮已拆分（首页 / 工作台各为「上传题目」+「上传附件」）')

// ── 5d. 工作台左侧任务列表（2026-10-08 改版：从首页搬进来） ──────────────────
// 原本这块挂在首页（一条常驻条 + 展开列表）。改版的理由：首页是入口页，摆运维面板
// 不对；而「哪几条任务在跑、各自到哪一步」本来就是工作台视图最该先说清的事。
// 所以整块搬进 #view-work 的 .wbody，做成左侧竖列。
//
// 首页从此不留工作台入口 —— 回工作台走顶栏那个「工作台」按钮（已改成 Vue 绑定，
// 见 5b 上面的注释：它现在也走「从任务仓贴回来」那条路，刷新后照样点得动）。
//
// 数据来自 Vue 侧的任务仓（stores/tasks.ts）；这里只负责渲染与点击，
// 状态、落盘、以及和后端的暂停/删除同步都在那边 —— 模板里不出现 localStorage 字眼。
const WORK_BODY_ANCHOR = `  <div class="wbody">`
const workBodyHits = shellBody.split(WORK_BODY_ANCHOR).length - 1
if (workBodyHits !== 1) fail(`工作台 .wbody 有 ${workBodyHits} 处（预期 1）`)

const WORKTASK_SIDE = [
  `    <!-- 任务列表：切任务 / 看阶段 / 暂停 / 删除都在这里（任务仓在 stores/tasks.ts） -->`,
  `    <aside class="wside" :class="{ pin: workSidePinned }">`,
  `      <div class="ws-head" title="展开 / 收起任务列表" @click="workSidePinned = !workSidePinned">`,
  `        <i class="ws-fold"></i><span>任务</span><b>{{ workTasks.length }}</b>`,
  `      </div>`,
  `      <div class="ws-list">`,
  `        <div v-if="!workTasks.length" class="ws-empty">还没有任务。回首页输入一道题，它就会出现在这里。</div>`,
  `        <div v-for="t in workTasks" :key="t.id" class="ws-row" :class="{ on: t.id === workCurrentId }" @click="openWorkTask(t.id)">`,
  `          <i class="ws-dot" :class="{ live: taskLive(t), paused: t.paused }"></i>`,
  `          <div class="ws-main">`,
  `            <div class="ws-name" :title="t.title">{{ shortTitle(t.title) }}</div>`,
  `            <div class="ws-meta">{{ taskMeta(t) }}</div>`,
  `          </div>`,
  `          <div class="ws-acts">`,
  `            <button class="ws-act" :disabled="busyTask === t.id" @click.stop="togglePauseTask(t.id)">{{ t.paused ? '继续' : '暂停' }}</button>`,
  `            <button class="ws-act" :class="{ hot: dropAsk === t.id }" :disabled="busyTask === t.id" @click.stop="askDropTask(t.id)">{{ dropAsk === t.id ? '确认删除' : '删除' }}</button>`,
  `          </div>`,
  `        </div>`,
  `      </div>`,
  `    </aside>`,
].join(EOL)

shellBody = shellBody.replace(WORK_BODY_ANCHOR, WORK_BODY_ANCHOR + EOL + WORKTASK_SIDE)
console.log('✅ 工作台已注入左侧任务列表（切换 / 暂停 / 删除）；首页不再挂任务查看')

// ── 5d-2. 工作台右栏：闸门 + 产物（2026-10-12 改版，用户定版）────────────────
// 版式：左（任务窄条）| 中（对话）| 右（闸门 / 产物）。
//
// 有一处要紧的取舍：**原型那个 aside#drawer 原样搬进右栏的「产物」pane**，不重写。
// 理由是 openDrawer / openArt / openArtifactView / closeDrawer 四条链路全部按 id 找
// #drawer / #d-title2 / #drawer-body（真实代码的复制下载、动画抽屉都挂在里面），
// 重写这套等于推翻产物预览。搬进去只是换了个位置 —— 样式在 workRailCss 里把
// width:0 / .on{width:520px} / .wide{width:720px} 那几条覆盖掉即可。
const WORK_RAIL = (drawer) => [
  `    <!-- 右栏：闸门 + 产物（2026-10-12 用户定版：左对话 / 右闸门 + 产物） -->`,
  `    <aside class="wrail" id="wrail">`,
  `      <div class="rail-tabs">`,
  `        <button type="button" class="rail-tab on" data-rail="gate">闸门<b class="rb" id="rail-gate-badge" style="display:none"></b></button>`,
  `        <button type="button" class="rail-tab" data-rail="art">产物<b class="rb" id="rail-art-badge" style="display:none"></b></button>`,
  `        <div class="grow"></div>`,
  `        <button type="button" class="rail-min" id="rail-min" title="收起 / 展开右栏">›</button>`,
  `      </div>`,
  `      <div class="rail-pane on" id="rail-pane-gate">`,
  `        <div class="rail-gate-list" id="rail-gate-list"></div>`,
  `      </div>`,
  `      <div class="rail-pane" id="rail-pane-art">`,
  `        <div class="rail-art-list" id="rail-art-list"></div>`,
  drawer,
  `      </div>`,
  `    </aside>`,
].join(EOL)

const DRAWER_BLOCK_RE = /    <aside class="drawer" id="drawer">[\s\S]*?<\/aside>/
const drawerHits = (shellBody.match(new RegExp(DRAWER_BLOCK_RE.source, 'g')) || []).length
if (drawerHits !== 1) fail(`工作台产物抽屉有 ${drawerHits} 处（预期 1）`)
shellBody = shellBody.replace(DRAWER_BLOCK_RE, WORK_RAIL(shellBody.match(DRAWER_BLOCK_RE)[0]))
console.log('✅ 工作台已改为右栏（闸门 / 产物两个 tab）；产物预览复用原型抽屉')

// ── 5e. 顶栏：状态与操作靠右（2026-10-08 用户定） ───────────────────────────
// 原型把撑开元素 `<div style="flex:1">` 放在 flow-tools **之后**，于是
// 「未创建环境 / 等待你的指令 / 我有问题 / 回到上一步」这一排夹在题名与进度条中间，
// 右侧留一大片空白 —— 看着像没对齐。用户要的是**这一排贴最右**。
// 做法就是把撑开元素从尾部挪到进度条之后：左半区只剩「返回 / 标签 / 题名 / 进度条」，
// 状态与两个操作被顶到右边，收起产物仍在最末。
const WT_SPACER = `    <div style="flex:1"></div>`
const WSTEPS_LINE = `    <div class="steps" id="w-steps"></div>`
if (shellBody.split(WT_SPACER).length - 1 !== 1) fail('顶栏撑开元素不是恰好 1 处')
if (shellBody.split(WSTEPS_LINE).length - 1 !== 1) fail('顶栏进度条不是恰好 1 处')
shellBody = shellBody.replace(`${WT_SPACER}${EOL}`, '')

// 写死对话删掉之后，顶栏那两颗按钮（「我有问题」/「回到上一步」）的承载体也没了 ——
// openQuestionPause / backToPreviousDecision 两个函数已随写死对话一起删除，
// 留着就是两颗点了报 is not a function 的死按钮。输入框上方那条快捷语容器同理。
const FLOW_TOOLS_RE = /[ \t]*<div class="flow-tools">[\s\S]*?<\/div>\r?\n/
if ((shellBody.match(FLOW_TOOLS_RE) || []).length !== 1) fail('顶栏 flow-tools 不是恰好 1 处')
shellBody = shellBody.replace(FLOW_TOOLS_RE, '')
const WORK_CHIPS_RE = /[ \t]*<div class="chips"[^>]*id="work-chips"><\/div>\r?\n/
if ((shellBody.match(WORK_CHIPS_RE) || []).length !== 1) fail('工作台 work-chips 不是恰好 1 处')
shellBody = shellBody.replace(WORK_CHIPS_RE, '')
console.log('✅ 写死对话的两颗顶栏按钮与快捷语容器已从外壳移除')

shellBody = shellBody.replace(WSTEPS_LINE, `${WSTEPS_LINE}${EOL}${WT_SPACER}`)
if (shellBody.indexOf(WT_SPACER) < shellBody.indexOf(WSTEPS_LINE)) fail('撑开元素没挪到进度条之后')
console.log('✅ 工作台顶栏已改为「状态与操作靠右」')

const shellVue = `<!-- 由 scripts/port/build-port.mjs 从原型外壳生成，勿手改 -->
<!--
  站点外壳：原型四个视图（首页 / 题目详情 / 工作台 / 我的成果）+ 顶栏 + 全屏演示层。
  模板逐字取自原型，id 与 class 全部保留 —— 因为原型那 162 个函数就是靠
  这些 id 找 DOM 的（\`$('view-home')\` 之类）。改模板上的 id/class 会静默打断原型逻辑。
-->
<template>
<div class="smyy-proto">
${shellBody}
</div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { bootPrototype, shutdownPrototype } from '@/legacy/prototype/boot'
import {
  setPrototypeAnalysisHooks,
  setPrototypeAnimationHooks,
  setPrototypeCodegenHooks,
  setPrototypeNavHooks,
  setPrototypeWorkHooks,
} from '@/legacy/prototype/hooks'
import * as core from '@/legacy/prototype/core'
import { mountPrototypeCss, unmountPrototypeCss } from '@/styles/prototypeCss'
/* 分析通道自带的三张样式表（对话流 / 产物卡 / 报告卡）原本只在各自的渲染函数里
   懒注入。对话区是**可恢复**的：刷新后它是从任务快照整块贴回来的，那一刻没有任何
   渲染函数被调用 —— 那些 lv-* / art-* / rep-* 节点就全掉回浏览器默认样式
   （「对话内容没样式」，2026-10-12 用户报）。所以挂载时统一补一次，幂等。 */
import { ensureAnalysisStyles } from '@/utils/analysis/styles'
import { useUserStore } from '@/stores/user'
import { createAnalysisHooks } from '@/legacy/bridge/analysisHooks'
import {
  currentCase,
  resolveCaseIdFor,
  setThreadLandedSink,
  suspend,
  threadIdForTask,
  threadIdsForCase,
  threadIdsForTask,
} from '@/stores/analysis'
import { builtinProblem } from '@/data/problemStatement'
import { createAnimationHooks } from '@/legacy/bridge/animationHooks'
import { createCodegenHooks } from '@/legacy/bridge/codegenHooks'
import {
  beginTask,
  currentId as workCurrentId,
  currentTask,
  isLive,
  relTime,
  removeTask,
  saveRail,
  saveStage,
  saveStep,
  saveStream,
  selectTask,
  setPaused,
  setTaskCase,
  setTaskThread,
  shortTitle,
  flushRemote,
  hydrateTasks,
  tasks as workTasks,
  type WorkTask,
} from '@/stores/tasks'
import { clearSession, interruptSession } from '@/api/taskSync'
import {
  clearRequest,
  putRequest,
  saveStreamSnapshot,
  takeReceipt,
  takeStreamSnapshot,
} from '@/data/runHandoff'

/**
 * 站点题目 → 沙箱会话键。
 *
 * 沙箱不是另一套题库：它必须复用分析通道当前 CaseRef 的 caseId，才能与
 * S1/S2/S3 共享同一个 thread/session 和产物目录。详情页内置题目由
 * currentCase() 提供（例如 p3 → 2026-C），自定义题目同样沿用其 user-* caseId。
 */
function sandboxIdOf(protoId?: string | null): string {
  const active = currentCase()
  // 内置题目切换后，currentCase 可能短暂保留上一题；必须先校验 protoId，
  // 避免把新题目误绑到旧题目的 thread/session 和产物目录。
  if (active && active.protoId === protoId && active.caseId) return active.caseId
  if (protoId) return builtinProblem(protoId)?.caseId || protoId
  if (active?.caseId) return active.caseId
  return ''
}

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

/** 视图 → URL 路径（detail 要看当前题目 id） */
function pathOf(view: string): string {
  if (view === 'detail') return \`/p/\${core.state.detailId ?? ''}\`
  return view === 'home' ? '/' : \`/\${view}\`
}

// 路由 ←→ 视图 双向同步。syncing 用来掐断「同步过去、又被监听到再同步回来」的循环。
let syncing = false

setPrototypeNavHooks({
  isLogged: () => userStore.isLogged,
  requireLogin: (view) => {
    void router.push({ name: 'login', query: { redirect: pathOf(view) } })
  },
  syncRoute: (view) => {
    const target = pathOf(view)
    if (route.path === target) return
    syncing = true
    void router.replace(target).finally(() => {
      syncing = false
    })
  },

  /** 视图切完顺带对账顶栏「工作台」按钮 —— 有任务时它在任何视图都该点得到 */
  syncNav: refreshNav,

  /* ── 沙箱衔接（本轮新增）──────────────────────────────────────────────
     对话里代码生成好之后给一条通往真沙箱的路，跑完的产物再回到这条对话。
     这里只负责「跳转 + 记交接单」，消息长什么样由 core.ts 的桥接层决定；
     产物回执的读取放在 onMounted 里（见 deliverSandboxReceipt）。 */

  /** 当前这道题在真沙箱里有没有环境 —— 决定「进入沙箱运行」按钮出不出现 */
  sandboxReady: () => sandboxIdOf(core.state.detailId) !== '',

  /** 「进入沙箱运行」：写下交接单再过去，沙箱那边据此预填运行指令 */
  gotoSandboxRun: (qn) => {
    const sid = sandboxIdOf(core.state.detailId)
    if (!sid) {
      toastTip('这道题的沙箱环境还没有开放，先用上面的演示流程走一遍', '')
      return
    }
    const q = core.QUIZ[qn] ?? {}
    putRequest({
      protoId: String(core.state.detailId ?? ''),
      sandboxId: sid,
      qn,
      qLabel: q.cn ? \`问题\${q.cn}\` : \`第 \${qn + 1} 问\`,
      codeName: q.codeName || 'main.py',
      instruction: \`运行 \${q.codeName || 'main.py'}，把结果写进产物目录\`,
      at: Date.now(),
    })
    leaveTalk()
    void router.push('/sandbox/work/' + sid)
  },

  /** 对话里那张回执卡片的「在沙箱中查看产物」 */
  gotoSandboxArtifacts: (sandboxId) => {
    void router.push('/sandbox/artifacts/' + sandboxId)
  },
})

/* ── 真实分析通道（本轮新增）────────────────────────────────────────────
   注册给 core 用：进 C 题工作台时先让「数模演易」智能体读题并生成分析，
   分析与候选模型优先用它；未登录、后端不可用、跑失败都自动回落到内置剧本。
   注册表为空时 core 会当作「没有这条通道」，所以单跑原型逻辑不受影响。 */
setPrototypeAnalysisHooks(createAnalysisHooks())
/* 动画与代码两条通道同构注册 —— 注册表为空时 core 当作「没有这条通道」，
   单跑原型逻辑（脚本 / 测试）不受影响。 */
setPrototypeAnimationHooks(createAnimationHooks())
setPrototypeCodegenHooks(createCodegenHooks())

/* ── 工作台任务仓（2026-10-08 新增）────────────────────────────────────────
   为什么有它：工作台的状态只在内存里，一次刷新或一次热更新重载就全没了 ——
   表现是「聊着聊着跳回首页，地址栏还停在 #/work」。这里把每条任务（题面、
   题号、阶段、对话快照）落盘，并给首页一个常驻入口，能回到正在进行的任务、
   也能在多个任务之间切换。

   分工：落盘与列表在 stores/tasks.ts；core 侧只负责「开题登记」与
   「把某条任务贴回工作台」。两边通过 hooks 注册表对话，core 不认识 localStorage。 */
setPrototypeWorkHooks({
  currentId: () => workCurrentId.value,
  begin: (init) => {
    const t = beginTask(init)
    /* 开题这一刻把**会话键**记下来 —— 附件与题面都还在手上，算得准；此后暂停/删除
       一律按它查会话，不再重新推导（内存暂存区一刷新就没了）。
       ⚠ 2026-10-09 修：这里原来只有 setTaskThread(threadIdForTask(...))，而当时
       threadIdForTask 会顺手**新建**一条会话 → start() 里 ownedThreadForRun
       读到的 previous 恒非空 → 对这条刚建、后端还不认识的会话探活必然 404 → 判成
       「别人的会话」而 rotateThread。结果每开一道新题都白建一条死会话，任务仓记的
       正是它，暂停/删除打上去 404（实测 probe-pause：请求打到 main-bdbc1d63，后端 404）。
       会话 id 本身改由 start() 真正落地后回写（见下面的 setThreadLandedSink）。 */
    setTaskCase(t.id, resolveCaseIdFor(init.protoId, init.title, init.fromDetail))
    setTaskThread(t.id, threadIdForTask(init.protoId, init.title, init.fromDetail))
  },
})

/**
 * 真正落地用的会话 id 由分析通道回报（start() 里 ownedThreadForRun /
 * prepareAttachments 都可能换过会话）。
 *
 * 按 caseId 认领任务，多任务并行时不会串台；一条都没认领到（任务仓里 caseId 为空，
 * 比如升级前留下的老任务）就兜给前台那条。
 */
setThreadLandedSink((caseId, threadId) => {
  if (!threadId) return
  let hit = false
  for (const t of workTasks.value) {
    if (t.caseId && t.caseId === caseId) {
      setTaskThread(t.id, threadId)
      hit = true
    }
  }
  if (hit) return
  const id = workCurrentId.value
  if (id) {
    setTaskCase(id, caseId)
    setTaskThread(id, threadId)
  }
})

/** 左侧任务栏的界面时钟：15 秒一跳，只为让「进行中」的小圆点自己灭掉 */
const nowTick = ref(Date.now())
/** 左栏被钉住（点了标题）：不靠鼠标悬停也保持展开 —— 触屏与「边看边切」时要用 */
const workSidePinned = ref(false)
/** 正在等后端回应的那条任务（暂停/删除要走网络，期间禁用按钮，避免连点） */
const busyTask = ref('')
/** 「删除」的两步确认：第一次点只是把它变成「确认删除」 */
const dropAsk = ref('')

function taskLive(t: WorkTask): boolean {
  return isLive(t, nowTick.value)
}

/** 侧栏每行的副标题：暂停态优先，其次才是阶段，末尾挂相对时间 */
function taskMeta(t: WorkTask): string {
  const phase = t.paused ? '已暂停' : t.stage || '尚未开始'
  return phase + ' · ' + relTime(t.updatedAt)
}

/** 顶栏「工作台」按钮：原型只在 work 视图里显示它；有任务时任何视图都该点得到 */
function refreshNav(): void {
  const btn = document.getElementById('nv-work')
  if (!btn) return
  btn.style.display = core.state.view === 'work' || workTasks.value.length ? 'block' : 'none'
}

/** 原型那个 toast 是两参数签名（第二参是图标），包一层免得每处都补个空串 */
function toastTip(msg: string, icon?: string): void {
  core.toast(msg, icon || '')
}

/** 回到某条任务的工作台（不传 id = 回最近动过的那条） */
function openWorkTask(id?: string): void {
  const t = (id ? workTasks.value.find((x) => x.id === id) : null) ?? currentTask()
  if (!t) return
  selectTask(t.id)
  if (core.state.view === 'work' && core.state.workTitle === t.title) return
  core.workSessionResume(t)
}

/**
 * 暂停 / 继续某条任务。
 *
 * 「暂停」要做两件事，顺序不能反：
 *   ① 让**后端**把在跑的 run 按中断路径收尾（产物目录保留，什么都不删）；
 *   ② 让**本地**的分析通道停止空转等产物。
 * 少了 ①，用户以为停了、后台还在烧模型；少了 ②，harvestUntil 会照旧空转最多 40 分钟。
 *
 * 后端那一步失败（后端没起、会话已不在）也照样打「已暂停」标记并如实说一句 ——
 * 用户点的是「停住这个任务」，本地至少要真的停住。
 *
 * 「继续」只清标记：会话与产物都还在，用户接着发消息就是新一轮 run。
 */
async function togglePauseTask(id: string): Promise<void> {
  if (busyTask.value) return
  const t = workTasks.value.find((x) => x.id === id)
  if (!t) return
  if (t.paused) {
    setPaused(id, false)
    toastTip('已继续。这条会话与产物都还在，接着往下走就行')
    return
  }
  busyTask.value = id
  try {
    /* 会话 id 两个来源合并：
       ① 任务仓里那个**会话键**（开题时记下，刷新/切视图都不丢）—— 主来源；
       ② 现按题面重算一次 —— 只为兼容升级前的老任务（那些没有 caseId）。
       ⚠ 必须取**本题全部会话**（全局那次 + 四问深挖各一条）：真正在烧模型的
       往往正是某一问 —— 单问深挖跑在独立的 -qN 会话里，只中断全局那次会出现
       「界面说已暂停、后端还在跑」。 */
    const fromFlow = [...new Set([
      ...threadIdsForCase(t.caseId),
      ...threadIdsForTask(t.protoId, t.title, t.fromDetail),
    ])]
    const sids = fromFlow.length ? fromFlow : t.threadId ? [t.threadId] : []
    if (sids.length && sids[0] !== t.threadId) setTaskThread(id, sids[0])
    if (!sids.length) {
      if (id === workCurrentId.value) suspend()
      toastTip('已暂停（这条任务还没在后端建会话，只停了本地等待）')
      return
    }
    let stopped = 0
    let failed = 0
    for (const sid of sids) {
      try {
        if (await interruptSession(sid)) stopped += 1
      } catch {
        failed += 1
      }
    }
    if (id === workCurrentId.value) suspend()
    toastTip(stopped
      ? '已暂停：后端这一轮（含单问深挖）已中断'
      : failed
        ? '已停住本地等待；后端中断没成功（' + failed + ' 个会话）'
        : '已暂停（后端这几条本来就没在跑）')
  } catch (e) {
    if (id === workCurrentId.value) suspend()
    toastTip('已停住本地等待；后端中断没成功（' + (e instanceof Error ? e.message : '未知原因') + '）')
  } finally {
    setPaused(id, true)
    busyTask.value = ''
  }
}

/** 「删除」是两步：先点一次变成「确认删除」，再点才真删（不可逆，且连后端会话一起清） */
function askDropTask(id: string): void {
  if (dropAsk.value !== id) {
    dropAsk.value = id
    return
  }
  void dropWorkTask(id)
}

/**
 * 删掉一条任务：**本地任务条 + 后端会话**一起清。
 *
 * 后端那步调的是「POST /sessions/{sid}/clear」：先中断在跑的 run，再删产物目录 / 状态行 /
 * 归属行（不可逆）。会话 id 为空的（题面太短、还没建过会话）就只删本地。
 * 后端失败也照样删本地 —— 否则用户会卡在一条删不掉的任务上；失败原因如实说一句。
 *
 * ⚠ **本题全部会话都要清**（全局那次 + 四问深挖各一条）：只清全局那次的话，
 * 某一问的 run 还在后台烧模型、产物目录也还在 —— 用户看到的正是「删了它还在跑」。
 */
async function dropWorkTask(id: string): Promise<void> {
  if (busyTask.value) return
  const t = workTasks.value.find((x) => x.id === id)
  dropAsk.value = ''
  if (!t) return
  const wasCurrent = workCurrentId.value === id
  /* 与「暂停」同一口径：任务仓的会话键优先，现算只兜升级前的老任务 */
  const fromFlow = [...new Set([
    ...threadIdsForCase(t.caseId),
    ...threadIdsForTask(t.protoId, t.title, t.fromDetail),
  ])]
  const sids = fromFlow.length ? fromFlow : t.threadId ? [t.threadId] : []
  if (sids.length) {
    busyTask.value = id
    let failed = 0
    try {
      for (const sid of sids) {
        try {
          await clearSession(sid)
        } catch {
          failed += 1
        }
      }
      if (failed) toastTip('有 ' + failed + ' 个会话没清掉，已只删本地任务')
    } finally {
      busyTask.value = ''
    }
  }
  removeTask(id)
  core.workSessionDrop(id)
  if (!wasCurrent) return
  /* 删掉的是前台那条：还有别的任务就接着上一条，一条都不剩才回首页 */
  if (workTasks.value.length) openWorkTask()
  else if (core.state.view === 'work') core.go('home')
}

/**
 * 对话快照对账：每 1.5 秒把前台任务的对话区 HTML 存一次（内容没变就跳过）。
 *
 * 为什么是轮询而不是让 core 回调：往对话区写东西的地方有十几处
 * （pushMsg / thinking / analysisBoot / 各条真实通道 / 沙箱回执），逐个挂钩子
 * 既散又容易漏；而这条链路本来就有 700ms 的进度轮询，对账式读取与它同一口径。
 */
function snapshotWork(): void {
  const id = workCurrentId.value
  if (!id || core.state.view !== 'work') return
  const el = core.workHostEl()
  if (!el) return
  saveStream(id, el.innerHTML)
  const st = document.getElementById('w-stage')
  saveStage(id, st ? (st.textContent || '').trim() : '')
  /* 步骤条也要一起存（2026-10-12）：它只在 startWork() 里画过一次，刷新后没人补，
     顶上那块就是空的；恢复时得按「他当时停在第几步」画回来，不能一律第 0 步。 */
  saveStep(id, core.curStep)
  /* 右栏闸门区（2026-10-12）：闸门现在只活在右栏，不在对话区快照里 ——
     不单独存的话，刷新 / 切任务回来右栏会空掉，用户以为闸门丢了。 */
  const rg = document.getElementById('rail-gate-list')
  saveRail(id, rg ? rg.innerHTML : '')
  /* 右栏产物清单是对账出来的（来源是对话区里的 .arts .acard），
     顺手在这里对一次 —— 它与上面这条 1.5 秒的节拍天然同频。 */
  core.railSync()
}

/**
 * URL → 视图：直接输网址、刷新、前进后退都要能落到对的视图上。
 *
 * ⚠️ 「#/work」这一支要单独兜：工作台的状态（workTitle / detailId / 对话区）只在
 *    内存里，刷新或热更新重载后是空的，而 go('work') 第一行就是
 *    if(!state.workTitle){ toast(...); return } —— 不兜的话视图会停在首页、
 *    地址栏却还挂着 #/work，用户看到的就是「聊着聊着跳回首页」。
 *    所以这里先用任务仓里那条任务把工作台贴回来；贴不回来就把 URL 收回首页，
 *    让地址与视图口径一致，别留错乱。
 */
function applyRoute(path: string): void {
  const want = path.startsWith('/p/') ? 'detail' : path === '/mine' ? 'mine' : path === '/work' ? 'work' : 'home'
  if (core.state.view === want) return
  if (want === 'work' && !core.state.workTitle) {
    const t = currentTask()
    if (t) {
      selectTask(t.id)
      if (core.workSessionResume(t)) return
    }
    void router.replace('/')
    return
  }
  if (want === 'detail') core.openDetail(String(route.params.id))
  else core.go(want)
}

watch(
  () => route.path,
  (p) => {
    if (syncing) return
    applyRoute(p)
  },
)

function openLogin(): void {
  void router.push({ name: 'login', query: { redirect: route.fullPath } })
}

/* ── 沙箱入口（本轮新增）─────────────────────────────────────────────────
   双轨并存：原型那套演示工作台原样不动，这两个入口通向真沙箱。
   登录闸门不在按钮上做，交给路由 meta.requiresAuth —— 一个地方管，
   按钮不必各自判断，也就不会出现「有的入口拦、有的入口不拦」。 */

/**
 * 离开这条对话去沙箱之前，把对话区存一份。
 *
 * 沙箱走的是另一棵组件树（App.vue 里 /sandbox/* 挂的是沙箱外壳），
 * 一去一回 PrototypeShell 整个重建、对话区跟着空掉 —— 所以走之前留一份。
 * 快照只活在内存里（见 runHandoff.ts）：刷新后自然失效，
 * 不会把旧 DOM 贴回一个已经重置的状态上。
 */
function leaveTalk(): void {
  if (core.state.view !== 'work') return
  /* 取「当前任务的对话容器」而不是 #stream-in 本身 —— 容器是按任务分桶的，
     拿错了会把这份快照贴到别的任务上 */
  const stream = core.workHostEl()
  if (stream) saveStreamSnapshot(stream.innerHTML)
}

// 顶栏「沙箱」与详情页「进入沙箱环境」由路由统一承载；
// 对话里的「进入沙箱运行」按钮由 hooks.sandboxReady() 决定。
// leaveTalk() 保留：离开对话去沙箱前仍要存一份对话快照。

async function onLogout(): Promise<void> {
  await userStore.logout()
  core.go('home')
}

/** 把走之前存下的对话贴回去（没有快照就什么都不做） */
function restoreTalkStream(): void {
  const snap = takeStreamSnapshot()
  if (!snap) return
  const box = core.workHostEl()
  if (box) box.innerHTML = snap
}

/**
 * 把沙箱交回来的运行凭据追加成一条对话消息。
 * takeReceipt 是「取走即清」—— 否则每次进 /work 都会把同一条再追加一遍。
 */
function deliverSandboxReceipt(): void {
  const rcp = takeReceipt()
  if (!rcp) return
  clearRequest()
  core.pushSandboxResult(rcp)
}

/** 任务仓的两条对账定时器（卸载时收掉） */
let snapTimer: number | null = null
let tickTimer: number | null = null
/** 任务正文推后端的慢节拍（对话快照几十 KB，不能跟着 1.5 秒的对账节奏推） */
let pushTimer: number | null = null

/** 任务列表变了（新增 / 移除）就顺带对账顶栏入口 */
watch(workTasks, () => {
  refreshNav()
})

/** 页面临走前把还没推上去的任务推一次（切标签页、关页面都算） */
function flushOnHide(): void {
  if (document.visibilityState === 'hidden') void flushRemote()
}

onMounted(async () => {
  // 沙箱回来时组件会重建。这条对话要不要接续，看的是「走的时候人在哪个视图」，
  // 而不是回来的 URL —— 用户点顶栏「返回站点」时落的是首页，可这条对话该留着，
  // 切到工作台就能看见。
  const wasInWork = core.state.view === 'work'

  // 样式按需挂载：原型那 44K 样式是全局的，与沙箱模块有 17 个类名重叠，
  // 直接全局引入会静默改掉沙箱页面观感。详见 styles/prototypeCss.ts。
  mountPrototypeCss()
  /* 分析通道那三张样式表：跑着的时候由各自的渲染函数懒注入，刷新后没人注入 ——
     而对话区/产物卡/报告卡是**从快照贴回来的**，那些 lv-* / art-* / rep-* 节点
     就会掉回浏览器默认样式（用户看到的「对话内容没样式」）。这里与原型 CSS 一起
     补挂，幂等。 */
  ensureAnalysisStyles()

  /* 任务与对话的真相源在后端（会话扩展，见 api/workbenchStore.ts）。
     顺序要紧：applyRoute → workSessionResume 是「把任务贴回工作台」的唯一时机，
     所以要**先拉回来再落视图** —— 否则先贴一份本地缓存、随后才被新数据覆盖，
     用户会看到对话闪一下。
     后端慢或挂了也不能卡住页面：1.5 秒拿不到就按本地缓存走（hydrateTasks 自己
     吞掉所有网络错误，这里只是加一道时间闸）。 */
  await Promise.race([
    hydrateTasks(),
    new Promise<void>((resolve) => window.setTimeout(resolve, 1500)),
  ])

  // 原型逻辑必须在模板进 DOM 之后启动（它一上来就 $('d-tabs') 找元素）
  bootPrototype()
  applyRoute(route.path)

  // 顺序要紧：先把对话贴回来，再把沙箱的回执接在它后面
  if (wasInWork) {
    restoreTalkStream()
    deliverSandboxReceipt()
  }

  // 任务仓的三条对账：① 对话快照（1.5s）② 界面时钟（15s，让「进行中」自己灭）
  // ③ 任务正文推后端（10s —— 对话快照几十 KB，不跟着 1.5 秒的节拍推）
  refreshNav()
  snapTimer = window.setInterval(snapshotWork, 1500)
  tickTimer = window.setInterval(() => {
    nowTick.value = Date.now()
  }, 15000)
  pushTimer = window.setInterval(() => {
    void flushRemote()
  }, 10000)
  document.addEventListener('visibilitychange', flushOnHide)
})

onUnmounted(() => {
  if (snapTimer) window.clearInterval(snapTimer)
  if (tickTimer) window.clearInterval(tickTimer)
  if (pushTimer) window.clearInterval(pushTimer)
  snapTimer = null
  tickTimer = null
  pushTimer = null
  document.removeEventListener('visibilitychange', flushOnHide)
  /* 临走再推一次：切路由 / 关页面时不该把最后几句话丢在本地 */
  void flushRemote()
  shutdownPrototype()
  unmountPrototypeCss()
})
</script>

<style scoped>
.nav-user {
  font-size: 13px;
  font-weight: 600;
  color: var(--blue-900, #0b6e68);
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
`
write('src/views/PrototypeShell.vue', shellVue)

/* ── 落盘 + 产物一致性自检（2026-10-12） ────────────────────────────────────
   全部产物先在内存里算完（见 write()：只入队、不写盘），到这里才统一落盘。
   落盘前逐个与磁盘现状比对，**忽略行尾差异** —— 本仓库 core.autocrlf=true，
   工作区 CRLF、仓库 LF，行尾不算内容差异（生成器一律写 LF）。
   对不上 ⇒ 磁盘上有本脚本再生不出来的内容（有人直接改了产物），此时**整体拒绝
   写入**并指出首个差异，避免留下「一半新一半旧」的产物。二进制（PNG）跳过。
   确需覆盖：node build-port.mjs --force。                                    */
const normEol = (s) => s.replace(/\r\n/g, '\n')
function firstDiff(a, b) {
  const la = a.split('\n')
  const lb = b.split('\n')
  for (let i = 0; i < Math.max(la.length, lb.length); i += 1) {
    if (la[i] !== lb[i]) {
      return `首处差异在第 ${i + 1} 行：\n     磁盘：${String(la[i]).slice(0, 150)}\n     生成：${String(lb[i]).slice(0, 150)}`
    }
  }
  return ''
}
const FORCE = process.argv.includes('--force')
const mismatch = []
if (!FORCE) {
  for (const [rel, text] of pending) {
    if (typeof text !== 'string') continue
    const p = path.join(ROOT, rel)
    if (!fs.existsSync(p)) continue
    const disk = normEol(fs.readFileSync(p, 'utf8'))
    const next = normEol(text)
    if (disk !== next) mismatch.push([rel, firstDiff(disk, next)])
  }
}
/* ── 产物一致性自检（2026-10-19 由「硬 ABORT」改为「告警 + 跳过写入」） ──────
   背景：core.ts 已是**手工维护的产物**（成果页双 Tab + PDF/Word/ZIP 下载、E 题数据、
   动画 rail、微网回放等约 1050 行，本脚本用现有原型 HTML 再生不出来）。
   所以这里不再 `process.exit(1)` 中断流水线，而是：
     · 有差异 → 打印告警 + **整体跳过写入**（磁盘一个字节都不动），exit 0；
     · 想强制覆盖 → 加 `--force`（会丢掉上面那批手工内容，慎用）。
   这样「误跑一次」最坏结果是「什么都没发生」，而不是把手工改动抹掉。 */
if (mismatch.length) {
  console.warn('\n⚠ WARN：生成结果与磁盘产物不一致 —— 磁盘上有本脚本再生不出来的内容：')
  for (const [rel, detail] of mismatch) {
    console.warn(`   - ${rel}`)
    console.warn('     ' + detail)
  }
  console.warn('\n   已跳过全部写入（磁盘未改动）。core.ts 现为手工维护产物，')
  console.warn('   改动请直接改产物文件；确需用生成结果覆盖，才加 --force。\n')
  process.exit(0)
}
for (const [rel, text] of pending) {
  const p = path.join(ROOT, rel)
  fs.mkdirSync(path.dirname(p), { recursive: true })
  /* 行尾跟随磁盘现状：本仓库 core.autocrlf=true，工作区是 CRLF，而生成器内部
     一律按 LF 处理。若这里硬写 LF，git 内容虽无差异、文件字节却全变（每次重跑
     都像动过整个文件）。跟随现状 → 重跑后字节不变，md5 相同。二进制跳过。 */
  let out = text
  if (typeof text === 'string' && fs.existsSync(p)) {
    if (/\r\n/.test(fs.readFileSync(p, 'utf8'))) out = normEol(text).replace(/\n/g, '\r\n')
  }
  fs.writeFileSync(p, out)
  console.log(`   → ${rel}  ${(fs.statSync(p).size / 1024).toFixed(1)} KB`)
}

console.log('\n✅ 生成完毕')
console.log('   core.ts 里保留的原型函数：', fnNames.length, '个')
console.log('   ~待 Vue 化 标记的函数：', TODO_VUE.length, '个（', TODO_VUE.join(', '), '）')
