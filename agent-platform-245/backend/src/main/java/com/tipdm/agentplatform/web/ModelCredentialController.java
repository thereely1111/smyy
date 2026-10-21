package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.domain.ModelCredential;
import com.tipdm.agentplatform.service.credential.ModelCredentialService;
import com.tipdm.agentplatform.service.credential.ModelProviderRegistry;
import com.tipdm.agentplatform.service.credential.PlatformModelDefaultService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型凭证管理（模型供应商 API Key 等接入配置，用户级）。
 *
 * <p>路径按平台规范用复数资源名（/api/credentials，2026-09-19 核对命名时由
 * 单数改齐，原单数形状来自被移植的旧 Python 服务）。权限模型（2026-09-21 重构，
 * 对齐 SkillController 范式——scope 不经请求体传递，由端点承载）：</p>
 * <ul>
 *   <li>POST /api/credentials → 个人凭证（USER），登录即可（默认语义，历史端点保持）。</li>
 *   <li>POST /api/credentials/platform → 平台凭证（PLATFORM），{@code credential:create}
 *       注解把门。</li>
 *   <li>PATCH/DELETE /{id} → 先查实体 scope，PLATFORM 则要求 {@code credential:update}
 *       / {@code credential:delete}（条件码，2026-09-21 动词统一为 CRUD 风格）；
 *       USER 靠归属（requireOwned 越权 404）。</li>
 * </ul>
 * <p>密钥不回显、编辑保旧，见 {@link com.tipdm.agentplatform.service.credential.ModelCredentialService}。</p>
 */
@RestController
@RequestMapping("/api/credentials")
public class ModelCredentialController {

    private final ModelCredentialService service;
    private final ModelProviderRegistry registry;
    private final PlatformModelDefaultService platformModelDefaultService;
    private final SessionAccessGuard accessGuard;

    public ModelCredentialController(ModelCredentialService service,
                                     ModelProviderRegistry registry,
                                     PlatformModelDefaultService platformModelDefaultService,
                                     SessionAccessGuard accessGuard) {
        this.service = service;
        this.registry = registry;
        this.platformModelDefaultService = platformModelDefaultService;
        this.accessGuard = accessGuard;
    }

    /** 凭证列表（尾斜杠 + 无斜杠双映射）。 */
    @GetMapping({"", "/"})
    public Map<String, Object> list(HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        List<Map<String, Object>> credentials = service.list(userId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("credentials", credentials);
        body.put("total", credentials.size());
        return body;
    }

    /** 供应商 schema（新建 / 编辑表单按它动态渲染；侧栏分组按 properties.type.const）。 */
    @GetMapping("/schemas")
    public Map<String, Object> schemas() {
        return Map.of("schemas", registry.schemas());
    }

    /**
     * 平台默认模型（全局唯一，2026-09-22）：登录可读——对话未选模型时后端按它
     * 兜底，前端展示「平台默认」占位语义。未设置时 credential_id/model 为 null。
     */
    @GetMapping("/default-model")
    public Map<String, Object> getDefaultModel(HttpServletRequest request) {
        accessGuard.requireUserId(request);
        return platformModelDefaultService.view();
    }

    /**
     * 设置平台默认模型（覆盖式，全局互斥）：body = {credential_id, model}。
     * 只允许平台共享凭证下的启用模型（校验见 PlatformModelDefaultService）；
     * 权限复用 {@code credential:update}——与平台凭证编辑同级，不新增权限码。
     */
    @PutMapping("/default-model")
    @RequirePermission("credential:update")
    public Map<String, Object> setDefaultModel(@RequestBody Map<String, Object> body,
                                               HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return platformModelDefaultService.set(
                userId,
                body.get("credential_id") == null ? "" : body.get("credential_id").toString(),
                body.get("model") == null ? "" : body.get("model").toString());
    }

    /** 个人凭证创建：登录即可（scope=USER 由端点决定，不经请求体）。 */
    @PostMapping({"", "/"})
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.create(userId, body, ModelCredentialService.SCOPE_USER);
    }

    /** 平台凭证创建：credential:create 由 {@link RequirePermission} 切面把门（无条件权限用注解，
     *  对齐 AgentManagementController/ToolController 主流惯例）；scope=PLATFORM 由端点决定。 */
    @PostMapping("/platform")
    @RequirePermission("credential:create")
    public Map<String, Object> createPlatform(@RequestBody Map<String, Object> body,
                                              HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        return service.create(userId, body, ModelCredentialService.SCOPE_PLATFORM);
    }

    /** 编辑：按实体 scope 分流——平台凭证要求 credential:update；个人凭证靠归属。 */
    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable("id") String id,
                                      @RequestBody Map<String, Object> body,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        requirePlatformCodeIfNeeded(userId, id, request, "credential:update");
        return service.update(userId, id, body);
    }

    /** 删除：分流规则同 {@link #update}。 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable("id") String id,
                                      HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        requirePlatformCodeIfNeeded(userId, id, request, "credential:delete");
        service.delete(userId, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", true);
        return body;
    }

    /**
     * 条件码门槛：仅当目标凭证是平台共享时要求传入的细粒度码（静态注解表达不了
     * 「按资源 scope 分支」，与 SkillController.requirePlatformCodeIfNeeded 同模式）。
     * 个人凭证的归属校验（requireOwned 越权 404）已先行保证。
     */
    private void requirePlatformCodeIfNeeded(String userId, String id,
                                             HttpServletRequest request, String code) {
        ModelCredential entity = service.requireOwned(userId, id);
        if (ModelCredentialService.SCOPE_PLATFORM.equals(entity.getScope())) {
            accessGuard.requirePermissionCode(request, code);
        }
    }
}
