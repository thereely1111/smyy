package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.SmyyCaseContentProperties;
import com.tipdm.agentplatform.domain.SmyyOutsourcedCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 案例内容解析：库表只存相对路径，正文从内容根（{@code <仓库>/public/cases}）读回，
 * 再按**原有内联字段形状**拼好返回，让前端渲染契约零改动。
 *
 * <p>两类字段的读取规则：
 * <ul>
 *   <li>{@code guideHtml}/{@code animationHtml}/{@code codeText}/{@code resultHtml}/{@code replayHtml}：
 *       优先读 {@code *_file_name} 指向的文件；文件缺失或列未迁移时回落到同名内容列（老数据兜底）。</li>
 *   <li>{@code *_files_json} / {@code animation_segments_json}：新格式条目带 {@code path} → 读文件后
 *       还原成 {@code {name,text}} / {@code {key,label,fileName,html}}；旧格式（只有内联 {@code text}/{@code html}）
 *       则原样透传，保证未迁移案例逐字节一致。</li>
 * </ul>
 *
 * <p>结果按 {@code (caseId, version)} 缓存：{@code /api/smyy/cases} 列表会带上全部内容字段，
 * 不缓存的话每次列表都要读几 MB 文件。
 */
@Service
public class SmyyCaseContentResolver {

    private static final Logger log = LoggerFactory.getLogger(SmyyCaseContentResolver.class);

    private final SmyyCaseContentProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<UUID, Cached> cache = new ConcurrentHashMap<>();

    public SmyyCaseContentResolver(SmyyCaseContentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** 内容投影，字段名与改造前的「内联内容」字段完全一致。 */
    public record Content(
            String guideHtml,
            String animationHtml,
            String animationSegmentsJson,
            String codeText,
            String resultHtml,
            String replayHtml,
            String guideFilesJson,
            String codeFilesJson,
            String resultFilesJson) {
    }

    private record Cached(int version, Content content) {
    }

    /** 读到的文件正文与 UTF-8 字节数（{@code size} 沿用改造前库里的字节长度语义）。 */
    private record FileText(String text, int size) {
    }

    public Content resolve(SmyyOutsourcedCase c) {
        UUID id = c.getId();
        if (id == null) {
            return build(c);
        }
        Cached hit = cache.get(id);
        if (hit != null && hit.version() == c.getVersion()) {
            return hit.content();
        }
        Content content = build(c);
        cache.put(id, new Cached(c.getVersion(), content));
        return content;
    }

    /** 上传 / 替换 / 删除后清掉该案例的缓存。 */
    public void evict(UUID id) {
        if (id != null) {
            cache.remove(id);
        }
    }

    private Content build(SmyyOutsourcedCase c) {
        return new Content(
                textOrInline(c.getGuideFileName(), c.getGuideHtml()),
                textOrInline(c.getAnimationFileName(), c.getAnimationHtml()),
                segmentsJson(c.getAnimationSegmentsJson()),
                textOrInline(c.getCodeFileName(), c.getCodeText()),
                textOrInline(c.getResultFileName(), c.getResultHtml()),
                textOrInline(c.getReplayFileName(), c.getReplayHtml()),
                filesJson(c.getGuideFilesJson()),
                filesJson(c.getCodeFilesJson()),
                filesJson(c.getResultFilesJson()));
    }

    /** 单文件：优先读路径指向的文件，读不到回落到内联内容列。 */
    private String textOrInline(String relativePath, String inline) {
        FileText file = readFile(relativePath);
        return file != null ? file.text() : inline;
    }

    /** 文件集合：把 {@code [{name,path}]} 还原成 {@code [{name,text,size}]}；旧格式原样透传。 */
    private String filesJson(String storedJson) {
        List<Map<String, Object>> entries = readEntries(storedJson);
        if (entries == null || !hasPath(entries)) {
            return storedJson;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String path = str(entry.get("path"));
            FileText file = readFile(path);
            if (file == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", str(entry.get("name")) != null ? entry.get("name") : fileNameOf(path));
            item.put("text", file.text());
            item.put("size", file.size());
            out.add(item);
        }
        return out.isEmpty() ? null : toJson(out);
    }

    /** 可视化分段：把 {@code [{key,label,fileName,path}]} 还原成 {@code [{key,label,fileName,html}]}。 */
    private String segmentsJson(String storedJson) {
        List<Map<String, Object>> entries = readEntries(storedJson);
        if (entries == null || !hasPath(entries)) {
            return storedJson;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            String path = str(entry.get("path"));
            FileText file = readFile(path);
            if (file == null) {
                continue;
            }
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("key", entry.get("key"));
            segment.put("label", entry.get("label"));
            segment.put("fileName", str(entry.get("fileName")) != null ? entry.get("fileName") : fileNameOf(path));
            segment.put("html", file.text());
            out.add(segment);
        }
        return out.isEmpty() ? null : toJson(out);
    }

    private List<Map<String, Object>> readEntries(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(storedJson, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("案例内容字段不是合法 JSON 数组，按原值透传: {}", e.getOriginalMessage());
            return null;
        }
    }

    private static boolean hasPath(List<Map<String, Object>> entries) {
        return entries.stream().anyMatch(entry -> str(entry.get("path")) != null);
    }

    private FileText readFile(String relativePath) {
        Path file = resolvePath(relativePath);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new FileText(new String(bytes, StandardCharsets.UTF_8), bytes.length);
        } catch (IOException e) {
            log.warn("读取案例内容文件失败: {} ({})", relativePath, e.getMessage());
            return null;
        }
    }

    /** 相对路径 → 内容根下的绝对路径；越界（{@code ..}、绝对路径）一律拒绝。 */
    private Path resolvePath(String relativePath) {
        String relative = str(relativePath);
        if (relative == null) {
            return null;
        }
        relative = relative.replace('\\', '/');
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }
        if (relative.isBlank()) {
            return null;
        }
        Path root = properties.root();
        Path resolved = root.resolve(relative).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化案例内容失败", e);
        }
    }

    private static String fileNameOf(String path) {
        String value = str(path);
        if (value == null) {
            return null;
        }
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
