package com.tipdm.agentplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 未提交表单中断的内存登记表（M11）。
 *
 * <p>背景：ui_form_advanced 表单挂起后，AG-UI 框架把 pending interrupt
 * （id 形如 {@code {replyId}:{toolCallId}}）存在 <b>进程内存</b> 的
 * {@code AguiResumeCoordinator} 里。前端刷新后 replyId 丢失（replyId 不在
 * transcript / state store 中——那条 assistant Msg 的 id 是另一套 UUID），
 * 无法构造 resume 继续提交，会话从此卡死。</p>
 *
 * <p>本登记表由前端在收到 RUN_FINISHED(interrupt) 时上报（此时表单已渲染、
 * interruptId 在手上），供刷新/回访后按会话查回，走标准 AG-UI resume 续填。
 * 与框架 pending 同为进程内存：后端重启后两者一并消失——此时 AG-UI 不再锁
 * 会话，前端 resume 会报 "does not match any open interrupt"，降级为
 * user-message 新 run 即可恢复，语义自洽。</p>
 */
@Component
public class PendingFormInterruptStore {

    private static final Logger log = LoggerFactory.getLogger(PendingFormInterruptStore.class);

    /** agentId|userId|sessionId → toolCallId → 中断明细。 */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, PendingFormInterrupt>> byKey =
            new ConcurrentHashMap<>();

    /** 一条待续填表单的中断快照（字段名与前端 PlatformFormInterrupt 对齐）。 */
    public record PendingFormInterrupt(
            String interruptId,
            String replyId,
            String toolCallId,
            String toolName,
            String toolInput,
            String createdAt) {
    }

    private static String key(String agentId, String userId, String sessionId) {
        return agentId + "|" + userId + "|" + sessionId;
    }

    /** 上报/更新一条（按 toolCallId 覆盖）。 */
    public void put(String agentId, String userId, String sessionId, PendingFormInterrupt interrupt) {
        byKey.computeIfAbsent(key(agentId, userId, sessionId), k -> new ConcurrentHashMap<>())
                .put(interrupt.toolCallId(), interrupt);
    }

    /** 列出某会话全部待续填表单中断（无则空表）。 */
    public List<PendingFormInterrupt> list(String agentId, String userId, String sessionId) {
        Map<String, PendingFormInterrupt> inner = byKey.get(key(agentId, userId, sessionId));
        if (inner == null || inner.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(inner.values());
    }

    /**
     * 列出某会话全部待续填表单中断（不区分上报用户）。
     *
     * <p>用途：判定「会话是否存在本进程内仍然存活、可提交的闸门表单」。上报方（前端）
     * 与消费者（wakeup 守卫）各自解析 userId 的路径不同，按三元组精确命中可能漏判，
     * 从而把活闸门误当陈旧闸门放行；按 agent+session 归并消除该偏差。</p>
     */
    public List<PendingFormInterrupt> listBySession(String agentId, String sessionId) {
        if (agentId == null || sessionId == null) {
            return List.of();
        }
        String prefix = agentId + "|";
        String suffix = "|" + sessionId;
        List<PendingFormInterrupt> out = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, PendingFormInterrupt>> e : byKey.entrySet()) {
            String k = e.getKey();
            if (k.startsWith(prefix) && k.endsWith(suffix) && !e.getValue().isEmpty()) {
                out.addAll(e.getValue().values());
            }
        }
        return out;
    }

    /** 某会话运行正常收尾后清空待续填登记（run success 已消费中断）。 */
    public void clear(String agentId, String userId, String sessionId) {
        ConcurrentHashMap<String, PendingFormInterrupt> removed =
                byKey.remove(key(agentId, userId, sessionId));
        if (removed != null && !removed.isEmpty()) {
            log.debug("Pending form interrupts cleared: agent={} session={} count={}",
                    agentId, sessionId, removed.size());
        }
    }

    /** 全量枚举条目（含三元组归属），供超时巡检等全局扫描者使用。 */
    public List<StoredInterrupt> listAll() {
        List<StoredInterrupt> out = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, PendingFormInterrupt>> e : byKey.entrySet()) {
            String[] parts = e.getKey().split("\\|", 3);
            if (parts.length != 3) {
                continue;
            }
            for (PendingFormInterrupt interrupt : e.getValue().values()) {
                out.add(new StoredInterrupt(parts[0], parts[1], parts[2], interrupt));
            }
        }
        return out;
    }

    /** 移除单条登记（按 toolCallId）；返回是否确有删除。 */
    public boolean remove(String agentId, String userId, String sessionId, String toolCallId) {
        ConcurrentHashMap<String, PendingFormInterrupt> inner = byKey.get(key(agentId, userId, sessionId));
        if (inner == null) {
            return false;
        }
        return inner.remove(toolCallId) != null;
    }

    /** 一条登记的全量归属视图（listAll 用）。 */
    public record StoredInterrupt(
            String agentId, String userId, String sessionId, PendingFormInterrupt interrupt) {
    }
}
