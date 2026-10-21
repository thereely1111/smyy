package com.tipdm.agentplatform.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;

import java.util.List;
import java.util.regex.Pattern;

/** Removes host filesystem prefixes from model/tool output before it crosses the API boundary. */
public final class PathDisclosureSanitizer {
    /**
     * 匹配运行态绝对路径前缀。覆盖两种布局：
     * <ul>
     *   <li>{@code <host>/workspace/runtimes/<agentId>/<sessionId>/}（会话工作区）</li>
     *   <li>{@code <host>/workspace/runtimes/<agentId>/.harness} 等智能体级目录
     *       （2026-09-15 补：会话段可选，否则 AGENTS.md/.harness 这类路径漏网）</li>
     * </ul>
     * 字符类排除空白与引号：匹配不会跨越 JSON 字符串边界，替换值
     * （{@code workspace/}）不含 JSON 结构字符——对序列化后的 JSON 串做
     * 字符串级替换始终保持 JSON 合法。
     */
    private static final Pattern RUNTIME_ABSOLUTE_PATH = Pattern.compile(
            "(?:[A-Za-z]:[\\\\/]|/)[^\\s\\\"'<>]*?[\\\\/]workspace[\\\\/]runtimes[\\\\/]"
                    + "[^\\s\\\"'<>\\\\/]+[\\\\/](?:[^\\s\\\"'<>\\\\/]+[\\\\/])?",
            Pattern.CASE_INSENSITIVE);

    private PathDisclosureSanitizer() { }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return RUNTIME_ABSOLUTE_PATH.matcher(value).replaceAll("workspace/");
    }

    public static Msg sanitize(Msg message) {
        if (message == null || message.getContent() == null) {
            return message;
        }
        List<ContentBlock> cleaned = message.getContent().stream().map(block -> {
            if (block instanceof TextBlock text && text.getText() != null) {
                return TextBlock.builder().text(sanitize(text.getText())).build();
            }
            return block;
        }).toList();
        return message.withContent(cleaned);
    }
}
