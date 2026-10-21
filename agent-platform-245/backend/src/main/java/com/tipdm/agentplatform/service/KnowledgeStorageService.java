package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.KnowledgeBaseProperties;
import com.tipdm.agentplatform.web.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.UUID;

/**
 * 知识库文件系统存储（rag / text 共用）：
 * - 根目录取自 {@link com.tipdm.agentplatform.config.KnowledgeBaseProperties}；
 * - 文档绝对路径 = root/{kbId}/{relativePath}，所有解析均做 startsWith(root) 防穿越；
 * - DB 仅存 relative_path（{docId}/{filename}），迁移时换机器只需移动文件 + 改配置。
 */
@Service
public class KnowledgeStorageService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeStorageService.class);

    private final Path root;

    public KnowledgeStorageService(KnowledgeBaseProperties properties) {
        this.root = properties.root();
    }

    /** KB 绝对目录 root/{kbId}，带防穿越守卫。 */
    public Path kbDir(UUID kbId) {
        Path dir = root.resolve(kbId.toString()).normalize();
        if (!dir.startsWith(root)) {
            throw ApiException.badRequest("非法知识库路径");
        }
        return dir;
    }

    /** 文档绝对路径 root/{kbId}/{relativePath}，带防穿越守卫。 */
    public Path resolve(UUID kbId, String relativePath) {
        Path p = kbDir(kbId).resolve(relativePath).normalize();
        if (!p.startsWith(root)) {
            throw ApiException.badRequest("非法文档路径: " + relativePath);
        }
        return p;
    }

    /** 落盘文档，返回相对路径 {docId}/{filename}。 */
    public String store(UUID kbId, UUID docId, String filename, InputStream content) {
        Path dir = kbDir(kbId).resolve(docId.toString());
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(sanitize(filename)).normalize();
            if (!target.startsWith(dir)) {
                throw ApiException.badRequest("非法文件名: " + filename);
            }
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return docId + "/" + target.getFileName().toString();
        } catch (IOException e) {
            throw ApiException.badRequest("文档存储失败: " + e.getMessage());
        }
    }

    /** 读取已落盘文档的全部字节（摄入/解析用），带防穿越守卫。 */
    public byte[] readAllBytes(UUID kbId, String relativePath) {
        Path p = resolve(kbId, relativePath);
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            throw ApiException.badRequest("读取文档文件失败: " + e.getMessage());
        }
    }

    public void deleteDocument(UUID kbId, String relativePath) {
        Path p = resolve(kbId, relativePath);
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("删除文档文件失败 {}: {}", p, e.getMessage());
        }
    }

    public void deleteKnowledgeBase(UUID kbId) {
        Path dir = kbDir(kbId);
        try {
            if (Files.exists(dir)) {
                try (var walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
                }
            }
        } catch (IOException e) {
            log.warn("删除知识库目录失败 {}: {}", dir, e.getMessage());
        }
    }

    /** 去掉文件名中的路径分隔符，避免目录穿越。 */
    private String sanitize(String filename) {
        if (filename == null) {
            return "file";
        }
        String n = filename.replaceAll("[/\\\\]", "_").trim();
        return n.isBlank() ? "file" : n;
    }
}
