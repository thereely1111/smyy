package com.tipdm.agentplatform.service.channel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道类型注册表：四种出站渠道的类型元数据与凭据校验规则。
 *
 * <p>类型元数据（credentials_schema / config_schema）是 JSON-Schema 风格的
 * 精简子集，前端渠道表单按 {@code properties} 动态渲染输入框（{@code format:
 * "password"} 渲染密码框，{@code required} 标星），无需为每个类型写专属表单。</p>
 *
 * <p>告警扇出的 payload 差异见 {@code AlertChannelDispatcher}；本类只管
 * 「类型是什么、要哪些凭据、级别阈值怎么校验」。</p>
 */
@Component
public class ChannelTypeRegistry {

    public static final String TYPE_WECHAT_WORK = "wechat_work";
    public static final String TYPE_DINGTALK = "dingtalk";
    public static final String TYPE_FEISHU = "feishu";
    public static final String TYPE_WEBHOOK = "webhook";

    public static final Set<String> SUPPORTED_TYPES =
            Set.of(TYPE_WECHAT_WORK, TYPE_DINGTALK, TYPE_FEISHU, TYPE_WEBHOOK);

    /** 各类型必填凭据键（create/test 时校验）。 */
    private static final Map<String, List<String>> REQUIRED_CREDENTIALS = Map.of(
            TYPE_WECHAT_WORK, List.of("webhook_url"),
            TYPE_DINGTALK, List.of("webhook_url"),
            TYPE_FEISHU, List.of("webhook_url"),
            TYPE_WEBHOOK, List.of("url"));

    /**
     * 类型元数据列表（前端「新建渠道」的类型选择与动态表单数据源）。
     */
    public List<Map<String, Object>> listTypes() {
        List<Map<String, Object>> types = new java.util.ArrayList<>();

        types.add(typeDef(TYPE_WECHAT_WORK, "企业微信机器人",
                "群机器人 webhook，markdown 消息，与平台全局告警渠道同构。",
                Map.of(
                        "webhook_url", credDef("Webhook 地址",
                                "企业微信群机器人 webhook 地址"
                                        + "（https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=...）", "password"),
                        "secret", credDef("加签密钥", "机器人开启「加签」安全设置时必填", "password")),
                minLevelSchema("warn")));

        types.add(typeDef(TYPE_DINGTALK, "钉钉机器人",
                "群机器人 webhook，markdown 消息，支持加签安全设置。",
                Map.of(
                        "webhook_url", credDef("Webhook 地址",
                                "钉钉自定义机器人 webhook 地址"
                                        + "（https://oapi.dingtalk.com/robot/send?access_token=...）", "password"),
                        "secret", credDef("加签密钥", "机器人安全设置为「加签」时必填（SEC 开头的密钥）", "password")),
                minLevelSchema("warn")));

        types.add(typeDef(TYPE_FEISHU, "飞书机器人",
                "自定义机器人 webhook，文本消息，支持签名校验。",
                Map.of(
                        "webhook_url", credDef("Webhook 地址",
                                "飞书自定义机器人 webhook 地址（https://open.feishu.cn/open-apis/bot/v2/hook/...）", "password"),
                        "secret", credDef("签名校验密钥", "机器人开启「签名校验」时必填", "password")),
                minLevelSchema("warn")));

        types.add(typeDef(TYPE_WEBHOOK, "通用 HTTP Webhook",
                "向任意 HTTP 端点 POST 告警 JSON（自建网关、Slack 兼容层等）。",
                Map.of(
                        "url", credDef("Webhook URL", "接收 POST JSON 的完整 URL", "password"),
                        "secret", credDef("请求头 Token", "可选；提供时以 Authorization: Bearer <token> 头携带", "password")),
                minLevelSchema("warn")));

        return types;
    }

    /** 校验渠道类型受支持。 */
    public boolean isSupported(String channelType) {
        return channelType != null && SUPPORTED_TYPES.contains(channelType);
    }

    /** 校验必填凭据齐全。返回缺失键列表（空列表 = 通过）。 */
    public List<String> missingCredentials(String channelType, Map<String, Object> credentials) {
        List<String> required = REQUIRED_CREDENTIALS.getOrDefault(channelType, List.of());
        return required.stream()
                .filter(key -> credentials == null
                        || credentials.get(key) == null
                        || credentials.get(key).toString().isBlank())
                .toList();
    }

    /** 校验 min_level 配置合法（info/warn/error，空 = warn）。 */
    public String normalizeMinLevel(String raw) {
        String value = raw == null ? null : raw.toLowerCase();
        if ("info".equals(value) || "warn".equals(value) || "error".equals(value)) {
            return value;
        }
        return "warn";
    }

    /* ─────────────────────────── 内部：schema 构造 ─────────────────────────── */

    private Map<String, Object> typeDef(String channelType, String displayName, String description,
                                        Map<String, Map<String, Object>> credentials,
                                        Map<String, Object> configSchema) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("channel_type", channelType);
        def.put("display_name", displayName);
        def.put("description", description);
        def.put("credentials_schema", Map.of(
                "properties", credentials,
                "required", REQUIRED_CREDENTIALS.get(channelType)));
        def.put("config_schema", configSchema);
        return def;
    }

    private Map<String, Object> credDef(String title, String description, String format) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("title", title);
        def.put("description", description);
        if (format != null) {
            def.put("format", format);
        }
        return def;
    }

    private Map<String, Object> minLevelSchema(String defaultLevel) {
        return Map.of("properties", Map.of(
                "min_level", Map.of(
                        "title", "外发最低级别",
                        "description", "info / warn / error，达到该级别的告警才外发到此渠道（默认 warn）",
                        "type", "string",
                        "default", defaultLevel)));
    }
}
