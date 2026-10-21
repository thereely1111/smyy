package com.tipdm.agentplatform.harness;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 跟踪每个会话当前是否有智能体运行（AG-UI 用户请求、平台 chat、Wakeup 运行等）。
 *
 * <p>供 {@link WakeupDispatcher} 的 {@code isSessionRunning} 使用：如果会话正在运行，
 * 其当前 run 会自然 drain inbox，唤醒器跳过，避免并发双跑同一会话状态。</p>
 */
public class SessionActivityTracker {

    private final ConcurrentHashMap<String, AtomicInteger> activeBySession = new ConcurrentHashMap<>();

    /**
     * 登记一次 run 开始。
     *
     * <p>必须在 {@code compute} 内自增：原实现 {@code computeIfAbsent(...).incrementAndGet()}
     * 分两步，若两步之间 {@link #end} 把计数减到 0 并 remove，这次 +1 就落在
     * 已脱离 map 的 AtomicInteger 上——map 里再无该会话，计数永久"消失"，
     * {@link #isRunning} 恒 false。后果是 AG-UI 入口的 409 互斥守卫放行第二个
     * run（即"同会话双流程"），且后续 end() 静默 no-op。</p>
     */
    public void begin(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        activeBySession.compute(sessionId, (key, counter) -> {
            if (counter == null) {
                return new AtomicInteger(1);
            }
            counter.incrementAndGet();
            return counter;
        });
    }

    /** 一次 run 结束；计数归零才移除条目（与 {@link #begin} 同锁内完成，避免丢计数）。 */
    public void end(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        activeBySession.computeIfPresent(sessionId,
                (key, counter) -> counter.decrementAndGet() <= 0 ? null : counter);
    }

    /**
     * 强制清除会话的在跑计数（自动恢复专用）。
     *
     * <p>仅用于 {@code RunSilenceDetector} 把「长时间静默、判定已死」的 run 从
     * 活跃口径中移除：僵尸 run 的 doFinally 永远不会执行（取消链死锁），正常
     * end() 永远等不到，只能由看护方主动归零。强制清除后新 run 可以接管会话；
     * 若僵尸 run 之后奇迹般自愈，其事件只会被静默丢弃（订阅早已被框架超时路径
     * 撕掉），不应再写回活跃状态。</p>
     */
    public void forceEnd(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        activeBySession.remove(sessionId);
    }

    public boolean isRunning(String sessionId) {
        AtomicInteger counter = activeBySession.get(sessionId);
        return counter != null && counter.get() > 0;
    }

    /**
     * 当前在跑会话集合快照（供静默探测器做「计数 ∪ 时钟」并集扫描）。
     *
     * <p>3.2：活跃计数与事件时钟是两份独立登记表，结束路径并非严格同源——
     * 「有计数无时钟」的会话此前对探测器完全不可见（既不告警也不恢复）。
     * 本方法让探测器把计数表纳入扫描源，两表不齐成为可观测状态。</p>
     */
    public Set<String> runningSessionIds() {
        Set<String> out = new HashSet<>();
        activeBySession.forEach((key, counter) -> {
            if (counter.get() > 0) {
                out.add(key);
            }
        });
        return out;
    }
}
