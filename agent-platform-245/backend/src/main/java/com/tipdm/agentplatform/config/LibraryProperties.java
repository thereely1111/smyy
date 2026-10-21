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
 * 资料库存储配置：文件系统根目录从配置读取（默认复用workspace-root 下的 library），
 * DB 仅存相对路径（{@code LibraryNode.contentRel}），便于系统数据迁移。
 *
 * <p>与 {@link KnowledgeBaseProperties} 同构但独立成配置项——两者目录不同、
 * 生命周期不同（删知识库不牵连资料库），不共用一个 root。</p>
 */
@Component
public class LibraryProperties {

    private static final Logger log = LoggerFactory.getLogger(LibraryProperties.class);

    private final Path root;

    public LibraryProperties(
            @Value("${agent-platform.library.root:"
                    + "${agent-platform.workspace-root}/library}") String root) {
        this.root = Paths.get(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            log.info("Library root directory: {}", this.root);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建资料库根目录: " + this.root, e);
        }
    }

    public Path root() {
        return root;
    }
}
