package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AG-UI 事件落库配置（{@code agent-platform.agui-event-log}）。
 *
 * <p>事件捕获在 SSE 输出路径上（{@link AguiEventLogEnricher}），因此写入采用
 * 「内存队列 + 后台批量 flush」：capture 只入队、序列化与落库都在 flush 线程，
 * 不增加事件发射延迟。队列有上限防内存膨胀，超限丢弃并告警（审计场景允许
 * 极端积压时丢尾部，优先保对话主流程）。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.agui-event-log")
public class AguiEventLogProperties {

    /** 是否启用事件落库（默认开；关闭后 Enricher 纯透传，无任何开销）。 */
    private boolean enabled = true;

    /** 单批最多落库行数。 */
    private int batchSize = 100;

    /** 后台 flush 周期（毫秒）。 */
    private long flushIntervalMs = 500;

    /** 待写队列上限，超过即丢弃新事件并告警。 */
    private int queueLimit = 20000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public void setFlushIntervalMs(long flushIntervalMs) {
        this.flushIntervalMs = flushIntervalMs;
    }

    public int getQueueLimit() {
        return queueLimit;
    }

    public void setQueueLimit(int queueLimit) {
        this.queueLimit = queueLimit;
    }
}
