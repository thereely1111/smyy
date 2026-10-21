package com.tipdm.agentplatform.service.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话实时通知发布门面：把「run 结束 / wakeup 注入 / HITL 中断登记」三类信号
 * 以游标形态推给该会话归属用户的全部通知流。
 *
 * <p><b>只传信号，不传内容</b>：payload 仅 {@code {sessionId, runId, eventSequence, reason}}。
 * 前端收到后触发一次 transcript 同步（当前会话）或亮角标（其他会话），
 * 数据一律由前端拉取。eventSequence 是 {@code agui_events} 的跨 run 连续序号
 * 水位线，wakeup/interrupt 路径不经事件落库时传 {@code null}。</p>
 *
 * <p><b>尽力而为</b>：发布失败（无连接、序列化失败等）只记 DEBUG——通知丢失由
 * 前端 30s 兜底轮询覆盖，绝不影响 run 主流程。anonymous（归属解析失败的落库事件）
 * 直接跳过：前端不按匿名分区渲染，通知无意义。</p>
 *
 * <p><b>时序约束（重要）</b>：发布点必须在数据可拉取之后——事件落库侧必须在
 * flush 成功回调里发布（不能在事件入队时发，否则前端按水位线拉取会拉空）。</p>
 */
@Service
public class SessionNotifyPublisher {

    private static final Logger log = LoggerFactory.getLogger(SessionNotifyPublisher.class);

    public static final String REASON_RUN_FINISHED = "run-finished";
    public static final String REASON_WAKEUP = "wakeup";
    public static final String REASON_INTERRUPT = "interrupt";

    private final NotifyStreamRegistry registry;

    public SessionNotifyPublisher(NotifyStreamRegistry registry) {
        this.registry = registry;
    }

    /**
     * 发布一条会话活动信号。
     *
     * @param userId        会话归属用户（anonymous/null 直接跳过）
     * @param sessionId     会话 ID（threadId）
     * @param runId         关联 run（wakeup 路径可能为空串）
     * @param eventSequence agui_events 水位线（落库路径才有；wakeup/interrupt 传 null）
     * @param reason        {@link #REASON_RUN_FINISHED} / {@link #REASON_WAKEUP} / {@link #REASON_INTERRUPT}
     */
    public void publish(String userId, String sessionId, String runId, Long eventSequence, String reason) {
        // 注意：整段包在 try 里，且不引用任何嵌套类——devtools 热重载下嵌套类可能
        // 挂在不同 classloader，方法入口处解析会抛 NoClassDefFoundError 炸穿调用方
        // 的 reactor 链（2026-09-22 实测：wakeup 链三次因此中断，inbox hint 滞留、
        // 父会话空转等看门狗）。本方法尽力而为，任何异常只记 DEBUG。
        try {
            if (userId == null || userId.isBlank() || ANONYMOUS.equals(userId)) {
                return;
            }
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("runId", runId == null ? "" : runId);
            payload.put("eventSequence", eventSequence);
            payload.put("reason", reason == null ? "" : reason);
            registry.send(userId, "run-activity", payload);
            log.debug("notify: published {} for session {} (user={}, seq={})",
                    reason, sessionId, userId, eventSequence);
        } catch (Throwable e) {
            log.debug("notify: publish failed for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** 与 JwtInterceptor 匿名用户口径一致的常量（内联常量，避免嵌套类跨 classloader 解析）。 */
    private static final String ANONYMOUS = "anonymous";
}
