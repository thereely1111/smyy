# Stable Development Decisions

This file records durable decisions that affect how coding agents work on this
repository. For architecture decisions with trade-offs, create a separate ADR
under `adr/`.

## 2026-10-19: Introduce agent harness

**Decision:** Use `docs/agent-harness/` as the control plane for coding agents
working on this repository.

**Reason:** Coding agents need stable task classification, context selection,
gates, verification, and memory across sessions.

**Consequences:** Non-trivial development tasks should follow the task kinds and
gates documented here.

## 2026-10-20: 外包案例正文外置，DB 只存路径

**Decision:** `smyy_outsourced_cases` 不再保存案例正文。正文落盘到
`public/cases/<protoId>/<板块>/`，DB 的路径列（`*_file_name` /
`*_files_json` / `animation_segments_json` / 新增 `replay_file_name`）只存**相对路径**。
`/case-files/**` 由后端静态映射免鉴权暴露（同 `/covers` 口径）。
`SmyyCaseContentResolver` 按路径读文件、拼回**原有内联字段形状**返回，
前端 `core.ts` 渲染器与 DTO 契约零改动。

**Reason:** 正文混在库里难版本化，且让 `public/cases/` 形同虚设；单案例正文
0.6–1.8 MB，字段上限被迫卡到 2 MB / 8 MB。

**Consequences:**
- **内容列映射暂不删除**（与原计划不同）。<内网地址> 是**多机共享库**，
  切片期只有 p3 迁完；删映射会让 p1/p2/p4~p9 共 7 个案例详情页立刻变空。
  Resolver 采用「路径优先 / 内联兜底」，全量迁移并与其他连库实例协调后才可删列。
- `*FilesJson` 条目的 `"size"` 字段由 Resolver 按 UTF-8 字节数还原。
  管理端「编辑案例」用 `formatBytes(file.size)` 显示文件大小，丢了会显示 `NaN B`。
- 管理端上传走同一套落盘逻辑（`upsert` / `replaceSection`），新增案例自动
  写回 `public/cases/<protoId>/<板块>/` 并在 DB 写路径。
- 生产发布必须把 `public/cases` 纳入产物，或改用后端独立内容根
  （`SMYY_CASE_CONTENT_ROOT` / `smyy.case-content-root`）。

## 2026-10-20: 全量推广 —— 7 个案例迁完，内联列已全空

**Decision:** p1/p2/p4/p5/p6/p7/p9 通过**真实管理端接口** `POST /api/smyy/admin/cases`
重放迁移（body 取改造前 API 快照 `.artifacts/cases-list-after.json`），不另写镜像写库脚本。
迁移由生产代码路径完成，`upsert` 的落盘布局天然等于未来上传的布局。

**Reason:** 手写脚本容易出现与生产逻辑不一致的落盘/命名；走接口能保证「迁移 = 一次上传」。

**Consequences:**
- `version` 因 `upsert` 语义各 +1（p1 12→13、p2 5→6、p4 7→10、p5 4→7、p6 2→3、p7 3→4、p9 2→3），
  文件名随之带新 `_v<n>`；p4/p5 的 `_v10`/`_v7` 偏大是因为此前多次编辑，不是本次多迁。
- p5 的 `slug` 由 `case-p5` 变成 `sem-ad-optimization`：`slug()` 对 p5 有固定可读别名
  （与 p3 同理），重放后按生产规则重算。slug 不参与渲染，仅影响文件名。
- 每个板块会落在两份文件：后端按板块正文写的主文件（`<protoId>_<slug>_<板块>_v<n>.*`）
  与 `*_files_json` 里按上传原名写的副本。这是 `upsert` 既有行为（管理端上传同样如此），
  不是迁移引入的重复。
- `*FilesJson` / `animationSegmentsJson` 保留**上传时的原始文件名**（含中文），
  以换取 resolver 拼回后的 JSON 与改造前**逐字节一致**。
- 旧内置兜底资源（`p1/anims`、`p2/anims`、`p5/anims`、`p5/anim-shots`、
  `p3/anims`、`p3/anim-shots`、`p3/microgrid-replay.html`）在磁盘上删除。
  `core.ts` 里指向它们的常量与兜底分支**保留不动**（8 个案例均有库记录，分支不可达）。
  若将来某个案例被删库，其内置演示会失效 —— 登记为已知限制。
- 内容列（`guide_html` / `animation_html` / `code_text` / `result_html` / `replay_html`）
  现在 8 行全为 NULL，但**实体映射仍保留**（多机共享库，删列需与其他连库实例协调后单独执行）。



## 2026-10-21: Reuse platform model and OAuth settings in the smyy backend

**Decision:** At the user's explicit request, reuse the model name, relay URL, model/read-image credential configuration and OAuth service/client settings from the local E:/agent-platform-java copy. Port only the default ModelCreationContext forwarding logic into the smyy AgentAssemblyService.

**Consequences:** The smyy backend continues using agent_platform, its existing JWT/encryption keys and case functionality. Deployment settings for the extra instance remain external in config/application.yml. Do not replace the entire YAML or the authentication implementation. Real model credentials and browser callback/login require deployment-time verification; secret values are not recorded here.
