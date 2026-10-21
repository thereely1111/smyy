package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.config.SettingCatalog;
import com.tipdm.agentplatform.config.SettingOverrideService;
import com.tipdm.agentplatform.web.auth.RequirePermission;
import com.tipdm.agentplatform.web.error.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 系统配置页后端：读全部可改配置项、保存覆盖值、恢复默认。
 *
 * <p><b>权限复用 {@code admin:system}，不新增权限码</b>——与「平台凭证编辑」
 * （{@code credential:update}）同一手法：权限码的权威清单在外部统一认证服务
 * （{@code agent-platform.auth.oauth.permissions-path}），本仓库无法自助新增，
 * 新码要等外部开通才生效。复用已开通的码，本功能上线即刻可用。</p>
 *
 * <p><b>字段不由本类定义</b>：全部来自 {@link SettingCatalog}，前端也据此渲染表单。
 * 本类不硬编码任何配置键名，避免「后端认得、前端不认得」这类不一致。</p>
 */
@RestController
@RequestMapping("/api/admin/settings")
public class PlatformSettingController {

    private final SettingCatalog catalog;
    private final SettingOverrideService overrides;
    private final SessionAccessGuard accessGuard;

    public PlatformSettingController(SettingCatalog catalog,
                                     SettingOverrideService overrides,
                                     SessionAccessGuard accessGuard) {
        this.catalog = catalog;
        this.overrides = overrides;
        this.accessGuard = accessGuard;
    }

    /**
     * 全部可改配置项：声明 + 当前生效值 + 是否有数据库覆盖。
     *
     * <p>读权限与写权限一致（都用 {@code admin:system}）：读到的虽不含密钥明文，
     * 但含全部运行参数（如各 webhook 是否已配置），按写口径收紧更简单，
     * 也避免出现「能看不能改」的中间态。</p>
     */
    @GetMapping
    @RequirePermission("admin:system")
    public Map<String, Object> list() {
        Map<String, Object> values = overrides.currentValues();
        Set<String> overridden = overrides.overriddenKeys();

        List<Map<String, Object>> groups = new ArrayList<>();
        for (SettingCatalog.Group g : catalog.groups()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (SettingCatalog.Descriptor d : catalog.inGroup(g.id())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("key", d.key());
                item.put("type", d.type().name());
                item.put("label", d.label());
                item.put("description", d.description());
                item.put("unit", d.unit());
                item.put("min", d.min());
                item.put("max", d.max());
                item.put("options", d.options());
                item.put("secret", d.secret());
                item.put("overridden", overridden.contains(d.key()));
                item.put("value", values.get(d.key()));
                items.add(item);
            }
            if (!items.isEmpty()) {
                Map<String, Object> group = new LinkedHashMap<>();
                group.put("id", g.id());
                group.put("label", g.label());
                group.put("description", g.description());
                group.put("items", items);
                groups.add(group);
            }
        }
        return Map.of("groups", groups);
    }

    /**
     * 保存覆盖值：body = <code>{"&lt;key&gt;": &lt;value&gt;, ...}</code>。
     *
     * <p>只提交改动项——未出现在 body 里的键保持不动。敏感项回传掩码
     * （即页面读到的 {@code ***}）表示「不改这一项」，要改就传新值。</p>
     */
    @PutMapping
    @RequirePermission("admin:system")
    public Map<String, Object> save(@RequestBody Map<String, Object> body,
                                    HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        try {
            overrides.save(body, UUID.fromString(userId));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return list();
    }

    /** 恢复某一项的默认值（删覆盖 + 运行时改回）。 */
    @DeleteMapping("/{key:.+}")
    @RequirePermission("admin:system")
    public Map<String, Object> reset(@PathVariable String key, HttpServletRequest request) {
        String userId = accessGuard.requireUserId(request);
        try {
            overrides.reset(key, UUID.fromString(userId));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        return list();
    }
}
