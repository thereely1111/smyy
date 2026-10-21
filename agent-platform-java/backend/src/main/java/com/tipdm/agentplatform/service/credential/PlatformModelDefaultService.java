package com.tipdm.agentplatform.service.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.tipdm.agentplatform.domain.ModelCredential;
import com.tipdm.agentplatform.domain.PlatformModelDefault;
import com.tipdm.agentplatform.repository.ModelCredentialRepository;
import com.tipdm.agentplatform.repository.PlatformModelDefaultRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 平台默认模型（2026-09-22）：全局有且只有一个，以「平台共享凭证 + 启用模型」
 * 二元组表示，存 V57 单例行（id=1），设置新值直接覆盖（互斥由结构保证）。
 *
 * <p>约束：只能选择 scope=PLATFORM 凭证下的<b>启用中</b>模型（enabled_models
 * 为空 = 全部启用）。消费点在 {@code PlatformAguiRuntimeContextResolver}：
 * run 未携带 forwardedProps 模型时优先取本默认（构造 {@code RunModelSelection}
 * 走凭证覆盖装配路径），取不到回落 {@code agent-platform.model} 配置。</p>
 *
 * <p>一致性钩子：凭证删除 / 模型被禁用时清理指向它的默认行（见
 * {@link ModelCredentialService} 调用点），运行期兜底再兜一层（解析失败回落配置）。</p>
 */
@Service
public class PlatformModelDefaultService {

    private static final Logger log = LoggerFactory.getLogger(PlatformModelDefaultService.class);

    /** 默认引用（凭证 + 模型名）。 */
    public record DefaultRef(UUID credentialId, String model) {
    }

    private final PlatformModelDefaultRepository repository;
    private final ModelCredentialRepository credentialRepository;

    public PlatformModelDefaultService(PlatformModelDefaultRepository repository,
                                       ModelCredentialRepository credentialRepository) {
        this.repository = repository;
        this.credentialRepository = credentialRepository;
    }

    @Transactional(readOnly = true)
    public Optional<DefaultRef> get() {
        return repository.findById((short) 1)
                .map(row -> new DefaultRef(row.getCredentialId(), row.getModel()));
    }

    /** 出参视图：未设置时 credential_id/model 为 null。 */
    @Transactional(readOnly = true)
    public Map<String, Object> view() {
        Map<String, Object> body = new LinkedHashMap<>();
        get().ifPresentOrElse(
                ref -> {
                    body.put("credential_id", ref.credentialId().toString());
                    body.put("model", ref.model());
                },
                () -> {
                    body.put("credential_id", null);
                    body.put("model", null);
                });
        return body;
    }

    /**
     * 设置（覆盖式，全局唯一）。校验：凭证存在且 scope=PLATFORM、模型处于启用
     * 状态（enabled_models 空 = 全部启用）、api_key 已配置（凭证核验流程保证非空）。
     */
    @Transactional
    public Map<String, Object> set(String userId, String credentialId, String model) {
        UUID credId = parseId(credentialId, "credential_id 无效");
        String modelName = model == null ? "" : model.trim();
        if (modelName.isEmpty()) {
            throw ApiException.badRequest("model 不能为空");
        }
        ModelCredential credential = credentialRepository.findById(credId)
                .orElseThrow(() -> ApiException.notFound("凭证不存在"));
        if (!ModelCredentialService.SCOPE_PLATFORM.equals(credential.getScope())) {
            throw ApiException.badRequest("平台默认模型只能设置为平台共享凭证下的模型");
        }
        JsonNode enabled = credential.getEnabledModels();
        if (enabled != null && enabled.isArray()) {
            boolean found = false;
            for (JsonNode n : enabled) {
                if (modelName.equals(n.asText())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw ApiException.badRequest("只能将启用的模型设为平台默认模型");
            }
        }

        PlatformModelDefault row = repository.findById((short) 1).orElseGet(PlatformModelDefault::new);
        row.setCredentialId(credId);
        row.setModel(modelName);
        row.setUpdatedBy(parseIdOrNull(userId));
        repository.save(row);
        log.info("platform-default-model: user {} set default = {}:{}",
                userId, credentialId, modelName);
        return view();
    }

    /**
     * 未指定 run 级模型时的生效模型串：动态默认（{@code provider:model}，provider
     * 取凭证的供应商类型）优先；未设置或凭证已失效时回落传入的配置兜底值。
     * 供 AgentConfigResponse 等展示口径使用（与运行期 resolver 的回落顺序一致）。
     */
    @Transactional(readOnly = true)
    public String effectiveModelString(String fallback) {
        return get()
                .flatMap(ref -> credentialRepository.findById(ref.credentialId())
                        .map(c -> c.getCredType() + ":" + ref.model()))
                .orElse(fallback);
    }

    /** 凭证删除时清理指向它的默认行。 */
    @Transactional
    public void clearForCredential(UUID credentialId) {
        repository.findById((short) 1)
                .filter(row -> credentialId.equals(row.getCredentialId()))
                .ifPresent(row -> {
                    repository.delete(row);
                    log.info("platform-default-model: cleared (credential {} deleted)", credentialId);
                });
    }

    /** 启用清单变更时，若默认模型被禁用则清除默认（保持「默认必须是启用模型」不变量）。 */
    @Transactional
    public void clearIfModelDisabled(UUID credentialId, JsonNode enabledModels) {
        if (enabledModels == null || !enabledModels.isArray()) {
            return; // null = 恢复全部启用，无需清理
        }
        repository.findById((short) 1)
                .filter(row -> credentialId.equals(row.getCredentialId()))
                .ifPresent(row -> {
                    for (JsonNode n : enabledModels) {
                        if (row.getModel().equals(n.asText())) {
                            return; // 仍启用，保留
                        }
                    }
                    repository.delete(row);
                    log.info("platform-default-model: cleared (model {} disabled in credential {})",
                            row.getModel(), credentialId);
                });
    }

    private UUID parseId(String raw, String message) {
        try {
            return UUID.fromString(raw == null ? "" : raw.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(message);
        }
    }

    private UUID parseIdOrNull(String raw) {
        try {
            return raw == null || raw.isBlank() ? null : UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
