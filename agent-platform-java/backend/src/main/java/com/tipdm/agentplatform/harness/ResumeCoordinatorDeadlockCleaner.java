package com.tipdm.agentplatform.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * AG-UI 线程锁死锁清理看门狗。
 *
 * <p><b>问题</b>：框架 {@code io.agentscope.core.agui.processor.AguiResumeCoordinator}
 * 用 {@code activeRunsByThread: ConcurrentMap<threadId, runId>} 给每个 AG-UI
 * 线程持锁。{@code finishRun(threadId, runId)} 仅在 run 的 Flux 终止时由
 * Reactor {@code doFinally} 触发——run 异常退出/超时时该 hook 不可靠，
 * 锁即泄漏。表现：会话再次 {@code beginRun} 时被
 * {@code "Thread already has an active run; wait for run {id} to finish"}
 * 拒绝；前端闸门提交全部 500/异常。</p>
 *
 * <p><b>兜底机制</b>：定期反射读 {@code activeRunsByThread}，对每个
 * {@code (sessionId, runId)}，若 {@link SessionActivityTracker#isRunning}
 * 返回 {@code false}（即本次 run 的 Flux 已终止 / 超时）但锁还在，
 * 即视为死锁——主动调一次 {@code finishRun(sessionId, runId)} 释放。
 * 仅做反射 + 单调方法调用，对正常挂起的 HITL run（{@code tracker} 仍在计数）
 * 不会触发。</p>
 *
 * <p><b>设计取舍</b>：反射访问框架私有字段是已知耦合（{@code AguiMvcController.processor}
 * → {@code AguiRequestProcessor.resumeCoordinator} → {@code activeRunsByThread}），
 * 但这是当前唯一不需要改框架的兜底路径；只要反射字段名不变就能工作。
 * 字段重命名时本看门狗会 silent 跳过并在日志里看到 "not wired"，不会 crash。</p>
 */
@Component
public class ResumeCoordinatorDeadlockCleaner implements ApplicationContextAware {

    private static final Logger log = LoggerFactory.getLogger(ResumeCoordinatorDeadlockCleaner.class);

    private final SessionActivityTracker tracker;
    private ApplicationContext applicationContext;

    /** 已通过反射解析到的 {@code activeRunsByThread} map 引用；null 表示尚未解析或失败。 */
    private volatile ConcurrentMap<String, String> activeRunsCache;

    public ResumeCoordinatorDeadlockCleaner(SessionActivityTracker tracker) {
        this.tracker = tracker;
    }

    @Override
    public void setApplicationContext(ApplicationContext ctx) throws BeansException {
        this.applicationContext = ctx;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void sweep() {
        ConcurrentMap<String, String> activeRuns = resolveActiveRuns();
        if (activeRuns == null) {
            return;
        }
        if (activeRuns.isEmpty()) {
            return;
        }
        int scanned = 0;
        int cleared = 0;
        for (Map.Entry<String, String> e : activeRuns.entrySet()) {
            String sessionId = e.getKey();
            String runId = e.getValue();
            scanned++;
            if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()) {
                continue;
            }
            // 仅释放「Flux 已终止但锁未释放」的条目；正常挂起的 HITL run 仍
            // 在 SessionActivityMiddleware 的 tracker 计数里，isRunning=true
            // 会被跳过。
            if (tracker.isRunning(sessionId)) {
                continue;
            }
            if (activeRuns.remove(sessionId, runId)) {
                cleared++;
                log.warn(
                        "Deadlock cleaner: force-released AG-UI thread lock (session={}, runId={}, "
                                + "SessionActivityTracker reports idle)",
                        sessionId, runId);
            }
        }
        if (cleared > 0) {
            log.info("Deadlock cleaner: scanned={} activeRuns, cleared={} stale lock(s)",
                    scanned, cleared);
        }
    }

    /**
     * 解析框架的 {@code AguiResumeCoordinator.activeRunsByThread}。
     * 失败（框架未装配/字段改名）时返回 null，看门狗当周期静默跳过。
     */
    @SuppressWarnings("unchecked")
    private ConcurrentMap<String, String> resolveActiveRuns() {
        if (activeRunsCache != null) {
            return activeRunsCache;
        }
        if (applicationContext == null) {
            return null;
        }
        try {
            // 1) 找 AguiMvcController bean（spring-boot starter 装配的入口 bean）。
            //    类名固定（io.agentscope.spring.boot.agui.mvc.AguiMvcController），
            //    通过按类型 getBean 找不到时降级为按名称 getBean("aguiMvcController")。
            Object controller = null;
            try {
                Class<?> cls = Class.forName("io.agentscope.spring.boot.agui.mvc.AguiMvcController");
                controller = applicationContext.getBean(cls);
            } catch (ClassNotFoundException | RuntimeException primary) {
                try {
                    controller = applicationContext.getBean("aguiMvcController");
                } catch (RuntimeException fallback) {
                    log.debug("Deadlock cleaner: AguiMvcController bean not found, skipping cycle");
                    return null;
                }
            }
            if (controller == null) {
                return null;
            }

            // 2) controller.processor → AguiRequestProcessor
            Object processor = readFieldByName(controller, "processor");
            if (processor == null) {
                return null;
            }
            // 3) processor.resumeCoordinator → AguiResumeCoordinator
            Object coordinator = readFieldByName(processor, "resumeCoordinator");
            if (coordinator == null) {
                return null;
            }
            // 4) coordinator.activeRunsByThread → ConcurrentMap<String, String>
            Object activeRuns = readFieldByName(coordinator, "activeRunsByThread");
            if (!(activeRuns instanceof ConcurrentMap<?, ?> map)) {
                return null;
            }
            // 静态类型断言：key/value 都是 String（AG-UI threadId/runId）
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
                    log.warn("Deadlock cleaner: activeRunsByThread has non-String entries, bailing");
                    return null;
                }
            }
            this.activeRunsCache = (ConcurrentMap<String, String>) map;
            log.info("Deadlock cleaner: wired through AguiMvcController → processor "
                    + "→ resumeCoordinator → activeRunsByThread");
            return activeRunsCache;
        } catch (RuntimeException | IllegalAccessException e) {
            log.debug("Deadlock cleaner: resolve failed this cycle: {}", e.toString());
            return null;
        }
    }

    /** 按字段名沿 class 链向上找 declared field，避开 getDeclaredFields() 顺序依赖。 */
    private static Object readFieldByName(Object owner, String fieldName)
            throws IllegalAccessException {
        Class<?> c = owner.getClass();
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(owner);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}
