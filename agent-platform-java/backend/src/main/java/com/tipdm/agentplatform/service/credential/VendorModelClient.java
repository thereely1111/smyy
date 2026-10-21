package com.tipdm.agentplatform.service.credential;

import com.tipdm.agentplatform.util.OutboundUrlGuard;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 厂商模型清单客户端（2026-09-22 凭证核验流程）：
 * 保存凭证时用「API Key + models 列表端点」做 KEY 核验——鉴权通过即认为 KEY 有效，
 * 并把厂商返回的模型 id 清单缓存进凭证（data.vendor_models），供详情页开关与下拉消费。
 *
 * <p>各主流供应商均提供 OpenAI 兼容的 {@code GET .../models}（Bearer 鉴权），
 * Anthropic 用 {@code x-api-key + anthropic-version} 头。用户填写了 base_url 时优先
 * 用它推导端点（先试 {@code <base>/models}，404 再补 {@code /v1/models}），
 * 未填则用本表内置默认端点。出网统一过 {@link OutboundUrlGuard}（base_url 用户可控）。</p>
 */
@Component
public class VendorModelClient {

    private static final Logger log = LoggerFactory.getLogger(VendorModelClient.class);

    /** 供应商 → 默认 models 端点（未填 base_url 时使用）。 */
    private static final Map<String, String> DEFAULT_MODELS_URL = Map.of(
            "dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1/models",
            "openai", "https://api.openai.com/v1/models",
            "deepseek", "https://api.deepseek.com/models",
            "moonshot", "https://api.moonshot.cn/v1/models",
            "zhipu", "https://open.bigmodel.cn/api/paas/v4/models",
            "anthropic", "https://api.anthropic.com/v1/models");

    /** Bearer 鉴权之外的特例：Anthropic 用 x-api-key + 版本头。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final OutboundUrlGuard urlGuard;
    private final HttpClient http;

    public VendorModelClient(OutboundUrlGuard urlGuard) {
        this.urlGuard = urlGuard;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 核验 KEY 并返回厂商模型 id 清单（去重排序）。任何失败都抛 400——
     * 「核验不通过不允许保存」是本流程的语义：KEY 是凭证的唯一价值，存一个
     * 未核验的 KEY 只会把失败推迟到第一次对话。
     */
    public List<String> verifyAndListModels(String type, String apiKey, String baseUrl) {
        List<String> candidates = endpointCandidates(type, baseUrl);
        ApiException last = null;
        for (String url : candidates) {
            try {
                return fetchModels(url, type, apiKey);
            } catch (ApiException e) {
                // 鉴权失败不必换端点重试——KEY 错误与端点无关，直接判死
                if (e.getMessage() != null && e.getMessage().contains("API Key")) {
                    throw e;
                }
                last = e;
            }
        }
        throw last == null
                ? ApiException.badRequest("模型核验失败：未知错误")
                : last;
    }

    /** 端点候选：base_url 推导（<base>/models → <base>/v1/models）优先，内置默认兜底。 */
    private List<String> endpointCandidates(String type, String baseUrl) {
        List<String> out = new ArrayList<>();
        if (baseUrl != null && !baseUrl.isBlank()) {
            String base = baseUrl.trim().replaceAll("/+$", "");
            out.add(base + "/models");
            if (!base.endsWith("/v1") && !base.endsWith("/v4") && !base.endsWith("/compatible-mode/v1")) {
                out.add(base + "/v1/models");
            }
        }
        String fallback = DEFAULT_MODELS_URL.get(type);
        if (fallback != null && !out.contains(fallback)) {
            out.add(fallback);
        }
        if (out.isEmpty()) {
            throw ApiException.badRequest("供应商 " + type + " 暂不支持在线核验模型清单");
        }
        return out;
    }

    private List<String> fetchModels(String url, String type, String apiKey) {
        urlGuard.requireSafe(url, "模型供应商核验端点");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET();
        if ("anthropic".equals(type)) {
            builder.header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("vendor model list request failed: type={} url={} err={}", type, url, e.getMessage());
            throw ApiException.badRequest("模型核验请求失败（网络不可达或超时）: " + url);
        }
        int status = resp.statusCode();
        if (status == 401 || status == 403) {
            throw ApiException.badRequest("API Key 无效或无权限（供应商返回 " + status + "），请检查后重试");
        }
        if (status == 404) {
            throw ApiException.badRequest("该地址未提供模型列表接口: " + url);
        }
        if (status != 200) {
            throw ApiException.badRequest("模型核验失败（供应商返回 " + status + "），请稍后重试");
        }
        return parseModelIds(resp.body());
    }

    /** 解析 OpenAI 兼容形状 {"data":[{"id":...},...]}；Anthropic 同形状。 */
    private List<String> parseModelIds(String body) {
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        } catch (java.io.IOException e) {
            throw ApiException.badRequest("模型核验响应不是合法 JSON");
        }
        com.fasterxml.jackson.databind.JsonNode data = root.path("data");
        if (!data.isArray() || data.isEmpty()) {
            throw ApiException.badRequest("模型核验响应缺少 data 数组（供应商返回为空？）");
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode item : data) {
            String id = item.path("id").asText(null);
            if (id != null && !id.isBlank()) {
                seen.putIfAbsent(id, Boolean.TRUE);
            }
        }
        List<String> ids = new ArrayList<>(seen.keySet());
        java.util.Collections.sort(ids);
        return ids;
    }
}
