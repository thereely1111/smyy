package com.tipdm.agentplatform.service;

import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.repository.SkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M9-A：把平台 {@code skills} 表适配为 AgentScope 的 {@link AgentSkillRepository}，
 * 让 AgentScope 能从数据库加载技能（技能正文来自 {@link Skill#getContent()}），
 * 并在启用技能沉淀（skillManageTool / SkillCurator）时把沉淀出的技能持久化回数据库。
 *
 * <p>这样「技能」路径也能拿到 ui_form_advanced 等平台工具并执行，从而触发 FormCard。</p>
 */
@Component
public class JpaSkillRepository implements AgentSkillRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaSkillRepository.class);
    private static final String SOURCE = "platform-db";

    private final SkillRepository skillRepository;
    private boolean writeable = true;

    public JpaSkillRepository(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @Override
    public AgentSkill getSkill(String name) {
        return skillRepository.findByNameIgnoreCase(name)
                .map(this::toAgentSkill)
                .orElse(null);
    }

    @Override
    public List<String> getAllSkillNames() {
        return skillRepository.findAll().stream()
                .map(Skill::getName)
                .toList();
    }

    @Override
    public List<AgentSkill> getAllSkills() {
        return skillRepository.findAll().stream()
                .map(this::toAgentSkill)
                .toList();
    }

    @Override
    public boolean save(List<AgentSkill> skills, boolean overwrite) {
        if (!writeable) {
            log.warn("JpaSkillRepository 不可写，忽略 save({} 个技能)", skills.size());
            return false;
        }
        for (AgentSkill agentSkill : skills) {
            Skill entity = skillRepository.findByNameIgnoreCase(agentSkill.getName())
                    .orElseGet(() -> {
                        Skill s = new Skill();
                        s.setName(agentSkill.getName());
                        return s;
                    });
            entity.setDescription(agentSkill.getDescription());
            entity.setContent(agentSkill.getSkillContent());
            if (entity.getStatus() == null) {
                entity.setStatus(ResourceStatus.active);
            }
            skillRepository.save(entity);
        }
        log.info("JpaSkillRepository 持久化 {} 个技能到数据库", skills.size());
        return true;
    }

    @Override
    public boolean delete(String name) {
        Optional<Skill> found = skillRepository.findByNameIgnoreCase(name);
        if (found.isEmpty()) {
            return false;
        }
        skillRepository.delete(found.get());
        return true;
    }

    @Override
    public boolean skillExists(String name) {
        return skillRepository.existsByNameIgnoreCase(name);
    }

    @Override
    public AgentSkillRepositoryInfo getRepositoryInfo() {
        return new AgentSkillRepositoryInfo("jpa", SOURCE, writeable);
    }

    @Override
    public String getSource() {
        return SOURCE;
    }

    @Override
    public void setWriteable(boolean writeable) {
        this.writeable = writeable;
    }

    @Override
    public boolean isWriteable() {
        return writeable;
    }

    private AgentSkill toAgentSkill(Skill skill) {
        String content = skill.getContent();
        if (content == null || content.isBlank()) {
            // 退化为描述，保证技能至少有正文可用
            content = "# " + skill.getName() + "\n\n" + (skill.getDescription() == null ? "" : skill.getDescription());
        }
        // originDir：磁盘源目录（git 型导入快照）。仅在目录仍存在时透传——缓存被清理后
        // 悬空路径反而会让 prompt provider 输出无效 <files-root>。
        Path originDir = null;
        if (skill.getOriginDir() != null) {
            Path candidate = Path.of(skill.getOriginDir());
            if (candidate.isAbsolute() && Files.isDirectory(candidate)) {
                originDir = candidate;
            }
        }
        // 2.0.3-tipdm 没有 (name, description, content, resources, source, originDir)
        // 六参构造器，走 metadata 构造器透传 originDir
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", skill.getName());
        metadata.put("description", skill.getDescription() == null ? "" : skill.getDescription());
        return new AgentSkill(
                metadata,
                content,
                skill.getResources() == null ? Map.of() : skill.getResources(),
                SOURCE,
                originDir);
    }
}
