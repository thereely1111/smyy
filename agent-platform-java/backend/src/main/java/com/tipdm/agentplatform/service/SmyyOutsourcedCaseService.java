package com.tipdm.agentplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tipdm.agentplatform.config.SmyyCaseContentProperties;
import com.tipdm.agentplatform.domain.SmyyOutsourcedCase;
import com.tipdm.agentplatform.repository.SmyyOutsourcedCaseRepository;
import com.tipdm.agentplatform.web.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 外包案例读写。
 *
 * <p>正文不再落库：上传的每个板块都写到内容根（{@code <仓库>/public/cases/<protoId>/<板块>/}），
 * DB 只保存**相对路径**（{@code *_file_name} 存主文件路径，{@code *_files_json} /
 * {@code animation_segments_json} 存 {@code [{name,path}]} / {@code [{key,label,fileName,path}]}）。
 * 读回时由 {@link SmyyCaseContentResolver} 把文件内容拼回原字段形状。
 */
@Service
public class SmyyOutsourcedCaseService {

    private static final Set<String> SECTIONS = Set.of("guide", "animation", "code", "result", "replay");
    private static final Pattern NON_ASCII_ALNUM = Pattern.compile("[^a-z0-9]+", Pattern.CASE_INSENSITIVE);
    private static final int MAX_SECTION_CHARS = 2_000_000;
    private static final int MAX_FILES_CHARS = 8_000_000;
    private static final int MAX_FILE_NAME_CHARS = 180;

    private final SmyyOutsourcedCaseRepository repository;
    private final SmyyCaseContentProperties contentProperties;
    private final SmyyCaseContentResolver contentResolver;
    private final ObjectMapper objectMapper;

    public SmyyOutsourcedCaseService(SmyyOutsourcedCaseRepository repository,
                                     SmyyCaseContentProperties contentProperties,
                                     SmyyCaseContentResolver contentResolver,
                                     ObjectMapper objectMapper) {
        this.repository = repository;
        this.contentProperties = contentProperties;
        this.contentResolver = contentResolver;
        this.objectMapper = objectMapper;
    }

    public List<SmyyOutsourcedCase> list() {
        return repository.findAllByOrderByProtoIdAsc();
    }

    public SmyyOutsourcedCase get(String protoId) {
        return repository.findByProtoId(protoId)
                .orElseThrow(() -> ApiException.notFound("案例不存在: " + protoId));
    }

    @Transactional
    public SmyyOutsourcedCase upsert(Map<String, Object> request) {
        String protoId = text(request.get("protoId"));
        String title = text(request.get("title"));
        if (!protoId.matches("p(?:[1-9]|1[01])")) {
            throw ApiException.badRequest("题目编号必须是 p1 至 p11");
        }
        if (title.isBlank() || title.length() > 200) {
            throw ApiException.badRequest("案例名称必填且不超过 200 个字符");
        }

        SmyyOutsourcedCase entity = repository.findByProtoId(protoId).orElseGet(SmyyOutsourcedCase::new);
        boolean creating = entity.getId() == null;
        String slug = slug(protoId, title);
        if (repository.existsBySlug(slug) && !slug.equals(entity.getSlug())) {
            throw ApiException.conflict("案例文件名冲突，请调整案例名称");
        }

        entity.setProtoId(protoId);
        entity.setTitle(title);
        entity.setSlug(slug);
        entity.setTag(limit(text(request.get("tag")), 80));
        entity.setCategory(limit(text(request.get("category")), 80));
        entity.setSource("OUTSOURCED");
        entity.setStatus("PUBLISHED");
        if (creating) {
            entity.setVersion(1);
        } else {
            entity.setVersion(entity.getVersion() + 1);
        }

        Map<?, ?> sections = request.get("sections") instanceof Map<?, ?> map ? map : Map.of();
        Object animationSegments = sections.get("animationSegments");
        if (animationSegments != null) {
            String segmentsJson = animationSegments.toString();
            if (segmentsJson.length() > MAX_FILES_CHARS) {
                throw ApiException.badRequest("可视化分段内容不能超过 8 MB");
            }
            entity.setAnimationSegmentsJson(storeSegments(protoId, segmentsJson));
        }
        applyFiles(entity, protoId, sections.get("guideFiles"), "guide");
        applyFiles(entity, protoId, sections.get("codeFiles"), "code");
        applyFiles(entity, protoId, sections.get("resultFiles"), "result");
        for (String key : SECTIONS) {
            if (!sections.containsKey(key)) {
                continue;
            }
            Object raw = sections.get(key);
            String content = raw == null ? null : raw.toString();
            if (content != null && content.length() > MAX_SECTION_CHARS) {
                throw ApiException.badRequest("板块内容不能超过 2 MB: " + key);
            }
            persistSection(entity, protoId, slug, key, content);
        }
        SmyyOutsourcedCase saved = repository.save(entity);
        contentResolver.evict(saved.getId());
        return saved;
    }

    @Transactional
    public SmyyOutsourcedCase replaceSection(String protoId, String section, String content) {
        if (!SECTIONS.contains(section)) {
            throw ApiException.badRequest("不支持的案例板块: " + section);
        }
        if ("replay".equals(section)) {
            throw ApiException.badRequest("不支持的案例板块: " + section);
        }
        if (content != null && content.length() > MAX_FILES_CHARS) {
            throw ApiException.badRequest("板块内容不能超过 8 MB");
        }
        SmyyOutsourcedCase entity = get(protoId);
        entity.setVersion(entity.getVersion() + 1);
        persistSection(entity, protoId, entity.getSlug(), section, content);
        SmyyOutsourcedCase saved = repository.save(entity);
        contentResolver.evict(saved.getId());
        return saved;
    }

    /**
     * 把一个板块的正文落盘，并把 DB 里的路径列指向该文件。
     *
     * <p>{@code content} 为 {@code null} 表示清空该板块：路径列一并清空（旧的内联列同时置空），
     * 前端回落到空态卡片。
     */
    private void persistSection(SmyyOutsourcedCase entity, String protoId, String slug,
                                String section, String content) {
        if (content == null) {
            switch (section) {
                case "guide" -> entity.setGuideFileName(null);
                case "animation" -> entity.setAnimationFileName(null);
                case "code" -> entity.setCodeFileName(null);
                case "result" -> entity.setResultFileName(null);
                case "replay" -> entity.setReplayFileName(null);
                default -> {
                }
            }
            clearInlineContent(entity, section);
            return;
        }
        String name = fileName(protoId, slug, section, entity.getVersion());
        String relativePath = writeText(protoId, section, name, content);
        switch (section) {
            case "guide" -> entity.setGuideFileName(relativePath);
            case "animation" -> entity.setAnimationFileName(relativePath);
            case "code" -> entity.setCodeFileName(relativePath);
            case "result" -> entity.setResultFileName(relativePath);
            case "replay" -> entity.setReplayFileName(relativePath);
            default -> {
            }
        }
        clearInlineContent(entity, section);
    }

    /** 正文已外置，内联内容列不再写入，保证「DB 只存路径」。 */
    private static void clearInlineContent(SmyyOutsourcedCase entity, String section) {
        switch (section) {
            case "guide" -> entity.setGuideHtml(null);
            case "animation" -> entity.setAnimationHtml(null);
            case "code" -> entity.setCodeText(null);
            case "result" -> entity.setResultHtml(null);
            case "replay" -> entity.setReplayHtml(null);
            default -> {
            }
        }
    }

    private void applyFiles(SmyyOutsourcedCase entity, String protoId, Object raw, String section) {
        if (raw == null) {
            return;
        }
        String filesJson = raw.toString();
        if (filesJson.length() > MAX_FILES_CHARS) {
            throw ApiException.badRequest("文件集合不能超过 8 MB: " + section);
        }
        String stored = storeFiles(protoId, section, filesJson);
        switch (section) {
            case "guide" -> entity.setGuideFilesJson(stored);
            case "code" -> entity.setCodeFilesJson(stored);
            case "result" -> entity.setResultFilesJson(stored);
            default -> throw ApiException.badRequest("不支持的文件板块: " + section);
        }
    }

    /** {@code [{name,text}]} → 逐个落盘 → 只存 {@code [{name,path}]}。 */
    private String storeFiles(String protoId, String section, String filesJson) {
        List<Map<String, Object>> entries = readEntries(filesJson, section);
        if (entries.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> entry : entries) {
            index++;
            Object text = entry.get("text");
            if (text == null) {
                // 已经是 {name,path} 形态（回环/重复提交）：原样保留
                out.add(entry);
                continue;
            }
            String name = sanitizeFileName(str(entry.get("name")));
            if (name == null) {
                name = section + "-" + index + ".txt";
            }
            String relativePath = writeText(protoId, section, name, text.toString());
            Map<String, Object> file = new LinkedHashMap<>();
            file.put("name", name);
            file.put("path", relativePath);
            out.add(file);
        }
        return toJson(out);
    }

    /** {@code [{key,label,fileName,html}]} → 逐段落盘 → 只存 {@code [{key,label,fileName,path}]}。 */
    private String storeSegments(String protoId, String segmentsJson) {
        List<Map<String, Object>> entries = readEntries(segmentsJson, "animationSegments");
        List<Map<String, Object>> out = new ArrayList<>();
        int index = 0;
        for (Map<String, Object> entry : entries) {
            index++;
            Object html = entry.get("html");
            if (html == null) {
                out.add(entry);
                continue;
            }
            String name = sanitizeFileName(str(entry.get("fileName")));
            if (name == null) {
                name = "segment-" + index + ".html";
            }
            String relativePath = writeText(protoId, "animation", name, html.toString());
            Map<String, Object> segment = new LinkedHashMap<>();
            segment.put("key", entry.get("key"));
            segment.put("label", entry.get("label"));
            segment.put("fileName", name);
            segment.put("path", relativePath);
            out.add(segment);
        }
        return toJson(out);
    }

    private List<Map<String, Object>> readEntries(String json, String field) {
        try {
            TypeReference<List<Map<String, Object>>> type = new TypeReference<>() {
            };
            List<Map<String, Object>> parsed = objectMapper.readValue(json, type);
            return parsed == null ? List.of() : parsed;
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("字段不是合法 JSON 数组: " + field);
        }
    }

    /** 写文件并返回相对内容根的路径（统一用 {@code /} 分隔）。 */
    private String writeText(String protoId, String section, String name, String content) {
        Path root = contentProperties.root();
        Path dir = root.resolve(protoId).resolve(section).normalize();
        Path target = dir.resolve(name).normalize();
        if (!dir.startsWith(root) || !target.startsWith(dir)) {
            throw ApiException.badRequest("非法的案例文件名: " + name);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入案例文件失败: " + target, e);
        }
        return root.relativize(target).toString().replace('\\', '/');
    }

    /** 去掉目录成分与危险片段，只保留安全的文件名。 */
    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            return null;
        }
        return name.length() > MAX_FILE_NAME_CHARS ? name.substring(0, MAX_FILE_NAME_CHARS) : name;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化案例内容失败", e);
        }
    }

    @Transactional
    public void delete(String protoId) {
        SmyyOutsourcedCase entity = get(protoId);
        contentResolver.evict(entity.getId());
        repository.delete(entity);
    }

    public static String fileName(String protoId, String slug, String section, int version) {
        String extension = "code".equals(section) ? "py" : "html";
        return protoId + "_" + slug + "_" + section + "_v" + version + "." + extension;
    }

    private static String slug(String protoId, String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{ASCII}]", " ");
        String ascii = NON_ASCII_ALNUM.matcher(normalized).replaceAll("-").replaceAll("^-|-$", "");
        String readable = switch (protoId) {
            case "p3" -> "microgrid-power-dispatch";
            case "p5" -> "sem-ad-optimization";
            default -> ascii;
        };
        if (readable.isBlank()) {
            readable = "case-" + protoId;
        }
        return readable.length() > 120 ? readable.substring(0, 120).replaceAll("-$", "") : readable;
    }

    private static String str(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String limit(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() > max) {
            throw ApiException.badRequest("字段长度不能超过 " + max + " 个字符");
        }
        return value;
    }
}
