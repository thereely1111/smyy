package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentSessionProgress;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补醒预算核算（V40 真修复的封顶部分）。
 *
 * <p>判据从「模型自述」改成「平台事实」后，UNKNOWN/IDLE 的会话也会被补醒——
 * 若计数规则写错（比如把补醒本身算作进展），封顶永远触发不了，故障形态会从
 * 「静默空转」退化成「无限补醒烧 token」。这几条断言锁的就是这个规则。</p>
 */
class SessionProgressWakeBudgetTest {

    private static AgentSessionProgress row(int wakeCount) {
        AgentSessionProgress p = new AgentSessionProgress();
        p.setWakeCount(wakeCount);
        p.setLastActiveAt(Instant.now().minusSeconds(3600));
        p.setLastText("上一条尾文本");
        return p;
    }

    @Test
    void firstSightStartsAtZero() {
        assertEquals(1, SessionProgressService.computeWakeCount(null, false, false, true));
        assertEquals(0, SessionProgressService.computeWakeCount(null, false, false, false));
    }

    @Test
    void accumulatesWhenNoProgress() {
        AgentSessionProgress existing = row(1);
        assertEquals(2, SessionProgressService.computeWakeCount(existing, false, false, true));
        // 未补醒时计数保持不变（不是归零）
        assertEquals(1, SessionProgressService.computeWakeCount(existing, false, false, false));
    }

    @Test
    void progressResetsCounter() {
        AgentSessionProgress existing = row(3);
        assertEquals(1, SessionProgressService.computeWakeCount(existing, true, false, true));
    }

    @Test
    void legitWaitDoesNotConsumeBudget() {
        // 等人类应答 / 等 inbox 通知 / 别处有 run：重置为 0，不应把等待算进补醒次数
        AgentSessionProgress existing = row(2);
        assertEquals(0, SessionProgressService.computeWakeCount(existing, false, true, false));
    }

    @Test
    void capIsReachableExactly() {
        // 三次补醒后计数为 3 = 默认上限 → 下一轮 wakeCapped 成立，停止自愈
        AgentSessionProgress existing = row(1);
        int after = SessionProgressService.computeWakeCount(existing, false, false, true);
        assertEquals(2, after);
        existing.setWakeCount(after);
        after = SessionProgressService.computeWakeCount(existing, false, false, true);
        assertEquals(3, after);
    }

    // ---------- 治本 A：自检自答不算进展（2026-10-09）----------

    /**
     * 末尾是平台注入的自检 ⇒ 判为「只有自检自答」⇒ 不算进展。
     *
     * <p>回归背景（main-c7b25b4d 实证）：补醒注入自检后模型必然产出一条新回复，
     * 尾文本随之变化 ⇒ {@code progressed=true} ⇒ 每轮计数归零 ⇒ 四轮全是
     * {@code wake 1/3}、封顶永不触发、needs_human 永不置位 ⇒ 每 15 分钟
     * 无限补醒，而模型每轮都在回「没活了，别叫我」。</p>
     */
    @Test
    void selfCheckExchange_doesNotCountAsProgress() {
        List<Msg> context = List.of(
                msg(MsgRole.USER, "主编：先把第一章写出来"),
                msg(MsgRole.ASSISTANT, "好，开始写第一章"),
                msg(MsgRole.USER, "[停滞自检] 平台检测到本会话长时间无新消息与产物更新，请立即自查"),
                msg(MsgRole.ASSISTANT, "自查结论：无后台任务、无待补齐产物，按自检项 3/4 结束"));
        assertTrue(SessionProgressService.isSelfCheckExchange(context));
    }

    /**
     * 反向对照：主编在自检之后真的来了 ⇒ 不算「只有自检自答」⇒ 封顶计数应恢复累积。
     *这是本判据最关键的一侧——判据若只看「历史上出现过自检」，就会把真实工作
     * 也误判成自答、计数永远累积、会话真停滞时反而不再被唤醒。
     */
    @Test
    void realUserReplyAfterSelfCheck_isNotSelfCheckExchange() {
        List<Msg> context = List.of(
                msg(MsgRole.USER, "[停滞自检] 平台检测到本会话长时间无新消息"),
                msg(MsgRole.ASSISTANT, "自查结论：无待补齐产物，结束"),
                msg(MsgRole.USER, "等等，第二章也一起写了吧"),
                msg(MsgRole.ASSISTANT, "好的，继续写第二章"));
        assertFalse(SessionProgressService.isSelfCheckExchange(context));
    }

    /** 契约变体用的是同一个前缀常量，判据必须同样识别（两处字面量曾有漂移风险）。 */
    @Test
    void contractHintVariant_alsoCountsAsSelfCheck() {
        List<Msg> context = List.of(
                msg(MsgRole.USER, "[停滞自检] 平台检测到本会话已立工作计划，但完成契约未核验通过：\n- 缺 x"),
                msg(MsgRole.ASSISTANT, "我先落件 session-complete.json"));
        assertTrue(SessionProgressService.isSelfCheckExchange(context));
    }

    /** 普通主编发言（哪怕也提到"自检"字样但不带前缀）不得被误判为自检。 */
    @Test
    void normalUserMessage_isNotSelfCheck() {
        List<Msg> context = List.of(
                msg(MsgRole.USER, "你帮我做个停滞自检吧"),
                msg(MsgRole.ASSISTANT, "好的"));
        assertFalse(SessionProgressService.isSelfCheckExchange(context));
    }

    /** 空上下文（刚建会话首次扫描）不改变原行为。 */
    @Test
    void emptyContext_isNotSelfCheck() {
        assertFalse(SessionProgressService.isSelfCheckExchange(List.of()));
        assertFalse(SessionProgressService.isSelfCheckExchange(null));
    }

    /**
     * 端到端串起治本 A：自检自答时计数必须真的累加，而不是每轮归零。
     *
     * <p><b>这条用例必须同时经过「判自检」与「算计数」两段</b>（而不是把
     * {@code progressed} 结果手写成参数）——否则判据被改坏时测试仍会全绿。
     * 初版就是踩了这个坑：直接给 {@code computeWakeCount(existing, false, ...)}
     * 喂 false，把判据整个绕过去，于是对照实验（判据恒失效）也测不出问题。</p>
     */
    @Test
    void wakeCountAccumulatesAcrossSelfCheckRounds() {
        AgentSessionProgress existing = row(0);
        int after = 0;
        for (int round = 1; round <= 3; round++) {
            // 每轮模型都产出一条措辞微调的新回复 ⇒ 基础判据「有变化」为真
            String tail = "自查结论（状态未变化）：无待办，轮次 " + round;
            existing.setLastText("上一轮尾文本");
            existing.setLastActiveAt(Instant.now().minusSeconds(3600));
            // 但这一轮的变化只来自「平台注入自检 → 模型回答自检」⇒ 不算进展
            List<Msg> selfCheckRound = List.of(
                    msg(MsgRole.USER, "[停滞自检] 平台检测到本会话长时间无新消息"),
                    msg(MsgRole.ASSISTANT, tail));
            boolean progressed = SessionProgressService.progressedForWake(existing, Instant.now(), tail, selfCheckRound);
            assertFalse(progressed, "第 " + round + " 轮不应算作有进展");
            after = SessionProgressService.computeWakeCount(existing, progressed, false, true);
            existing.setWakeCount(after);
        }
        assertEquals(3, after, "三轮自检自答后计数必须到达封顶上限，而非恒为 1");
    }

    /** 反向对照：同样「有变化」，但变化来自主编真实发言 ⇒ 算进展、计数归零。 */
    @Test
    void realReplyAfterSelfCheck_resetsCounter() {
        AgentSessionProgress existing = row(3);
        String tail = "好的，第二章我这就写";
        existing.setLastText("上一轮尾文本");
        List<Msg> realRound = List.of(
                msg(MsgRole.USER, "[停滞自检] 平台检测到本会话长时间无新消息"),
                msg(MsgRole.ASSISTANT, "自查结论：无待办，结束"),
                msg(MsgRole.USER, "等等，第二章也一起写了吧"),
                msg(MsgRole.ASSISTANT, tail));
        boolean progressed = SessionProgressService.progressedForWake(existing, Instant.now(), tail, realRound);
        assertTrue(progressed, "主编真实发言后的变化必须算作有进展");
        assertEquals(1, SessionProgressService.computeWakeCount(existing, progressed, false, true),
                "真实工作应重置计数（而非继续累加到封顶）");
    }

    private static Msg msg(MsgRole role, String text) {
        return role == MsgRole.USER
                ? new UserMessage(text)
                : new AssistantMessage(List.of(TextBlock.builder().text(text).build()));
    }
}
