# 规范文档

本目录存放平台规范与对接文档，随开发阶段补充。

## 核心文档

| 文档 | 说明 |
|---|---|
| [openapi.yaml](openapi.yaml) | **OpenAPI 3.1 接口规范**（唯一权威接口文档）：全部 HTTP 端点、鉴权约定、错误码、分页、请求/响应结构 |
| [api.md](api.md) | 接口文档的 **Markdown 阅读版**（由 openapi.yaml 渲染生成 + curl 示例；修改接口先改 openapi.yaml 再重新渲染） |
| [vertical-agent-dev-guide.md](vertical-agent-dev-guide.md) | **垂类智能体技术对接文档**（面向开发者的对接指南）：开发流程、AGENTS.md/子智能体/产物/HITL 约定、AG-UI 事件协议与自定义 UI、常见坑与接入 Checklist |
| [workspace-layout.md](workspace-layout.md) | 工作区两层布局（定义区 / 运行区）与路径契约 |
| [form-config-contract.md](form-config-contract.md) | 配置驱动表单（form）的契约 |
| [agent-constraint-backstops.md](agent-constraint-backstops.md) | 平台硬约束与代码兜底的对照表 |
| [knowledge-base-module-design.md](knowledge-base-module-design.md) | 知识库模块设计 |
| [realtime-notification-design.md](realtime-notification-design.md) | 实时通知通道设计（通知面 + 水位线）：SSE 通知信号 + event_sequence 水位线补差，前端转录同步的兜底轮询为 30s 低频 |
| [智能体平台资源权限清单.xlsx](智能体平台资源权限清单.xlsx) | **资源权限码清单**：菜单/操作的权限字符串（如 `space:agents`、`agent:menu`）与页面路径、图标对照，前端 `PermissionGuard` 与后端权限校验的配置依据 |

> 约定：接口细节一律以 `openapi.yaml` 为准；开发流程与机制约定以 `vertical-agent-dev-guide.md` 为准。