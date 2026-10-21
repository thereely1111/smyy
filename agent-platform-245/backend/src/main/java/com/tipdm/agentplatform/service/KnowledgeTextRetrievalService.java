package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.web.dto.KnowledgeBaseDtos.SearchResult;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * text 类型检索（P3.5 次路径，服务端 grep 兜底）：在 KB 目录内对文件逐行匹配 query，
 * 返回命中行 {@code TextSearchResult} 形态（file/line/content/context）。
 *
 * <p>主路径（agent 运行时 grep/cat 工具作用域绑定）由调用方通过
 * {@code KnowledgeBaseService.resolveStorageDir} 取该 KB 的绝对目录，传入工具的沙箱根。
 * 两者共用 {@code KnowledgeStorageService.kbDir} 的 startsWith 防穿越守卫。
 */
@Service
public class KnowledgeTextRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeTextRetrievalService.class);
    private static final int CONTEXT_LINES = 1;

    /** 单文件检索上限 8MB：超过则跳过，避免 readAllLines 把误传的大文件读进堆。 */
    private static final long MAX_SEARCH_FILE_BYTES = 8L * 1024 * 1024;

    private final KnowledgeStorageService storageService;

    public KnowledgeTextRetrievalService(KnowledgeStorageService storageService) {
        this.storageService = storageService;
    }

    public List<SearchResult> search(UUID kbId, String query, int topK) {
        Path dir = storageService.kbDir(kbId);
        if (!Files.exists(dir)) {
            return List.of();
        }
        String needle = query == null ? "" : query.trim();
        if (needle.isEmpty()) {
            return List.of();
        }
        final String lower = needle.toLowerCase();
        List<SearchResult> hits = new ArrayList<>();
        List<Path> files;
        try (var walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
        } catch (IOException e) {
            throw ApiException.badRequest("遍历知识库目录失败: " + e.getMessage());
        }
        for (Path file : files) {
            if (hits.size() >= topK) {
                break;
            }
            try {
                // 单文件大小上限：readAllLines 会把整文件读进内存，知识库目录里一个
                // 数百 MB 的文件（误传的库文件/日志）足以在检索请求线程里 OOM。
                long size;
                try {
                    size = Files.size(file);
                } catch (IOException e) {
                    continue;
                }
                if (size > MAX_SEARCH_FILE_BYTES) {
                    log.debug("知识库检索跳过超大文件 {} ({} MB > {} MB)",
                            file, size / 1024 / 1024, MAX_SEARCH_FILE_BYTES / 1024 / 1024);
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (lines.get(i).toLowerCase().contains(lower)) {
                        List<String> ctx = new ArrayList<>();
                        for (int j = Math.max(0, i - CONTEXT_LINES);
                             j <= Math.min(lines.size() - 1, i + CONTEXT_LINES); j++) {
                            if (j != i) {
                                ctx.add(lines.get(j));
                            }
                        }
                        String rel = dir.relativize(file).toString();
                        hits.add(SearchResult.text(rel, i + 1, lines.get(i), ctx));
                    }
                    if (hits.size() >= topK) {
                        break;
                    }
                }
            } catch (IOException | UnsupportedOperationException e) {
                // 跳过无法读取的文件（二进制 / 编码异常）
                log.debug("跳过不可读文件 {}: {}", file, e.getMessage());
            }
        }
        return hits;
    }
}
