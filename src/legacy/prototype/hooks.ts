/**
 * 原型逻辑 ↔ 路由/登录/沙箱 之间的接缝
 *
 * 背景：原型是单页四视图，`go(v)` 直接切 DOM 类名，没有「路由」「登录」
 * 「真沙箱」这三个概念。搬进站点之后它们必须补上，但**不是**去重写原型那套流程，
 * 而是只在少数几处开口子（见 core.ts 里标注 `~迁移改动` 与桥接层的那几个函数）。
 *
 * 为什么用注册表而不是直接 import：
 *   core.ts（原型逻辑）需要问「登录了吗」「切个路由」「这道题有沙箱吗」，
 *   而路由、登录态与沙箱映射又是由 Vue 组件提供的。直接互相 import 会形成
 *   core → router → 视图组件 → core 的循环。这里只留一个可替换的钩子表，
 *   谁提供实现谁注册，依赖方向单向。
 *   钩子未注册时全部放行 / 返回「没有」—— 这样单独跑原型逻辑（脚本、测试）不会卡住。
 */

export interface PrototypeNavHooks {
  /** 当前是否已登录 */
  isLogged: () => boolean
  /** 要求登录：由接入方决定跳哪里，并记下登录后要回到哪个视图 */
  requireLogin: (redirectView: string) => void
  /** 把当前视图同步到 URL（刷新与分享不丢位置） */
  syncRoute: (view: string) => void
  /**
   * 视图切好后顺带对账一遍「与视图绑定的外壳元素」。
   * 目前只有一件事：顶栏「工作台」按钮 —— 原型只在 work 视图里显示它，
   * 但有任务在跑时它在任何视图都该点得到（否则用户从首页没法回工作台）。
   */
  syncNav?: () => void
  /** 当前这道题在真沙箱里有没有环境 —— 决定「进入沙箱运行」按钮出不出现 */
  sandboxReady?: () => boolean
  /** 把这一问的代码交给真沙箱去跑（跳哪、怎么记交接单，由接入方决定） */
  gotoSandboxRun?: (qn: number) => void
  /** 跳到沙箱的产物浏览页 */
  gotoSandboxArtifacts?: (sandboxId: string) => void
}

/* ==================================================================
   工作台任务仓（2026-10-08 新增）

   为什么要它：工作台状态只在内存里，刷新 / 热更新重载就会「跳回首页」；
   而首页又需要一个常驻入口，让用户回到正在进行的任务、并在多个任务间切换。

   这里只留**两个**口子，核心的落盘与列表都在 `stores/tasks.ts`：
     · `currentId()` —— core 用它决定「这段对话该写进哪个任务的容器」
     · `begin()`     —— 开题时登记一条任务
   快照与阶段文案由页面外壳观察 DOM 得到，不从这里过 —— core 少一层负担。
   ================================================================== */

export interface PrototypeWorkHooks {
  /** 前台任务 id；空串 = 任务仓还没起来（此时 core 退回单容器老行为） */
  currentId: () => string
  /** 开一道新题：登记一条任务（题面 / 题号 / 是否来自详情页） */
  begin: (init: { title: string; protoId: string; fromDetail: boolean; paperNames?: string[] }) => void
}

/* ==================================================================
   真实分析通道（本轮新增）

   原型那条对话的「分析 → 候选模型」是写死的剧本。这里给它开一个口子：
   接入方注册一个实现，core 在相应步骤优先取**智能体实时生成**的内容，
   取不到（未注册、未登录、跑失败、用户点了跳过）就用原来的剧本。

   为什么和沙箱钩子一样走注册表：core.ts 是生成物，不该 import 业务模块；
   而这条通路要读 token、要发网络请求 —— 都在核心之外。方向仍是单向：
   core → hooks → 业务模块。注册表为空时全部返回「没有」，流程自动回落，
   单跑原型逻辑（脚本 / 测试）不会因为缺依赖而卡住。

   类型用 `import type` 引入 —— 编译期擦除，运行时两边没有依赖关系，
   也就不会形成 core ↔ 业务模块的环。
   ================================================================== */

import type {
  AttachScope,
  CaseAttachment,
  CaseRef,
  HeldFile,
  RejectedFile,
} from '@/stores/analysis'
import type { GateSpec, GateSubmitResult, GateValues } from '@/utils/analysis/gate'

export interface PrototypeAnalysisHooks {
  /** 这道题该用哪份题面；没有可用题面返回 null（→ 不启用真实通道）
   *  paperAsProblem：题面由用户上传的题目文件承担（只可能来自首页入口）
   *  paperNamesFallback：刷新后的兜底 —— 开题那一刻记下的题目文件名列表 */
  resolveCase: (
    protoId: string,
    title: string,
    fromDetail: boolean,
    paperAsProblem?: boolean,
    paperNamesFallback?: string[],
  ) => CaseRef | null
  /** 是否具备真实通道（题面 + 登录 + 非 mock） */
  available: (protoId: string, title: string, fromDetail: boolean) => boolean
  /** 发起一次分析（幂等） */
  start: (ref: CaseRef) => void
  /** 用已存在的产物接回结果（刷新后免重跑）；成功返回 true */
  reuse: (ref: CaseRef) => Promise<boolean>
  /** idle | running | done | failed */
  status: () => string
  /** 进度文案（已用时 / 工具调用次数） */
  progressText: () => string
  /** 失败或回落原因 */
  failureText: () => string
  /**
   * 六步进度条走到第几步（0=全局分析 … 5=论文）。
   *
   * 判据在 analysis 模块里（产物树 + 平台台账，见 `syncStep`）—— core 只取数。
   * 2026-10-14 之前是 core 自己扫对话文本猜的，猜错过一次（进度话术里写 q02-q04
   * 就跳到「问题四」）。未接时返回 0，进度条不动。
   */
  step?: () => number
  /**
   * 产物签名（变了说明有新东西落盘）。对话区据此决定要不要重拉产物卡 ——
   * 跟随会话期间 700ms 叫一次，靠它去重，不能每次都真打接口。
   */
  artifactStamp?: () => number
  /**
   * 排队等待的说明（空 = 没在排队）。
   *
   * 后端同一会话只允许一个活跃 run，而平台自己会唤醒着往下做（20–40 分钟），
   * 这段时间用户发言要排队 —— 这条文案就是「它在等什么」，得让用户看得见。
   */
  askWaitNote?: () => string
  /** 代码那一步的说明（分析真实、代码仍内置，要如实写出来） */
  codeNote: () => string
  /** 产物抽屉里的真实分析文档；没有返回空串 */
  liveDoc: (type: string) => string
  /** 用户点「跳过等待」 */
  skip: () => void
  /** 清掉结果，强制重新生成 */
  reset: () => void
  /**
   * 清掉结果 —— 工作台**在多条任务之间切换**时用。
   * 分析通道是模块级单例（一次只有一条任务是「当前」的），切走之前必须把上一条
   * 的结果摘干净，否则会把上一条的分析贴进这一条的对话里。
   */
  resetRuns: () => void

  /* ── 对话区自由输入 ──────────────────────────────────────────────────────
     用户在对话区说的每一句 = 这一轮的真实指令，原样交给智能体（前端不做意图
     分流）；它可能只是回答，也可能接着往下做，还可能反过来发一张闸门。
     这条通路**不推进前端状态机、不要产物**，与 start 是两回事。 */

  /** 问一句；答案实时画进对话区。返回 ok=false 时 note 说明原因 */
  ask: (ref: CaseRef, text: string) => Promise<{ ok: boolean; text: string; note: string }>

  /* ── 附件（2026-09-30）────────────────────────────────────────────────────
     首页与工作台各两个按钮（「上传题目」/「上传附件」）。原型里只有一个按钮，
     且只把文件名写进输入框，文件本身读完就丢。这几个口子把它接成真上传：
     选完先校验并暂存，开题（或在工作台里选完）时传到本题会话工作区。
     题目文件与数据附件分槽存放 —— 前者是题面来源，只传它也能开题。 */

  /** 选文件：校验白名单与体积，通过的留下来；返回被拒原因供 UI 提示
   *  kind：'problem' = 题目文件（题面本身），'data' = 数据附件（默认） */
  holdFiles: (
    scope: AttachScope,
    files: File[],
    kind?: 'problem' | 'data',
  ) => { accepted: number; rejected: string[] }
  /** 这个入口有没有题目文件 —— 决定「开始演易」放不放行 */
  hasProblemFile: (scope: AttachScope) => boolean
  /** 题目文件的显示名（去扩展名）—— 只传了题目文件时，用它当工作台标题 */
  problemTitle: (scope: AttachScope) => string
  /** 当前挂着的附件（待上传 + 已上传），画附件条用 */
  heldList: (scope: AttachScope) => HeldFile[]
  /** 撤掉一个；已上传的连会话里的文件一起删 */
  removeHeld: (scope: AttachScope, key: string) => Promise<void>
  /** 没过校验的文件（白名单 / 体积）—— 留在附件条上标明原因，不要一闪而过 */
  rejectedList: (scope: AttachScope) => RejectedFile[]
  /** 点掉一条没通过的提示 */
  dropRejected: (scope: AttachScope, key: string) => void
  /** 工作台入口：题目已经定了，选完当场传到本题会话；返回没传上去的原因 */
  attachNow: (scope: AttachScope) => Promise<string[]>
  /** 换了一道题：摘掉上一题已上传的附件（不动还没上传的） */
  beginProblem: () => void
  /** 附件没传上去的原因，等待气泡如实显示 */
  attachFailureText: () => string
  /** 传上去了、但这一轮 run 已经发过请求、智能体读不到的附件 */
  lateAttachments: () => CaseAttachment[]
  /** 「带上刚传的附件重新分析」：丢结果、留附件，重新发起一次 */
  rerun: () => void

  /* ── 实时对话通道（2026-10-08）─────────────────────────────────────────────
     智能体的**事件流**（思考段 / 工具调用 / 正文 / 错误）实时画进工作台对话区。
     以前这条通路只把产物收回来，事件流当场丢掉 —— 平台那边看得见的「已思考
     12s」「已完成 N 项操作」，站点这边一概没有。这四个口子把它补回来：

       core 只管在等待气泡里留一个空 div（`.lv-stream`）并交给 liveMount，
       隔一会儿调一次 liveRender；画成什么样由 analysis 模块决定（不碰原型那
       162 个全局函数，也不改模板结构）。 */

  /** 挂上实时区容器（传 null 卸载）。传进来的那一刻它就成了「下一轮 run 该画哪」 */
  liveMount: (el: HTMLElement | null) => void
  /** 增量刷新实时区；传容器则以参数为准（气泡重建后旧引用会失效） */
  liveRender: (el?: HTMLElement | null) => void
  /** 这一轮有没有可显示的对话块；传容器则以该容器那轮为准 */
  liveActive: (el?: HTMLElement | null) => boolean
  /** 思考段数 / 工具次数（折叠摘要用） */
  liveCounters: (el?: HTMLElement | null) => { think: number; tools: number }
  /** 清掉实时块（换题、退出工作台） */
  liveReset: (el?: HTMLElement | null) => void

  /* ── 闸门 / 人在环（2026-10-09）─────────────────────────────────────────────
     平台的 ui_form_advanced / ui_gate 是 external 工具：模型一调，**run 就挂起**
     等用户填表。早先这条路上核心段只把表单登记进沙箱的待确认存储、然后返回一句
     「请到沙箱对话处理」—— 工作台对话区什么都没有，用户看到的就是「闸门回不到前端」。
     现在表单的形状由 analysis 模块解析成一份平铺描述（GateSpec），core 只负责画成
     卡片、把用户填的值送回去；填完后按**同一 (threadId, runId)** 续跑，所以对话区里
     仍是同一条气泡、同一段实时输出。 */

  /** 当前挂起的闸门；没有返回 null */
  gate: () => GateSpec | null
  /**
   * 用户填完并提交。
   *
   * 返回**回执**（后端受理了没有）：core 那边据此决定卡片是定格成「已提交」还是
   * 如实报错 + 恢复可交互。钩子没实现（单独跑原型）时允许返回 void，core 按
   * 「受理了」处理 —— 本地 resolve 那条老路没有失败可言。
   */
  gateSubmit: (values: GateValues) => Promise<GateSubmitResult | void>
  /** 用户放弃这张表单 —— 按取消收尾，不让那条 run 永远挂着（同样带回执） */
  gateCancel: () => Promise<GateSubmitResult | void>

  /* ── 产物卡（2026-10-09）──────────────────────────────────────────────────
     智能体写出的文件以前只在抽屉里按「分析 / 代码 / 论文」那几种固定档位看得到，
     真实产物（`analysis_v1.0.md`、`truth/*.json`、用户传的附件…）根本没有入口。
     这一组把**产物树**接到对话区：跑完分析后追一张「智能体产物」卡，列出本会话 +
     全部子会话 `artifacts/` 下的全部文件（先不过滤），点一张进右侧抽屉看文本。

     与实时通道同构：core 只管留一个空 div 并把事件委托交给桥接层，
     画什么、拉什么全在 analysis 模块里（不碰原型那 162 个全局函数）。 */

  /** 挂上产物卡容器（传 null 卸载）。caseId 在这里**冻结**，切任务后刷新也不会读错会话 */
  artifactMount: (el: HTMLElement | null, caseId: string) => void
  /** 拉一次产物树并重画；传容器以参数为准 */
  artifactRefresh: (el?: HTMLElement | null, caseId?: string) => void
  /** 清掉这张卡的记录 */
  artifactReset: (el?: HTMLElement | null) => void
  /** 取一个产物文件的预览（标题 / 副标题 / 正文 HTML）；拿不到返回 null */
  artifactPreview: (
    path: string,
    el?: HTMLElement | null,
    caseId?: string,
  ) => Promise<{ title: string; sub: string; html: string } | null>

  /* ── 分析报告卡（2026-10-09，P2）─────────────────────────────────────────
     需求口径「以 docx 为主，动画以 html」：分析跑完后，把产物里的**分析主稿**
     整理成 Word 交到对话区（自动挑文件 + 生成 + 下载）；动画产物是 HTML，本来
     就能在浏览器里直接跑，不进这条路径。

     与产物卡同构：core 只留容器 + 事件委托，挑文件、调后端导出、下载全在
     analysis 模块里（core 不认识 docx，也不认识后端那条导出接口）。 */

  /** 挂上报告卡容器（传 null 卸载）。caseId 同产物卡一样在挂载时冻结 */
  reportMount: (el: HTMLElement | null, caseId: string) => void
  /** 找主稿并重新导出一次（幂等，覆盖同一份导出物） */
  reportRefresh: (el?: HTMLElement | null, caseId?: string) => void
  /** 下载已生成的 Word；没有就先导一次 */
  reportDownload: (el?: HTMLElement | null, caseId?: string) => void
  /** 预览导出的**源 Markdown**（Word 本身是下载后本地打开的事） */
  reportPreview: (
    el?: HTMLElement | null,
    caseId?: string,
  ) => Promise<{ title: string; sub: string; html: string } | null>
}

let analysisHooks: PrototypeAnalysisHooks | null = null

export function setPrototypeAnalysisHooks(hooks: PrototypeAnalysisHooks | null): void {
  analysisHooks = hooks
}

/** 取真实分析通道；没注册返回 null（调用方据此走内置剧本） */
export function getAnalysisHooks(): PrototypeAnalysisHooks | null {
  return analysisHooks
}

/* ==================================================================
   S2 真实动画通道（本轮新增）

   「分析 → 动画」环节从**内置演示**换成智能体实时生成（分析动画智能体）。
   生成在后台并行跑，不阻塞对话流；站点右下角浮窗显示进度。
   真实通道接管期间内置演示被抑制（regAnims 不登记内置演示），失败/未完成
   一律如实显示，不拿内置演示冒充。

   注册表为空时全部返回「没有」→ 流程自动走内置演示（未登录/演示模式不变）。
   ================================================================== */

/** 一档真实动画（与 analysis 通道的 RealAnimation 结构一致，解耦不互相 import） */
export interface PrototypeAnimItem {
  /** 站点侧演示键：'0' = 全局，'1'.. = 问题一.. */
  k: string
  title: string
  sub: string
  /** 自包含 HTML 全文（渲染时用 blob URL 挂 iframe） */
  html: string
  path: string
}

export interface PrototypeAnimationHooks {
  /**
   * 发起一次动画生成（幂等；进工作台、S1 跑通后由桥接层调用）。
   *
   * 2026-10-13 起是 **S2**：同一个智能体、S1 的同一条会话，先接回会话里已有的
   * 动画产物，再等闸门 1（分析确认）被消费掉，最后才发请求。详见
   * `@/stores/animation` 文件头的三条硬约束。
   */
  start: (ref: CaseRef) => void
  /** 用会话里已有的动画产物接回（刷新后免重跑）；成功返回 true */
  reuse: (ref: CaseRef) => Promise<boolean>
  /** 通道当前持有的是哪道题的会话；空闲返回 null（多任务切换时对账用） */
  currentCase: () => CaseRef | null
  /** idle | running | done | failed */
  status: () => string
  /** 进度文案（已用时 / 工具调用次数） */
  progressText: () => string
  /** 失败原因 */
  failureText: () => string
  /** 动画就绪时返回档位列表；未就绪返回 null */
  takeAnims: () => PrototypeAnimItem[] | null
  /**
   * 当前已收到的档位（running 期间也在长）—— 右栏「边跑边出」读这里，
   * 不用等整个流水线收工。
   */
  knownAnims: () => PrototypeAnimItem[]
  /** 档位有新增时通知订阅者（右栏重画、登记进全屏列表）；返回退订函数 */
  onUpdate: (cb: () => void) => () => void
  /** 清掉结果（重新开始一道题时） */
  reset: () => void

  /* ── 实时区（2026-10-13：浮窗里的进展明细）───────────────────────────────
     与工作台对话区同构（见 PrototypeAnalysisHooks 的「实时对话通道」），
     差别只有一条：**它不走全局「当前容器」**。

     浮窗是**与对话区并发**的：`liveMount` 会把「下一轮 run 该画哪」改到浮窗里，
     对话区那条流就被截了。所以这一组全是**显式容器**的口子 ——
     core 只管留一个 `.lv-stream` 并把它交进来，画什么、什么时候画全在 store 里。 */

  /** 把浮窗的实时区容器交进来（传 null 卸载）。登记之后事件流才认这个通道 */
  liveAttach: (el: HTMLElement | null) => void
  /** 增量刷新实时区（传容器，气泡重建后旧引用会失效）。
   *  可见性不在这里管 —— 空着由 CSS `:empty` 收掉，JS 切 display 会有空窗。 */
  liveRender: (el?: HTMLElement | null) => void
}

let animationHooks: PrototypeAnimationHooks | null = null

export function setPrototypeAnimationHooks(hooks: PrototypeAnimationHooks | null): void {
  animationHooks = hooks
}

export function getAnimationHooks(): PrototypeAnimationHooks | null {
  return animationHooks
}

/* ==================================================================
   S3 真实代码通道（本轮新增）

   「候选模型点选 → 代码」环节从**内置剧本代码**换成解题代码智能体实时生成。
   与单问深挖同构：选定模型后弹等待气泡（可跳过），完成后真实代码包交给
   桥接层展示（复制/下载/进沙箱交接）——智能体只生成不运行。
   失败/跳过回落内置剧本并如实标注（原有 codeNote 通道）。
   ================================================================== */

/** 一个代码文件（相对路径 + 全文） */
export interface PrototypeCodeFile {
  path: string
  name: string
  content: string
}

/** 一问的真实代码包 */
export interface PrototypeCodePackage {
  /** 主入口文件名（如 main.py） */
  mainName: string
  mainText: string
  files: PrototypeCodeFile[]
  readme: string
  /** 生成本包所用的模型名 —— 换模型重选时要能识别并重新生成 */
  model: string
}

export interface PrototypeCodegenHooks {
  /** 发起这一问的代码生成（幂等；模型换了会自动重跑） */
  startQuestion: (ref: CaseRef, qi: number, model: { name: string; desc?: string }) => void
  /** 用会话里已有的产物接回这一问（刷新后免重跑） */
  reuseQuestion: (ref: CaseRef, qi: number, model: { name: string; desc?: string }) => Promise<boolean>
  /** idle | running | done | failed */
  questionStatus: (qi: number) => string
  questionProgressText: (qi: number) => string
  questionFailureText: (qi: number) => string
  /** 用户点「跳过等待」→ 这一问按失败处理，回落内置剧本 */
  skipQuestion: (qi: number) => void
  /** 这一问的真实代码包；未就绪返回 null */
  takeQuestion: (qi: number) => PrototypeCodePackage | null
  /** 清掉结果（重新开始一道题时） */
  reset: () => void

  /* ── 实时区（2026-10-13：等待气泡里的进展明细）─────────────────────────────
     与动画浮窗同一套口径（也不走全局「当前容器」），只多一个**问题号**：
     代码通道是**按问**的，一问一条等待气泡，通道名得跟着问走，否则第二问的
     事件会画进第一问那条（即将被 remove 的）气泡里。 */

  /** 把第 qi 问等待气泡的实时区容器交进来（传 null 卸载） */
  liveAttach: (qi: number, el: HTMLElement | null) => void
  /** 增量刷新实时区（可见性由 CSS `:empty` 管，不在这里切 display） */
  liveRender: (el?: HTMLElement | null) => void
}

let codegenHooks: PrototypeCodegenHooks | null = null

export function setPrototypeCodegenHooks(hooks: PrototypeCodegenHooks | null): void {
  codegenHooks = hooks
}

export function getCodegenHooks(): PrototypeCodegenHooks | null {
  return codegenHooks
}

/** 需要登录才能进入的视图（其余视图任何人可看） */
const PROTECTED_VIEWS = new Set(['mine', 'work'])

let navHooks: PrototypeNavHooks | null = null

export function setPrototypeNavHooks(hooks: PrototypeNavHooks | null): void {
  navHooks = hooks
}

/**
 * 进入某视图前的闸门。返回 false 表示已拦截（调用方必须中止切换）。
 * 钩子没注册时一律放行。
 */
export function guardView(view: string): boolean {
  if (!navHooks) return true
  if (!PROTECTED_VIEWS.has(view)) return true
  if (navHooks.isLogged()) return true
  navHooks.requireLogin(view)
  return false
}

/** 视图切好后同步 URL；钩子没注册时什么也不做 */
export function syncViewRoute(view: string): void {
  navHooks?.syncRoute(view)
  navHooks?.syncNav?.()
}

/* ── 工作台任务仓的钩子 ─────────────────────────────────────────────────── */

let workHooks: PrototypeWorkHooks | null = null

export function setPrototypeWorkHooks(hooks: PrototypeWorkHooks | null): void {
  workHooks = hooks
}

export function getWorkHooks(): PrototypeWorkHooks | null {
  return workHooks
}

/**
 * 这道题的沙箱环境就绪没有。
 * 未注册时返回 false —— 按钮不出现，比出现一个点了没反应的按钮好。
 */
export function sandboxReady(): boolean {
  return navHooks?.sandboxReady?.() ?? false
}

/** 把第 qn 问的代码交给真沙箱；未注册时什么也不做 */
export function gotoSandboxRun(qn: number): void {
  navHooks?.gotoSandboxRun?.(qn)
}

/** 跳到沙箱产物浏览；未注册时什么也不做 */
export function gotoSandboxArtifacts(sandboxId: string): void {
  navHooks?.gotoSandboxArtifacts?.(sandboxId)
}

export const PROTECTED_VIEW_LIST = [...PROTECTED_VIEWS]
