package com.tipdm.agentplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 主动告知（告警）配置。
 *
 * <p>告警用于把「服务端知道、用户不知道」的事件推给人：会话停摆、run 静默判死、
 * HITL 长期未决等。三条投递路径全部走 {@code AlertNotifier}：
 * 落库（离线不丢）→ SSE（在线即时）→ 企业微信机器人（人不在页面前）。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.alert")
public class AlertProperties {

    /** 总开关：关闭后告警只记日志，不落库不推送。 */
    private boolean enabled = true;

    /** 同一 alertKey 的抑制窗口（分钟）：周期内看护会反复命中同一停摆会话，必须去重。 */
    private long dedupCooldownMinutes = 30;

    /**
     * 未读抑制的最长时效（小时，默认 24）。
     *
     * <p>未读抑制的语义是「提醒一次，直到你看过」，但用户一直不读铃铛时，同一问题
     * 三天后再现也永远不会再提醒（冷却窗口实际永远走不到）。超过本时效后降级为
     * 「冷却判定」，即在未读条件下仍按 {@link #dedupCooldownMinutes} 去重。</p>
     */
    private long unreadSuppressMaxHours = 24;

    /** 通知保留天数（超出由清理任务删除，防表无限增长）。 */
    private long retentionDays = 30;

    private Wecom wecom = new Wecom();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDedupCooldownMinutes() {
        return dedupCooldownMinutes;
    }

    public void setDedupCooldownMinutes(long dedupCooldownMinutes) {
        this.dedupCooldownMinutes = dedupCooldownMinutes;
    }

    public long getUnreadSuppressMaxHours() {
        return unreadSuppressMaxHours;
    }

    public void setUnreadSuppressMaxHours(long unreadSuppressMaxHours) {
        this.unreadSuppressMaxHours = unreadSuppressMaxHours;
    }

    public long getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(long retentionDays) {
        this.retentionDays = retentionDays;
    }

    public Wecom getWecom() {
        return wecom;
    }

    public void setWecom(Wecom wecom) {
        this.wecom = wecom;
    }

    /** 企业微信机器人（群 webhook）。 */
    public static class Wecom {

        /** 是否外发。关闭则告警只走站内。 */
        private boolean enabled = false;

        /** 群机器人 webhook 地址（形如 https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx）。 */
        private String webhookUrl = "";

        /** 触发外发的最低级别：info / warn / error。默认只在 error 时打扰人。 */
        private String minLevel = "error";

        /** 单次调用超时（秒）。外部渠道失败一律吞掉，绝不影响主流程。 */
        private long timeoutSeconds = 5;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhookUrl() {
            return webhookUrl;
        }

        public void setWebhookUrl(String webhookUrl) {
            this.webhookUrl = webhookUrl;
        }

        public String getMinLevel() {
            return minLevel;
        }

        public void setMinLevel(String minLevel) {
            this.minLevel = minLevel;
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }
}
