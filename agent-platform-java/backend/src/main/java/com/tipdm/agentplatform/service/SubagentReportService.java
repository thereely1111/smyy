package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.dto.RuntimeDtos.SubagentReport;
import com.tipdm.agentplatform.web.dto.RuntimeDtos.SubagentArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 子智能体汇报：把「框架 spawn registry 里的子会话」+「子会话自己写的 {@code 【TASK_RESULT】}」
 * 合成一份可呈现的汇报列表。
 *
 * <p>为什么是这两块拼起来：</p>
 * <ul>
 *   <li>**谁派发了谁**（子会话归属、子智能体名、标签、深度）来自框架记录在父会话状态里的
 *       spawn registry（{@link SessionLineageService}）——平台不需要任何文本标记；
 *   <li>**这轮干了什么**（状态、完成/剩余、产出文件）来自子会话自己的最后一轮回复里的
 *       {@code 【TASK_RESULT】} 块（{@link SubagentReportParser}）。
 * </ul>
 *
 * <p>平台在呈现前会**逐个核对 artifacts 是否真的存在**（相对子会话目录解析），把不存在的标出来：
 * 这样「模型说写了但文件不在」会立刻暴露，而不是等到下游读文件时才失败。</p>
 *
 * <p>子会话状态读的是框架的 AgentState（与运行时同一个 Postgres 存储、同一 schema），
 * 只做只读查询；任何一步失败都退化为"该子会话无汇报"，不影响主流程。</p>
 */
@Service
public class SubagentReportService {

    /**
     * 读取子会话里助手产出过的全部文本（按时间序）。
     *
     * <p>抽成接口是为了让"汇报合成"与"状态存储实现"解耦：生产实现读框架持久化的 AgentState
     * （{@link PostgresSubagentStateReader}），测试可以直接喂文本。</p>
     *
     * <p>为什么返回**全部**而不是最后一条：助手消息里文本与工具调用是同一轮的不同 content
     * block，模型常在写完文件后追加说明再调工具，最后一条文本未必带 {@code 【TASK_RESULT】}；
     * 汇报块在更早的那条文本里。调用方从后往前逐条找。</p>
     */
    public interface SubagentStateReader {
        /** @return 该会话里助手文本，按时间先后排列；没有则返回空表 */
        List<String> assistantTexts(String userId, String sessionId);
    }

    private static final Logger log = LoggerFactory.getLogger(SubagentReportService.class);

    /** 单会话最多返回多少条子会话汇报（与血缘查询上限一致）。 */
    private static final int MAX_REPORTS = 200;

    private final SessionLineageService lineageService;
    private final SessionWorkspaceManager sessionWorkspaceManager;
    private final SubagentStateReader stateReader;

    public SubagentReportService(SessionLineageService lineageService,
                                 SessionWorkspaceManager sessionWorkspaceManager,
                                 SubagentStateReader stateReader) {
        this.lineageService = lineageService;
        this.sessionWorkspaceManager = sessionWorkspaceManager;
        this.stateReader = stateReader;
    }

    /**
     * 某会话的全部子智能体汇报（按派发顺序）。
     *
     * @param agentId   智能体 ID（决定运行态根）
     * @param userId    会话归属用户（血缘与状态都按 {@code userId} 分区）
     * @param sessionId 父/主编会话 ID
     */
    public List<SubagentReport> reportsFor(String agentId, String userId, String sessionId) {
        List<SessionLineageService.SubSession> subs = lineageService.subSessions(userId, sessionId);
        if (subs.isEmpty()) {
            return List.of();
        }
        Path runtimeRoot = sessionWorkspaceManager.resolveRuntimeRoot(agentId);
        List<SubagentReport> out = new ArrayList<>();
        for (SessionLineageService.SubSession sub : subs) {
            if (out.size() >= MAX_REPORTS) {
                break;
            }
            out.add(report(agentId, userId, runtimeRoot, sub));
        }
        return List.copyOf(out);
    }

    private SubagentReport report(String agentId, String userId, Path runtimeRoot,
                                  SessionLineageService.SubSession sub) {
        Path sessionDir = runtimeRoot.resolve(sub.sessionId());
        SubagentReportParser.Report parsed = lastReport(userId, sub.sessionId());
        if (parsed == null) {
            return new SubagentReport(sub.sessionId(), sub.agentId(), sub.label(), null,
                    null, null, null, false, List.of(), List.of(), List.of(),
                    List.of(), List.of(), lastModified(sessionDir));
        }
        List<SubagentArtifact> artifacts = new ArrayList<>();
        for (String ref : parsed.artifacts()) {
            artifacts.add(resolveArtifact(runtimeRoot, sub.sessionId(), ref));
        }
        List<String> missing = new ArrayList<>(parsed.missingArtifacts());
        artifacts.stream().filter(a -> !a.exists()).forEach(a -> missing.add(a.path()));
        return new SubagentReport(sub.sessionId(), sub.agentId(), sub.label(), parsed.status(),
                parsed.stage(), parsed.summary(), parsed.nextAction(), parsed.resumable(),
                parsed.completed(), parsed.inProgress(), parsed.remaining(),
                List.copyOf(artifacts), List.copyOf(missing), lastModified(sessionDir));
    }

    /**
     * 子会话里**最近的**一个 {@code 【TASK_RESULT】} 块（读不到 / 解析不到返回 {@code null}）。
     *
     * <p>从最后一条助手文本往前找：汇报块通常在最终回复里，但模型也可能在写完产物后继续
     * 调工具并追加说明，此时最后一条文本不含块，必须继续往前找，否则界面会误报"未汇报"。</p>
     */
    private SubagentReportParser.Report lastReport(String userId, String subSessionId) {
        List<String> texts;
        try {
            texts = stateReader.assistantTexts(userId, subSessionId);
        } catch (RuntimeException e) {
            log.warn("Subagent report: read state failed for {}: {}", subSessionId, e.getMessage());
            return null;
        }
        if (texts == null) {
            return null;
        }
        for (int i = texts.size() - 1; i >= 0; i--) {
            SubagentReportParser.Report parsed = SubagentReportParser.parse(texts.get(i));
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /**
     * 把汇报里的路径解析到真实文件。
     *
     * <p>汇报里的条目形如 {@code <产出会话目录>/<相对路径>}（运行态根相对路径）；也容忍
     * 只写会话内相对路径的历史写法。解析只在**该子会话目录内**进行，越界一律视为不存在。</p>
     */
    private SubagentArtifact resolveArtifact(Path runtimeRoot, String subSessionId, String ref) {
        String cleaned = ref == null ? "" : ref.trim().replace('\\', '/');
        while (cleaned.startsWith("./")) {
            cleaned = cleaned.substring(2);
        }
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        String prefix = subSessionId + "/";
        String relative = cleaned.startsWith(prefix) ? cleaned.substring(prefix.length()) : cleaned;

        Path base = runtimeRoot.resolve(subSessionId);
        Path target = relative.isEmpty() ? base : base.resolve(relative).normalize();
        boolean exists = target.startsWith(base) && Files.isRegularFile(target);
        return new SubagentArtifact(ref, relative, exists,
                exists ? sizeOf(target) : null, exists ? lastModified(target) : null);
    }

    private static Long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static Long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return null;
        }
    }
}
