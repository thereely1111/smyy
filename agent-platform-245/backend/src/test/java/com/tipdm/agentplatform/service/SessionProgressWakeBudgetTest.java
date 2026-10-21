package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.AgentSessionProgress;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
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
 *
 * <p>2026-10-13 增补：偷跑实证（main-c7b25b4d）暴露两条规则缺陷——① 合法等待
 * 会清零计数，导致封顶永不触发；② 纯文本收尾快速通道把「用户问一句、模型答完」
 * 也当成「宣告干活却不干」，3 分钟后主动偷跑。</p>
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
        assertEquals(1, SessionProgressService.computeWakeCount(null, false, true));
        assertEquals(0, SessionProgressService.computeWakeCount(null, false, false));
    }

    @Test
    void accumulatesWhenNoProgress() {
        AgentSessionProgress existing = row(1);
        assertEquals(2, SessionProgressService.computeWakeCount(existing, false, true));
        // 未补醒时计数保持不变（不是归零）
        assertEquals(1, SessionProgressService.computeWakeCount(existing, false, false));
    }

    @Test
    void progressResetsCounter() {
        AgentSessionProgress existing = row(3);
        assertEquals(1, SessionProgressService.computeWakeCount(existing, true, true));
    }

    /**
     * 未补醒的一轮不得清零计数——哪怕这一轮处在「合法等待」状态。
     *
     * <p>回归背景（main-c7b25b4d，2026-10-13 实测）：原实现把 legitWait 也算作
     * 清零条件，而自检 run 自己登记的租约在收尾后仍新鲜约 5 分钟 ⇒ 每轮扫描
     * 都命中「别处有 run」⇒ 计数每轮归零 ⇒ 17:54 与 18:09 两轮都是
     * {@code wake 1/3} ⇒ 封顶永不触发、needs_human 永不置位 ⇒ 会话被每 15 分钟
     * 无限补醒，而模型每轮都在回「没活了，别叫我」。</p>
     */
    @Test
    void idleRoundsNeverResetCounter() {
        AgentSessionProgress existing = row(2);
        // 连扫三轮都不补醒（合法等待 / 未到阈值），计数必须原样保留
        for (int round = 1; round <= 3; round++) {
            assertEquals(2, SessionProgressService.computeWakeCount(existing, false, false),
                    "第 " + round + " 轮未补醒时计数不得被清零");
        }
    }

    /**
     * 端到端锁住「自检 run 的租约让 legitWait 每轮成立」这个具体故障形态：
     * 计数必须跨轮累加到封顶，而不是恒为 1。
     *
     * <p>这条与 {@link #idleRoundsNeverResetCounter} 的区别：前者断言单轮不变量，
     * 本条按真实时序串起「补醒一轮 → 租约新鲜期内连扫若干轮 → 冷却后再补醒」，
     * 覆盖计数从 1 走到封顶的全过程。</p>
     */
    @Test
    void selfCheckLeaseDoesNotResetBudgetAcrossRounds() {
        AgentSessionProgress existing = row(0);
        int count = 0;
        for (int round = 1; round <= 3; round++) {
            // 每轮都补醒一次（真实停滞、无进展）
            count = SessionProgressService.computeWakeCount(existing, false, true);
            existing.setWakeCount(count);
            // 自检 run 收尾后租约仍新鲜 → 后续若干轮扫描「别处有 run」成立，
            // 但这期间不该补醒，计数也必须保持不变
            for (int idle = 0; idle < 5; idle++) {
                assertEquals(count, SessionProgressService.computeWakeCount(existing, false, false),
                        "租约新鲜期内的空闲扫描不得清零计数");
            }
        }
        assertEquals(3, count, "三轮补醒后必须到达封顶上限，而非恒为 1");
    }

    @Test
    void capIsReachableExactly() {
        // 三次补醒后计数为 3 = 默认上限 → 下一轮 wakeCapped 成立，停止自愈
        AgentSessionProgress existing = row(1);
        int after = SessionProgressService.computeWakeCount(existing, false, true);
        assertEquals(2, after);
        existing.setWakeCount(after);
        after = SessionProgressService.computeWakeCount(existing, false, true);
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
            after = SessionProgressService.computeWakeCount(existing, progressed, true);
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
        assertEquals(1, SessionProgressService.computeWakeCount(existing, progressed, true),
                "真实工作应重置计数（而非继续累加到封顶）");
    }

    // ---------- 快速通道只对立过清单的会话开（2026-10-13）----------

    private static AgentSessionProgress textEndedAt(Instant endingAt) {
        AgentSessionProgress p = row(0);
        p.setLastTextEndingAt(endingAt);
        return p;
    }

    /**
     * 无工作清单的纯问答会话以纯文本收场 ⇒ <b>不走</b> 3 分钟快速通道。
     *
     * <p>回归背景（main-c7b25b4d，2026-10-13 实测）：该会话 {@code plan/} 与
     * {@code session-complete.json} 均不存在（纯资料库问答）。17:50:00 用户问目录里
     * 有哪些文件，17:50:32 模型答完，17:54:25 平台就把自检提示塞进去——距收尾只有
     * 3 分 53 秒，{@code stall-min=8} 根本没到。之后模型回「无后台任务、无待补齐
     * 产物…本轮结束」又是一次纯文本收尾，18:09:26 再来一轮，无限循环。</p>
     */
    @Test
    void pureQaTextEnding_doesNotTriggerFastPath() {
        Instant endingAt = Instant.now().minusSeconds(4 * 60);
        AgentSessionProgress progress = textEndedAt(endingAt);
        assertFalse(SessionProgressService.textEndingStale(
                progress, endingAt, false, 3, Instant.now()),
                "无工作清单的纯问答收尾不得走 3 分钟快速通道（否则就是平台主动偷跑）");
    }

    /**
     * 立过工作清单的会话以纯文本收场 ⇒ 快速通道照旧生效。
     *
     * <p>这是 V61 快速通道的原始场景（「宣告了要干活却没干」），不能被上面的收紧
     * 误伤：教材流水线会话（plan/ 存在）停滞时仍要 3 分钟内被捞，而不是等满 8 分钟。</p>
     */
    @Test
    void plannedSessionTextEnding_stillTriggersFastPath() {
        Instant endingAt = Instant.now().minusSeconds(4 * 60);
        AgentSessionProgress progress = textEndedAt(endingAt);
        assertTrue(SessionProgressService.textEndingStale(
                progress, endingAt, true, 3, Instant.now()),
                "立过工作清单的会话仍应走快速通道，否则真停滞要等满 8 分钟");
    }

    /** 宽限期未过时快速通道不生效（用户刚答完、平台别急着问）。 */
    @Test
    void withinGraceWindow_fastPathDisabled() {
        Instant endingAt = Instant.now().minusSeconds(60);
        assertFalse(SessionProgressService.textEndingStale(
                textEndedAt(endingAt), endingAt, true, 3, Instant.now()));
    }

    /** 从未登记纯文本收尾 ⇒ 快速通道不生效（无台账/无登记的老会话）。 */
    @Test
    void noTextEndingRecord_fastPathDisabled() {
        AgentSessionProgress progress = row(0);
        progress.setLastTextEndingAt(null);
        assertFalse(SessionProgressService.textEndingStale(
                progress, Instant.now().minusSeconds(600), true, 3, Instant.now()));
    }

    /**
     * 登记之后用户又说了话 ⇒ 快速通道失效（宽限期内回复不打断对话节奏）。
     *
     * <p>与「有无清单」正交：立过清单的会话也可能正在与主编对话，此时同样不能催。</p>
     */
    @Test
    void activityAfterEnding_fastPathDisabled() {
        Instant endingAt = Instant.now().minusSeconds(4 * 60);
        AgentSessionProgress progress = textEndedAt(endingAt);
        assertFalse(SessionProgressService.textEndingStale(
                progress, endingAt.plusSeconds(60), true, 3, Instant.now()),
                "登记之后又有活动时不得走快速通道");
    }

    // ---------- 根本判据：hasWork（有没有活要干）----------

    /**
     * 除 hasWork 外全部条件都成立时，hasWork=false 必须仍然拦住补醒。
     *
     * <p>这是本次根本修复的核心断言，也是此前三次修复全部漏掉的那一项：纯问答会话
     * 的实测状态是无待消费 inbox（队列仅剩 {@code .keep}）、无租约、未删除、
     * 未归档、未封存、契约 MISSING ⇒ 除 hasWork 外<b>其余否定项全为假</b>，
     * 放行条件自动满足。</p>
     */
    @Test
    void noWork_blocksWake_evenWhenEveryOtherConditionHolds() {
        boolean woke = SessionProgressService.shouldWake(
                /* hasWork      */ false,
                /* dueToStall   */ true,
                /* finished     */ false,
                /* waitingHuman */ false,
                /* pendingInbox */ false,
                /* activeElse.  */ false,
                /* wakeCapped   */ false,
                /* cooldownOk   */ true,
                /* sealedByHuman*/ false);
        assertFalse(woke,
                "没有活要干的会话，即便其余条件全部成立也不得补醒（14 轮偷跑的根因）");
    }

    /** 反向对照：同样条件下 hasWork=true ⇒ 照常补醒（真停滞必须救得回来）。 */
    @Test
    void hasWork_allowsWake_whenOtherConditionsHold() {
        assertTrue(SessionProgressService.shouldWake(
                true, true, false, false, false, false, false, true, false),
                "有活要干的真停滞会话仍必须被补醒，否则这次修复就成了漏救");
    }

    /** hasWork=true 时，其余每个否定项仍各自能拦住补醒（逐项锁，防止被后续改动绕过）。 */
    @Test
    void everyOtherConditionStillBlocksWake_whenHasWork() {
        assertFalse(SessionProgressService.shouldWake(
                true, /* dueToStall */ false, false, false, false, false, false, true, false),
                "未停滞不该补醒");
        assertFalse(SessionProgressService.shouldWake(
                true, true, /* finished */ true, false, false, false, false, true, false),
                "已完成不该补醒");
        assertFalse(SessionProgressService.shouldWake(
                true, true, false, /* waitingHuman */ true, false, false, false, true, false),
                "等人类不该补醒");
        assertFalse(SessionProgressService.shouldWake(
                true, true, false, false, /* pendingInbox */ true, false, false, true, false),
                "inbox 待消费不该补醒（InboxWatchdog 会兜底）");
        assertFalse(SessionProgressService.shouldWake(
                true, true, false, false, false, /* activeElsewhere */ true, false, true, false),
                "别处有 run 不该补醒（并发互斥）");
        assertFalse(SessionProgressService.shouldWake(
                true, true, false, false, false, false, /* wakeCapped */ true, true, false),
                "达封顶不该补醒");
        assertFalse(SessionProgressService.shouldWake(
                true, true, false, false, false, false, false, /* cooldownOk */ false, false),
                "冷却未过不该补醒");
        assertFalse(SessionProgressService.shouldWake(
                true, true, false, false, false, false, false, true, /* sealedByHuman */ true),
                "人工封存不该补醒");
    }

    /**
     * 端到端复现 main-c7b25b4d 的 14 轮偷跑：模拟 3 小时内每一分钟的扫描，断言零补醒。
     *
     * <p>该会话 2026-10-13 实测 17:54–21:09 被偷跑 <b>14 轮</b>、2218 条事件，
     * 用户只在 17:50 说过一次话。真实节奏是「补醒后模型回一句 → 15 分钟冷却到点 →
     * 下一轮扫描 idle 已远超 8 分钟 → 再次放行」，本测试按同一形态推进时间轴。</p>
     */
    @Test
    void qaSessionIsNeverWoken_overThreeHours() {
        Instant now = Instant.now();
        // 该会话实测：无 plan/、无契约标记、无后台任务台账 ⇒ hasWork 恒 false
        boolean hasWork = false;
        int woken = 0;
        for (int minute = 4; minute <= 180; minute++) {
            Instant scanAt = now.plusSeconds(minute * 60L);
            // 每轮都以「模型刚回过一句话」收尾 ⇒ idle 随时间累积，10 分钟后
            // 必然越过 stall-min=8（这正是第二轮起走的 8 分钟主通道）
            Instant lastReplyAt = scanAt.minusSeconds(12 * 60);
            boolean idleLong = Duration.between(lastReplyAt, scanAt).toMinutes() >= 8;
            boolean textEndingStale = SessionProgressService.textEndingStale(
                    textEndedAt(lastReplyAt), lastReplyAt, hasWork, 3, scanAt);
            if (SessionProgressService.shouldWake(hasWork, idleLong || textEndingStale,
                    false, false, false, false, false, true, false)) {
                woken++;
            }
        }
        assertEquals(0, woken,
                "纯问答会话在 3 小时内不得被补醒一次（实测曾被偷跑 14 轮）");
    }

    /**
     * 反向对照：同样的时间轴，教材流水线会话（hasWork=true）仍能被补醒。
     *
     * <p>防止这次修复退化成「看护彻底罢工」——真停滞的流水线会话必须照旧被捞，
     * 且因 idle 远超阈值，走的是 8 分钟主通道而非快速通道。</p>
     */
    @Test
    void pipelineSessionStillGetsWoken_overSameThreeHours() {
        Instant now = Instant.now();
        boolean hasWork = true;
        int woken = 0;
        int firstWakeMinutes = -1;
        for (int minute = 4; minute <= 180; minute++) {
            Instant scanAt = now.plusSeconds(minute * 60L);
            Instant lastReplyAt = scanAt.minusSeconds(12 * 60);
            boolean idleLong = Duration.between(lastReplyAt, scanAt).toMinutes() >= 8;
            boolean textEndingStale = SessionProgressService.textEndingStale(
                    textEndedAt(lastReplyAt), lastReplyAt, hasWork, 3, scanAt);
            if (SessionProgressService.shouldWake(hasWork, idleLong || textEndingStale,
                    false, false, false, false, false, true, false)) {
                woken++;
                if (firstWakeMinutes < 0) {
                    firstWakeMinutes = minute;
                }
            }
        }
        assertTrue(woken > 0, "有活要干的流水线会话必须仍能被补醒（修复不能变成看护罢工）");
        assertEquals(4, firstWakeMinutes,
                "有清单的会话应在首个扫描点（3 分钟快速通道生效时）即被补醒");
    }

    private static Msg msg(MsgRole role, String text) {
        return role == MsgRole.USER
                ? new UserMessage(text)
                : new AssistantMessage(List.of(TextBlock.builder().text(text).build()));
    }
}
