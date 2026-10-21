package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * AgentScope Studio 集成配置（{@code agentscope.studio.*}）。
 *
 * <p>对应 {@code agentscope-extensions-studio} 的 {@code StudioConfig} 字段。
 * 是否装配 {@code StudioMessageHook} 由
 * {@code @ConditionalOnProperty("agentscope.studio.enabled")} 决定，默认关闭；
 * 开启后智能体每次回复（PostCallEvent）自动推送到 Studio 可视化。</p>
 */
@ConfigurationProperties(prefix = "agentscope.studio")
public class StudioProperties {

    /** 是否启用 AgentScope Studio；为 true 时才装配 StudioMessageHook。 */
    private boolean enabled = false;

    /** Studio 服务地址（消息推送 HTTP 根地址，如 http://localhost:3000）。 */
    private String url = "http://localhost:3000";

    /** OTLP tracing 端点；留空时框架按 {url}/v1/traces 推导。 */
    private String tracingUrl = "";

    /** Studio 侧项目名（用于区分不同平台/租户的运行）。 */
    private String project = "agent-platform";

    /** 本次应用运行的展示名称。 */
    private String runName = "agent-platform";

    /** 运行 ID；留空时框架生成随机 UUID。 */
    private String runId = "";

    /** Studio HTTP 请求失败后的最大重试次数（含消息推送）。 */
    private int maxRetries = 3;

    /** WebSocket 重连尝试次数（当前未建立 WebSocket，预留字段）。 */
    private int reconnectAttempts = 3;

    /** WebSocket 重连初始延迟（预留字段）。 */
    private Duration reconnectDelay = Duration.ofSeconds(1);

    /** WebSocket 重连最大延迟（预留字段）。 */
    private Duration reconnectMaxDelay = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTracingUrl() {
        return tracingUrl;
    }

    public void setTracingUrl(String tracingUrl) {
        this.tracingUrl = tracingUrl;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getRunName() {
        return runName;
    }

    public void setRunName(String runName) {
        this.runName = runName;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    public void setReconnectAttempts(int reconnectAttempts) {
        this.reconnectAttempts = reconnectAttempts;
    }

    public Duration getReconnectDelay() {
        return reconnectDelay;
    }

    public void setReconnectDelay(Duration reconnectDelay) {
        this.reconnectDelay = reconnectDelay;
    }

    public Duration getReconnectMaxDelay() {
        return reconnectMaxDelay;
    }

    public void setReconnectMaxDelay(Duration reconnectMaxDelay) {
        this.reconnectMaxDelay = reconnectMaxDelay;
    }
}
