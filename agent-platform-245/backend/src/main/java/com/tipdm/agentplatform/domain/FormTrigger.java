package com.tipdm.agentplatform.domain;

/**
 * M11 配置驱动表单（form）的触发策略。
 * 决定运行时在哪个时机把 form 注入为 SURVEY_REQUEST 事件。
 */
public enum FormTrigger {
    /** 不启用（默认）。 */
    disabled,
    /** 进入会话时由前端渲染一次（语义对齐既有 welcomeForm；后端 /form/answer 无 pending 时按 welcome 路径走）。 */
    on_session_start,
    /** 每轮对话开始都发（后端 streamChat 前置门：先发表单，hold 用户原始消息，答后再续）。 */
    on_round_start,
    /** 同一 user×agent 仅发一次（持久化去重）。 */
    once
}
