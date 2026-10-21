package com.tipdm.agentplatform.web;

import com.tipdm.agentplatform.service.credential.ModelProviderRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录（只读静态清单，形状对齐前端 modelApi / ttsModelApi / embeddingModelApi）。
 *
 * <p>路径按平台规范用复数资源名（/api/models、/api/tts-models、/api/embedding-models，
 * 2026-09-19 核对命名时由单数改齐；原单数形状来自被移植的旧 Python 服务）。
 * 目录来自 {@link ModelProviderRegistry} 的内置清单，按 provider 过滤；
 * 未登记的 provider 返回空列表而非 404——前端「可用模型」表格对未知供应商
 * 呈现空态，语义比报错柔和。</p>
 */
@RestController
public class ModelCatalogController {

    private final ModelProviderRegistry registry;
    private final SessionAccessGuard accessGuard;

    public ModelCatalogController(ModelProviderRegistry registry,
                                  SessionAccessGuard accessGuard) {
        this.registry = registry;
        this.accessGuard = accessGuard;
    }

    @RequestMapping("/api/models")
    public Map<String, Object> chatModels(@RequestParam("provider") String provider,
                                          HttpServletRequest request) {
        accessGuard.requireUserId(request);
        return catalog(registry.chatModels(provider));
    }

    @RequestMapping("/api/tts-models")
    public Map<String, Object> ttsModels(@RequestParam("provider") String provider,
                                         HttpServletRequest request) {
        accessGuard.requireUserId(request);
        return catalog(registry.ttsModels(provider));
    }

    @RequestMapping("/api/embedding-models")
    public Map<String, Object> embeddingModels(@RequestParam("provider") String provider,
                                               HttpServletRequest request) {
        accessGuard.requireUserId(request);
        return catalog(registry.embeddingModels(provider));
    }

    private Map<String, Object> catalog(List<Map<String, Object>> models) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("models", models);
        body.put("total", models.size());
        return body;
    }
}
