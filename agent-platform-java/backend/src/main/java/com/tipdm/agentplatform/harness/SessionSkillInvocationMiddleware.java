package com.tipdm.agentplatform.harness;

import com.tipdm.agentplatform.service.SessionSkillInvocation;
import com.tipdm.agentplatform.service.SessionWorkspaceManager;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 消息级技能调用中间件（对话页「/ 调用 skill」）。
 *
 * <p>模型是先决性的：它不知道用户显式调用了哪个技能，就不会遵循该技能的流程。
 * 本中间件在每轮 run 的 {@link MiddlewareBase#onSystemPrompt} 钩子里读取请求期
 * 注入的 {@link SessionSkillInvocation}（由
 * {@code PlatformAguiRuntimeContextResolver} 用登录用户重校验后的快照），做两件事：</p>
 * <ol>
 *   <li><b>指令注入</b>：向系统提示追加「用户在本条消息中显式调用技能 &lt;name&gt;，
 *       其指令（SKILL.md 全文）如下；请优先遵循该技能的流程与约束执行本条消息的请求」。</li>
 *   <li><b>技能文件落位</b>：把技能的 SKILL.md / resources / 磁盘源目录（originDir）
 *       幂等复制到会话运行区 {@code .harness/skills/&lt;name&gt;/}，使
 *       {@code load_skill_through_path} / {@code read_skill} 工具可达、脚本可经
 *       execute 直接运行（与装配期 {@code copyDefinitionSkills} 同一根目录约定）。</li>
 * </ol>
 *
 * <p><b>为什么不做单 run 临时 enableSkills</b>：Agent 装配实例按 agentId 全局缓存共享，
 * 运行期改技能装配会污染其它会话；注入系统提示 + 文件落位只作用于本次 run 的上下文，
 * 无副作用。消息文本里的 {@code /skill-name} 人类可读标记由前端 chip 渲染消费，
 * 历史消息不再重复触发（中间件只读本次 run 的 RuntimeContext 快照）。</p>
 *
 * <p>无技能调用的 run（快照为空）直接透传原提示，零开销。</p>
 */
public class SessionSkillInvocationMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SessionSkillInvocationMiddleware.class);

    private final SessionWorkspaceManager sessionWorkspaceManager;

    public SessionSkillInvocationMiddleware(SessionWorkspaceManager sessionWorkspaceManager) {
        this.sessionWorkspaceManager = sessionWorkspaceManager;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String prompt) {
        SessionSkillInvocation invocation =
                ctx != null ? ctx.get(SessionSkillInvocation.class) : null;
        if (invocation == null || invocation.isEmpty() || prompt == null) {
            return Mono.just(prompt);
        }
        StringBuilder sb = new StringBuilder(prompt);
        for (SessionSkillInvocation.Invocation item : invocation.invocations()) {
            // 文件落位失败不影响指令注入（脚本类技能降级为纯指令遵循）
            copySkillFiles(ctx, item);
            sb.append(skillSection(item));
        }
        return Mono.just(sb.toString());
    }

    /** 单个技能调用的提示注入段。 */
    private static String skillSection(SessionSkillInvocation.Invocation item) {
        String content = item.content();
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[用户显式调用技能] 用户在本条消息中通过 /").append(item.name())
                .append(" 显式调用了技能「").append(item.name()).append("」");
        if (!content.isBlank()) {
            sb.append("，其指令（SKILL.md）全文如下：\n---\n")
                    .append(content);
            if (!content.endsWith("\n")) {
                sb.append('\n');
            }
            sb.append("---");
        } else {
            sb.append("（该技能未提供正文指令，请按技能资源与既有约定执行）");
        }
        sb.append("\n请优先遵循该技能的流程与约束执行本条消息的请求。");
        if (!item.resources().isEmpty() || (item.originDir() != null && !item.originDir().isBlank())) {
            sb.append("\n技能资源已落位到会话运行区 .harness/skills/").append(item.name())
                    .append("/（可用 load_skill_through_path / read_skill 加载，脚本可经 execute 直接运行）。");
        }
        return sb.toString();
    }

    /**
     * 幂等落位技能文件到 {@code runtimes/{agentId}/.harness/skills/&lt;name&gt;/}：
     * SKILL.md（DB content）→ resources（相对路径文本）→ originDir 快照（递归 merge，
     * 只覆盖不删除既有文件）。失败仅 warn，不阻断 run。
     */
    private void copySkillFiles(RuntimeContext ctx, SessionSkillInvocation.Invocation item) {
        boolean hasContent = !item.content().isBlank();
        boolean hasResources = !item.resources().isEmpty();
        boolean hasOrigin = item.originDir() != null && !item.originDir().isBlank();
        if (!hasContent && !hasResources && !hasOrigin) {
            return;
        }
        try {
            String agentId = ctx != null ? ctx.get("agentId") : null;
            String sessionId = ctx != null ? ctx.getSessionId() : null;
            if (agentId == null || agentId.isBlank() || sessionId == null || sessionId.isBlank()) {
                return;
            }
            Path target = sessionWorkspaceManager.resolveRuntimeRoot(agentId)
                    .resolve(".harness")
                    .resolve("skills")
                    .resolve(sanitizeDirName(item.name()));
            Files.createDirectories(target);
            if (hasContent) {
                Files.writeString(target.resolve("SKILL.md"), item.content());
            }
            for (Map.Entry<String, String> entry : item.resources().entrySet()) {
                String rel = entry.getKey();
                if (rel == null || rel.isBlank() || rel.contains("..")) {
                    continue;
                }
                Path resource = target.resolve(rel).normalize();
                if (!resource.startsWith(target)) {
                    continue;
                }
                Files.createDirectories(resource.getParent());
                Files.writeString(resource, entry.getValue() == null ? "" : entry.getValue());
            }
            if (hasOrigin) {
                Path origin = Path.of(item.originDir());
                if (Files.isDirectory(origin)) {
                    copyTree(origin, target);
                }
            }
            log.info("技能文件已落位: {} → {} (agent={}, session={})",
                    item.name(), target, agentId, sessionId);
        } catch (Exception e) {
            log.warn("技能文件落位失败 name={}: {}", item.name(), e.getMessage());
        }
    }

    /** 技能名 → 安全目录名：保留字母数字、CJK 与 ._ -，其余替换为下划线。 */
    private static String sanitizeDirName(String name) {
        if (name == null || name.isBlank()) {
            return "_skill";
        }
        String cleaned = name.trim().replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
        if (cleaned.isBlank() || cleaned.startsWith(".")) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    /** 递归复制目录（merge，不删除目标既有文件）。 */
    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
