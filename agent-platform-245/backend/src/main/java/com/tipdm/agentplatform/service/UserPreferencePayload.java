package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 用户偏好：一个 record，字段与 {@code user_preferences.prefs} 的 JSON 一一对应。
 *
 * <p><b>为什么偏好体在库里是 JSONB 而这里用强类型 record</b>：存JSONB 是为了让新增
 * 偏好项不必 ALTER TABLE；这一层用 record 承担取值范围校验。若直接存 Map，
 * 一个非法主题值（如 {@code "purple"}）会静默落库，直到前端读出来才出错。</p>
 *
 * <p>⚠️ 新增偏好项时同时改三处：{@code @JsonProperty} 字段、{@link #normalize()} 的
 * 归一、{@link #DEFAULT} 的默认值。只改字段不改正则会导致「老用户读到新偏好时
 * 被当成非法值丢弃」。</p>
 *
 * @param theme 界面主题：{@code light} / {@code dark} / {@code system}。
 *               此前只能存 {@code 'true'}/{@code 'false'}，表达不了「跟随系统」——
 *               而前端的 {@code applySystemTheme} 其实一直支持它
 * @param language 界面语言：{@code zh} / {@code en}。<b>不再有「跟随浏览器」态</b>：
 *               语言必须是明确二选一（2026-10-16 用户要求）。此前可为空表示
 *               「交给 i18next 的 navigator 探测」，结果是用户在这页看到「跟随浏览器」却
 *               无法说出自己实际看到哪种语言，且该态与偏好表的语义（记录用户选择）矛盾
 * @param defaultModel 全局默认模型；结构化三元组，与对话页 {@code chat_model_config}
 *                     同一形状（{@code type} / {@code credential_id} / {@code model}）。
 *                     为空表示不设，落到平台默认模型。
 *                     ⚠️ **必须是结构化而非裸模型名**：裸名无法唯一定位——同一模型名可能
 *                     出现在多个凭证下（如「我的模型」与平台共享各有同名模型），
 *                     用户无从确认自己选的是哪一个
 * @param defaultLandingPath 登录后默认落地页路径；空则按权限算第一个可达页
 * @param dndEnabled 是否启用免打扰
 * @param dndStart 免打扰起始（{@code HH:mm}，本地时间）
 * @param dndEnd 免打扰结束（{@code HH:mm}）；小于起始值表示跨零点
 * @param alertMinLevel 个人告警最低级别：{@code all} / {@code error}。
 *                      {@code all} 表示不额外过滤；平台级阈值仍照常生效
 */
public record UserPreferencePayload(
        @JsonProperty("theme") String theme,
        @JsonProperty("language") String language,
        @JsonProperty("defaultModel") DefaultModelRef defaultModel,
        @JsonProperty("defaultLandingPath") String defaultLandingPath,
        @JsonProperty("dndEnabled") Boolean dndEnabled,
        @JsonProperty("dndStart") String dndStart,
        @JsonProperty("dndEnd") String dndEnd,
        @JsonProperty("alertMinLevel") String alertMinLevel) {

    /**
     * 默认模型引用。
     *
     * @param type 供应商标识（{@code credential.data.type}）
     * @param credentialId 凭证 id
     * @param model 模型名（{@code ModelCard.name}，非展示用 label）
     */
    public record DefaultModelRef(
            @JsonProperty("type") String type,
            @JsonProperty("credentialId") String credentialId,
            @JsonProperty("model") String model) {

        public boolean isComplete() {
            return type != null && !type.isBlank()
                    && credentialId != null && !credentialId.isBlank()
                    && model != null && !model.isBlank();
        }
    }

    /** 全部可选主题。 */
    public static final List<String> THEMES = List.of("light", "dark", "system");
    /** 全部可选语言。二选一，无「跟随浏览器」。 */
    public static final List<String> LANGUAGES = List.of("zh", "en");
    /** 全部可选的个人告警级别。 */
    public static final List<String> ALERT_LEVELS = List.of("all", "error");

    /** 默认语言：中文（本平台主要使用者是中文团队，且中文是文案第一语言）。 */
    public static final String DEFAULT_LANGUAGE = "zh";

    /** 全部字段取默认值的偏好；新用户首次读到它。 */
    public static final UserPreferencePayload DEFAULT =
            new UserPreferencePayload("system", DEFAULT_LANGUAGE, null, null,
                    Boolean.FALSE, "22:00", "08:00", "all");

    /**
     * 归一 + 校验：逐字段把非法值换成默认值，而不是整体拒绝。
     *
     * <p>逐字段而非整体拒绝的理由：偏好是体验参数，一个字段非法不该让用户其余
     * 已经填好的偏好全部保存失败。但<b>静默丢弃用户设置同样不可接受</b>，故每个
     * 被替换的字段都打warn 日志——排查「我设了怎么没生效」时能直接看到。</p>
     */
    public UserPreferencePayload normalize() {
        return new UserPreferencePayload(
                pick(theme, THEMES, DEFAULT.theme, "theme"),
                // 空值回默认语言而非留空：没有「跟随浏览器」这个选项了
                pick(language, LANGUAGES, DEFAULT.language, "language"),
                normalizeModel(defaultModel),
                normalizePath(defaultLandingPath),
                dndEnabled == null ? DEFAULT.dndEnabled : dndEnabled,
                clockOrDefault(dndStart, DEFAULT.dndStart),
                clockOrDefault(dndEnd, DEFAULT.dndEnd),
                pick(alertMinLevel, ALERT_LEVELS, DEFAULT.alertMinLevel, "alertMinLevel"));
    }

    /**
     * 默认模型归一：三元组缺任何一项就整体丢弃。
     *
     * <p>整体丢弃而非部分修补——半截的引用（只有模型名没有凭证）无法定位到
     * 具体模型，留着会在使用时静默解析失败，而用户看不出是哪一项坏了。</p>
     */
    private static DefaultModelRef normalizeModel(DefaultModelRef raw) {
        if (raw == null) {
            return null;
        }
        DefaultModelRef trimmed = new DefaultModelRef(
                raw.type() == null ? null : raw.type().trim(),
                raw.credentialId() == null ? null : raw.credentialId().trim(),
                raw.model() == null ? null : raw.model().trim());
        if (trimmed.isComplete()) {
            return trimmed;
        }
        log.warn("偏好 defaultModel 缺少字段（type/credentialId/model 之一为空），已整体忽略");
        return null;
    }

    /**
     * 落地页路径归一：只接受站内相对路径。
     *
     * <p>刻意拒绝绝对 URL 与协议头：偏好由用户自己写入，但落点会被浏览器直接跳转，
     * 接受任意外链等于开了一个「把登录后的默认落地页指向任意站点」的口子。
     * 必须是 {@code /} 开头且不含协议。</p>
     */
    private static String normalizePath(String raw) {
        String v = blankToNull(raw);
        if (v == null) {
            return null;
        }
        if (!v.startsWith("/") || v.startsWith("//") || v.contains("://")) {
            log.warn("偏好 defaultLandingPath 不是站内路径（{}），已忽略", v);
            return null;
        }
        return v;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * 校验 {@code HH:mm}。
     *
     * <p>用正则而非 {@code DateTimeFormatter}：解析器会接受 {@code 8:00} 这类缺位形式，
     * 而前端下拉只会给出两位数的形式，放宽解析等于允许一批永远匹配不上正则的值入库。</p>
     */
    private static String clockOrDefault(String raw, String fallback) {
        String v = blankToNull(raw);
        if (v == null) {
            return fallback;
        }
        if (!v.matches("([01]\\d|2[0-3]):[0-5]\\d")) {
            log.warn("偏好中的时间 {} 不是 HH:mm 格式，已回退为 {}", v, fallback);
            return fallback;
        }
        return v;
    }

    private static String pick(String raw, List<String> allowed, String fallback, String field) {
        String v = blankToNull(raw);
        if (v == null) {
            return fallback;
        }
        if (!allowed.contains(v)) {
            log.warn("偏好 {} 的取值 {} 不在允许范围 {} 内，已回退为 {}", field, v, allowed, fallback);
            return fallback;
        }
        return v;
    }

    private static final Logger log = LoggerFactory.getLogger(UserPreferencePayload.class);
}
