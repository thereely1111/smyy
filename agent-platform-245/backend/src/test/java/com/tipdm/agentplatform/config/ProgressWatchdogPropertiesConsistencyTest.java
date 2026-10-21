package com.tipdm.agentplatform.config;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 停滞看护配置的<b>代码默认值与 application.yml 必须一致</b>（守护类）。
 *
 * <p>背景：曾出现 {@code ProgressWatchdogProperties.wakeCooldownMinutes=30} 而
 * {@code application.yml} 是 {@code 15}。yml 值一直生效（绑定覆盖默认值），
 * 于是「补醒间隔到底是 15 还是 30 分钟」这件事只能靠翻配置才知道——排查
 * 「会话每 15 分钟被偷跑」时先查代码、拿到 30，与日志的 15 分钟节拍矛盾，
 * 误判为「冷却失效」，白查一轮。根因是这两组数字没有单一真源。</p>
 *
 * <p>本测试把 yml 当权威、用它校验代码默认值，于是不一致会在 CI 里立刻红。
 * 它同时也是「yml 里的键真能绑上」的证明——只比对数字不够，还要确认键名
 * 与 {@code @ConfigurationProperties} 的 relaxed binding 对得上。</p>
 */
class ProgressWatchdogPropertiesConsistencyTest {

    private static final String YML = "src/main/resources/application.yml";
    /**
     * 一行形如 {@code    key-name: ${ENV_VAR:123}} 的数值型配置。
     * 逐行匹配（不用 {@code \s+}跨行：它会吃掉换行，导致上一行末尾与下一行之间误配）。
     */
    private static final Pattern YML_ENTRY =
            Pattern.compile("^[ \\t]+([a-z][a-z0-9-]*):[ \\t]*\\$\\{[A-Z0-9_]+:(\\d+)}");

    /** camelCase → kebab-case（Spring Boot relaxed binding 的键名形态）。 */
    private static String kebab(String camel) {
        return camel.replaceAll("(?<!^)(?=[A-Z])", "-").toLowerCase();
    }

    @Test
    void codeDefaultsMatchApplicationYml() throws Exception {
        Map<String, String> ymlValues = readYmlValues();
        assertThat(ymlValues)
                .as("yml 中 agent-platform.progress-watchdog 段应至少含7 个数值型配置")
                .hasSizeGreaterThanOrEqualTo(7);

        ProgressWatchdogProperties code = new ProgressWatchdogProperties();
        Map<String, Long> codeValues = new LinkedHashMap<>();
        codeValues.put(kebab("stallMinMinutes"), code.getStallMinMinutes());
        codeValues.put(kebab("wakeCooldownMinutes"), code.getWakeCooldownMinutes());
        codeValues.put(kebab("activeWindowHours"), code.getActiveWindowHours());
        codeValues.put(kebab("stallAlertMinMinutes"), code.getStallAlertMinMinutes());
        codeValues.put(kebab("hitlAlertMinMinutes"), code.getHitlAlertMinMinutes());
        codeValues.put(kebab("maxWakeAttempts"), (long) code.getMaxWakeAttempts());
        codeValues.put(kebab("textEndingGraceMinutes"), code.getTextEndingGraceMinutes());
        codeValues.put(kebab("leaseStaleMinutes"), code.getLeaseStaleMinutes());

        // 每个代码字段都必须在 yml 里有对应键：只在代码里存在的字段无法按环境调整，
        // 改它得改代码，而改动对读配置的人不可见。
        assertThat(codeValues.keySet())
                .as("代码里的每个看护阈值都应在 yml 中显式列出（否则只能改代码默认值）")
                .containsAll(ymlValues.keySet());

        ymlValues.forEach((key, ymlRaw) -> {
            Long codeVal = codeValues.get(key);
            assertThat(codeVal)
                    .as("yml 键 `%s` 在代码字段默认值里没有对应项", key)
                    .isNotNull();
            assertThat(String.valueOf(codeVal))
                    .as("配置不一致：application.yml 的 `%s` = %s，而代码默认字段 %s = %s。"
                            + "以 yml 为准，请把代码默认值改成同值（否则读代码的人会被误导，"
                            + "且键缺失时静默用另一组数字比启动失败更难查）",
                            key, ymlRaw, camelOf(key), codeVal)
                    .isEqualTo(ymlRaw);
        });
    }

    /**
     * 键名拼错时（如 {@code wake-cooldown-min}）上一条会因数字仍匹配而放过，
     * 这条按 Spring 的 relaxed binding 规则核对键名形态：每个 yml 键去掉前缀后
     * 应恰好是某个字段的 kebab-case。
     */
    @Test
    void ymlKeyNamesMatchFieldNames() throws Exception {
        Map<String, String> ymlValues = readYmlValues();
        ProgressWatchdogProperties code = new ProgressWatchdogProperties();
        Map<String, String> fieldKebab = new LinkedHashMap<>();
        fieldKebab.put(kebab("stallMinMinutes"), "stallMinMinutes");
        fieldKebab.put(kebab("wakeCooldownMinutes"), "wakeCooldownMinutes");
        fieldKebab.put(kebab("activeWindowHours"), "activeWindowHours");
        fieldKebab.put(kebab("stallAlertMinMinutes"), "stallAlertMinMinutes");
        fieldKebab.put(kebab("hitlAlertMinMinutes"), "hitlAlertMinMinutes");
        fieldKebab.put(kebab("maxWakeAttempts"), "maxWakeAttempts");
        fieldKebab.put(kebab("textEndingGraceMinutes"), "textEndingGraceMinutes");
        fieldKebab.put(kebab("leaseStaleMinutes"), "leaseStaleMinutes");

        assertThat(ymlValues.keySet())
                .as("yml 键名必须与字段名对应（relaxed binding），拼错的键会被静默忽略")
                .isSubsetOf(fieldKebab.keySet());

        // 字段确实存在于类上（编译期外的最后一道确认，避免改名后测试仍绿）
        ymlValues.keySet().forEach(k -> {
            String field = fieldKebab.get(k);
            assertThat(hasField(field))
                    .as("yml 键 `%s` 映射到字段 %s，但该字段在类上不存在", k, field)
                    .isTrue();
        });
        assertThat(code).isNotNull();
    }

    private static boolean hasField(String name) {
        try {
            ProgressWatchdogProperties.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static Map<String, String> readYmlValues() throws Exception {
        String text = Files.readString(Path.of(YML));
        int start = text.indexOf("  progress-watchdog:");
        assertThat(start).as("yml 中应存在 agent-platform.progress-watchdog 段").isPositive();
        // 段落 = 从该行到下一个同级（缩进 2 空格）键为止
        String section = text.substring(start).split("\n  [a-z]", 2)[0];
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : section.split("\n")) {
            Matcher m = YML_ENTRY.matcher(line);
            if (m.matches()) {
                out.put(m.group(1), m.group(2));
            }
        }
        return out;
    }

    private static String camelOf(String kebabKey) {
        StringBuilder sb = new StringBuilder();
        for (String part : kebabKey.split("-")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0))).append(sb.length() == 0 ? "" : part.substring(1));
        }
        return sb.toString();
    }
}