package com.tipdm.agentplatform.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子智能体汇报块（{@code 【TASK_RESULT】}）的解析器。
 *
 * <p>子智能体每轮 return 末尾必须输出唯一一个 {@code 【TASK_RESULT】} YAML 块；平台在这里把它
 * 变成结构化数据，用于「会话 → 子任务汇报」视图：谁派发的、什么状态、完成了什么、
 * 产出了哪些文件、有没有缺失。解析是<b>尽力而为</b>的：解析失败不抛异常（模型输出不可能百分百
 * 合规），失败时返回 {@code null}，由上层退化为"该子会话没有可解析的汇报"。</p>
 *
 * <p>本类只认键名，不认缩进细节：取值按 {@code key: value} 行解析，数组支持
 * {@code [a, b]} 行内形式与 {@code - item} 块形式；嵌套的 {@code missing_artifacts}
 * （形如 {@code - chapter: 第4章 / artifacts: [...]}）只在文本层面保留，不展开为结构。</p>
 */
public final class SubagentReportParser {

    /** 汇报块围栏：模型可能用 ```yaml / ```yml / ```md / 无围栏，统一按标记行定位。 */
    private static final Pattern BLOCK = Pattern.compile(
            "【TASK_RESULT】\\s*\\n(.*?)(?:\\n```|\\n(?=【)|\\z)", Pattern.DOTALL);

    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([a-z_]+):\\s*(.*)$");

    private static final List<String> VALID_STATUS =
            List.of("COMPLETED", "PARTIAL", "BLOCKED", "FAILED");

    private SubagentReportParser() { }

    /**
     * 从一段文本（通常是子智能体最后的 assistant 回复）中解析汇报块。
     *
     * @return 解析结果；文本中没有 {@code 【TASK_RESULT】} 时返回 {@code null}
     */
    public static Report parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher block = BLOCK.matcher(text);
        String body = null;
        while (block.find()) {
            body = block.group(1);   // 取最后一个块（模型偶尔会重复输出，以最后一次为准）
        }
        if (body == null) {
            return null;
        }

        String status = null;
        String stage = null;
        String summary = null;
        String nextAction = null;
        Boolean resumable = null;
        List<String> completed = new ArrayList<>();
        List<String> inProgress = new ArrayList<>();
        List<String> remaining = new ArrayList<>();
        List<String> artifacts = new ArrayList<>();
        List<String> privates = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        String current = null;
        for (String rawLine : body.split("\n")) {
            String line = rawLine.replace("\r", "");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("- ")) {
                if (current != null) {
                    appendItem(current, trimmed.substring(2).trim(),
                            completed, inProgress, remaining, artifacts, privates, missing);
                }
                continue;
            }
            Matcher kv = KEY_LINE.matcher(line);
            if (!kv.matches()) {
                continue;
            }
            String key = kv.group(1);
            String value = kv.group(2).trim();
            switch (key) {
                case "status" -> {
                    status = value.toUpperCase().replaceAll("[^A-Z_]", "");
                }
                case "stage" -> {
                    stage = value;
                }
                case "summary" -> {
                    summary = value;
                }
                case "next_action" -> {
                    nextAction = value;
                }
                case "resumable" -> {
                    resumable = "true".equalsIgnoreCase(value);
                }
                case "completed" -> completed.addAll(inlineList(value));
                case "in_progress" -> inProgress.addAll(inlineList(value));
                case "remaining" -> remaining.addAll(inlineList(value));
                case "artifacts" -> artifacts.addAll(inlineList(value));
                case "private" -> privates.addAll(inlineList(value));
                case "missing_artifacts" -> missing.addAll(inlineList(value));
                default -> {
                    // 未知键（如审核智能体的 reviewed/passed/failed）忽略，不视为错误
                }
            }
            current = ARRAY_KEYS.contains(key) ? key : null;
        }

        if (status == null && artifacts.isEmpty() && completed.isEmpty()) {
            return null;   // 空块（例如只有围栏）视为无汇报
        }
        if (status == null || !VALID_STATUS.contains(status)) {
            status = "PARTIAL";   // 状态缺失或非法：按"部分完成"处理，避免误判为已完成
        }
        return new Report(status, stage, summary, nextAction, resumable != null && resumable,
                completed, inProgress, remaining, artifacts, privates, missing);
    }

    /** 块形式可接数组的键（这些键下面的 {@code - item} 行属于它）。 */
    private static final Set<String> ARRAY_KEYS = Set.of(
            "completed", "in_progress", "remaining", "artifacts", "private", "missing_artifacts");

    private static void appendItem(String key, String item,
                                   List<String> completed, List<String> inProgress,
                                   List<String> remaining, List<String> artifacts,
                                   List<String> privates, List<String> missing) {
        switch (key) {
            case "completed" -> completed.add(item);
            case "in_progress" -> inProgress.add(item);
            case "remaining" -> remaining.add(item);
            case "artifacts" -> artifacts.add(item);
            case "private" -> privates.add(item);
            case "missing_artifacts" -> missing.add(item);
            default -> { }
        }
    }

    /** 解析 {@code [a, b]} 行内数组；非数组形式（单值）按单元素处理。 */
    private static List<String> inlineList(String value) {
        if (value == null || value.isBlank() || "[]".equals(value.trim())) {
            return List.of();
        }
        String v = value.trim();
        if (v.startsWith("[") && v.endsWith("]")) {
            v = v.substring(1, v.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String part : v.split(",")) {
            String item = stripQuotes(part.trim());
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /**
     * 一次子智能体汇报。
     *
     * @param status          COMPLETED / PARTIAL / BLOCKED / FAILED（缺失或非法时为 PARTIAL）
     * @param stage           阶段标识（可空）
     * @param summary         一句话说明
     * @param nextAction      下一步动作
     * @param resumable       是否可续跑
     * @param completed       已完成项（章节号/步骤名）
     * @param inProgress      进行中项
     * @param remaining       剩余项
     * @param artifacts       本次产出（运行态根相对路径：{@code <会话目录>/<相对路径>}）
     * @param privates        私有临时件
     * @param missingArtifacts 缺失产物（文本层面，不展开嵌套结构）
     */
    public record Report(String status, String stage, String summary, String nextAction,
                         boolean resumable, List<String> completed, List<String> inProgress,
                         List<String> remaining, List<String> artifacts, List<String> privates,
                         List<String> missingArtifacts) { }
}
