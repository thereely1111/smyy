package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 定义文件卫生检查：智能体定义里**不得**再出现路径占位符与已废弃的汇报块。
 *
 * <p>背景（2026-09-11）：平台曾用 {@code ${WORKSPACE_ROOT}} / {@code ${SESSION_ID}} 占位符
 * 拼接产物路径，并由 {@code PathExpansionMiddleware} 展开、由 {@code 【SESSION_ID=…】} 标记承载
 * 父子会话血缘。回归框架官方版后这三样全部删除：跨会话路径改用「运行态根 + 产出方会话目录」，
 * 血缘改用框架 spawn registry，汇报统一到 {@code 【TASK_RESULT】artifacts}。</p>
 *
 * <p>只要定义文件里再出现旧写法，模型就会照着写——而平台已不再展开它们，结果是一个谁都找不到的
 * 字面量目录。本测试把这条约束固化下来（样例定义区缺失时跳过，避免依赖仓库布局的 CI 误报）。</p>
 */
class AgentDefinitionHygieneTest {

    /** 禁用写法（出现在定义文件里即失败）。 */
    private static final List<String> FORBIDDEN = List.of(
            "${WORKSPACE_ROOT}",
            "${SESSION_ID}",
            "【SESSION_ID=",
            "【WROTE】");

    /**
     * 显式豁免标记：定义里**禁止**使用这些写法的那一两句说明本身会包含字面量，
     * 该行必须带上本标记才能通过（这样"新增一处旧写法"仍然会被拦下）。
     */
    private static final String ALLOW_MARK = "hygiene-allow";

    private static Path definitionsRoot() {
        Path root = Path.of(System.getProperty("user.dir")).resolve("../workspace/agents").normalize();
        return Files.isDirectory(root) ? root : null;
    }

    @Test
    void agentDefinitionsHaveNoLegacyPathPlaceholders() throws IOException {
        Path root = definitionsRoot();
        assumeTrue(root != null, "样例定义区不存在，跳过");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".md") || p.toString().endsWith(".yaml"))
                    .toList()) {
                for (String line : Files.readAllLines(file)) {
                    if (line.contains(ALLOW_MARK)) {
                        continue;
                    }
                    for (String forbidden : FORBIDDEN) {
                        if (line.contains(forbidden)) {
                            offenders.add(root.relativize(file) + " → " + forbidden
                                    + " ｜ " + line.strip());
                        }
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "定义文件里出现已废弃的占位符/汇报块（平台不再展开它们）：\n" + String.join("\n", offenders));
    }
}
