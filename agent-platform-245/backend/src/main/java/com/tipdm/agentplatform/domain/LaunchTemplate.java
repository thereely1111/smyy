package com.tipdm.agentplatform.domain;

import java.util.Map;

/**
 * 非对话式启动模板：前端渲染为功能卡片，点击后把 instruction 作为结构化指令发送给 Agent。
 * params 为可选预填参数（随指令一起提示给模型，如 JSON 文本）。
 */
public record LaunchTemplate(
        String title,
        String instruction,
        Map<String, Object> params) {
}
