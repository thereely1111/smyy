package com.tipdm.agentplatform.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RuntimePathEnvironmentPostProcessor} 的单元用例：只驱动 Environment，
 * 不启动完整上下文、不连库、不起端口。
 */
class RuntimePathEnvironmentPostProcessorTest {

    private String originalUserDir;

    @BeforeEach
    void rememberUserDir() {
        originalUserDir = System.getProperty("user.dir");
    }

    @AfterEach
    void restoreUserDir() {
        if (originalUserDir != null) {
            System.setProperty("user.dir", originalUserDir);
        }
        System.clearProperty("agent-platform.workspace-root");
        System.clearProperty("logging.file.name");
        System.clearProperty("logging.file.path");
    }

    /** 造一个「仓库根」：backend/pom.xml + workspace/agents。 */
    private static Path makeRepoRoot(Path base) throws IOException {
        Files.createDirectories(base.resolve("backend"));
        Files.createFile(base.resolve("backend").resolve("pom.xml"));
        Files.createDirectories(base.resolve("workspace").resolve("agents"));
        return base;
    }

    @Test
    @DisplayName("无显式声明：自推导注入 workspace-root 与 logging.file.name")
    void injectsDetectedPaths(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));
        System.setProperty("user.dir", repo.resolve("backend").toString());
        StandardEnvironment environment = new StandardEnvironment();

        new RuntimePathEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertEquals(repo.resolve("workspace").toString(),
                environment.getProperty("agent-platform.workspace-root"));
        assertEquals(repo.resolve("logs").resolve("backend.log").toString(),
                environment.getProperty("logging.file.name"));
        assertEquals("detected", environment.getProperty("info.app.workspace-root-source"));
        assertEquals(repo.toString(), environment.getProperty("info.app.repo-root"));
    }

    @Test
    @DisplayName("显式声明优先：system property 存在时不改写，来源标 explicit")
    void explicitWins(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));
        System.setProperty("user.dir", repo.resolve("backend").toString());
        System.setProperty("agent-platform.workspace-root", "X:/explicit/root");
        StandardEnvironment environment = new StandardEnvironment();

        new RuntimePathEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        // 注入源里不应出现被改写的 workspace-root
        MapPropertySource injected = (MapPropertySource) environment.getPropertySources()
                .get(RuntimePathEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertNotNull(injected);
        assertFalse(injected.containsProperty("agent-platform.workspace-root"),
                "显式声明不应被自推导覆盖");
        assertEquals("X:/explicit/root", environment.getProperty("agent-platform.workspace-root"));
        assertEquals("explicit", environment.getProperty("info.app.workspace-root-source"));
    }

    @Test
    @DisplayName("无仓库根：回落 <启动目录>/workspace，来源标 fallback")
    void fallbackWhenNoRepoRoot(@TempDir Path tmp) {
        Path standalone = tmp.resolve("opt").resolve("app");
        standalone.toFile().mkdirs();
        System.setProperty("user.dir", standalone.toString());
        StandardEnvironment environment = new StandardEnvironment();

        new RuntimePathEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertEquals(standalone.resolve("workspace").toString(),
                environment.getProperty("agent-platform.workspace-root"));
        assertEquals("fallback", environment.getProperty("info.app.workspace-root-source"));
    }

    @Test
    @DisplayName("属性源以最高优先级注入（addFirst），压过 application.yml 的 ${user.dir} 默认值")
    void propertySourceHasHighestPrecedence(@TempDir Path tmp) throws IOException {
        Path repo = makeRepoRoot(tmp.resolve("repo"));
        System.setProperty("user.dir", repo.resolve("backend").toString());
        StandardEnvironment environment = new StandardEnvironment();
        // 模拟 application.yml 已解析出的旧默认值（优先级低于我们的注入）
        environment.getPropertySources().addLast(new MapPropertySource("applicationConfig: [classpath:/application.yml]",
                java.util.Map.of("agent-platform.workspace-root",
                        repo.resolve("backend").resolve("workspace").toString())));

        new RuntimePathEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        MapPropertySource injected = (MapPropertySource) environment.getPropertySources()
                .get(RuntimePathEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertNotNull(injected);
        assertTrue(environment.getPropertySources().iterator().next().getName()
                .equals(RuntimePathEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));
        assertEquals(repo.resolve("workspace").toString(),
                environment.getProperty("agent-platform.workspace-root"));
    }
}
