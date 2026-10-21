package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.harness.WakeupInterruptRegistrar;
import com.tipdm.agentplatform.service.PendingFormInterruptStore;
import com.tipdm.agentplatform.service.notify.NotifyStreamRegistry;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import com.tipdm.agentplatform.web.auth.JwtInterceptor;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 用户级实时通知流（SSE）。
 *
 * <p>{@code GET /api/notify/stream}：一条挂 user 的常驻通知连接，替代前端轮询
 * transcript 作为「服务端自发 run（wakeup 续跑 / 定时汇报 / 看门狗补投递）产生
 * 新内容」的主感知路径。事件只有游标信号 {@code run-activity}
 * {@code {sessionId, runId, eventSequence, reason}}，不含任何消息内容；
 * 数据面仍由前端按需拉取（transcript / agui-events）。</p>
 *
 * <p><b>连接即对账（P-01 修复）</b>：注册成功后把该用户「仍然活着」的待办闸门
 * 重播一遍 {@code reason=interrupt} 游标，覆盖两个丢信号窗口——
 * ① 闸门在后台唤醒 run 里渲染时本端未连接（未登录 / 网关掐断 / 页面刚打开）；
 * ② 前端在标签页隐藏期间主动丢弃了信号（轮询在 hidden 时让路）。重播只含游标，
 * 天然幂等；「活着」的判定复用 {@link WakeupInterruptRegistrar#filterLive}，
 * 与超时巡检、进度看护同一口径。</p>
 *
 * <p><b>鉴权</b>：与 /api/** 一致由 JwtAuthFilter 强制登录，未登录直接 401。
 * <b>生命周期</b>：timeout=0（不超时），30s 心跳保活，断开自动从注册表移除；
 * 通知尽力而为，丢失由前端低频兜底轮询覆盖。</p>
 */
@RestController
@RequestMapping("/api/notify")
public class NotifyStreamController {

    private static final Logger log = LoggerFactory.getLogger(NotifyStreamController.class);

    private final NotifyStreamRegistry registry;
    private final PendingFormInterruptStore pendingFormInterruptStore;
    private final WakeupInterruptRegistrar interruptRegistrar;
    private final SessionNotifyPublisher notifyPublisher;

    public NotifyStreamController(
            NotifyStreamRegistry registry,
            PendingFormInterruptStore pendingFormInterruptStore,
            WakeupInterruptRegistrar interruptRegistrar,
            SessionNotifyPublisher notifyPublisher) {
        this.registry = registry;
        this.pendingFormInterruptStore = pendingFormInterruptStore;
        this.interruptRegistrar = interruptRegistrar;
        this.notifyPublisher = notifyPublisher;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(HttpServletRequest request) {
        String userId = JwtInterceptor.resolveUserId(request);
        if (userId == null || userId.isBlank() || JwtInterceptor.ANONYMOUS_USER.equals(userId)) {
            throw ApiException.unauthorized("未登录或登录已过期，请重新登录");
        }
        SseEmitter emitter = registry.register(userId);
        replayLiveGateSignals(userId);
        return emitter;
    }

    /**
     * 把该用户仍存活的待办闸门重播一遍游标信号（P-01）。
     *
     * <p>尽力而为：任何一步异常都不得影响通知流建立，故整体兜底捕获并降级为日志。</p>
     */
    private void replayLiveGateSignals(String userId) {
        int replayed = 0;
        try {
            for (PendingFormInterruptStore.StoredInterrupt stored : pendingFormInterruptStore.listAll()) {
                if (stored.userId() == null || !stored.userId().equals(userId)) {
                    continue;
                }
                // 与超时巡检同口径：框架 pendingInterruptsByThread 里已无记录的 = 陈旧，跳过
                if (interruptRegistrar.filterLive(stored.sessionId(), List.of(stored.interrupt())).isEmpty()) {
                    continue;
                }
                String replyId = stored.interrupt().replyId();
                notifyPublisher.publish(userId, stored.sessionId(), replyId == null ? "" : replyId, null,
                        SessionNotifyPublisher.REASON_INTERRUPT);
                replayed++;
            }
        } catch (RuntimeException e) {
            log.warn("notify stream: replay live gate signals failed for user {}: {}", userId, e.getMessage());
            return;
        }
        if (replayed > 0) {
            log.info("notify stream: replayed {} live gate signal(s) for user {} on connect",
                    replayed, userId);
        }
    }
}
