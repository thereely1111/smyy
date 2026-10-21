package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.domain.ExternalProvider;
import com.tipdm.agentplatform.domain.KnowledgeBase;
import com.tipdm.agentplatform.util.OutboundUrlGuard;
import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchResult;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 外部 RAG 平台委托检索（P4）。按 {@code external_provider} 适配 RAGFlow / Dify / 百炼，
 * 用存储的 {@code external_endpoint} + 解密后的 {@code external_api_key_enc} 调用，
 * 结果归一化为 {@link SearchResult#vector}（来自外部平台）。
 */
@Service
public class ExternalRagClient {

    private static final Logger log = LoggerFactory.getLogger(ExternalRagClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ExternalApiKeyCipher cipher;
    private final ObjectMapper json;
    private final OutboundUrlGuard urlGuard;
    private final HttpClient http;

    public ExternalRagClient(ExternalApiKeyCipher cipher, ObjectMapper json, OutboundUrlGuard urlGuard) {
        this.cipher = cipher;
        this.json = json;
        this.urlGuard = urlGuard;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    }

    public List<SearchResult> search(KnowledgeBase kb, String query, int topK) {
        ExternalProvider provider = kb.getExternalProvider();
        String endpoint = kb.getExternalEndpoint();
        if (provider == null || endpoint == null || endpoint.isBlank()) {
            throw ApiException.badRequest("外部知识库缺少 provider 或 endpoint 配置");
        }
        String apiKey = cipher.decrypt(kb.getExternalApiKeyEnc());
        if (apiKey == null || apiKey.isBlank()) {
            throw ApiException.badRequest("外部知识库缺少 API Key（解密失败或未配置）");
        }
        // SSRF 防护：endpoint 由建库者自由填写且请求会带上解密后的 API Key，
        // 不校验即可让其打到云元数据(169.254.169.254)、本机服务或内网管理端。
        urlGuard.requireSafe(endpoint, "外部知识库 endpoint ");
        Map<String, Object> extra = kb.getExternalExtra();
        return switch (provider) {
            case ragflow -> searchRagflow(endpoint, apiKey, extra, query, topK);
            case dify -> searchDify(endpoint, apiKey, extra, query, topK);
            case bailian -> searchBailian(endpoint, apiKey, extra, query, topK);
        };
    }

    // ---- RAGFlow：POST {endpoint}/api/v1/retrieval ----

    private List<SearchResult> searchRagflow(
            String endpoint, String apiKey, Map<String, Object> extra, String query, int topK) {
        String datasetIds = extra == null ? null : str(extra.get("dataset_ids"));
        String body = """
                {"question":"%s","top_k":%d%s}
                """.formatted(escapeJson(query), topK,
                (datasetIds != null ? ",\"dataset_ids\":[\"" + escapeJson(datasetIds) + "\"]" : ""));
        String resp = postJson(endpoint.replaceAll("/$", "") + "/api/v1/retrieval", apiKey, body);
        return parseRagflow(resp, topK);
    }

    private List<SearchResult> parseRagflow(String resp, int topK) {
        try {
            JsonNode chunks = json.readTree(resp).path("data").path("chunks");
            List<SearchResult> out = new ArrayList<>();
            if (chunks.isArray()) {
                for (JsonNode c : chunks) {
                    out.add(SearchResult.vector(null, 0, c.path("score").asDouble(0.0),
                            c.path("content").asText("")));
                    if (out.size() >= topK) {
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw ApiException.badRequest("解析 RAGFlow 响应失败: " + e.getMessage());
        }
    }

    // ---- Dify：POST {endpoint}/v1/datasets/{dataset_id}/retrieval ----

    private List<SearchResult> searchDify(
            String endpoint, String apiKey, Map<String, Object> extra, String query, int topK) {
        String datasetId = extra == null ? null : str(extra.get("dataset_id"));
        if (datasetId == null || datasetId.isBlank()) {
            throw ApiException.badRequest("Dify 检索需要 external_extra.dataset_id");
        }
        String body = """
                {"question":"%s","top_k":%d,"with_content":true}
                """.formatted(escapeJson(query), topK);
        String resp = postJson(
                endpoint.replaceAll("/$", "") + "/v1/datasets/" + escapeJson(datasetId) + "/retrieval",
                apiKey, body);
        return parseDify(resp, topK);
    }

    private List<SearchResult> parseDify(String resp, int topK) {
        try {
            JsonNode data = json.readTree(resp).path("data");
            List<SearchResult> out = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode c : data) {
                    out.add(SearchResult.vector(null, 0, c.path("score").asDouble(0.0),
                            c.path("content").asText("")));
                    if (out.size() >= topK) {
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw ApiException.badRequest("解析 Dify 响应失败: " + e.getMessage());
        }
    }

    // ---- 百炼（Bailian）：POST {endpoint}/retrieval，app_id 取自 extra ----

    private List<SearchResult> searchBailian(
            String endpoint, String apiKey, Map<String, Object> extra, String query, int topK) {
        String appId = extra == null ? null : str(extra.get("app_id"));
        String body = """
                {"query":"%s","top_k":%d%s}
                """.formatted(escapeJson(query), topK,
                (appId != null ? ",\"app_id\":\"" + escapeJson(appId) + "\"" : ""));
        String resp = postJson(endpoint.replaceAll("/$", "") + "/retrieval", apiKey, body);
        return parseBailian(resp, topK);
    }

    private List<SearchResult> parseBailian(String resp, int topK) {
        try {
            JsonNode root = json.readTree(resp);
            JsonNode arr = root.path("data").isArray() ? root.path("data")
                    : root.path("results").isArray() ? root.path("results") : root;
            List<SearchResult> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode c : arr) {
                    out.add(SearchResult.vector(null, 0, c.path("score").asDouble(0.0),
                            c.path("content").asText("")));
                    if (out.size() >= topK) {
                        break;
                    }
                }
            }
            return out;
        } catch (Exception e) {
            throw ApiException.badRequest("解析百炼响应失败: " + e.getMessage());
        }
    }

    // ---- HTTP 工具 ----

    private String postJson(String url, String apiKey, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                // 不回显响应体：响应可能来自被 SSRF 命中的内网服务，回显等于提供内网探测通道。
                // 细节只进日志，前端只拿到状态码。
                log.warn("外部 RAG 调用失败 status={} url={} body={}", resp.statusCode(), url, truncate(resp.body()));
                throw ApiException.badRequest("外部 RAG 调用失败 " + resp.statusCode());
            }
            return resp.body();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 异常信息里会带目标地址（如 "Connection to http://10.0.0.5 refused"），
            // 同样属于内网信息泄露，只进日志不回显。
            log.warn("外部 RAG 调用异常 url={}: {}", url, e.getMessage());
            throw ApiException.badRequest("外部 RAG 调用异常，请检查 endpoint 配置与网络连通性");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String truncate(String s) {
        return s == null ? "" : (s.length() > 300 ? s.substring(0, 300) + "..." : s);
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
