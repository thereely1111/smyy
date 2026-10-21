package com.tipdm.agentplatform.service.credential;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.ModelCredential;
import com.tipdm.agentplatform.repository.ModelCredentialRepository;
import com.tipdm.agentplatform.service.ExternalApiKeyCipher;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 模型凭证 CRUD（用户级，归属即权限，越权 404，对齐 ChannelController 的口径）。
 *
 * <p><b>密钥处理三原则</b>：</p>
 * <ol>
 *   <li>入库前：writeOnly 字段逐个 {@link ExternalApiKeyCipher#encrypt}（沿用平台唯一
 *       外部凭据密钥 {@code agent-platform.external.key}），非密钥字段明文存 JSONB；</li>
 *   <li>出参：密钥字段一律替换为 {@link ModelProviderRegistry#SECRET_MASK} 哨兵，
 *       任何接口不回显明文；</li>
 *   <li>编辑：提交值 == 哨兵或为空 → 保留库中旧值（前端编辑框不回填密钥，
 *       提交的 data 里带的是哨兵占位）。</li>
 * </ol>
 */
@Service
public class ModelCredentialService {

    private static final Logger log = LoggerFactory.getLogger(ModelCredentialService.class);

    /** 共享范围：用户私有。 */
    public static final String SCOPE_USER = "USER";
    /** 共享范围：平台共享（管理员创建，全员可用于对话选模型）。 */
    public static final String SCOPE_PLATFORM = "PLATFORM";

    private final ModelCredentialRepository repository;
    private final ModelProviderRegistry registry;
    private final ExternalApiKeyCipher cipher;
    private final ObjectMapper objectMapper;
    private final VendorModelClient vendorModelClient;
    private final PlatformModelDefaultService platformModelDefaultService;

    public ModelCredentialService(ModelCredentialRepository repository,
                                  ModelProviderRegistry registry,
                                  ExternalApiKeyCipher cipher,
                                  ObjectMapper objectMapper,
                                  VendorModelClient vendorModelClient,
                                  PlatformModelDefaultService platformModelDefaultService) {
        this.repository = repository;
        this.registry = registry;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.vendorModelClient = vendorModelClient;
        this.platformModelDefaultService = platformModelDefaultService;
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /**
     * 凭证列表 = 本人私有 + 平台共享（scope=PLATFORM）。平台共享凭证对非归属者
     * 只暴露 type / name（见 {@link #toView}），供对话模型选择使用。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String userId) {
        UUID uid = parseUserId(userId);
        List<ModelCredential> own = repository.findByUserIdOrderByUpdatedAtDesc(uid);
        List<ModelCredential> platform = repository.findByScopeOrderByUpdatedAtDesc(SCOPE_PLATFORM);
        // 本人凭证在前，平台共享在后；按 id 去重（归属者本人的 PLATFORM 凭证两条查询会重叠）
        LinkedHashMap<UUID, ModelCredential> merged = new LinkedHashMap<>();
        for (ModelCredential c : own) {
            merged.put(c.getId(), c);
        }
        for (ModelCredential c : platform) {
            merged.putIfAbsent(c.getId(), c);
        }
        return merged.values().stream().map(c -> toView(c, uid)).toList();
    }

    // ── 写操作 ───────────────────────────────────────────────────────────

    /**
     * @param scope 共享范围（{@link #SCOPE_USER} / {@link #SCOPE_PLATFORM}），由调用方
     *              （Controller 的不同端点）传入常量——scope 不经请求体传递，防变造。
     *              平台凭证端点在 Controller 层已 {@code requirePermissionCode} 把门，
     *              service 不再感知权限码。
     */
    @Transactional
    public Map<String, Object> create(String userId, Map<String, Object> body, String scope) {
        Map<String, Object> data = dataOf(body);
        String type = requireType(data);
        validateRequired(type, data);

        // 核验流程（2026-09-22）：新建必填 api_key，保存前调厂商 models 端点验 KEY，
        // 成功则缓存厂商模型清单（data.vendor_models）；失败 400 阻断保存。
        String apiKey = str(data.get("api_key"));
        List<String> vendorModels = vendorModelClient.verifyAndListModels(
                type, apiKey, str(data.get("base_url")));

        ModelCredential entity = new ModelCredential();
        entity.setUserId(parseUserId(userId));
        entity.setCredType(type);
        entity.setScope(scope);
        Map<String, Object> stored = storeForm(type, data, Map.of());
        stored.put("vendor_models", vendorModels);
        stored.put("models_verified_at", Instant.now().toString());
        entity.setData(objectMapper.valueToTree(stored));
        applyEnabledModels(entity, body.get("enabled_models"));
        repository.save(entity);
        log.info("model-credential: user {} created credential {} ({}, scope={}, verifiedModels={})",
                userId, entity.getId(), type, scope, vendorModels.size());
        return Map.of("credential_id", entity.getId().toString());
    }

    @Transactional
    public Map<String, Object> update(String userId, String id, Map<String, Object> body) {
        ModelCredential entity = requireOwned(userId, id);
        String type = entity.getCredType();

        // enabled_models 单独可改（详情页开关只传该字段，不带 data）
        boolean hasEnabledField = body.containsKey("enabled_models");
        if (!body.containsKey("data") && hasEnabledField) {
            applyEnabledModels(entity, body.get("enabled_models"));
            repository.save(entity);
            // 一致性钩子：默认模型被禁用 → 清除平台默认（保持「默认必须是启用模型」）
            platformModelDefaultService.clearIfModelDisabled(entity.getId(), entity.getEnabledModels());
            return toView(entity, entity.getUserId());
        }

        Map<String, Object> incoming = dataOf(body);
        if (!type.equals(str(incoming.get("type")))) {
            throw ApiException.badRequest("凭证的供应商类型不可变更");
        }

        Map<String, Object> current = decryptedData(entity);
        validateRequired(type, incoming);
        // 核验时机：新提交了 api_key，或 base_url 变更（端点变了必须重验）→ 重新核验；
        // 仅改名称等非密钥字段 → 保留原核验结果。
        Map<String, Object> stored = storeForm(type, incoming, current);
        String newKey = str(incoming.get("api_key"));
        boolean keyChanged = !newKey.isEmpty()
                && !ModelProviderRegistry.SECRET_MASK.equals(newKey);
        String oldBaseUrl = str(current.get("base_url"));
        String newBaseUrl = str(incoming.get("base_url"));
        if (keyChanged || !newBaseUrl.equals(oldBaseUrl)) {
            String effectiveKey = keyChanged ? newKey : str(current.get("api_key"));
            List<String> vendorModels = vendorModelClient.verifyAndListModels(
                    type, effectiveKey, newBaseUrl);
            stored.put("vendor_models", vendorModels);
            stored.put("models_verified_at", Instant.now().toString());
        }
        entity.setData(objectMapper.valueToTree(stored));
        if (hasEnabledField) {
            applyEnabledModels(entity, body.get("enabled_models"));
        }
        repository.save(entity);
        if (hasEnabledField) {
            platformModelDefaultService.clearIfModelDisabled(entity.getId(), entity.getEnabledModels());
        }
        log.info("model-credential: user {} updated credential {} ({}), reverified={}",
                userId, id, type, stored.containsKey("models_verified_at")
                        && !stored.get("models_verified_at").equals(current.get("models_verified_at")));
        return toView(entity, entity.getUserId());
    }

    /** enabled_models：null = 清除限制（恢复全部可用）；数组 = 全量替换（校验为字符串数组）。 */
    private void applyEnabledModels(ModelCredential entity, Object raw) {
        if (raw == null) {
            entity.setEnabledModels(null);
            return;
        }
        if (!(raw instanceof List<?> list)) {
            throw ApiException.badRequest("enabled_models 必须是字符串数组");
        }
        List<String> names = list.stream()
                .map(v -> v == null ? "" : v.toString().trim())
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        entity.setEnabledModels(objectMapper.valueToTree(names));
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    @Transactional
    public void delete(String userId, String id) {
        ModelCredential entity = requireOwned(userId, id);
        repository.delete(entity);
        // 一致性钩子：默认行指向该凭证 → 一并清除
        platformModelDefaultService.clearForCredential(entity.getId());
    }

    /**
     * 取解密后的完整凭证数据（含明文密钥）——仅供装配 Agent 模型实例等
     * 平台内部消费方使用；任何 Controller 出参禁止直接透传本方法结果。
     *
     * <p>归属校验：本人私有凭证，或平台共享（scope=PLATFORM）凭证——
     * 对话选模型时普通用户要能用管理员的共享凭证发起 run。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> resolveDecrypted(String userId, String id) {
        return decryptedData(resolveUsable(userId, id));
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** 归属或平台共享即可用（越权 404 口径不变：不可用一律当不存在）。 */
    private ModelCredential resolveUsable(String userId, String id) {
        UUID uuid = parseCredentialId(id);
        UUID uid = parseUserId(userId);
        ModelCredential entity = repository.findById(uuid)
                .orElseThrow(() -> ApiException.notFound("凭证不存在"));
        boolean usable = uid.equals(entity.getUserId())
                || SCOPE_PLATFORM.equals(entity.getScope());
        if (!usable) {
            throw ApiException.notFound("凭证不存在");
        }
        return entity;
    }

    private UUID parseCredentialId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("凭证不存在");
        }
    }

    /** 归属校验：返回实体供 Controller 查 scope 分流权限码；越权一律 404（当不存在）。 */
    public ModelCredential requireOwned(String userId, String id) {
        UUID uuid;
        try {
            uuid = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("凭证不存在");
        }
        return repository.findByIdAndUserId(uuid, parseUserId(userId))
                .orElseThrow(() -> ApiException.notFound("凭证不存在"));
    }

    /**
     * 出参视图：密钥字段打掩码；editable = 是否归属者。
     * 平台共享凭证对非归属者只暴露 type / name（base_url 等配置不外泄）。
     */
    private Map<String, Object> toView(ModelCredential entity, UUID viewerId) {
        boolean owner = entity.getUserId().equals(viewerId);
        Map<String, Object> masked = decryptedData(entity);
        for (String secret : registry.secretFields(entity.getCredType())) {
            if (masked.containsKey(secret)) {
                masked.put(secret, ModelProviderRegistry.SECRET_MASK);
            }
        }
        if (!owner) {
            Map<String, Object> restricted = new LinkedHashMap<>();
            if (masked.containsKey("type")) {
                restricted.put("type", masked.get("type"));
            }
            if (masked.containsKey("name")) {
                restricted.put("name", masked.get("name"));
            }
            // 核验时间对平台凭证使用者可见（前端展示「已核验」状态；无敏感字段）
            if (masked.containsKey("models_verified_at")) {
                restricted.put("models_verified_at", masked.get("models_verified_at"));
            }
            masked = restricted;
        }
        // enabled_models 是独立列（不在 data 里），两种视图都要显式带上——
        // 非归属者（平台凭证使用者）的对话选模型也要按它过滤；无敏感字段
        masked.put("enabled_models", enabledModelsOf(entity));
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", entity.getId().toString());
        view.put("user_id", entity.getUserId().toString());
        view.put("scope", entity.getScope());
        view.put("created_at", entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        view.put("updated_at", entity.getUpdatedAt() == null ? null : entity.getUpdatedAt().toString());
        view.put("data", masked);
        view.put("editable", owner);
        return view;
    }

    /** 实体列 → List<String>；未设置（null）返回 null 语义（= 全部可用）。 */
    private List<String> enabledModelsOf(ModelCredential entity) {
        JsonNode node = entity.getEnabledModels();
        if (node == null || node.isNull() || !node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        node.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** 库中 data（密文字段）→ 明文 Map。解密失败按配置错误抛 500 语义的 IllegalStateException。 */
    private Map<String, Object> decryptedData(ModelCredential entity) {
        JsonNode node = entity.getData();
        Map<String, Object> raw = node == null
                ? new HashMap<>()
                : objectMapper.convertValue(node,
                        new TypeReference<Map<String, Object>>() { });
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> secrets = registry.secretFields(entity.getCredType());
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (secrets.contains(e.getKey()) && e.getValue() != null) {
                out.put(e.getKey(), cipher.decrypt(e.getValue().toString()));
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * 提交 data → 入库形态：非密钥字段照搬；密钥字段为空或哨兵 → 沿用 current（编辑保旧），
     * 否则加密。新建时 current 传空 Map，密钥必填校验已在前保证非空。
     */
    private Map<String, Object> storeForm(String type, Map<String, Object> incoming,
                                          Map<String, Object> current) {
        Map<String, Object> out = new LinkedHashMap<>();
        Set<String> secrets = registry.secretFields(type);
        for (Map.Entry<String, Object> e : incoming.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (secrets.contains(key)) {
                String str = value == null ? "" : value.toString().trim();
                if (str.isEmpty() || ModelProviderRegistry.SECRET_MASK.equals(str)) {
                    if (current.containsKey(key)) {
                        out.put(key, cipher.encrypt(current.get(key).toString()));
                    }
                    continue;
                }
                out.put(key, cipher.encrypt(str));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    /** 供应商类型必须已知；required 字段（name / api_key 等）必须非空。 */
    private void validateRequired(String type, Map<String, Object> data) {
        Map<String, Object> schema = registry.schemaOf(type);
        if (schema == null) {
            throw ApiException.badRequest("未知的模型供应商类型: " + type);
        }
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.getOrDefault("required", List.of());
        List<String> missing = new ArrayList<>();
        for (String key : required) {
            Object v = data.get(key);
            if (v == null || v.toString().isBlank()
                    || ModelProviderRegistry.SECRET_MASK.equals(v.toString())) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw ApiException.badRequest("缺少必填字段: " + String.join(", ", missing));
        }
    }

    private String requireType(Map<String, Object> data) {
        Object type = data.get("type");
        if (type == null || type.toString().isBlank()) {
            throw ApiException.badRequest("凭证数据缺少 type 字段");
        }
        return type.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> body) {
        Object data = body == null ? null : body.get("data");
        if (!(data instanceof Map)) {
            throw ApiException.badRequest("请求体缺少 data 对象");
        }
        return (Map<String, Object>) data;
    }

    private UUID parseUserId(String userId) {
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            throw ApiException.unauthorized("用户身份无效，请重新登录");
        }
    }
}
