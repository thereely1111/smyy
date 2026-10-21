package com.tipdm.agentplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 知识库存储配置：文件系统根目录从配置读取（默认复用 workspace-root 下的 knowledge_bases），
 * DB 仅存相对路径，便于系统数据迁移。
 */
@Component
public class KnowledgeBaseProperties {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseProperties.class);

    private final Path root;

    public KnowledgeBaseProperties(
            @Value("${agent-platform.knowledge-base.root:"
                    + "${agent-platform.workspace-root}/knowledge_bases}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            log.info("Knowledge base root directory: {}", this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建知识库根目录: " + this.root, e);
        }
    }

    public Path root() {
        return root;
    }
}
