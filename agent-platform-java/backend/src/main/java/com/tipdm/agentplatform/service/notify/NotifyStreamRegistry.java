package com.tipdm.agentplatform.service.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 用户级 SSE 通知流注册表：维护 {@code userId → SseEmitter} 集合，并负责心跳保活。
 *
 * <p><b>通道挂 user 而非 session</b>：wakeup 常唤醒父会话而用户可能正盯着别的会话，
 * user 级通道才能支撑「当前会话触发同步、其他会话亮角标」的路由语义。事件 payload
 * 只带 {@code {sessionId, runId, eventSequence, reason}} 游标信号（几字节），
 * 重数据一律由前端拉取（transcript / agui-events）。</p>
 *
 * <p><b>尽力而为语义</b>：SSE 不保交付也不保序——通知丢失由前端低频兜底轮询覆盖，
 * 重复由前端转录合并的去重逻辑吸收。因此发送失败只移除失效连接，绝不向上抛。</p>
 *
 * <p><b>多实例预留</b>：本类是单实例进程内注册表；横向扩容时在
 * {@link SessionNotifyPublisher} 前面接 Redis pub/sub / PG LISTEN-NOTIFY 扇出即可，
 * 契约不变。</p>
 */
@Service
public class NotifyStreamRegistry {

    private static final Logger log = LoggerFactory.getLogger(NotifyStreamRegistry.class);

    /** 心跳间隔：防代理/网关对空闲连接掐断（Nginx 默认 60s idle）。 */
    static final long HEARTBEAT_INTERVAL_MS = 30_000L;

    private final ObjectMapper objectMapper;

    /** userId → 该用户当前打开的通知流。 */
    private final ConcurrentMap<String, Set<SseEmitter>> streamsByUser = new ConcurrentHashMap<>();

    private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "notify-stream-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public NotifyStreamRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.heartbeat.scheduleWithFixedDelay(this::pingAll, HEARTBEAT_INTERVAL_MS,
                HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 为用户建立一条通知流：注册后立即发送 connected 握手事件
     * （让前端确认链路可用，而非干等首个业务通知/心跳）。
     */
    public SseEmitter register(String userId) {
        SseEmitter emitter = new SseEmitter(0L);
        Set<SseEmitter> set = streamsByUser.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable cleanup = () -> set.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"ts\":" + System.currentTimeMillis() + "}"));
        } catch (IOException e) {
            log.debug("notify stream: connected handshake failed for user {}: {}", userId, e.getMessage());
            cleanup.run();
        }
        log.info("notify stream: user {} connected (total streams for user: {})", userId, set.size());
        return emitter;
    }

    /** 向某用户的全部通知流发送一个事件；失效连接静默移除。 */
    public void send(String userId, String eventName, Map<String, Object> payload) {
        Set<SseEmitter> set = streamsByUser.get(userId);
        if (set == null || set.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.debug("notify stream: payload serialize failed ({}): {}", eventName, e.getMessage());
            return;
        }
        for (SseEmitter emitter : set) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(json));
            } catch (Exception e) {
                set.remove(emitter);
                log.debug("notify stream: send failed, dropped emitter for user {}: {}", userId, e.getMessage());
            }
        }
    }

    /** 心跳：注释行形态（: ping），不触发前端 event 回调，仅保活。 */
    private void pingAll() {
        for (Map.Entry<String, Set<SseEmitter>> entry : streamsByUser.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (Exception e) {
                    entry.getValue().remove(emitter);
                }
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        heartbeat.shutdownNow();
    }
}
