# public/cases/ —— 案例正文内容根（DB 只存路径）

这里是**外包案例正文的唯一存储位置**。数据库 `smyy_outsourced_cases` 只保存**相对本目录的路径**，
正文（指导书 / 可视化演示 / 代码 / 成果 / 复现）全部落在磁盘上，由后端读取后按原字段形状回填给前端。

## 目录约定

```
public/cases/<protoId>/
├── guide/      指导书（可多份，HTML/MD/TXT）
├── animation/  可视化演示，每段一个 HTML（key: global / problem1..problem4）
├── code/       源代码（可多份）
├── result/     成果（可多份）
└── replay/     复现（单份 HTML）
```

DB 里存的就是相对路径，例如 `p3/guide/p3_case_guide_01_v6.html`。

## DB 列语义

| 列 | 存什么 |
|---|---|
| `guide_file_name` / `animation_file_name` / `code_file_name` / `result_file_name` / `replay_file_name` | 该板块**主文件**的相对路径 |
| `guide_files_json` / `code_files_json` / `result_files_json` | `[{"name":原名,"path":相对路径}]` |
| `animation_segments_json` | `[{"key","label","fileName","path"}]` |
| `guide_html` / `animation_html` / `code_text` / `result_html` / `replay_html` | **废弃**，一律为 NULL（内容已落盘） |

> 主文件列与 `*_files_json` 指向的文件内容相同：前者是后端按板块正文单独落盘的主文件
> （单文件兜底口径），后者是管理端上传时按原名落盘的副本。前端渲染用的是 `*_files_json`。

## 后端与前端接线

- 后端 `SmyyCaseContentResolver` 按路径读文件，拼回 `guideHtml` / `animationSegmentsJson` 等
  **内容字段**，前端契约与改造前完全一致。
- 静态路由 `/case-files/**` → 本目录（`WebConfig.addResourceHandlers`，免 JWT）；
  Vite 侧同名代理转发。
- 管理端【案例管理】上传 / 替换板块时，后端把内容写到 `<protoId>/<板块>/`，DB 只更新路径。

## 新增一个案例

在管理端【案例管理】新建即可：后端会按 `p<编号>/<板块>/<文件名>` 落盘并写路径，
无需手工在本目录建文件。

## 已废弃

`p1/anims/`、`p2/anims/`、`p5/anims/`、`p5/anim-shots/`、`p3/anims/`、`p3/anim-shots/`、
`p3/microgrid-replay.html` 是改造前的**内置兜底演示**，仅当库中**没有**该题记录时才会被
`core.ts` 的内置渲染分支使用。8 个案例现在都有库记录，兜底分支不可达，相关文件已删除。
`core.ts` 里指向它们的常量（`ANIM_SHOT` / `ANIM_SHOT_E` / `ANIM_PREFIX_CUR` 等）保留未动。
