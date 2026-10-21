package com.tipdm.agentplatform.service.credential;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型供应商注册表：凭证 schema（新建 / 编辑表单按它动态渲染）+ 三类模型目录
 * （chat / embedding / tts，详情页「可用模型」表格的数据源）。
 *
 * <p>纯静态目录（代码内置、无 DB）：模型元数据只是展示口径（上下文长度、模态、
 * 维度），不校验供应商侧真伪；供应商新增模型时在此补一行即可。</p>
 *
 * <p>schema 形状对齐前端 {@code SchemaForm}：{@code format: "password"} 渲染密码框、
 * {@code writeOnly: true} 标记密钥字段（编辑不回填、服务端不回显）、
 * {@code properties.type.const} 是供应商类型标识（前端据此把 schema 与凭证关联）。</p>
 */
@Component
public class ModelProviderRegistry {

    /** writeOnly 字段在出参里的掩码哨兵；回传同值或空值 = 保留旧密钥。 */
    public static final String SECRET_MASK = "••••••••";

    /** schema → 供应商标识（properties.type.const）。 */
    private final List<Map<String, Object>> schemas = new ArrayList<>();

    /** 供应商标识 → chat 模型目录。 */
    private final Map<String, List<Map<String, Object>>> chatCatalog = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> embeddingCatalog = new LinkedHashMap<>();
    private final Map<String, List<Map<String, Object>>> ttsCatalog = new LinkedHashMap<>();

    public ModelProviderRegistry() {
        registerProviders();
    }

    private void registerProviders() {
        // ── 阿里云百炼（DashScope）──────────────────────────────────────────
        addSchema("dashscope", "阿里云百炼（DashScope）",
                prop("type", Map.of("type", "string", "const", "dashscope", "title", "供应商类型")),
                prop("name", Map.of("type", "string", "title", "名称")),
                prop("api_key", Map.of(
                        "type", "string", "title", "API Key",
                        "format", "password", "writeOnly", true)),
                prop("base_url", Map.of("type", "string", "title", "Base URL（可选，默认官方入口）")));
        chat("dashscope",
                chatCard("qwen3.8-flash", "Qwen3.8 Flash", 1_000_000, 32_768, true, false),
                chatCard("qwen-plus", "Qwen Plus", 131_072, 8_192, false, false),
                chatCard("qwen-max", "Qwen Max", 32_768, 8_192, false, false),
                chatCard("qwen3-vl-plus", "Qwen3 VL Plus", 131_072, 8_192, false, true));
        embedding("dashscope",
                embeddingCard("text-embedding-v4", "Text Embedding V4", 1024,
                        List.of(2048.0, 1536.0, 1024.0, 768.0, 512.0, 256.0, 128.0, 64.0)),
                embeddingCard("text-embedding-v3", "Text Embedding V3", 1024, null));
        tts("dashscope",
                ttsCard("cosyvoice-v2", "CosyVoice V2", false),
                ttsCard("qwen-tts", "Qwen TTS", false));

        // ── OpenAI ────────────────────────────────────────────────────────
        addSchema("openai", "OpenAI",
                prop("type", Map.of("type", "string", "const", "openai", "title", "供应商类型")),
                prop("name", Map.of("type", "string", "title", "名称")),
                prop("api_key", Map.of(
                        "type", "string", "title", "API Key",
                        "format", "password", "writeOnly", true)),
                prop("base_url", Map.of("type", "string", "title", "Base URL（可选，兼容代理）")));
        chat("openai",
                chatCard("gpt-4o", "GPT-4o", 128_000, 16_384, false, true),
                chatCard("gpt-4o-mini", "GPT-4o mini", 128_000, 16_384, false, true),
                chatCard("gpt-4.1", "GPT-4.1", 1_000_000, 32_768, false, true),
                chatCard("o3", "o3", 200_000, 100_000, true, true));
        embedding("openai",
                embeddingCard("text-embedding-3-small", "Text Embedding 3 Small", 1536, null),
                embeddingCard("text-embedding-3-large", "Text Embedding 3 Large", 3072,
                        List.of(256.0, 512.0, 1024.0, 3072.0)));
        tts("openai",
                ttsCard("gpt-4o-mini-tts", "GPT-4o mini TTS", false),
                ttsCard("tts-1", "TTS-1", false));

        // ── DeepSeek ──────────────────────────────────────────────────────
        addSchema("deepseek", "DeepSeek（深度求索）",
                prop("type", Map.of("type", "string", "const", "deepseek", "title", "供应商类型")),
                prop("name", Map.of("type", "string", "title", "名称")),
                prop("api_key", Map.of(
                        "type", "string", "title", "API Key",
                        "format", "password", "writeOnly", true)),
                prop("base_url", Map.of("type", "string", "title", "Base URL（可选）")));
        chat("deepseek",
                chatCard("deepseek-chat", "DeepSeek Chat", 131_072, 8_192, false, false),
                chatCard("deepseek-reasoner", "DeepSeek Reasoner", 131_072, 65_536, true, false));

        // ── Moonshot（Kimi）───────────────────────────────────────────────
        addSchema("moonshot", "Moonshot（Kimi）",
                prop("type", Map.of("type", "string", "const", "moonshot", "title", "供应商类型")),
                prop("name", Map.of("type", "string", "title", "名称")),
                prop("api_key", Map.of(
                        "type", "string", "title", "API Key",
                        "format", "password", "writeOnly", true)),
                prop("base_url", Map.of("type", "string", "title", "Base URL（可选）")));
        chat("moonshot",
                chatCard("kimi-k2-0905-preview", "Kimi K2 (0905)", 262_144, 16_384, false, false),
                chatCard("kimi-latest", "Kimi Latest", 131_072, 16_384, false, true));

        // ── 智谱（Zhipu / GLM）────────────────────────────────────────────
        addSchema("zhipu", "智谱（GLM）",
                prop("type", Map.of("type", "string", "const", "zhipu", "title", "供应商类型")),
                prop("name", Map.of("type", "string", "title", "名称")),
                prop("api_key", Map.of(
                        "type", "string", "title", "API Key",
                        "format", "password", "writeOnly", true)),
                prop("base_url", Map.of("type", "string", "title", "Base URL（可选）")));
        chat("zhipu",
                chatCard("glm-4.6", "GLM-4.6", 200_000, 128_000, true, false),
                chatCard("glm-4.5-air", "GLM-4.5 Air", 131_072, 98_304, true, false),
                chatCard("glm-4-flash", "GLM-4 Flash", 131_072, 4_096, false, false));
        embedding("zhipu",
                embeddingCard("embedding-3", "Embedding 3", 2048, null));

        // ── Anthropic ─────────────────────────────────────────────────────
        addSchema("anthropic", "Anthropic（Claude）",
                prop("type", Map.of("type", "string", "const", "anthropic", "title", "供应商类型")),
                prop("name", Map.of("type", "string", "title", "名称")),
                prop("api_key", Map.of(
                        "type", "string", "title", "API Key",
                        "format", "password", "writeOnly", true)),
                prop("base_url", Map.of("type", "string", "title", "Base URL（可选）")));
        chat("anthropic",
                chatCard("claude-sonnet-4-5", "Claude Sonnet 4.5", 200_000, 64_000, true, true),
                chatCard("claude-haiku-4-5", "Claude Haiku 4.5", 200_000, 64_000, false, true));
    }

    // ── 查询 ─────────────────────────────────────────────────────────────

    /** 全部供应商 schema（凭证页侧栏分组 + 动态表单的数据源）。 */
    public List<Map<String, Object>> schemas() {
        return schemas;
    }

    public boolean isKnownProvider(String type) {
        return type != null && chatCatalog.containsKey(type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> schemaOf(String type) {
        if (type == null) {
            return null;
        }
        for (Map<String, Object> schema : schemas) {
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            if (properties == null) {
                continue;
            }
            Object typeProp = properties.get("type");
            if (typeProp instanceof Map<?, ?> tp && type.equals(tp.get("const"))) {
                return schema;
            }
        }
        return null;
    }

    /** writeOnly 字段名集合（该 provider 的 schema 里 format=password / writeOnly=true）。 */
    public Set<String> secretFields(String type) {
        Map<String, Object> schema = schemaOf(type);
        if (schema == null) {
            return Set.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Set<String> out = new java.util.HashSet<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            if (e.getKey().equals("type") || !(e.getValue() instanceof Map<?, ?> p)) {
                continue;
            }
            if (Boolean.TRUE.equals(p.get("writeOnly")) || "password".equals(p.get("format"))) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    public List<Map<String, Object>> chatModels(String provider) {
        return chatCatalog.getOrDefault(provider, List.of());
    }

    public List<Map<String, Object>> embeddingModels(String provider) {
        return embeddingCatalog.getOrDefault(provider, List.of());
    }

    public List<Map<String, Object>> ttsModels(String provider) {
        return ttsCatalog.getOrDefault(provider, List.of());
    }

    // ── 构建辅助 ─────────────────────────────────────────────────────────

    private void chat(String provider, Map<String, Object>... models) {
        chatCatalog.put(provider, List.of(models));
    }

    private void embedding(String provider, Map<String, Object>... models) {
        embeddingCatalog.put(provider, List.of(models));
    }

    private void tts(String provider, Map<String, Object>... models) {
        ttsCatalog.put(provider, List.of(models));
    }

    private void addSchema(String type, String title, Map<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map<String, Object> p : props) {
            String key = (String) p.keySet().iterator().next();
            properties.put(key, p.values().iterator().next());
            if (!key.equals("type") && !key.equals("base_url")) {
                required.add(key);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("title", title);
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schemas.add(schema);
    }

    private static Map<String, Object> prop(String key, Map<String, Object> value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    /** chat 模型卡片（形状对齐前端 ModelCard）。 */
    private static Map<String, Object> chatCard(String name, String label, long context,
                                                long output, boolean thinking, boolean vision) {
        List<String> input = new ArrayList<>();
        input.add("text");
        if (vision) {
            input.add("image");
        }
        if (thinking) {
            input.add("application/x-thinking");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "chat_model");
        m.put("name", name);
        m.put("label", label);
        m.put("status", "active");
        m.put("deprecated_at", null);
        m.put("input_types", input);
        m.put("output_types", List.of("text"));
        m.put("context_size", context);
        m.put("output_size", output);
        m.put("parameter_schema", Map.of());
        m.put("parameters_overrides", Map.of());
        return m;
    }

    /** embedding 模型卡片（形状对齐前端 EmbeddingModelCard）。 */
    private static Map<String, Object> embeddingCard(String name, String label, int dimensions,
                                                     List<Double> supportedDimensions) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "embedding_model");
        m.put("name", name);
        m.put("label", label);
        m.put("status", "active");
        m.put("input_types", List.of("text"));
        m.put("output_types", List.of("application/x-embedding"));
        m.put("context_size", null);
        m.put("dimensions", dimensions);
        m.put("supported_dimensions", supportedDimensions);
        m.put("parameter_schema", Map.of());
        m.put("parameter_overrides", Map.of());
        return m;
    }

    /** TTS 模型卡片（形状对齐前端 TTSModelCard）。 */
    private static Map<String, Object> ttsCard(String name, String label, boolean realtime) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "tts_model");
        m.put("name", name);
        m.put("label", label);
        m.put("status", "active");
        m.put("deprecated_at", null);
        m.put("input_types", List.of("text"));
        m.put("output_types", List.of("audio"));
        m.put("realtime", realtime);
        m.put("parameter_schema", Map.of());
        m.put("parameters_overrides", Map.of());
        return m;
    }
}
