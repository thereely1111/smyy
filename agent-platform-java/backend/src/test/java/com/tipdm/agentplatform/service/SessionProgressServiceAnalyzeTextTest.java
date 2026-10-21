package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.service.SessionProgressService.NextActionDecision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 尾文本语义分类回归（纯静态方法，无 Spring 上下文）。
 *
 * <p>重点守住两类历史误报：2026-09-07「仍在运行中…推进主编闸门」被闸门关键词
 * 误判 WAIT_HITL 导致 8h 死等；2026-09-15 恢复自查尾文本「无闸门未决」同因
 * 误判 WAIT_HITL 导致重启后恢复流程死等（闸门分支只认关键词，不理解否定）。</p>
 */
class SessionProgressServiceAnalyzeTextTest {

    private NextActionDecision analyze(String tail) {
        return SessionProgressService.analyzeText(tail);
    }

    @Test
    void noPendingGateDeclaration_classifiesContinue_notWaitHitl() {
        // 2026-09-15 实证尾文本（截断版）：明确自述无闸门未决、有下一步计划。
        String tail = "恢复自查完成，**结论：无闸门未决、无重复派发**（task_8738db2b 已 completed，"
                + "磁盘与回执吻合）。三段基线按**权威边界**复算全绿。"
                + "派 17e 前把报告表头与判定依据实抽到位（只读，零冲突）：";
        assertEquals(SessionProgressService.NEXT_CONTINUE, analyze(tail).action());
    }

    @Test
    void negationVariants_allContinue() {
        for (String phrase : new String[] {
                "无闸门未决", "无未决闸门", "闸门无未决", "无待决闸门", "无挂起闸门"}) {
            assertEquals(SessionProgressService.NEXT_CONTINUE,
                    analyze("阶段小结，" + phrase + "，继续推进。").action(),
                    () -> "negation phrase should not block wakeup: " + phrase);
        }
    }

    @Test
    void explicitGateWait_stillWaitHitl() {
        // 真实等待主编的表述不得被否定词误伤。
        assertEquals(SessionProgressService.NEXT_WAIT_HITL,
                analyze("闸门表单已挂起，等待主编审核意见后继续。").action());
        assertEquals(SessionProgressService.NEXT_WAIT_HITL,
                analyze("GATE_WAIT: 意向收集表单等待提交").action());
    }

    @Test
    void backgroundRunWording_stillContinue() {
        // 2026-09-07 盲区回归：后台任务等待语义保持 CONTINUE。
        assertEquals(SessionProgressService.NEXT_CONTINUE,
                analyze("子任务仍在后台运行中，完成后统一推进主编闸门").action());
    }

    @Test
    void fixedStatusFields_unchanged() {
        assertEquals(SessionProgressService.NEXT_DONE,
                analyze("[进度] 阶段=收尾，状态=已完成").action());
        assertEquals(SessionProgressService.NEXT_WAIT_HITL,
                analyze("[进度] 阶段=审阅，状态=等待人工").action());
    }
}
