package com.tipdm.agentplatform.harness;

import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端自发 run 的订阅句柄登记表（2026-09-30 方案 A：dispose 直达 HTTP abort）。
 *
 * <p><b>为什么需要</b>：框架 {@code interrupt()} 是检查点语义——只在两轮 reasoning
 * 之间生效，无法打断阻塞中的 LLM 流式 HTTP 调用（2026-09-30 实测：自检 run 卡流后
 * {@link SessionRunStopper} 等 3s 超时只能 "proceeding anyway"，僵尸 run 占住活跃
 * 计数直到 dashscope 客户端超时，实测超过 11 分钟）。而 run 的 Flux 订阅本来就在
 * 平台手里——Reactor 的 {@code cancel()} 会沿订阅链向上游传播，reactor-netty 直接
 * abort 底层 HTTP 连接，LLM 流立刻死、run 以 cancel 收尾、收尾闸门/租约清理照常走
 * {@code doFinally}。此前调用方 {@code subscribe()} 后丢弃 Disposable，无从 dispose。</p>
 *
 * <p>本登记表在 {@link ServerSideRunLauncher} 出口用 {@code doOnSubscribe} 捕获订阅
 * 并登记；stop / 收尸路径据此<b>先 dispose（真取消）</b>，再走框架 interrupt 作为
 * 检查点语义兜底（状态落库语义保留）。会话内 run 严格串行，map 值取最新订阅；
 * {@link #unregister} 按 (key, value) 相等删除，避免误删后继 run 的句柄。</p>
 *
 * <p><b>覆盖范围</b>：仅服务端自发 run（wakeup 补醒 / 停滞自检 / 未来定时类）。
 * 用户聊天路径的订阅在框架 AguiRequestProcessor 手里，需框架提供 hard-cancel API
 * 才能覆盖（已另立案，与「框架取消链死锁」同族）。</p>
 */
@Component
public class RunSubscriptionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RunSubscriptionRegistry.class);

    /** sessionId → 该会话当前在跑 run 的订阅（串行语义下最多一个）。 */
    private final Map<String, Subscription> running = new ConcurrentHashMap<>();

    /** run 订阅建立时登记（由 {@code doOnSubscribe} 回调驱动）。 */
    public void register(String sessionId, Subscription subscription) {
        if (sessionId == null || sessionId.isBlank() || subscription == null) {
            return;
        }
        Subscription prev = running.put(sessionId, subscription);
        if (prev != null && prev != subscription) {
            log.warn("Run subscription registry: session {} had a previous live subscription "
                    + "on register — serial-run invariant broken, old handle replaced", sessionId);
        }
    }

    /** run 终止时注销（按对象相等删除，避免误删后继 run 的句柄）。 */
    public void unregister(String sessionId, Subscription subscription) {
        if (sessionId == null || subscription == null) {
            return;
        }
        running.remove(sessionId, subscription);
    }

    /**
     * 取消该会话在跑 run 的订阅（真取消：上游 HTTP 连接被 abort）。
     *
     * @return true = 存在登记句柄并已发出 cancel；false = 无登记（聊天路径 run，
     *         或 run 恰好已自然终止完成注销）
     */
    public boolean dispose(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        Subscription sub = running.remove(sessionId);
        if (sub == null) {
            return false;
        }
        try {
            sub.cancel();
            log.info("Run subscription registry: disposed run subscription for session {}", sessionId);
            return true;
        } catch (RuntimeException e) {
            log.warn("Run subscription registry: dispose failed for session {}: {}",
                    sessionId, e.getMessage());
            return false;
        }
    }

    /** 供运维/测试观察：该会话是否有登记的在跑订阅。 */
    public boolean hasSubscription(String sessionId) {
        return sessionId != null && running.containsKey(sessionId);
    }
}
