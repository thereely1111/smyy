package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.AguiEventLogProperties;
import com.tipdm.agentplatform.domain.AguiEventRecord;
import com.tipdm.agentplatform.repository.AguiEventRecordRepository;
import com.tipdm.agentplatform.service.notify.SessionNotifyPublisher;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.util.JsonUtils;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AG-UI 事件异步落库服务：内存队列 + 单线程后台批量 flush。
 *
 * <p><b>为什么异步：</b>capture 在 SSE 事件发射路径上（{@link com.tipdm.agentplatform.config.AguiEventLogEnricher}），
 * 一次对话可产生数百个 TEXT_MESSAGE_CONTENT chunk；同步写库会直接放大对话延迟。
 * capture 只做入队（零序列化开销），事件的 JSON 序列化、userId 反查、写库全部
 * 在 flush 线程完成。落库失败仅告警并丢弃，绝不阻塞对话主流程。</p>
 *
 * <p><b>序号分配（event_sequence）：</b>在 flush 线程按队列 FIFO 顺序为每个会话
 * 维护一个 AtomicLong——AG-UI 线程锁保证同一会话的 run 串行、wakeup 续跑排队
 * 入队，因此队列顺序即事件发射顺序，flush 单线程分配的序号与 SSE 输出顺序一致，
 * 且跨 run 连续。服务重启后按库内 {@code max(event_sequence)} 接续分配。</p>
 *
 * <p><b>归属解析（userId）：</b>enrich 层拿不到 RuntimeContext，按
 * {@code agent_session_owners} 反查（threadId=sessionId 全局唯一），结果按会话
 * 缓存（会话归属不变）。查不到时记 {@code anonymous} 并限速告警。</p>
 *
 * <p><b>覆盖范围：</b>经 AguiAgentAdapter 输出的全部事件（正常对话、HITL 闸门、
 * adapter 内错误转出的收尾事件）。框架 AguiRequestProcessor 在 run 未启动即失败时
 * 直接补发 RunError（processorErrorEvents/contractErrorEvents）不经 enrich，属于
 * 已知边界。</p>
 */
@Service
public class AguiEventPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AguiEventPersistenceService.class);

    private static final long WARN_INTERVAL_MS = 30_000L;

    private final AguiEventRecordRepository repository;
    private final AguiEventLogProperties properties;
    private final SessionOwnerService sessionOwnerService;
    private final SessionNotifyPublisher notifyPublisher;

    /** 待落库事件队列（FIFO，flush 线程单消费者）。 */
    private final ConcurrentLinkedQueue<AguiEvent> queue = new ConcurrentLinkedQueue<>();

    /**
     * 待写事件数的自计数（与 {@link #queue} 的 add/poll 严格配对）。
     *
     * <p>不用 {@code queue.size()}：ConcurrentLinkedQueue 的 size() 是 O(n) 全量遍历。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger queuedCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 会话 → 序号分配器（flush 线程专用，ConcurrentMap 仅防万一的并发 flush）。 */
    private final ConcurrentMap<String, AtomicLong> sequences = new ConcurrentHashMap<>();

    /** 会话 → 归属用户缓存。 */
    private final ConcurrentMap<String, String> ownerCache = new ConcurrentHashMap<>();

    private final ScheduledExecutorService flusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agui-event-log-flusher");
                t.setDaemon(true);
                return t;
            });

    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private volatile long lastDropWarnAt = 0L;
    private volatile long lastUserWarnAt = 0L;

    public AguiEventPersistenceService(
            AguiEventRecordRepository repository,
            AguiEventLogProperties properties,
            SessionOwnerService sessionOwnerService,
            SessionNotifyPublisher notifyPublisher) {
        this.repository = repository;
        this.properties = properties;
        this.sessionOwnerService = sessionOwnerService;
        this.notifyPublisher = notifyPublisher;
        this.flusher.scheduleWithFixedDelay(
                this::flushSafely,
                properties.getFlushIntervalMs(),
                properties.getFlushIntervalMs(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 捕获一个事件：仅入队，不阻塞、不抛异常。
     * 序列化、userId 反查、序号分配、写库都在 flush 线程。
     */
    public void capture(AguiEvent event) {
        if (!properties.isEnabled() || event == null) {
            return;
        }
        // 用自计数而非 queue.size()：ConcurrentLinkedQueue#size() 是 O(n)，而 capture
        // 每个事件要判两次阈值 —— 单事件成本 O(n)、整轮 O(n²)，高并发下事件捕获本身
        // 就成了瓶颈（2026-09-17）。
        if (queuedCount.get() >= properties.getQueueLimit()) {
            long now = System.currentTimeMillis();
            if (now - lastDropWarnAt > WARN_INTERVAL_MS) {
                lastDropWarnAt = now;
                log.error("AG-UI 事件落库队列已满（{}），丢弃新事件（threadId={}，type={}）",
                        properties.getQueueLimit(), event.getThreadId(), event.getType());
            }
            return;
        }
        queue.add(event);
        if (queuedCount.incrementAndGet() >= properties.getBatchSize()) {
            flushSafely();
        }
    }

    /** 当前待写队列长度（运维观测用）。 */
    public int pendingCount() {
        return queuedCount.get();
    }

    private void flushSafely() {
        if (queue.isEmpty() || !flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            flush();
        } catch (Exception e) {
            log.error("AG-UI 事件落库 flush 失败：{}", e.getMessage(), e);
        } finally {
            flushing.set(false);
        }
    }

    private void flush() {
        int batchSize = Math.max(1, properties.getBatchSize());
        List<AguiEvent> drained = new ArrayList<>(batchSize);
        AguiEvent event;
        while (drained.size() < batchSize && (event = queue.poll()) != null) {
            drained.add(event);
            // 与入队配对递减（见 queuedCount 注释）
            queuedCount.decrementAndGet();
        }
        if (drained.isEmpty()) {
            return;
        }

        List<AguiEventRecord> records = new ArrayList<>(drained.size());
        for (AguiEvent e : drained) {
            AguiEventRecord record = toRecord(e);
            if (record != null) {
                records.add(record);
            }
        }
        if (records.isEmpty()) {
            return;
        }
        try {
            repository.saveAll(records);
        } catch (DataAccessException e) {
            // 批量写失败时降级为逐条写，抢救可写入的部分（单条坏数据不拖垮整批）
            log.warn("AG-UI 事件批量落库失败（{} 行：{}），降级逐条写入", records.size(), e.getMessage());
            for (AguiEventRecord record : records) {
                try {
                    repository.save(record);
                } catch (Exception inner) {
                    log.warn("AG-UI 事件落库失败并丢弃：seq={} type={} {}",
                            record.getEventSequence(), record.getEventType(), inner.getMessage());
                }
            }
        }
        // 实时通知发布点（run-finished）：必须在落库成功之后——前端收到通知即按
        // 水位线拉取，若先发后落会拉空。只对终态事件发（RUN_FINISHED / RUN_ERROR），
        // 中间批次不发，避免一次 run 内通知风暴。尽力而为：失败不影响落库主流程。
        for (AguiEventRecord record : records) {
            if (isTerminalRunEvent(record.getEventType())) {
                notifyPublisher.publish(record.getUserId(), record.getSessionId(),
                        record.getRunId(), record.getEventSequence(),
                        SessionNotifyPublisher.REASON_RUN_FINISHED);
            }
        }
    }

    /** run 终态事件：该事件落库意味着这轮 run 的产出已可被前端拉取。 */
    private static boolean isTerminalRunEvent(String eventType) {
        return "RUN_FINISHED".equals(eventType) || "RUN_ERROR".equals(eventType);
    }

    private AguiEventRecord toRecord(AguiEvent event) {
        try {
            String threadId = event.getThreadId();
            String json = JsonUtils.getJsonCodec().toJson(event);

            AguiEventRecord record = new AguiEventRecord();
            record.setUserId(resolveUserId(threadId));
            record.setSessionId(threadId);
            record.setRunId(event.getRunId() == null ? "" : event.getRunId());
            record.setEventSequence(nextSequence(threadId));
            record.setEventType(event.getType() == null ? "UNKNOWN" : event.getType().name());
            record.setPayload(json);
            return record;
        } catch (Exception e) {
            log.warn("AG-UI 事件序列化/装配失败并丢弃（type={}）：{}",
                    event.getType(), e.getMessage());
            return null;
        }
    }

    private long nextSequence(String threadId) {
        return sequences
                .computeIfAbsent(threadId, id -> {
                    Long max = repository.findMaxSequenceBySessionId(id);
                    return new AtomicLong(max == null ? 0L : max);
                })
                .incrementAndGet();
    }

    private String resolveUserId(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return "anonymous";
        }
        return ownerCache.computeIfAbsent(threadId, id -> {
            Optional<String> owner = sessionOwnerService
                    .agentBySessionId(id)
                    .flatMap(agentId -> sessionOwnerService.owner(agentId, id));
            if (owner.isEmpty()) {
                long now = System.currentTimeMillis();
                if (now - lastUserWarnAt > WARN_INTERVAL_MS) {
                    lastUserWarnAt = now;
                    log.warn("AG-UI 事件落库：会话 {} 未找到归属用户，记为 anonymous", id);
                }
                return "anonymous";
            }
            return owner.get();
        });
    }

    /** 停机前把队列剩余事件写完。 */
    @PreDestroy
    void flushOnShutdown() {
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(3, TimeUnit.SECONDS)) {
                flusher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        int remaining = queuedCount.get();
        if (remaining > 0) {
            log.info("停机前落库剩余 AG-UI 事件 {} 条", remaining);
            flushSafely();
        }
    }
}
