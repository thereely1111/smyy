# 实时通知通道设计 — 通知面 + 水位线

> 状态：已实施
> 归属：本仓库 Java（Spring）后端 + React 前端
> 关联：`agui_events` 事件落库（V37）、`transcriptSync.ts` 转录同步、AG-UI run 流（POST /agui/run SSE）

---

## 0. 通道结构

| 维度 | 机制 |
|---|---|
| **通知面** | `GET /api/notify/stream`（user 级 SSE 信号）：run 结束 / wakeup / interrupt 时发布 `{sessionId, runId, eventSequence}` 游标，前端 `useNotifyStream` 常驻消费 |
| **数据面** | 前端发起的 run 走 POST /agui/run 的 SSE 事件流；服务端自发的 run（wakeup 续跑、看门狗补投递）无流、事件只落库，由通知信号触发 `syncOnce()` 拉取 |
| **兜底** | `transcriptSync.ts` 低频轮询 `GET …/transcript`（默认 30s，通知流健康时降频至 120s），覆盖通知丢失 |
| **水位线** | `agui_events` 表（V37）：`(user_id, session_id, event_sequence)`，序列号跨 run 连续、重启按 max 接续；`GET /api/agui-events?afterSequence=` 支持增量拉取 |

**已知边界（设计兼容）**：`processorErrorEvents` / 断连收尾 RunFinished 不经 AguiEventLogEnricher；wakeup run 无 SSE 流不覆盖。→ 通知发布点**不**依赖 enricher 拦截，在 service 层显式发布。

---

## 1. 目标与非目标

**设计目标**
1. 页面打开时，服务端自发 run 的产出**秒级**出现在当前会话（或会话树角标，角标未实施）。
2. 前端轮询仅作为**通道断开时的兜底**（低频）。
3. live（前端发起 run）、catch-up（重连/错过）、replay（刷新页面）三条路径收敛为同一套「通知 + 水位线补差」逻辑。

**非目标**
- 不改变 per-run SSE 的 AG-UI 协议流本身（协议不动）。
- 不做通知的内容投递——通知只传**信号**（几字节），数据一律由前端拉取。
- 不引入 WebSocket——复用现有 SSE 基础设施与鉴权链路。
- 不替代 transcript（模型视角检查点）与 agui_events（观察者全量记录）任何一方。

---

## 2. 总体架构

```
┌────────────┐  run 结束/唤醒注入   ┌──────────────────────┐
│ 服务端 run  │ ──────────────────→ │ NotifyPublisher       │
│ (任意来源)  │   落库完成后发布     │ (进程内 → 可换 pub/sub)│
└────────────┘                     └──────────┬───────────┘
                                              │ SSE 信号 {sessionId, runId, eventSequence}
                                   ┌──────────▼───────────┐
                                   │ 前端 useNotifyStream  │
                                   │  (user 级常驻连接)     │
                                   └──────────┬───────────┘
                          当前会话: 触发 syncOnce()   其他会话: 树角标
                                              │
                              GET transcript / agui-events?afterSequence=N
```

**核心不变式**：
- **数据面与通知面分离**：通知只带 `{sessionId, runId, eventSequence}` 游标，重数据走既有拉取接口。
- **at-least-once + 前端幂等**：通知允许丢失/重复（SSE 不保交付），丢失由低频兜底轮询覆盖，重复由 `mergeTranscript` 既有去重吸收。
- **transcript 是渲染事实源**：水位线（event_sequence）只用于「要不要同步」的判断与精确补差，渲染仍走 transcript 合并，保证与刷新恢复完全同构。

---

## 3. 后端实现

### 3.1 组件

```
com.tipdm.agentplatform
├── service
│   └── notify
│       ├── SessionNotifyPublisher   // 发布门面：publish(userId, sessionId, runId, seq, reason)
│       └── NotifyStreamRegistry     // SseEmitter 注册表（userId → emitters，含心跳调度）
└── web
    └── NotifyStreamController       // GET /api/notify/stream (SSE)
```

### 3.2 端点契约

`GET /api/notify/stream`
- 鉴权：复用 `JwtInterceptor`（与 `/api/agui-events` 同规则）；**通道挂 user 而非 session**——wakeup 常唤醒父会话而用户可能正盯着别的会话，user 级通道才能支撑会话树角标。
- 响应：`text/event-stream`，事件形态：

```
event: run-activity
data: {"sessionId":"main-xxx","runId":"run-yyy","eventSequence":1234,"reason":"run-finished|wakeup|interrupt"}

event: ping
data: {"ts":...}          // 30s 心跳，防代理空闲断连
```

- 服务端配置：`X-Accel-Buffering: no`（Nginx 不缓冲）、`async timeout` 放宽、断开即从 Registry 注销。

### 3.3 发布点（三处，全部显式调用，不依赖 enricher）

| 发布点 | 时机 | reason |
|---|---|---|
| `AguiEventPersistenceService` | 每批 flush 成功后，取该 (user, session) 的 max(event_sequence) 发布 | run-finished |
| `WakeupDispatcher` / `InboxWatchdogWakeupService` | inbox push 成功后 | wakeup |
| HITL interrupt 登记 | 闸门/表单产生后 | interrupt |

注意：`AguiEventPersistenceService` 是异步批量写——**必须在 flush 成功回调里发布**，不能在事件入队时发布（否则通知早于数据落库，前端按水位线拉取会拉空）。发布失败只记 WARN，不影响主流程（通知是尽力而为，兜底轮询保证最终一致）。

### 3.4 多实例预留

单实例先用进程内 `SseEmitter` 注册表。横向扩容时把 `publish` 改为经 Redis pub/sub 或 PG `LISTEN/NOTIFY` 扇出到各实例的本地 Registry——因通知只有几字节游标，换传输层不影响任何上下游契约。

---

## 4. 前端实现

### 4.1 已实现

1. **`usePlatformMessages.ts`**：`syncOnce()` 为可显式调用的同步入口；`mergeTranscript` 与全部去重逻辑保持不变。
2. **`hooks/useNotifyStream.ts`**：流式读取 `/api/notify/stream`（复用 streamRun 的鉴权与 SSE 解析方式，EventSource 不带 Authorization header 故不用）。
   - 通知的 `sessionId` == 当前会话 → 立即 `syncOnce()`；
   - 连接断开：依赖浏览器自动重连 + 兜底定时器；**不**在通知层做保序/补发。
3. **`transcriptSync.ts`**：兜底轮询，默认 30s，通知流健康时降频至 120s（run 进行中/有待答中断/页面不可见时跳过）。
4. **全局通知铃（`NotificationBell`）**：消费通知流展示平台级通知。

### 4.2 未实施

- **会话树角标**：未打开的会话收到通知 → 树节点亮点，进入会话即清除。
- **水位线精确补差**：当前 `mergeTranscript` 靠内容签名找「第一个缺失片段」，长会话下每轮合并 O(消息数)。进阶方案：前端本地记录已消费的 `eventSequence` 水位线，通知触发 `GET /api/agui-events?afterSequence=N` 拉差量，仅当差量非空才做 transcript 合并；SSE 重连带 `Last-Event-ID` 同一游标。此为纯优化，不依赖也可正常工作。

---

## 5. 与既有机制的关系（结论备忘）

| 机制 | 职责 | 本设计是否改动 |
|---|---|---|
| `agui_events`（观察者视角，全量 payload） | 审计/回放/水位线事实源 | 不改（发布点读它） |
| transcript / 会话状态（模型视角检查点） | 恢复 agent 上下文、渲染事实源 | 不改（仍是 syncOnce 的数据源） |
| per-run AG-UI SSE | 前端发起 run 的实时协议流 | 不改 |
| transcriptSync 兜底轮询 | 服务端自发 run 的感知兜底 | 30s（通知流健康时 120s） |
| run_lease / RunLeaseReconciler（V38） | 孤儿 run 恢复 | 正交，无交互 |

**为什么不用 transcript 替代 agui_events（或反之）**：事件流是「给人和前端看的黑匣子记录」，transcript 是「给模型续命的记忆快照」——进模型上下文的是转录后的产物（tool 输出截断/摘要、系统注入隐藏、HITL 等待态），从全量事件重放无法无损还原，且 schema 演进会破坏旧会话重放。transcript 可瘦身（大对象存指针指向 agui_events、定期 checkpoint），但恢复语义必须留在 transcript/会话状态层。

---

## 6. 风险与边界

- **后台标签页节流**：浏览器对后台 tab 的 SSE/定时器均可能节流——通知可能延迟到 tab 前台才收到；兜底定时器 + tab visibilitychange 时主动 `syncOnce()` 覆盖。
- **代理层断流**：Nginx/网关需关闭缓冲并放长 idle timeout；30s ping 保活。
- **通知风暴**：run 内每批 flush 都发一次 run-finished？——定义 reason=run-finished 仅在 RunFinished 时发；flush 中间批次可用 reason=progress（前端只刷水位线不触发同步），当前只发 run-finished + wakeup + interrupt 三类。
- **重复通知**：幂等由 mergeTranscript 去重保证；角标亮灭由进入会话动作收敛。
