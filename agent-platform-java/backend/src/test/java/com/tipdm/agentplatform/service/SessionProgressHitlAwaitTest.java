package com.tipdm.agentplatform.service;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 【平台事实 3】「球在主编手里」判定（lastHITLGateAwaitingHuman）。
 *
 * <p>背景（2026-09-20 main-78d39f79 实证）：停滞自检 run 渲染的 ui_gate 无人登记 →
 * watchdog 判"无 pending HITL"反复注入自检 → 模型反复重渲染闸门死循环。本判定把
 * 「最后一个 HITL 闸门未获主编真实提交」视为等人工（不再补醒），覆盖两类此前漏判：
 * 挂起但登记缺失、刚被平台超时取消（[HITL_TIMEOUT] 的 SUCCESS 不算主编决定）。</p>
 */
class SessionProgressHitlAwaitTest {

    private static ToolUseBlock hitlUse(String id, String name) {
        return new ToolUseBlock(id, name, Map.of(), Map.of());
    }

    private static Msg assistantWith(ContentBlock... blocks) {
        return Msg.builderForRole(MsgRole.ASSISTANT).content(List.of(blocks)).build();
    }

    private static Msg user(String text) {
        return new UserMessage(text);
    }

    private static Msg timeoutResult(String toolCallId) {
        return assistantWith(ToolResultBlock.of(toolCallId, "ui_gate",
                TextBlock.builder().text(
                        "[HITL_TIMEOUT] 审批超时自动取消：主编未在 30 分钟内提交。").build())
                .withState(ToolResultState.SUCCESS));
    }

    @Test
    void noGateMeansNotAwaiting() {
        assertFalse(SessionProgressService.lastHITLGateAwaitingHuman(
                List.of(user("帮我写教材"), assistantWith(hitlUse("t1", "execute")))));
    }

    @Test
    void pendingGateWithoutResultAwaitsWithoutRealUserInput() {
        // 挂起但登记缺失的形态：tool_use 悬着、无结果、主编没回来过 → 等人工。
        assertTrue(SessionProgressService.lastHITLGateAwaitingHuman(
                List.of(user("开始吧"), assistantWith(hitlUse("g1", "ui_gate")))));
    }

    @Test
    void pendingGateWithRealUserReplyDisengages() {
        assertFalse(SessionProgressService.lastHITLGateAwaitingHuman(
                List.of(assistantWith(hitlUse("g1", "ui_gate")), user("先别管表单，查下进度"))));
    }

    @Test
    void platformInjectedMessagesDoNotCountAsRealUserInput() {
        // 闸门后只有平台注入的自检/留痕/恢复指示/完成通知 → 仍是等人工（不补醒）。
        assertTrue(SessionProgressService.lastHITLGateAwaitingHuman(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                user("[停滞自检] 平台检测到会话停滞…"),
                user("[平台留痕] 审批超时自动取消…"),
                user("[HITL_RECOVERY] 会话在人工审批等待中被中断…"),
                user("[异步任务完成通知] 子任务完成…"))));
    }

    @Test
    void resolvedGateViaResumeSuccessDisengages() {
        // 主编经 resume 提交（SUCCESS 且非超时文本）→ 已决，不抑制推进。
        assertFalse(SessionProgressService.lastHITLGateAwaitingHuman(List.of(
                assistantWith(hitlUse("g1", "ui_gate"),
                        ToolResultBlock.of("g1", "ui_gate",
                                TextBlock.builder().text("主编已提交").build())
                                .withState(ToolResultState.SUCCESS)))));
    }

    @Test
    void timeoutCancelledGateAwaitsWithoutRealUserInput() {
        // 刚被平台超时取消 + 主编没回来 → 球在主编手里（不再驱动重渲染循环）。
        assertTrue(SessionProgressService.lastHITLGateAwaitingHuman(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                timeoutResult("g1"),
                user("[平台留痕] 审批超时自动取消：闸门「目录」（toolCallId=g1）…"))));
    }

    @Test
    void timeoutCancelledGateWithRealUserReplyDisengages() {
        // 主编回来发言 → 解除，正常走补醒/推进判定。
        assertFalse(SessionProgressService.lastHITLGateAwaitingHuman(List.of(
                assistantWith(hitlUse("g1", "ui_gate")),
                timeoutResult("g1"),
                user("继续，按上一版目录推进"))));
    }

    @Test
    void lastGateWins() {
        // 旧闸门已决、新闸门挂起 → 以最后一个闸门为准（等人工）。
        assertTrue(SessionProgressService.lastHITLGateAwaitingHuman(List.of(
                assistantWith(hitlUse("g1", "ui_gate"),
                        ToolResultBlock.of("g1", "ui_gate",
                                TextBlock.builder().text("主编已提交").build())
                                .withState(ToolResultState.SUCCESS)),
                assistantWith(hitlUse("g2", "ui_gate")))));
    }

    @Test
    void nonHITLToolUseIsIgnored() {
        assertFalse(SessionProgressService.lastHITLGateAwaitingHuman(
                List.of(assistantWith(hitlUse("t1", "read_file")))));
    }
}
