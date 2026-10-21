package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.domain.ResourceStatus;
import com.tipdm.agentplatform.domain.Skill;
import com.tipdm.agentplatform.domain.SkillScope;
import com.tipdm.agentplatform.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillService#resolveVisible} 运行态可见性判定（「/ 调用 skill」防伪造核心）：
 * 平台库需启用、个人库需 owner 本人，他人个人库与不存在一律不可见，绝不 fail run。
 */
class SkillServiceTest {

    private SkillRepository repository;
    private SkillService service;

    private final UUID user = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(SkillRepository.class);
        service = new SkillService(repository);
    }

    private static Skill skill(SkillScope scope, ResourceStatus status, UUID owner) {
        Skill s = new Skill();
        s.setId(UUID.randomUUID());
        s.setName("skill-" + UUID.randomUUID().toString().substring(0, 8));
        s.setScope(scope);
        s.setStatus(status);
        s.setOwnerUserId(owner);
        return s;
    }

    @Test
    void resolveVisible_platformActive_visible() {
        Skill s = skill(SkillScope.PLATFORM, ResourceStatus.active, null);
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.resolveVisible(List.of(s.getId()), user)).containsExactly(s);
    }

    @Test
    void resolveVisible_platformDisabled_notVisible() {
        Skill s = skill(SkillScope.PLATFORM, ResourceStatus.inactive, null);
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.resolveVisible(List.of(s.getId()), user)).isEmpty();
    }

    @Test
    void resolveVisible_personalOwned_visible() {
        Skill s = skill(SkillScope.PERSONAL, ResourceStatus.active, user);
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.resolveVisible(List.of(s.getId()), user)).containsExactly(s);
    }

    @Test
    void resolveVisible_personalOwnedByOthers_notVisible() {
        Skill s = skill(SkillScope.PERSONAL, ResourceStatus.active, other);
        when(repository.findById(s.getId())).thenReturn(Optional.of(s));

        assertThat(service.resolveVisible(List.of(s.getId()), user)).isEmpty();
    }

    @Test
    void resolveVisible_notFound_notVisible() {
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        assertThat(service.resolveVisible(List.of(missing), user)).isEmpty();
    }

    @Test
    void resolveVisible_nullUserOrEmptyIds_empty() {
        assertThat(service.resolveVisible(List.of(UUID.randomUUID()), null)).isEmpty();
        assertThat(service.resolveVisible(null, user)).isEmpty();
        assertThat(service.resolveVisible(List.of(), user)).isEmpty();
    }

    @Test
    void resolveVisible_orderFollowsInput_notRepositoryOrder() {
        Skill a = skill(SkillScope.PLATFORM, ResourceStatus.active, null);
        Skill b = skill(SkillScope.PERSONAL, ResourceStatus.active, user);
        when(repository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(repository.findById(b.getId())).thenReturn(Optional.of(b));
        when(repository.findById(a.getId())).thenReturn(Optional.of(a));

        // 入参顺序 b, a → 返回顺序也应为 b, a（提示注入顺序与用户选择一致）
        assertThat(service.resolveVisible(List.of(b.getId(), a.getId()), user))
                .containsExactly(b, a);
    }
}
