package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.DocumentPreviewProperties;
import com.tipdm.agentplatform.util.OutboundUrlGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 文档预览服务（TipDM-FV）客户端。
 *
 * <p><b>服务形态</b>：kkFileView 定制分支，只有一个业务接口
 * {@code GET /onlinePreview?url=<文件URL>}——<b>由它自己去拉源文件</b>，
 * 并把渲染后的网页返回。因此本类只负责「拼地址 + 探活」，
 * 不传文件字节（它的接口不接受 POST）。</p>
 *
 * <p><b>为什么不经前端直连</b>：① 本平台未配置 CORS，前端跨域必失败；
 * ② 该服务<b>零鉴权</b>，不能暴露给浏览器；③ 出网统一走本类，
 * 便于复用 {@link OutboundUrlGuard} 的SSRF 防护。</p>
 */
@Service
public class DocumentPreviewClient {

    private static final Logger log = LoggerFactory.getLogger(DocumentPreviewClient.class);

    /**
     * 转换超时。
     *
     * <p>office → pdf 要经LibreOffice，首次转换明显慢于普通 HTTP 调用；
     * 但仍设上限，避免卡死占满连接。</p>
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /** 建连超时。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /** 探活可接受的 HTTP 状态码上界（不含）。 */
    private static final int ACCEPTABLE_STATUS_LIMIT = 400;

    /** HTTP 成功状态码下界。 */
    private static final int HTTP_OK = 200;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final DocumentPreviewProperties properties;
    private final OutboundUrlGuard outboundUrlGuard;

    public DocumentPreviewClient(DocumentPreviewProperties properties, OutboundUrlGuard outboundUrlGuard) {
        this.properties = properties;
        this.outboundUrlGuard = outboundUrlGuard;
    }

    /**
     * 拼出预览页URL。
     *
     * <p><b>只拼地址、不发请求</b>：真正的转换由 TipDM-FV 在浏览器打开该URL 时才触发，
     * 后端不在这里阻塞等待。</p>
     *
     * <p><b>原名必须放在「源地址」上，不能放在外层</b>：TipDM-FV 内部
     * {@code FileUtils.getFileAttribute(url)} 只从<b>源文件 url</b> 上读
     * {@code fullfilename} 参数（不是从 {@code /onlinePreview} 的外层 query 读），
     * 读到就用它定扩展名，读不到才回退到从 URL 路径取后缀。我们的回拉地址是
     * {@code /api/doc-preview/raw?ticket=...}，路径里没有扩展名，所以必须显式传。
     * 选不到扩展名时它<b>根本不去下载</b>，直接返回「系统还不支持该格式」。</p>
     *
     * <p>⚠️⚠️ <b>{@code fullfilename} 只传 ASCII 安全名，绝不传真实中文名</b>。
     * 这不是洁癖，是硬约束：TipDM-FV 会把源 URL <b>解码两次</b>才拿去回拉
     * （容器绑定 {@code url} 参数解一次，业务代码再 {@code URLDecoder.decode} 一次）。
     * 于是我们编码一次的中文名会被还原成<b>裸中文与裸空格</b>并直接进request line，
     * Tomcat 在解析请求行时就以 RFC 7230 违规拒掉——
     * <b>连Controller 都进不去</b>，日志表现为 400 + "Invalid character found in the request target"。
     * （且Java 的 {@code URLDecoder} 会把 {@code +} 还原成空格，空格同样非法。）
     * 而 {@code fullfilename} 的唯一用途就是让对方认出扩展名，
     * 真实文件名本来就由票据承载（{@code fnm} claim，用于回拉响应的
     * {@code Content-Disposition}），这里传 ASCII 名不丢任何信息。
     * ASCII 且不含 {@code %} / {@code +} 的字符串是 {@code URLDecoder.decode} 的
     * <b>不动点</b>——解多少次都不变，故对解码轮次免疫。</p>
     *
     * @param sourceUrlWithoutName 回拉端点地址（不带文件名）
     * @param fileName原始文件名（<b>仅取其扩展名</b>用于渲染方式判定）
     * @return 可直接放进 iframe 的预览地址
     * @throws IllegalStateException 配置缺失或未启用时抛出
     */
    public String buildPreviewUrl(String sourceUrlWithoutName, String fileName) {
        if (!properties.configured()) {
            throw new IllegalStateException("文档预览服务未启用或配置不完整");
        }
        // 出网地址过 SSRF 守卫：私有网段要求 allow-private-outbound=true，否则直接 400。
        // 放在这里而非构造期，是为了让「服务未启用」时不因地址缺失而启动失败。
        outboundUrlGuard.requireSafe(properties.baseUrl(), "文档预览服务");

        // 扩展名保持原样（它决定渲染方式），主名换成固定 ASCII——见方法注释的解码轮次约束
        String asciiName = asciiNameFor(fileName);
        String separator = sourceUrlWithoutName.contains("?") ? "&" : "?";
        String sourceUrl = sourceUrlWithoutName + separator + "fullfilename=" + asciiName;
        // 整条源地址再编码一次，作为外层 url 参数的值。
        // 这一层是必需的：外层是 query 参数值，ticket 里的 '.' '-' 等字符
        // 以及 '&' '=' 都必须转义，否则会被解析成额外的参数。
        String encodedSource = URLEncoder.encode(sourceUrl, StandardCharsets.UTF_8);
        return properties.baseUrl() + "/onlinePreview?url=" + encodedSource;
    }

    /**
     * 构造给预览服务识别格式用的ASCII 文件名：固定主名 + 原始扩展名。
     *
     * <p>扩展名<b>整体校验、要么全收要么全弃</b>，不做逐字符截断：
     * 截断会把 {@code 报告.pn g} 变成 {@code file.pn}——一个<b>合法但错误</b>的扩展名，
     * 预览服务会拿它去匹配一个不存在的格式，比老实退回 {@code file} 更难排查。
     * 故只要出现任一非法字符，就视为整个扩展名不可信。</p>
     *
     * @param fileName 原始文件名（可能为 null / 空 / 无扩展名 / 扩展名含非法字符）
     * @return 只含 {@code [A-Za-z0-9._-]} 的文件名，保证 URL 解码轮次下不变形
     */
    private static String asciiNameFor(String fileName) {
        int dot = fileName == null ? -1 : fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "file";
        }
        for (int i = dot + 1; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (!safe) {
                return "file";
            }
        }
        return "file." + fileName.substring(dot + 1);
    }

    /**
     * 探活：确认预览服务可达且已启用。
     *
     * <p>用于工具调用前的快速失败——避免把一个明显不可用的地址交给用户/模型。
     * 响应体不回显（见 {@link DocumentPreviewClient} 类的内网信息防护约定）。</p>
     */
    public void ensureAvailable() {
        if (!properties.configured()) {
            throw new IllegalStateException("文档预览服务未启用或配置不完整");
        }
        outboundUrlGuard.requireSafe(properties.baseUrl(), "文档预览服务");
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/"))
                .timeout(TIMEOUT)
                .GET()
                .build();
        try {
            // 只看状态码：根路径正常会302 跳 /index，不跟随跳转也算通
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() < HTTP_OK || resp.statusCode() >= ACCEPTABLE_STATUS_LIMIT) {
                log.warn("文档预览服务探活失败 status={} baseUrl={}",
                        resp.statusCode(), properties.baseUrl());
                throw new IllegalStateException("文档预览服务不可用");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            // 异常信息含目标地址，属内网信息，只进日志不回显
            log.warn("文档预览服务探活异常 baseUrl={}: {}", properties.baseUrl(), e.getMessage());
            throw new IllegalStateException("文档预览服务不可用");
        }
    }
}
