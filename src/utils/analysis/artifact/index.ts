/**
 * 产物卡（Step3）—— 把智能体的**全部**产物列进工作台对话区
 *
 * 数据来源是既有的产物树接口（`sandbox/api/artifact.ts` 的 fetchArtifactTree →
 * 后端 `SessionArtifactController`）。它本来就把**本会话 + 全部子会话**的
 * `artifacts/` 合并成一棵树，所以「全部产物」这件事后端早就做完了 —— 这里只补 UI。
 *
 * 三条设计约束：
 *   ① **会话坐标在挂载那一刻冻结**（`caseId` 由桥接层传进来）。切到别的任务后
 *      再点刷新，读的仍是这张卡所属那道题的会话 —— 否则会读到「当前任务」的产物，
 *      出现「A 的卡里长出 B 的文件」这种错。
 *   ② **用只读的 thread 访问器**（`threadIdsForCase`），不碰 `threadIdOf` ——
 *      后者会顺手把 caseId 写进映射表并生成一条新会话，只是看一眼产物不该建会话。
 *   ③ **失败如实说**（没读到清单 / 后端不可用），不把「读不到」画成「没有产物」。
 */

import { fetchArtifactContent, fetchArtifactObjectUrl, fetchArtifactTree } from '@/api/artifact'
import { AGENT_ID } from '@/config'
import { threadIdsForCase } from '@/stores/analysis'

import { ensureArtifactStyle, esc, flattenFiles, wallHTML, type ArtifactFile, type WallState } from './render'

/** 每张卡一份状态（容器是 key，节点回收则状态跟着走） */
interface View extends WallState {
  /** 这张卡属于哪个会话（挂载时冻结） */
  caseId: string
}

const views = new WeakMap<HTMLElement, View>()
/** 最近一次挂载的容器 */
let mounted: HTMLElement | null = null

function viewOf(box: HTMLElement, caseId: string): View {
  let v = views.get(box)
  if (!v) {
    v = { caseId, files: [], loading: false, loaded: false, error: '' }
    views.set(box, v)
  } else if (caseId) {
    v.caseId = caseId
  }
  return v
}

function paint(box: HTMLElement, v: View): void {
  ensureArtifactStyle()
  box.innerHTML = wallHTML(v)
}

/**
 * 这道题的**主会话** threadId。
 *
 * 只取第一个（主会话）是刻意的：单问深挖那几个 `-qN` 会话是前端另开的、
 * 不在框架的 spawn registry 里，产物树接口不会把它们的子会话合并进来 ——
 * 也就是说**主会话那棵树里本来就没有它们**，多取几个也没用。
 */
export function sessionThreadFor(caseId: string): string {
  if (!caseId) return ''
  return threadIdsForCase(caseId)[0] ?? ''
}

/** 挂上这张卡（传 null 卸载）。caseId 为空时先画空态，刷新时再按参数补 */
export function artifactMount(el: HTMLElement | null, caseId = ''): void {
  mounted = el
  if (!el) return
  const v = viewOf(el, caseId)
  paint(el, v)
}

/** 清掉某个容器的记录（换题、退出工作台） */
export function artifactReset(el?: HTMLElement | null): void {
  const box = el ?? mounted
  if (!box) return
  views.delete(box)
  box.innerHTML = ''
}

/**
 * 拉一次产物树并重画。
 *
 * **不轮询**（2026-10-09 用户拍板「跑完自动刷一次 + 手动刷新」）：
 * 智能体写产物要二三十分钟，逐秒轮询只是白打接口；跑完那一次由桥接层触发，
 * 之后用户想看新的就点卡上的「刷新」。
 */
export function artifactRefresh(el?: HTMLElement | null, caseId = ''): Promise<void> {
  const box = el ?? mounted
  if (!box) return Promise.resolve()

  const v = viewOf(box, caseId)
  const threadId = sessionThreadFor(v.caseId)
  if (!AGENT_ID || !threadId) {
    v.loading = false
    v.error = v.caseId ? '这道题的会话还没在后端建立，暂时读不到产物' : '还没有会话（先在首页发一次题）'
    paint(box, v)
    return Promise.resolve()
  }

  v.loading = true
  v.error = ''
  paint(box, v)

  return fetchArtifactTree(AGENT_ID, threadId)
    .then((nodes) => {
      v.files = flattenFiles(nodes)
      v.loaded = true
      v.loading = false
      v.error = ''
      paint(box, v)
    })
    .catch((e: unknown) => {
      v.loading = false
      v.error = e instanceof Error ? e.message : String(e)
      paint(box, v)
    })
}

/* ── 预览 ───────────────────────────────────────────────────────────────── */

export interface ArtifactPreview {
  title: string
  sub: string
  html: string
}

/** 后端内容接口在二进制 / 超限 / 非 UTF-8 时**不抛错**，只把内容置空并给 reason */
interface ContentEnvelope {
  path?: string
  content?: string | null
  reason?: string | null
}

function noteHTML(text: string): string {
  return '<div class="art-pv-note">' + esc(text) + '</div>'
}

/**
 * 可当图片预览的扩展名。
 *
 * 与后端的二进制名单（`SessionArtifactController.BINARY_EXT`）不冲突：那边决定的只是
 * 「`/content` 不给文本」，图片实际内容走 `/raw` 取字节，两条路互不干扰。
 */
const IMAGE_EXT = /\.(png|jpe?g|gif|webp|bmp|svg)$/i

/** 取一个产物文件的预览（标题 / 副标题 / 正文 HTML）—— 二进制与超限也返回卡片，只是写明原因 */
export async function artifactPreview(
  path: string,
  el?: HTMLElement | null,
  caseId = '',
): Promise<ArtifactPreview | null> {
  const box = el ?? mounted
  const v = box ? viewOf(box, caseId) : null
  const threadId = sessionThreadFor(v?.caseId ?? caseId)
  if (!AGENT_ID || !threadId) return null

  const name = path.slice(path.lastIndexOf('/') + 1) || path

  // 图片走 /raw 取字节再转 objectURL —— /content 对二进制只回 reason，拿不到图。
  // ⚠️ objectURL 生命周期交给浏览器缓存（同一次预览里复用同一张），不再逐个 revoke：
  //    预览抽屉会反复打开同一个文件，每次 revoke 反而会让还在显示的图变空。
  if (IMAGE_EXT.test(path)) {
    try {
      const url = await fetchArtifactObjectUrl(AGENT_ID, threadId, path)
      return { title: name, sub: path, html: '<img class="art-pv-img" src="' + esc(url) + '" alt="">' }
    } catch (e: unknown) {
      return { title: name, sub: path, html: noteHTML(e instanceof Error ? e.message : String(e)) }
    }
  }

  try {
    const got = (await fetchArtifactContent(AGENT_ID, threadId, path)) as unknown as ContentEnvelope
    if (typeof got?.content !== 'string') {
      return { title: name, sub: path, html: noteHTML(got?.reason || '这个文件没有可预览的文本内容') }
    }
    return { title: name, sub: path, html: '<pre class="art-pv">' + esc(got.content) + '</pre>' }
  } catch (e: unknown) {
    return { title: name, sub: path, html: noteHTML(e instanceof Error ? e.message : String(e)) }
  }
}
