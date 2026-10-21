package com.tipdm.agentplatform.harness;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 会话事件时钟：记录每个会话最近一次产生 AgentEvent 的时刻。
 *
 * <p>由 {@link SessionActivityMiddleware} 在事件流上逐事件 touch（覆盖 AG-UI、
 * 平台 chat 与 wakeup run 全路径，含复制给子智能体的 middleware），供
 * {@link RunSilenceDetector} 判定「run 活跃但长时间无任何事件」——这是
 * LLM 流式响应中途停滞（2026-09-14 当日 4 次，全部需人工 jstack 诊断）的
 * 平台侧可观测信号。</p>
 *
 * <p><b>起点由 begin() 写入</b>（2026-09-17）：早先只在 touch（即首个事件到达）时
 * 才有条目，于是「run 已开始但一个事件都还没产出」的会话根本不在
 * {@link #snapshot()} 里，RunSilenceDetector 遍历不到 → 这种卡死既无告警也无恢复，
 * 且 wakeup 全被跳过造成会话死锁。现在 run 起跑即登记，停滞能被看见。</p>
 *
 * <p><b>按 run 引用计数</b>：并发 run 场景下，先结束的一方不再擦掉仍在跑那方的
 * 时钟（原实现直接 clear，会让探测器对仍在跑的 run 完全失明）。</p>
 */
public class SessionEventClock {

    /** 单会话时钟：runs = 当前在跑 run 数，归零才移除条目。 */
    private static final class Entry {
        private final AtomicInteger runs = new AtomicInteger();
        private volatile long lastEventAtMillis;
    }

    private final ConcurrentHashMap<String, Entry> clocks = new ConcurrentHashMap<>();

    /** run 开始：登记引用并把时钟拨到"现在"。 */
    public void begin(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        clocks.compute(sessionId, (key, entry) -> {
            Entry current = entry == null ? new Entry() : entry;
            current.runs.incrementAndGet();
            // 以「run 开始时刻」作为首个事件之前的兜底活动时间，避免新 run 被
            // 上一轮遗留的旧时间戳（或缺失条目）误判为已停滞。
            current.lastEventAtMillis = now;
            return current;
        });
    }

    /** run 结束：引用归零才移除；仍在跑的其他 run 继续保有这条时钟。 */
    public void end(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        clocks.computeIfPresent(sessionId,
                (key, entry) -> entry.runs.decrementAndGet() <= 0 ? null : entry);
    }

    /** 逐事件刷新最近事件时刻；条目不存在（非本进程 run）时不新建，避免无界增长。 */
    public void touch(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        // computeIfPresent：run 已结束或被强制收口后，迟到的僵尸事件不再把条目"复活"，
        // 否则 RunSilenceDetector 会对着已恢复的会话反复告警。
        clocks.computeIfPresent(sessionId, (key, entry) -> {
            entry.lastEventAtMillis = now;
            return entry;
        });
    }

    /** 强制移除（自动恢复专用）：连同引用计数一起丢弃，后续 touch/end 均为 no-op。 */
    public void clear(String sessionId) {
        if (sessionId != null) {
            clocks.remove(sessionId);
        }
    }

    /** 只读快照（sessionId → 最近事件/起跑 epoch millis）。 */
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new ConcurrentHashMap<>();
        clocks.forEach((key, entry) -> out.put(key, entry.lastEventAtMillis));
        return Map.copyOf(out);
    }
}
