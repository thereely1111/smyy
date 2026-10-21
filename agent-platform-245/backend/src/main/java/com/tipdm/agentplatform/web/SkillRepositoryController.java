package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.SkillRepositoryService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.dto.CommonDtos.PageResponse;
import com.tipdm.agentplatform.web.dto.SkillDtos.SkillResponse;
import com.tipdm.agentplatform.web.dto.SkillRepositoryDtos.SkillRepoCard;
import com.tipdm.agentplatform.web.dto.SkillRepositoryDtos.SkillRepoInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 外部技能仓库接入（类型/地址由 agent-platform.skill-repository 配置指定）。
 *
 * <p>路径按平台集合资源复数规范（/api/skill-repositories，2026-09-19 由单数
 * 改齐；配置前缀 {@code agent-platform.skill-repository} 与缓存目录名不受影响）。
 * 读取端点对登录用户开放（与 /api/skills 列表一致）；导入会写 skills 表，
 * 与手工新建技能一样要求 {@code skill:create} 权限。</p>
 */
@RestController
@RequestMapping("/api/skill-repositories")
public class SkillRepositoryController {

    private final SkillRepositoryService service;

    public SkillRepositoryController(SkillRepositoryService service) {
        this.service = service;
    }

    /** 仓库状态概览（类型、地址、最近同步时间/错误、技能数量）。 */
    @GetMapping
    public SkillRepoInfo info() {
        return service.info();
    }

    /**
     * 强制重新同步（git 重新 fetch，http 重新拉索引）。
     *
     * <p>原实现对任意登录用户开放：可被反复调用强制平台对外部仓库发起出站请求
     * （git fetch / HTTP 索引），既是 SSRF 放大面，也消耗配额、抢占同步锁
     * （2026-09-17）。收敛到与「导入技能」同权：同步是对技能库的管理动作。</p>
     */
    @PostMapping("/sync")
    @RequirePermission("skill:create")
    public SkillRepoInfo sync() {
        return service.sync();
    }

    @GetMapping("/skills")
    public PageResponse<SkillRepoCard> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(search, page, size);
    }

    /** 详情（含 SKILL.md 正文；http 型正文按需懒加载）。 */
    @GetMapping("/skills/{id}")
    public SkillRepoCard detail(@PathVariable String id) {
        return service.detail(id);
    }

    /** 导入到平台技能库（skills 表）。名称冲突时返回 409。 */
    @PostMapping("/skills/{id}/import")
    @RequirePermission("skill:create")
    public SkillResponse importSkill(@PathVariable String id) {
        return service.importSkill(id);
    }
}
