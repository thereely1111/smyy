package com.tipdm.agentplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 案例内容根目录：{@code smyy_outsourced_cases} 表只存相对路径，正文文件都相对它解析。
 *
 * <p>解析顺序（不含绝对路径字面量）：
 * <ol>
 *   <li>显式配置 {@code smyy.case-content-root}（环境变量 {@code SMYY_CASE_CONTENT_ROOT} 可覆盖）；</li>
 *   <li>自推导：从启动目录逐级向上找**前端工程根**（含 {@code vite.config.ts}，或已存在 {@code public/cases}），
 *       取 {@code <前端根>/public/cases}；</li>
 *   <li>兜底：{@code <启动目录>/public/cases}。</li>
 * </ol>
 *
 * <p>刻意不用 {@link WorkspaceRootResolver#detectRepoRoot}：它认的是「含 {@code backend/pom.xml}
 * 与 {@code workspace/agents}」的目录，在本仓里命中的是 {@code agent-platform-java}（后端子仓），
 * 而不是放着前端 {@code public/} 的 smyy 工程根，会指到不存在的目录。
 */
@Component
public class SmyyCaseContentProperties {

    private static final Logger log = LoggerFactory.getLogger(SmyyCaseContentProperties.class);

    /** 案例目录相对前端工程根的位置。 */
    static final String CASES_RELATIVE = "public/cases";

    /** 前端工程根标记：Vite 配置存在即认定。 */
    private static final String FRONTEND_MARKER = "vite.config.ts";

    /** 向上探测层数上限。 */
    private static final int MAX_UPWARD_LEVELS = 8;

    private final Path root;

    public SmyyCaseContentProperties(@Value("${smyy.case-content-root:}") String configured) {
        this.root = resolve(configured);
        log.info("smyy case content root = {} (source={})",
                root, (configured == null || configured.isBlank()) ? "auto" : "configured");
    }

    public Path root() {
        return root;
    }

    static Path resolve(String configured) {
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        Path start = WorkspaceRootResolver.currentStartDir();
        return detectFrontendRoot(start)
                .orElse(start)
                .resolve(CASES_RELATIVE)
                .toAbsolutePath()
                .normalize();
    }

    /** 从启动目录向上找前端工程根（含 {@code vite.config.ts} 或已有 {@code public/cases}）。 */
    private static Optional<Path> detectFrontendRoot(Path startDir) {
        Path cursor = startDir.toAbsolutePath().normalize();
        for (int level = 0; level <= MAX_UPWARD_LEVELS && cursor != null; level++) {
            if (Files.isRegularFile(cursor.resolve(FRONTEND_MARKER))
                    || Files.isDirectory(cursor.resolve(CASES_RELATIVE))) {
                return Optional.of(cursor);
            }
            Path parent = cursor.getParent();
            if (parent == null || parent.equals(cursor)) {
                break;
            }
            cursor = parent;
        }
        return Optional.empty();
    }
}
