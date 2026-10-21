package com.tipdm.agentplatform.util;

import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 出网请求地址守卫：拦住「用户可控 URL」造成的 SSRF。
 *
 * <p>知识库的 external endpoint、告警渠道的 webhook 地址都由普通用户填写。若不校验
 * 直接发请求，攻击者可让服务端去访问 {@code 169.254.169.254}（云元数据）、
 * {@code 127.0.0.1}（本机服务）或内网管理端，再把响应体回显出来做内网探测。</p>
 *
 * <p>规则：</p>
 * <ul>
 *   <li>只放行 http/https；</li>
 *   <li>loopback、link-local、any-local、组播一律拒绝（与开关无关）；</li>
 *   <li>私有网段（10/8、172.16/12、192.168/16）默认拒绝。私有化部署若确实要访问内网
 *       RAGFlow / IM 网关，配置 {@code agent-platform.security.allow-private-outbound=true}。</li>
 * </ul>
 *
 * <p>注意：这是发起请求前的解析期校验，不能防御 DNS rebinding（校验时解析到公网、
 * 实际连接时解析到内网）。彻底解决需要在 HttpClient 上固定 DNS 解析结果，代价较大；
 * 此处先拦住绝大部分直接填内网/元数据地址的滥用。</p>
 */
@Component
public class OutboundUrlGuard {

    private final boolean allowPrivate;

    public OutboundUrlGuard(
            @Value("${agent-platform.security.allow-private-outbound:false}") boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    /**
     * 校验出网地址，不通过时抛 400。
     *
     * @param rawUrl  用户填写的地址
     * @param purpose 用途描述，用于错误提示（如「外部知识库 endpoint」）
     */
    public void requireSafe(String rawUrl, String purpose) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw ApiException.badRequest(purpose + "地址为空");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (URISyntaxException e) {
            throw ApiException.badRequest(purpose + "地址不是合法 URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw ApiException.badRequest(purpose + "地址只支持 http/https");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.badRequest(purpose + "地址缺少主机名");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw ApiException.badRequest(purpose + "地址无法解析: " + host);
        }
        if (addresses.length == 0) {
            throw ApiException.badRequest(purpose + "地址无法解析: " + host);
        }
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw ApiException.badRequest(purpose + "地址不允许指向本机或链路本地地址");
            }
            if (address.isMulticastAddress()) {
                throw ApiException.badRequest(purpose + "地址不允许指向组播地址");
            }
            if (address.isSiteLocalAddress() && !allowPrivate) {
                throw ApiException.badRequest(purpose + "地址不允许指向内网地址"
                        + "（私有化部署确需访问内网时配置 agent-platform.security.allow-private-outbound=true）");
            }
        }
    }
}
