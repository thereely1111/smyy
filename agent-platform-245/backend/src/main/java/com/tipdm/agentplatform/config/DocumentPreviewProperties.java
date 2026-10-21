package com.tipdm.agentplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 文档预览（TipDM-FV）配置。
 *
 * <p><b>为什么单独一个开关且默认关</b>：预览服务是独立部署的外部依赖（当前地址
 * {@code http://192.168.1.131:30182/}），它不可用时不应影响正常对话，因此
 * {@code enabled} 默认false——工具仍会被注册，但调用时立刻返回一句
 * 「预览服务未启用」而不是抛异常拖垮整轮run。</p>
 *
 * <p><b>地址可达性的前提</b>：TipDM-FV 在私有网段，出网必须先放开
 * {@code agent-platform.security.allow-private-outbound}，否则
 * {@code OutboundUrlGuard} 会直接拒绝（它对 {@code isSiteLocalAddress()} 默认 fail）。
 * 该开关默认false，只有线上 values 打开了，见 {@code deploy/values.yaml}。</p>
 *
 * <p><b>两个地址的区别</b>：{@link #baseUrl()} 是本机去<b>调</b> TipDM-FV；
 * {@link #publicBaseUrl()} 是 TipDM-FV <b>回拉</b>本平台文件时用的地址，
 * 必须是它能访问到的地址，填 {@code localhost} 或 Pod IP 会拿不到文件。</p>
 *
 * <p><b>格式支持与否不由平台判定</b>：产物面板一律把文件交给预览服务，
 * 由它自己决定能否渲染（不认识时它渲染一个明确的「不支持该格式」页面）。
 * 平台侧曾维护过一份扩展名清单，但它与对方真实能力对不上（把
 * {@code odt}/{@code webp}/{@code svg} 等列为可预览，实际点开只会得到
 * 「系统还不支持该格式」），一个不准的清单比没有更糟——排查时反而误导人，故已删除。</p>
 */
@Component
public class DocumentPreviewProperties {

    private static final Logger log = LoggerFactory.getLogger(DocumentPreviewProperties.class);

    /**
     * 票据有效期下限（秒）。
     *
     * <p>太短会让预览服务还没发起回拉就过期；不设下限则可能被配成 0导致必然失败。</p>
     */
    private static final long MIN_TICKET_TTL_SECONDS = 30L;

    private final boolean enabled;
    private final String baseUrl;
    private final String publicBaseUrl;
    private final Duration ticketTtl;

    public DocumentPreviewProperties(
            @Value("${agent-platform.document-preview.enabled:false}") boolean enabled,
            @Value("${agent-platform.document-preview.base-url:}") String baseUrl,
            @Value("${agent-platform.document-preview.public-base-url:}") String publicBaseUrl,
            @Value("${agent-platform.document-preview.ticket-ttl-seconds:300}") long ticketTtlSeconds) {
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.publicBaseUrl = stripTrailingSlash(publicBaseUrl);
        // 下限 30 秒：太短会让TipDM-FV 还没发起回拉就过期；不设下限容易被配成 0 导致必然失败
        this.ticketTtl = Duration.ofSeconds(Math.max(MIN_TICKET_TTL_SECONDS, ticketTtlSeconds));
        if (enabled) {
            if (this.baseUrl.isEmpty()) {
                log.warn("文档预览已启用但未配置 base-url，调用时将直接失败");
            }
            if (this.publicBaseUrl.isEmpty()) {
                log.warn("文档预览已启用但未配置 public-base-url，TipDM-FV 将无法回拉文件");
            }
        }
    }

    /**
     * 预览功能是否启用。
     *
     * <p>关闭时工具返回一句提示而非抛异常，保证外部服务故障不影响正常对话。</p>
     *
     * @return 是否启用
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * TipDM-FV 的基地址。
     *
     * @return 形如 {@code http://host:port} 的地址（末尾斜杠已去除）
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * TipDM-FV 回拉平台文件时使用的平台对外地址。
     *
     * @return 形如 {@code https://platform.internal} 的地址（末尾斜杠已去除）
     */
    public String publicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * 回拉票据的有效期。
     *
     * @return 票据有效期，已施加下限保护
     */
    public Duration ticketTtl() {
        return ticketTtl;
    }

    /**
     * 配置是否齐备。
     *
     * <p>启用状态下两个地址都必须存在，缺一则无法完成「回拉」这一环。</p>
     *
     * @return 已启用且两个地址齐备时为 {@code true}
     */
    public boolean configured() {
        return enabled && !baseUrl.isEmpty() && !publicBaseUrl.isEmpty();
    }

    private static String stripTrailingSlash(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
