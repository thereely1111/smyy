package com.tipdm.agentplatform.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 运行期「消息级技能调用」上下文快照（对话页「/ 调用 skill」）。
 *
 * <p>由 {@code PlatformAguiRuntimeContextResolver} 在每轮 AG-UI run 时从请求
 * {@code forwardedProps.skill_invocations} 解析，逐个经 {@link SkillService}
 * 用<b>登录用户</b>重校验可见性（不信任前端），有效项连同 SKILL.md 正文与资源
 * 快照一起以 typed 属性放进 {@code RuntimeContext}；
 * {@code SessionSkillInvocationMiddleware} 在调用期按同一类型取回，向系统提示
 * 注入技能指令并幂等落位技能文件（.harness/skills/&lt;name&gt;/）。</p>
 *
 * <p>生效粒度是<b>消息级</b>：只对携带该字段的本次 run 生效；历史消息里保留的
 * {@code /skill-name} 人类可读标记不参与（防伪造由 resolver 兜底——裸标记文本
 * 不携带 forwardedProps，自然不会注入）。</p>
 */
public record SessionSkillInvocation(List<Invocation> invocations) {

    /** 无技能调用时的常量，避免每轮 create。 */
    public static final SessionSkillInvocation NONE = new SessionSkillInvocation(List.of());

    /**
     * 单个已校验的技能调用。
     *
     * @param skillId    技能 ID（DB 主键）
     * @param name       技能名（与 /token 文本标记一致）
     * @param content    SKILL.md 正文（DB content 优先，回退磁盘源目录 SKILL.md）
     * @param resources  文本资源（相对路径 → 文本内容）
     * @param originDir  磁盘源目录（git 仓库导入快照；可为 null）
     */
    public record Invocation(
            UUID skillId,
            String name,
            String content,
            Map<String, String> resources,
            String originDir) {

        public Invocation {
            content = content == null ? "" : content;
            resources = resources == null ? Map.of() : Map.copyOf(resources);
        }
    }

    public SessionSkillInvocation {
        invocations = invocations == null ? List.of() : List.copyOf(invocations);
    }

    public boolean isEmpty() {
        return invocations.isEmpty();
    }
}
