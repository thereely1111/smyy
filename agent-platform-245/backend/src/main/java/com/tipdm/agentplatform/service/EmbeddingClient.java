package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 文本向量化客户端。
 * <ul>
 *   <li>若配置了 {@code EMBEDDING_API_KEY}，则调用 OpenAI 兼容 {@code /v1/embeddings} 端点（默认 1536 维）；</li>
 *   <li>否则回退到确定性本地向量（无需任何外部依赖），保证无密钥时摄入与检索验证可跑通。</li>
 * </ul>
 * 维度恒为 1536，与 V24 {@code vector(1536)} 列对齐。
 */
@Component
public class EmbeddingClient {

    public static final int DIM = 1536;

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String apiBase;
    private final String apiKey;
    private final String model;

    /**
     * 远程 embedding 失败时是否允许回退到本地确定性（哈希）向量。
     *
     * <p>默认 <b>false</b>：哈希向量与真实语义向量不在同一空间，混入索引后检索结果
     * 「看着正常实则全错」，而且不同次摄入的结果互相矛盾（同一文本第二次正常摄入后
     * 向量与第一次不可比）。开着这个开关会有明确的 ERROR 级提示，仅供离线/演示环境
     * 无外网时使用：{@code EMBEDDING_ALLOW_HASH_FALLBACK=true}。</p>
     */
    private final boolean allowHashFallback;

    public EmbeddingClient() {
        this.apiBase = orEnv("EMBEDDING_API_BASE", "https://api.openai.com/v1");
        this.apiKey = System.getenv("EMBEDDING_API_KEY");
        this.model = orEnv("EMBEDDING_MODEL", "text-embedding-3-small");
        this.allowHashFallback = "true".equalsIgnoreCase(orEnv("EMBEDDING_ALLOW_HASH_FALLBACK", "false"));
    }

    private static String orEnv(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    public boolean isRemoteEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    public float[] embed(String text) {
        if (isRemoteEnabled()) {
            try {
                return embedRemote(text);
            } catch (Exception e) {
                // 原实现无条件降级为哈希向量，且每块一条 WARN 刷爆日志；key 失效/限流时
                // 整库向量变成无语义哈希，检索"看着正常实则全错"。默认不再静默降级：
                // 抛出去让文档落到 error 状态、摄入显式失败，由人决定重试还是换 key。
                if (!allowHashFallback) {
                    throw new IllegalStateException(
                            "远程 embedding 调用失败（已禁用哈希回退，文档不会入库）: " + e.getMessage(), e);
                }
                log.error("远程 embedding 失败，按 EMBEDDING_ALLOW_HASH_FALLBACK=true 回退本地"
                        + "确定性向量——该向量与真实语义向量不可比，检索结果不可信: {}", e.getMessage());
                return embedDeterministic(text);
            }
        }
        return embedDeterministic(text);
    }

    private float[] embedRemote(String text) throws Exception {
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("input", text);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(apiBase.replaceAll("/$", "") + "/embeddings"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("embeddings HTTP " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw new IllegalStateException("embeddings 响应缺少 data");
        }
        JsonNode vec = data.get(0).path("embedding");
        float[] out = new float[vec.size()];
        for (int i = 0; i < vec.size(); i++) {
            out[i] = (float) vec.get(i).asDouble();
        }
        if (out.length != DIM) {
            throw new IllegalStateException("embedding 维度不符: 期望 " + DIM + " 实际 " + out.length);
        }
        return normalize(out);
    }

    /**
     * 确定性回退：按词哈希映射到维度并累加，再做 L2 归一化。
     * 相同文本 → 相同向量；词面相近的文本 → 向量近似（以便无密钥时也能验证检索排序）。
     */
    float[] embedDeterministic(String text) {
        float[] v = new float[DIM];
        if (text == null || text.isBlank()) {
            v[0] = 1.0f;
            return normalize(v);
        }
        String[] tokens = text.toLowerCase().split("\\W+");
        for (String t : tokens) {
            if (t.isEmpty()) {
                continue;
            }
            int idx = Math.floorMod(t.hashCode(), DIM);
            v[idx] += 1.0f;
        }
        // 叠加基于文本整体的位置信号，降低不同文本偶然正交的概率
        v[Math.floorMod(text.hashCode(), DIM)] += 0.5f;
        return normalize(v);
    }

    static float[] normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            v[0] = 1.0f;
            return v;
        }
        float s = (float) (1.0 / norm);
        for (int i = 0; i < v.length; i++) {
            v[i] *= s;
        }
        return v;
    }
}
