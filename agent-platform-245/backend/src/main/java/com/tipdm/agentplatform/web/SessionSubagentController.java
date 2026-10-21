package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.SubagentReportService;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SubagentReport;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 会话的子智能体汇报只读视图。
 *
 * <ul>
 *   <li>{@code GET /api/agents/{agentId}/sessions/{sessionId}/subagents} — 该会话派发过的子智能体
 *       及其最近一次 {@code 【TASK_RESULT】} 汇报
 * </ul>
 *
 * <p>数据来自两处框架/平台事实：子会话归属取自框架写进父会话状态的 spawn registry
 * （{@link com.tipdm.agentplatform.service.SessionLineageService}），汇报内容取自子会话自己
 * 输出并被持久化的 {@code 【TASK_RESULT】} 块。平台在返回前核对每个产出文件是否真实存在，
 * 因此这份列表可以直接回答「这轮派了谁、做到哪、产出了什么、有没有缺文件」。</p>
 *
 * <p>准入与产物视图一致（{@link SessionAccessGuard}）：非归属用户 404。</p>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/sessions/{sessionId}/subagents")
public class SessionSubagentController {

    private final SubagentReportService subagentReportService;
    private final SessionAccessGuard accessGuard;

    public SessionSubagentController(SubagentReportService subagentReportService,
                                     SessionAccessGuard accessGuard) {
        this.subagentReportService = subagentReportService;
        this.accessGuard = accessGuard;
    }

    @GetMapping
    public List<SubagentReport> reports(
            @PathVariable UUID agentId,
            @PathVariable String sessionId,
            HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        accessGuard.requireOwnedSessionRoot(agentId, userId, sessionId, request);
        return subagentReportService.reportsFor(agentId.toString(), userId, sessionId);
    }
}
