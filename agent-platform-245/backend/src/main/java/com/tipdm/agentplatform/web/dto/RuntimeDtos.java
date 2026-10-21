package com.tipdm.agentplatform.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 运行时交互 DTO（精简版）。
 *
 * <p>SSE 流式对话、HITL 恢复、表单提交等运行时功能已迁移至 AG-UI 标准协议，
 * 通过框架自动暴露的 POST /agui/run/{agentId} 端点提供。
 * 本文件仅保留会话管理所需的 SessionInfo。</p>
 */
public final class RuntimeDtos {

    private RuntimeDtos() {
    }

    /**
     * 会话列表项。
     *
     * @param archivedAt 归档时间；非 null 表示已归档（状态与产物目录均保留，可恢复）
     */
    public record SessionInfo(
            String sessionId,
            String summary,
            Instant updatedAt,
            Instant archivedAt,
            String title) {
    }

    /**
     * 会话产物树节点。
     *
     * <p>{@code path} 为会话产物目录内的相对路径（POSIX 分隔符），回传给服务端读取
     * 内容时原样使用；{@code size} 仅文件有值，目录为 null；{@code children} 仅目录
     * 有值（可能为 null，表示未递归）。</p>
     */
    public record ArtifactNode(
            String name,
            String path,
            String type,
            Long size,
            Long updatedAt,
            List<ArtifactNode> children) {
    }

    /**
     * 产物文件内容。{@code content} 为 null 表示无法以文本方式呈现
     * （二进制 / 超大文件），此时 {@code reason} 给出原因。
     */
    public record ArtifactContent(
            String path,
            String content,
            String reason) {
    }

    /**
     * 覆盖写产物文本的请求体。
     *
     * <p>{@code content} 是**完整文件内容**（不是补丁）：服务端整文件覆盖写。
     * 之所以不做增删改语义，是因为产物是模型自述的事实源，保持「读什么写什么、
     * 写什么读什么」最小惊讶，避免局部更新把模型写的结构改坏。</p>
     */
    public record ArtifactContentUpdate(String content) {
    }

    /**
     * 一条子智能体汇报（框架 spawn registry + 子会话 {@code 【TASK_RESULT】} 的合成视图）。
     *
     * <p>平台在返回前会核对每个 {@code artifacts} 条目是否真实存在，把不存在的汇总进
     * {@code missing}——「模型说写了但文件不在」由此在会话视图里直接可见。</p>
     *
     * @param sessionId 子会话 ID（{@code sub-…}），同时是产物面板里的目录名
     * @param agentId   子智能体名（如 {@code 目录生成智能体}）
     * @param label     派发时给的标签（可空）
     * @param status    COMPLETED / PARTIAL / BLOCKED / FAILED；{@code null} = 该子会话还没产出汇报
     * @param summary   子智能体的一句话完成说明
     * @param artifacts 本次产出（含存在性与大小）
     * @param missing   缺失产物（平台核对结果 + 子智能体自报的 missing_artifacts）
     * @param updatedAt 子会话目录最后修改时间
     */
    public record SubagentReport(
            String sessionId,
            String agentId,
            String label,
            String status,
            String stage,
            String summary,
            String nextAction,
            boolean resumable,
            List<String> completed,
            List<String> inProgress,
            List<String> remaining,
            List<SubagentArtifact> artifacts,
            List<String> missing,
            Long updatedAt) {
    }

    /**
     * 子智能体汇报里的一个产物条目。
     *
     * @param path        汇报里登记的原始路径（{@code <会话目录>/<相对路径>}），可直接用于产物内容接口
     * @param relativePath 子会话内的相对路径
     * @param exists      平台核对结果：文件是否真的存在
     */
    public record SubagentArtifact(
            String path,
            String relativePath,
            boolean exists,
            Long size,
            Long updatedAt) {
    }

    /**
     * 会话转录（后端持久化上下文）。
     *
     * <p>供前端在「子智能体完成 → 后端唤醒父会话续跑」这类非 AG-UI 推送路径发生后，
     * 增量同步缺失段落：AG-UI 的 POST /agui/run SSE 只推送前端自己发起的 run，
     * 唤醒续跑产生的事件仅在服务端（Studio 可见），前端通过本接口拉取持久化上下文
     * 补齐转录，避免「前端停在等待子任务、后端已推进到下一阶段」的状态不一致。</p>
     *
     * <p>{@code content} 中每个块与前端 AG-UI Msg 的 ContentBlock 形状对齐：</p>
     * <ul>
     *   <li>text：{type, id, text, createdAt}</li>
     *   <li>thinking：{type, id, thinking, createdAt}</li>
     *   <li>tool_call：{type, id, name, input(JSON 字符串), state, createdAt}</li>
     *   <li>tool_result：{type, id, name, output(文本), state, createdAt}</li>
     * </ul>
     */
    public record SessionTranscriptResponse(
            String sessionId,
            List<SessionTranscriptResponse.Message> messages,
            /** 服务端全量转录条数（分页/增量游标判定用）。 */
            int total,
            /**
             * 请求游标失效（{@code after}/{@code before} 指定的 id 在服务端当前
             * 全量列表中不存在——典型原因是 Compaction 重写了历史）。true 时
             * 客户端应丢弃游标、重新全量恢复。
             */
            boolean stale,
            /**
             * 会话 run 在跑标志（与 {@code GET /run-status} 同源，均为
             * SessionActivityTracker 活跃计数）。搭车在转录响应里是因为：
             * run 启动没有任何推送信号（通知流只在 run 结束时推 run-finished），
             * 前端 idle 期又只轮询本接口——服务端后台 run（wakeup 续跑 /
             * 其他标签页发起）启动后，这里是前端发现「在跑」的唯一常态通道，
             * 否则整个 run 存续期前端都误判 idle、无 spinner。前端发现 true
             * 且本地空闲时进入后台跟随态（5s run-status 轮询收尾）。
             */
            boolean running) {

        public record Message(
                String id,
                String role,
                String name,
                String createdAt,
                List<Map<String, Object>> content,
                Map<String, Object> metadata) {
        }
    }

    /**
     * 会话「用户轮次索引」——对话区左侧导航条（minimap）的锚点源。
     *
     * <p>导航条的锚点必须覆盖<b>整个会话</b>，而转录分批加载后前端手上只有尾部
     * 若干条消息：长会话里可用的轮次会随窗口一起缩小，少到一定程度整条导航条
     * 就不显示了。故锚点改由服务端单独下发——只回 {@code id} 与两端摘要，
     * 几十条轮次也只有几 KB，与加载窗口解耦。</p>
     *
     * <p>{@code id} 必须与 {@code /transcript} 返回的同一条消息 id 完全一致
     * （服务端同源产出），前端据此在 DOM 里定位 {@code [data-message-id]}；
     * 目标尚未加载时前端先按页向前翻再滚动。</p>
     *
     * <p>{@code userText}/{@code replyText} 是<b>截断后的摘要</b>，只用于悬停
     * 提示，不是完整正文。</p>
     */
    public record SessionTurnsResponse(
            String sessionId,
            List<SessionTurnsResponse.Turn> turns) {

        /**
         * 一个用户轮次锚点。
         *
         * @param id        该用户消息的 id（与 {@code /transcript} 同源）
         * @param userText  用户发言摘要（正文已还原附件引用、剥离模型专用提示）
         * @param replyText 该轮次首条可见助手正文的摘要；无正文时为空串
         */
        public record Turn(String id, String userText, String replyText) {
        }
    }

    /**
     * 会话进度台账视图（L3 停滞看护的可读快照，供前端/脚本渲染进度）。
     *
     * <p>{@code nextAction}：CONTINUE / WAIT_HITL / DONE / IDLE / UNKNOWN；
     * {@code stallNow} 为服务端按「CONTINUE + idle 超阈值」算好的疑似停滞标记；
     * {@code hitlStuck} 表示存在无法应答的权限确认残留（ASKING 且非表单/闸门类工具，
     * 确认卡片浮到前端但 resume 送达不了后台任务）——前端据此显示"恢复会话"入口。</p>
     */
    public record SessionProgressResponse(
            String sessionId,
            String phase,
            String nextAction,
            String progressLine,
            String lastText,
            Instant lastActiveAt,
            Instant updatedAt,
            boolean stallNow,
            boolean hitlStuck) {
    }

    /**
     * 会话 run 在跑状态（轻量探针）。
     *
     * <p>{@code running}=服务端仍有 run 在执行（AG-UI 用户请求、后台续跑、
     * wakeup 轮均计入）。AG-UI SSE 受超时/连接池约束不能无限长挂，前端连接
     * 断开后 run 转入后台继续推进（内容经 transcript 同步呈现）——前端此前
     * 只靠本地 SSE 生命周期判定「运行中」，断流即误判 idle。本端点以
     * SessionActivityTracker 活跃计数为权威，供前端断流探测与后台跟随轮询。</p>
     */
    public record SessionRunStatusResponse(
            String sessionId,
            boolean running) {
    }
}
