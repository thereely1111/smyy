package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * 平台版搜索工具 {@code bocha_web_search}（博查 Bocha 实现）。
 *
 * <p>框架 {@code WebTools.WebSearchTool} 硬编码 api.tavily.com 并读
 * {@code TAVILY_API_KEY}，国内环境不可用。平台版走博查开放 API
 * （api.bochaai.com/v1/web-search，Bearer 鉴权），key 来自
 * {@code agent-platform.bocha.api-key}（环境变量 BOCHA_API_KEY）。</p>
 *
 * <p><b>为什么不同名</b>：框架 build() 对传入 toolkit 深拷贝后<b>无条件</b>注册
 * Tavily 版 web_search（后注册覆盖同名者），无论平台在哪个时机以同名注册都会被
 * 覆盖或覆盖不了子智能体拷贝链——故用独立工具名，由需要搜索的子智能体在定义
 * tools 白名单里引用 {@code bocha_web_search} 精确启用。输出格式与框架版一致
 * （编号. 标题 / URL / 内容）。</p>
 */
public final class PlatformWebSearchTool {

    private static final String BOCHA_API = "https://api.bochaai.com/v1/web-search";

    private final ObjectMapper mapper =
            new ObjectMapper();
    private final HttpClient client =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .build();
    private final String apiKey;

    PlatformWebSearchTool(String apiKey) {
        this.apiKey = apiKey;
    }

    @Tool(
            name = "bocha_web_search",
            readOnly = true,
            description =
                    "Search the web for information (Bocha API). Returns titles, URLs and"
                        + " content snippets. Preferred over the built-in web_search"
                        + " (Tavily-based, unconfigured).")
    public String webSearch(
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(
                            name = "max_results",
                            description = "Maximum results (default 5, max 10)",
                            required = false)
                    Integer maxResults) {
        if (query == null || query.isBlank()) {
            return "Error: query is required";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "Error: BOCHA_API_KEY is not set. Configure agent-platform.bocha.api-key"
                    + " (env BOCHA_API_KEY) to enable web_search.";
        }
        int limit = maxResults != null && maxResults > 0 ? Math.min(maxResults, 10) : 5;
        String body;
        try {
            body =
                    mapper.writeValueAsString(
                            Map.of(
                                    "query", query,
                                    "summary", true,
                                    "count", limit,
                                    "freshness", "noLimit"));
        } catch (Exception e) {
            return "Error: failed to encode request: " + e.getMessage();
        }
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(BOCHA_API))
                            .timeout(Duration.ofSeconds(30))
                            .header("Content-Type", "application/json")
                            .header("Authorization", "Bearer " + apiKey)
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return "Error: Bocha API returned " + response.statusCode();
            }
            JsonNode pages =
                    mapper
                            .readTree(response.body())
                            .path("data")
                            .path("webPages")
                            .path("value");
            if (!pages.isArray() || pages.isEmpty()) {
                return "No results.";
            }
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (JsonNode r : pages) {
                String content = r.path("summary").asText("");
                if (content.isBlank()) {
                    content = r.path("snippet").asText("");
                }
                sb.append(i++)
                        .append(". ")
                        .append(r.path("name").asText(""))
                        .append("\n   ")
                        .append(r.path("url").asText(""))
                        .append("\n   ")
                        .append(content)
                        .append("\n\n");
            }
            return sb.toString().strip();
        } catch (Exception e) {
            return "Error: web_search failed: " + e.getMessage();
        }
    }
}
