package com.tipdm.agentplatform.service;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.extensions.model.dashscope.tool.DashScopeMultiModalTool;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AG-UI 平台内置读图工具：让纯文本主模型也能「看」图片。
 *
 * <p>背景：框架内置的 {@code read_file} 只读文本（返回 {@code String}，无二进制/多模态分支），
 * 无法让模型看到 PNG 等图片内容。但审核类智能体（如 {@code jc-6-导图生成审核}）需要
 * 「AI 读取导图 PNG」的视觉审核能力。</p>
 *
 * <p>实现：本工具内部独立调用 DashScope 多模态模型（qwen-vl 系列）读图，把图片转成
 * <b>文本描述</b>回传给主 Agent。主 Agent 无需多模态能力，读图由工具侧独立完成，
 * 二者解耦。对应框架现成能力 {@link DashScopeMultiModalTool#dashscopeImageToText}。</p>
 *
 * <p>职责边界：本工具只负责「把图片转成文字描述」，供主 Agent 做<b>内容对应关系</b>的
 * 文本判定（导图内容是否与目录/章节一致）。纯视觉审美（配色/布局/留白）不做判断，
 * 交由主编在闸门处看图定夺。</p>
 */
public final class ImageTools {

    /** 工具名：与审核智能体 tools 清单、jc-6-导图生成审核 SKILL 约定对齐。 */
    public static final String READ_IMAGE_TOOL = "read_image";

    private ImageTools() {
    }

    /**
     * 构建读图工具。内部持有独立的多模态模型调用器。
     *
     * @param apiKey   DashScope API key（与主模型同款百炼 key；空串则 fallback 到框架环境变量）
     * @param vlModel  多模态读图模型名（如 qwen3-vl-plus）
     */
    public static ToolBase readImageTool(String apiKey, String vlModel) {
        // 多模态读图调用器：构造器只吃 apiKey，模型名在每次调用时传入。
        final DashScopeMultiModalTool mmTool = new DashScopeMultiModalTool(apiKey);

        return new ToolBase(
                READ_IMAGE_TOOL,
                "读取图片内容并以文字描述返回。用于核对图片（如思维导图 PNG）的内容是否与文字材料"
                        + "（目录/章节）对应。仅返回文字描述，不做主观审美判断。"
                        + "支持本地文件路径或可访问的 http(s) 图片 URL。",
                readImageProperties(),
                true,   // readOnly
                true,   // concurrencySafe
                false,  // mcp
                null,   // mcpName
                false,  // externalTool
                false   // stateInjected
        ) {
            @Override
            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                Map<String, Object> input = param.getInput();
                String imageUrl = asString(input.get("image"));
                String prompt = asString(input.get("prompt"));
                String callId = param.getToolUseBlock().getId();

                if (imageUrl == null || imageUrl.isBlank()) {
                    return Mono.just(ToolResultBlock.error("read_image: 'image' 参数（图片路径或 URL）不能为空"));
                }
                String effectivePrompt = (prompt == null || prompt.isBlank())
                        ? "Describe the image"
                        : prompt;

                // 调多模态模型读图，把返回的文本重新包成带 id/name 的工具结果块。
                return mmTool.dashscopeImageToText(List.of(imageUrl), effectivePrompt, vlModel)
                        .map(block -> {
                            String text = extractText(block);
                            if (text == null) {
                                return ToolResultBlock.of(callId, READ_IMAGE_TOOL,
                                        TextBlock.builder().text("read_image: 读图失败，未返回文字描述").build());
                            }
                            return ToolResultBlock.of(callId, READ_IMAGE_TOOL,
                                    TextBlock.builder().text(text).build());
                        })
                        .onErrorResume(err -> Mono.just(
                                ToolResultBlock.of(callId, READ_IMAGE_TOOL,
                                        TextBlock.builder()
                                                .text("read_image: 读图异常：" + err.getMessage())
                                                .build())));
            }
        };
    }

    /** 从 ToolResultBlock 的 output 中提取首个 TextBlock 文本；无则返回 null。 */
    private static String extractText(ToolResultBlock block) {
        if (block == null || block.getOutput() == null) {
            return null;
        }
        for (var content : block.getOutput()) {
            if (content instanceof TextBlock tb && tb.getText() != null && !tb.getText().isBlank()) {
                return tb.getText();
            }
        }
        return null;
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Map<String, Object> readImageProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("image", Map.of(
                "type", "string",
                "description", "图片路径（本地文件绝对路径）或 http(s) 图片 URL"));
        props.put("prompt", Map.of(
                "type", "string",
                "description", "读图指令，说明要提取/核对哪些信息（可选，默认整体描述图片）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("image"));
        return schema;
    }
}
