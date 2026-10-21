# 案例内容外置：DB 只存路径，文件回项目案例文件夹

## Context（为什么做）

当前 `smyy_outsourced_cases` 表把**整份案例正文当大字段存**：`guide_html` / `animation_html` /
`animation_segments_json` / `code_text` / `result_html` / `replay_html`（单案例动辄 0.6–1.8 MB，
字段上限被后端卡到 2 MB / 8 MB）。这是"不合规范"的根因：内容混在库里，既难版本化，也让
`public/cases/` 下的案例文件形同虚设。

实测证据（2026-10-20，库 <内网地址>/smyy）：

- `smyy_outsourced_cases` 仍是**扁平内容列**（拆分脚本 `docs/sql/20261020_split_smyy_outsourced_cases.sql`
  并未真正落库，只有 `smyy_outsourced_cases_entries` 表被建出来）。
- 库里有 **8 条**案例：p1、p2、p3、p4、p5、p6、p7、p9（**p8 缺**），内容列全部非空。
- `public/hero-assets/*.png`（首页封面兜底）、`public/python/` + `public/python-worker.js`
  （Python 沙箱运行时）**仍在使用**；`public/cases/p*/anims|anim-shots/*` 只在"库里没有该题"时兜底。

目标：**DB 只存相对路径，正文落到项目案例文件夹 `public/cases/<题号>/`，前端渲染效果与现在逐字节一致**，
且以后新增案例由管理端上传 → 后端落盘到案例文件夹 + DB 写路径。

## 已确认决策（用户拍板）

1. 文件放 `public/cases/<题号>/`，后端加静态路由暴露为 URL（仿 `/covers/**` 思路）。
2. DB **沿用现有列**，把值从"内联全文/文件名"改成**相对路径**；大字段内容列最终删除。
3. **先做垂直切片**（p3 一个案例端到端跑通）再全量推广。
4. `public/cases` 尽量清空，只留新结构（`hero-assets`、`python` 保留）。
5. 前端取数方式：**后端读文件后仍按原字段返回**（`guideHtml`/`animationHtml`/`animationSegmentsJson`/
   `codeText`/`resultHtml`/`replayHtml`/`*FilesJson` 内容照旧内联），**core.ts 同步渲染器不动** →
   "效果一样"风险最低。
6. 文件目录：**按板块分子目录**。

## 目标数据模型

### 目录约定（content root = `public/cases`）

```
public/cases/<protoId>/
  guide/      <name>.html|md|txt     # 指导书，可多份
  animation/  <name>.html            # 每段一个（key: global / problem1 / …）
  code/       <name>.py|txt|…        # 源码，可多份
  result/     <name>.html|md|txt     # 成果，可多份
  replay/     <name>.html            # 复现，单份
```

DB 里存的值 = 相对 content root 的路径，如 `p3/guide/p3_case_guide_01_v6.html`。

### 表 `smyy_outsourced_cases` 列语义变化

| 列 | 变化 |
|---|---|
| `guide_files_json` / `code_files_json` / `result_files_json` | 由"内联 [{name,text}]"改为 **[{name,path}]** |
| `animation_segments_json` | 由"内联 [{key,label,fileName,html}]"改为 **[{key,label,fileName,path}]** |
| `replay_file_name`（**新增**） | 复现文件相对路径（取代 `replay_html`） |
| `guide_file_name` / `animation_file_name` / `code_file_name` / `result_file_name` | 保留，存**主文件相对路径**（首文件/兼容兜底） |
| `guide_html` / `animation_html` / `code_text` / `result_html` / `replay_html` | **删除**（内容已落盘） |

> DTO 契约不变：`CaseView` 仍返回 `guideHtml` 等**内容字段**（后端按路径读文件回填），
> 同时返回路径字段。前端 `api/outsourcedCases.ts` / `stores` / `core.ts` 消费方式零改动。

## 垂直切片实施（p3 一个案例）

### 阶段 A：内容导出（可独立回滚）

1. 写一个一次性导出脚本（放 `scripts/`，不提交长期维护）：读 p3 行的 6 个内容字段 +
   3 个 `*_files_json`，按上面目录约定落盘到 `public/cases/p3/**`，并记录每个文件
   **原始 name → 新相对路径** 的映射。
2. **字节级回环校验**：导出后把文件读回，与库里原值做 `String.equals` 比对（UTF-8，无换行转译），
   全等才继续。
3. 生成 p3 的路径清单（供阶段 B 写库）。

### 阶段 B：后端改造（`agent-platform-java` 仓库内副本）

> 注意：8080 在跑的 classpath 是 `D:\Desktop\smyy\smyy\agent-platform-java\backend\target\classes`
> （**仓库内副本**）。`D:\Desktop\agent-platform-java` 是另一条分叉，没有 smyy 案例迁移，别改错。

1. **配置**：新增 `smyy.case-content-root`（默认 `<仓库>/public/cases`，可被环境变量覆盖）。
2. **实体** `SmyyOutsourcedCase`：删除内容列映射（`guideHtml`/`animationHtml`/`codeText`/`resultHtml`/
   `replayHtml` 及对应 getter/setter），新增 `replayFileName`；`Hibernate ddl-auto=validate` 只校验
   已映射字段，**暂不删库列也能启动**。
3. **解析服务**（新）：`SmyyCaseContentResolver`——按实体里的相对路径读文件，拼回
   原 JSON 形状（`{name,text}` / `{key,label,fileName,html}`），供 DTO 使用；带**按 (caseId,version)
   的内存缓存**，避免 `/api/smyy/cases` 列表每次都读几 MB 文件。
4. **DTO** `SmyyOutsourcedCaseController.CaseView`：`from(entity, resolver)`，内容字段由 resolver
   回填，路径字段照原样输出。
5. **上传** `SmyyOutsourcedCaseService.upsert` / `replaceSection`：把上传进来的内容**落盘**到
   `public/cases/<protoId>/<板块>/<文件名>`，DB 只写相对路径；`fileName(...)` 命名规则沿用。
6. **静态路由**：`WebConfig` 增加 `addResourceHandlers`，把 `/case-files/**` 映射到
   `file:<case-content-root>/`（放 `/api` 之外，免 JWT，同现有 `/covers` 口径）；
   `vite.config.ts` 的 `proxyOptions` 加 `/case-files`。
7. 放弃 `docs/sql/20261020_split_smyy_outsourced_cases.sql` 的 entries 拆分路线（与已定模型冲突），
   在脚本头加一行"已被路径方案取代，勿执行"，并以新脚本替换。

### 阶段 C：落库 p3 路径 + 清库内容列（切片阶段先"不写不读"）

- 切片阶段**先不删列**：只把 p3 行的路径列写成阶段 A 的清单，内容列留空不再读。
- **删列是高风险步骤**（该库是**多机共享**：<内网地址>/165/214/140 等后端同时连着，删列会让
  跑旧代码的实例启动失败）。因此删列放到最后、且必须与其他连库实例协调后单独执行
  （排在 `docs/sql/` 手工脚本，不走 Flyway——该库已有 V75–V78 外来版本，Flyway 校验历史不稳定）。

### 阶段 D：前端

- **不改** `src/legacy/prototype/core.ts` 的渲染逻辑（Design 1 的核心收益）。
- `src/api/outsourcedCases.ts` 类型补 `replayFileName` 等路径字段（可选）。
- `src/views/CaseManagementView.vue`：回填逻辑改为从路径字段读（`resetForm` 里 `guideFilesJson`
  等现在期望 `{name,text}`；后端 DTO 仍返回 `text`，故**此文件也可不改**）。
- 清理 `src/data/outsourcedCaseSeeds.ts`：`buildSemFileSeed()` 现在 `fetch('/cases/p5/anims/*')`，
  新结构下路径变了；确认它没有生产调用点后可一并收敛（探索结论：仅 `CaseManagementView`
  引用了它的**类型**，`outsourcedCaseSeeds`/`buildSemFileSeed` 无实际调用）。

### 阶段 E：清理 `public/cases`

- p3 跑通后，删掉 `public/cases/p3/**` 的旧结构（`anims/`、`anim-shots/`、`microgrid-replay.html`），
  只留新结构。
- **保留** `public/hero-assets/`、`public/python/`、`public/python-worker.js`。

## 验收标准（AC）

- **AC1 效果一致**：p3 详情页四个面板（指导书 / 可视化演示 / 代码 / 成果）+「在平台复现」，
  改造前 vs 改造后逐项比对一致（正文 DOM 文本、演示 5 段可切换、复现代码可运行）。
- **AC2 数据库只存路径**：p3 行内容列不再有正文；路径列指向的文件**真实存在**且内容与原值**逐字节相等**。
- **AC3 上传落盘**：管理端对 p3 执行一次"编辑/替换"，新内容被写入 `public/cases/p3/<板块>/`，
  DB 只更新路径；页面刷新后效果与上传内容一致。
- **AC4 兜底不白屏**：某路径文件缺失时，前端回落到现有空态（`missingCard`），不白屏、不抛错。
- **AC5 旧文件清理**：p3 范围内 `public/cases` 无孤儿旧文件。

## 验证方法

1. 阶段 A 后：回环 `String.equals` 校验全等（脚本输出比对结果）。
2. 阶段 B/C 后：重启后端，`curl http://localhost:8080/actuator/info` 确认连库与内容根；
   `curl /api/smyy/cases/p3` 对比改造前后响应体中内容字段是否一致。
3. 前端：起 Vite（5181），进 p3 详情页人工核对四面板 + 演示 + 复现；必要时取改造前后
   DOM 文本/截图 diff。
4. AC3：走一遍管理端替换流程后重复步骤 2–3。

## 风险与回滚

- **多机共享库**：删列会打挂跑旧代码的实例 → 切片阶段不删列，删列单独协调后再做。
- **导出/回写保真**：必须 UTF-8 字节级回环校验，不做任何换行/编码转译。
- **回滚**：改动全在 git 内可回退；DB 内容在阶段 A 已完整落盘备份，删除前先导出。
- **性能**：`/api/smyy/cases` 列表会按需读文件 → 用 resolver 缓存兜住；若仍慢，再评估列表只回路径。

## 全量推广（2026-10-20 已完成）

对 p1、p2、p4、p5、p6、p7、p9 重复阶段 A/C/E；后端与前端改造一次到位，无需再改。

实施方式：**不写镜像脚本**，改用真实管理端接口 `POST /api/smyy/admin/cases` 重放，
body 直接取自改造前的 API 快照 `.artifacts/cases-list-after.json`（含 7 个案例的全部内联正文）。
迁移产物 = 一次生产上传的产物。

验证结果：

| 项 | 结果 |
|---|---|
| 内容字段（`guideHtml`/`animationHtml`/`animationSegmentsJson`/`codeText`/`resultHtml`/`replayHtml`/3 个 `*FilesJson`） | 7 个案例全部**逐字节一致**（重启后端冷缓存后再次 GET 复核，仍一致） |
| 路径字段 | 变为 `<protoId>/<板块>/<文件名>` 相对路径；`replayFileName` 对 p1/p6/p7/p9 新增 |
| DB 内联列 | 8 行全部 NULL |
| DB 路径可解析 | 101 条路径（去重后）全部命中真实文件，0 缺失、0 空文件 |
| 浏览器复核 | p1（四面板 + 5 段演示可切换 + 复现区）、p2、p9（四面板齐全）均正常，无 console 报错、无 500/404 |
| 旧资产 | `p1/anims`、`p2/anims`、`p5/anims`、`p5/anim-shots` 已删；`p3/anims`、`p3/anim-shots`、`p3/microgrid-replay.html` 在切片期已删 |

副作用（已确认并记录）：`version` 各 +1、p5 的 `slug` 按生产规则重算为 `sem-ad-optimization`、
每个板块会同时存在「主文件」与「`*_files_json` 原名副本」两份（`upsert` 既有行为）。
详见 `docs/agent-harness/DECISIONS.md`。

p8 无 DB 记录，暂不迁移（其内置兜底演示已随删除失效，作为已知限制登记待补 DB 记录）。

## 不做 / 已知限制

- 不动 `hero-assets`、`python` 沙箱运行时。
- 不引入对象存储；内容根就是项目 `public/cases`（生产需把该目录纳入发布，或改用后端独立内容根）。
- p8（及可能的 p10/p11）暂无 DB 记录，清空后失去内置演示，需后续补库。
