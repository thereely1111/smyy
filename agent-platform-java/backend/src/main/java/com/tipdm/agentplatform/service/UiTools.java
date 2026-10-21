package com.tipdm.agentplatform.service;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AG-UI 平台内置工具：让 Agent 在运行中驱动前端 UI。
 *
 * <ul>
 *   <li>{@code ui_form}（external）：Agent 请求用户填写表单。模型调用时携带
 *       JSON Schema 表单定义，运行时按外部工具挂起（{@code TOOL_RESULT_END state=running} +
 *       {@code DONE}），前端渲染可编辑表单；用户提交后通过 {@code /hitl/resume}
 *       的 {@code executionResults} 把表单值回传，Agent 以工具结果继续执行。</li>
 *   <li>{@code ui_card}（internal）：Agent 展示结构化内容卡片（text/table/metrics/list），
 *       前端根据工具入参渲染卡片。</li>
 *   <li>{@code ui_form}（external，M8）：Agent 发起一份产品级表单
 *       （checkbox-group / radio-group / select / text / textarea / number / other-text），
 *       前端以 FormCard 渲染并提交；答案通过 {@code /hitl/form/answer} 回传为下一回合
 *       Agent 可见的 ToolResultBlock。</li>
 * </ul>
 */
public final class UiTools {

    public static final String FORM_TOOL = "ui_form";
    public static final String CARD_TOOL = "ui_card";
    public static final String FORM_ADVANCED_TOOL = "ui_form_advanced";
    /** 语义专用的人工审批/闸门工具，底层复用 advanced form 的中断恢复机制。 */
    public static final String GATE_TOOL = "ui_gate";

    private UiTools() {
    }

    /** UI tools are the platform's HITL protocol, not permission-confirm tools. */
    private static Mono<PermissionDecision> platformAllow() {
        return Mono.just(PermissionDecision.allow("platform-ui"));
    }

    /**
     * 工具入参是否呈「审批闸门」形状。
     *
     * <p>只用于 {@code ui_card}：它是 internal 工具、调用即返回 SUCCESS，模型用卡片
     * 输出「审批通过 / 已通过」就能让流程继续，等于绕过 ui_gate——这正是历史上
     * 「HITL 假放行」的成因之一。</p>
     *
     * <p>{@code ui_form} 不需要这道守卫：它是 external 工具，必须等用户真实提交
     * 才产生结果，模型无法用它伪造审批结论；把守卫装在那里只会误伤内容里含
     * 「审批」字样的正常表单。</p>
     */
    private static boolean isGateShaped(Object input) {
        String s = String.valueOf(input).toLowerCase();
        return s.contains("闸门") || s.contains("审批") || s.contains("不通过")
                || s.contains("approve") || s.contains("approval");
    }

    /**
     * 表单收集工具：external 模式，调用后挂起等待用户提交。
     */
    public static ToolBase formTool() {
        return new ToolBase(
                FORM_TOOL,
                "请求用户填写表单：参数 schema 为表单字段的 JSON Schema（type=object，含 properties/required），"
                        + "提交后表单值会作为本工具的结果返回给你",
                formProperties(),
                false,
                true,
                false,
                null,
                true,
                false) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                // 不需要闸门守卫：ui_form 是 external 工具，必须等用户真实提交才产生结果，
                // 模型无法用它伪造「审批已通过」。守卫装在 ui_card 上（见 isGateShaped）。
                return Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name(FORM_TOOL)
                        .output(TextBlock.builder().text("[ui_form] awaiting user submission").build())
                        .state(ToolResultState.RUNNING)
                        .build());
            }

            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                               PermissionContextState permissions) {
                return platformAllow();
            }
        };
    }

    /**
     * 内容卡片工具：internal 模式，立即执行；前端按入参渲染卡片。
     */
    public static ToolBase cardTool() {
        return new ToolBase(
                CARD_TOOL,
                "仅展示只读结构化内容卡片给用户：kind 为 text/table/metrics/list，data 为对应内容数据。"
                        + "本工具不收集用户输入、不产生审批结果，严禁用于人工确认、闸门放行或替代 ui_gate；"
                        + "需要主编确认/选择/批准时必须调用 ui_gate。",
                cardProperties(),
                false,
                true,
                false,
                null,
                false,
                false) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                // Runtime guard: prompt text alone is not enough to prevent a model from
                // using the read-only card as an approval gate. ui_card is internal and
                // returns SUCCESS immediately, so a card saying "审批通过" would let the
                // model continue as though a human had approved. Fail the call instead so
                // the model must issue a real ui_gate request.
                if (isGateShaped(param.getInput())) {
                    return Mono.just(ToolResultBlock.builder()
                            .id(param.getToolUseBlock().getId())
                            .name(CARD_TOOL)
                            .output(TextBlock.builder().text(
                                    "[ui_card] rejected: approval decisions must use ui_gate; no approval was recorded")
                                    .build())
                            .state(ToolResultState.ERROR)
                            .build());
                }
                return Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name(CARD_TOOL)
                        .output(TextBlock.builder().text("[ui_card] rendered").build())
                        .state(ToolResultState.SUCCESS)
                        .build());
            }

            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                               PermissionContextState permissions) {
                return platformAllow();
            }
        };
    }

    private static Map<String, Object> formProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "表单标题"));
        props.put("description", Map.of("type", "string", "description", "表单说明"));
        props.put("schema", Map.of(
                "type", "object",
                "description", "表单字段的 JSON Schema：{type:object, properties:{字段:Schema}, required:[...]}"));
        props.put("values", Map.of("type", "object", "description", "预填值（可选）"));
        props.put("submitLabel", Map.of("type", "string", "description", "提交按钮文案，默认：下一步"));
        return objectSchema(props, List.of());
    }

    private static Map<String, Object> cardProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "卡片标题"));
        props.put("kind", Map.of(
                "type", "string",
                "enum", List.of("text", "table", "metrics", "list"),
                "description", "内容类型"));
        props.put("data", Map.of(
                "type", List.of("string", "object", "array"),
                "description", "内容数据：text 为 string；table 为 {columns:string[],rows:any[][]}；"
                        + "metrics 为 [{label,value}]；list 为 string[]"));
        return objectSchema(props, List.of("kind", "data"));
    }

    /**
     * M8 产品级表单工具：external 模式。模型调一次，挂起前端等用户填写；
     * 提交后 answers 直接以 ToolResult(SUCCESS) 回传为下一回合工具结果。
     */
    public static ToolBase advancedFormTool() {
        return new ToolBase(
                FORM_ADVANCED_TOOL,
                "在对话中向用户展示一份结构化表单，标题 + 多页 + 分组 + 联动 + 校验全部支持。"
                        + "支持 7 种 kind：checkbox-group（多选，可选最小/最大）/ radio-group（单选）/ "
                        + "select（下拉）/ text（单行）/ textarea（多行）/ number（数字 min/max/step）/"
                        + " other-text（选项 + 末尾始终打开\"其他...\"Input）。"
                        + "提交后 values 会回传为下一回合 Smart Agent 可见。",
                advancedFormProperties(),
                false,
                true,
                false,
                null,
                true,
                false) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name(FORM_ADVANCED_TOOL)
                        .output(TextBlock.builder().text("[ui_form_advanced] awaiting user submission").build())
                        .state(ToolResultState.RUNNING)
                        .build());
            }

            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                               PermissionContextState permissions) {
                return platformAllow();
            }
        };
    }

    /**
     * 人工审批闸门工具：与 ui_form_advanced 共用表单协议，但语义上只允许
     * 主编/审批人做“通过/不通过”决策。前端仍按同一 FormCard 渲染。
     */
    public static ToolBase gateTool() {
        return new ToolBase(
                GATE_TOOL,
                "发起人工审批闸门，调用后暂停运行，等待审批提交。"
                        + "本工具只承载决策表单：审核结论 decision（通过/不通过）+ 审核意见 feedback + "
                        + "业务必要的附加项（如样章章节编号）。"
                        + "待审批内容的摘要、产物路径、评审依据等属于说明性内容，必须先用普通文本输出给用户，"
                        + "再调用本工具；不得写进表单字段。不要用 ui_card 或纯文本追问替代本工具。",
                gateProperties(),
                false,
                true,
                false,
                null,
                true,
                false) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                // 载荷守卫：闸门表单schema摘掉 description 后，模型可能把同一坨摘要换到
                // title / label / 附加项里继续灌。这里做体积拦截并以 ERROR 明确告知正确的
                // 表达位置（说明性内容属于正文，不属于表单），而不是静默接受后挤爆上下文。
                String violation = findOversizedGateField(param.getInput());
                if (violation != null) {
                    return Mono.just(ToolResultBlock.builder()
                            .id(param.getToolUseBlock().getId())
                            .name(GATE_TOOL)
                            .output(TextBlock.builder().text(
                                    "[ui_gate] rejected: " + violation
                                            + "；待审批内容的摘要、产物路径、评审依据不属于表单，"
                                            + "请先用普通文本输出给用户，再用精简的 ui_gate 参数重新调用").build())
                            .state(ToolResultState.ERROR)
                            .build());
                }
                return Mono.just(ToolResultBlock.builder()
                        .id(param.getToolUseBlock().getId())
                        .name(GATE_TOOL)
                        .output(TextBlock.builder().text("[ui_gate] awaiting approval").build())
                        .state(ToolResultState.RUNNING)
                        .build());
            }

            @Override
            public Mono<PermissionDecision> checkPermissions(Map<String, Object> input,
                                                               PermissionContextState permissions) {
                return platformAllow();
            }
        };
    }

    /** 闸门表单单个字符串的上限：超出基本可以断定模型在往表单里灌正文。 */
    static final int MAX_GATE_FIELD_CHARS = 200;

    /** 闸门表单全部文本的预算：够放 decision + feedback + 若干短附加项，远不够放摘要。 */
    static final int MAX_GATE_INPUT_CHARS = 1000;

    private static final class Scan {
        private int total;
        private int longest;
        private String longestPath;
    }

    /**
     * 扫描闸门入参，返回超限原因；未超限返回 {@code null}。
     *
     * <p>ui_gate 的 schema 已移除 {@code description}，但模型仍可能把同一坨摘要换到
     * title / label / 附加项里。这里是最后一道拦截：超限就以 ERROR 回给模型，由它改写为
     * 「正文输出摘要 → 精简参数重新调用」，而不是静默接受后让这坨文本常驻上下文。</p>
     */
    static String findOversizedGateField(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        Scan scan = new Scan();
        scanStrings(input, "", scan);
        if (scan.longest > MAX_GATE_FIELD_CHARS) {
            return "字段 " + scan.longestPath + " 长度 " + scan.longest
                    + " 超过单字段上限 " + MAX_GATE_FIELD_CHARS;
        }
        if (scan.total > MAX_GATE_INPUT_CHARS) {
            return "表单文本总量约 " + scan.total + " 字符，超过闸门上限 " + MAX_GATE_INPUT_CHARS;
        }
        return null;
    }

    private static void scanStrings(Object node, String path, Scan scan) {
        if (node instanceof String s) {
            scan.total += s.length();
            if (s.length() > scan.longest) {
                scan.longest = s.length();
                scan.longestPath = path.isEmpty() ? "(root)" : path;
            }
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                scanStrings(entry.getValue(), path.isEmpty() ? key : path + "." + key, scan);
            }
            return;
        }
        if (node instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object value : iterable) {
                scanStrings(value, path + "[" + index + "]", scan);
                index++;
            }
        }
    }

    private static Map<String, Object> gateProperties() {
        Map<String, Object> decisionItem = new LinkedHashMap<>();
        decisionItem.put("type", "object");
        decisionItem.put("properties", Map.of(
                "key", Map.of("const", "decision"),
                "label", Map.of("type", "string"),
                "kind", Map.of("const", "radio-group"),
                "options", Map.of(
                        "type", "array",
                        "prefixItems", List.of(Map.of("const", "通过"), Map.of("const", "不通过")),
                        "minItems", 2,
                        "maxItems", 2),
                "required", Map.of("const", true)));
        decisionItem.put("required", List.of("key", "label", "kind", "options", "required"));

        Map<String, Object> feedbackItem = new LinkedHashMap<>();
        feedbackItem.put("type", "object");
        feedbackItem.put("properties", Map.of(
                "key", Map.of("const", "feedback"),
                "label", Map.of("type", "string"),
                "kind", Map.of("const", "textarea"),
                "required", Map.of("const", false),
                "placeholder", Map.of("type", "string")));
        feedbackItem.put("required", List.of("key", "label", "kind", "required"));

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("type", "object");
        page.put("properties", Map.of(
                "key", Map.of("const", "gate"),
                "title", Map.of("type", "string"),
                "items", Map.of(
                        "type", "array",
                        "prefixItems", List.of(decisionItem, feedbackItem),
                        "minItems", 2)));
        page.put("required", List.of("key", "title", "items"));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "闸门标题（一句话，如「闸门2 · 章节目录确认」）"));
        props.put("submitLabel", Map.of("type", "string", "description", "提交按钮文案"));
        props.put("pages", Map.of(
                "type", "array",
                "prefixItems", List.of(page),
                "minItems", 1,
                "maxItems", 1));
        return objectSchema(props, List.of("title", "pages"));
    }

    private static Map<String, Object> advancedFormProperties() {
        Map<String, Object> itemProps = new LinkedHashMap<>();
        itemProps.put("key", Map.of("type", "string", "description", "字段标识（key）"));
        itemProps.put("label", Map.of("type", "string", "description", "显示标签"));
        itemProps.put("kind", Map.of(
                "type", "string",
                "enum", List.of("checkbox-group", "radio-group", "select", "text", "textarea", "number", "other-text"),
                "description", "控件类型"));
        itemProps.put("options", Map.of(
                "type", "array", "items", Map.of("type", "string"),
                "description", "选项列表（checkbox-group/radio-group/other-text）"));
        itemProps.put("min", Map.of("type", "number", "description", "最小值或勾选项数下限"));
        itemProps.put("max", Map.of("type", "number", "description", "最大值或勾选项数上限"));
        itemProps.put("minLength", Map.of("type", "integer", "description", "文本最小长度"));
        itemProps.put("maxLength", Map.of("type", "integer", "description", "文本最大长度"));
        itemProps.put("pattern", Map.of("type", "string", "description", "正则"));
        itemProps.put("placeholder", Map.of("type", "string"));
        itemProps.put("required", Map.of("type", "boolean"));
        itemProps.put("step", Map.of("type", "number"));
        itemProps.put("initialValue", Map.of("type",
                List.of("string", "number", "array", "boolean", "null")));

        Map<String, Object> itemsSchema = new LinkedHashMap<>();
        itemsSchema.put("type", "object");
        itemsSchema.put("properties", itemProps);
        itemsSchema.put("required", List.of("kind", "key", "label"));

        Map<String, Object> pageProps = new LinkedHashMap<>();
        pageProps.put("key", Map.of("type", "string"));
        pageProps.put("title", Map.of("type", "string"));
        pageProps.put("items", Map.of(
                "type", "array",
                "items", itemsSchema));

        Map<String, Object> pagesSchema = new LinkedHashMap<>();
        pagesSchema.put("type", "array");
        pagesSchema.put("minItems", 1);
        pagesSchema.put("description", "分页列表；多页用 key 标识 page");
        pagesSchema.put("items", Map.of(
                "type", "object",
                "properties", pageProps));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string", "description", "表单主标题"));
        props.put("description", Map.of("type", "string", "description", "副标题/说明"));
        props.put("closeable", Map.of("type", "boolean", "description", "是否显示关闭按钮（默认 true）"));
        props.put("submitLabel", Map.of("type", "string", "description", "提交按钮文案"));
        props.put("pages", pagesSchema);
        // pages 是通用表单协议的核心载荷，必须由调用方提供。
        return objectSchema(props, List.of("title", "pages"));
    }

    /**
     * Wrap a property map in the complete JSON Schema expected by AgentScope.
     *
     * <p>ToolBase#getParameters() is a schema for the whole argument object,
     * not the contents of its {@code properties} member. Keeping this wrapper
     * in one place prevents property names such as {@code kind} or {@code data}
     * from being interpreted as custom JSON Schema keywords by networknt.</p>
     */
    private static Map<String, Object> objectSchema(Map<String, Object> properties,
                                                     List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }
}
