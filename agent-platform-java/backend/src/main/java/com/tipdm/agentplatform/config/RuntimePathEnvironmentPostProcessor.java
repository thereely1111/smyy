package com.tipdm.agentplatform.config;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.CommandLinePropertySource;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

/**
 * 在配置解析后、Bean 装配前，把「运行态根 / 落盘日志」解析成绝对路径写回 Environment。
 *
 * <p>目的：{@code agent-platform.workspace-root} 的默认值不再依赖「启动目录恰好是仓库根」
 * 这一巧合，而是由 {@link WorkspaceRootResolver} 从启动目录自推导（设备无关、无绝对路径）。
 * 由于全平台有 6 处 {@code @Value("${agent-platform.workspace-root}")}
 * （{@code SessionWorkspaceManager} / {@code FileSystemAgentLoader} / {@code AgentAssemblyService}
 * / {@code OrphanWorkspaceGc} / {@code WorkspaceGenerator} / {@code KnowledgeBaseProperties}），
 * 在这里以<b>最高优先级</b>注入一个属性源即可一处生效、无需改动任何消费方。</p>
 *
 * <p><b>优先级</b>：显式声明永远赢——命令行 {@code --agent-platform.workspace-root=…}、
 * JVM {@code -Dagent-platform.workspace-root=…}、环境变量
 * {@code AGENT_PLATFORM_WORKSPACE_ROOT} 三者任一存在时，本类不改写。
 * 落盘日志同理（{@code logging.file.name} / {@code logging.file.path} /
 * {@code AGENT_PLATFORM_LOG_FILE}）。</p>
 *
 * <p>自检：{@code curl localhost:8080/actuator/info} 会直接回显解析结果与来源
 * （{@code info.app.workspace-root} / {@code info.app.workspace-root-source} /
 * {@code info.app.repo-root}）。</p>
 */
public class RuntimePathEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 运行态根属性名（application.yml 中的键）。 */
    static final String WORKSPACE_ROOT_PROPERTY = "agent-platform.workspace-root";

    /** 运行态根环境变量名（显式值时优先于自推导）。 */
    static final String WORKSPACE_ROOT_ENV = "AGENT_PLATFORM_WORKSPACE_ROOT";

    /** 落盘日志属性名。 */
    static final String LOG_FILE_PROPERTY = "logging.file.name";

    /** 落盘日志目录属性名（与 {@link #LOG_FILE_PROPERTY} 任一显式设置即不覆盖）。 */
    static final String LOG_PATH_PROPERTY = "logging.file.path";

    /** 落盘日志环境变量名。 */
    static final String LOG_FILE_ENV = "AGENT_PLATFORM_LOG_FILE";

    /** 注入的属性源名（便于日志/排障时识别）。 */
    static final String PROPERTY_SOURCE_NAME = "runtimePaths";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path startDir = WorkspaceRootResolver.currentStartDir();
        Optional<Path> repoRoot = WorkspaceRootResolver.detectRepoRoot(startDir);

        Map<String, Object> resolved = new LinkedHashMap<>();
        String workspaceRootSource;
        String effectiveWorkspaceRoot;
        if (explicitlyConfigured(environment, WORKSPACE_ROOT_PROPERTY, WORKSPACE_ROOT_ENV)) {
            // 显式声明优先，不自作主张（取显式值供 /actuator/info 回显）
            workspaceRootSource = "explicit";
            effectiveWorkspaceRoot = environment.getProperty(WORKSPACE_ROOT_PROPERTY,
                    WorkspaceRootResolver.resolveWorkspaceRoot(startDir).toString());
        } else {
            effectiveWorkspaceRoot = WorkspaceRootResolver.resolveWorkspaceRoot(startDir).toString();
            resolved.put(WORKSPACE_ROOT_PROPERTY, effectiveWorkspaceRoot);
            workspaceRootSource = repoRoot.isPresent() ? "detected" : "fallback";
        }

        if (!explicitlyConfigured(environment, LOG_FILE_PROPERTY, LOG_FILE_ENV)
                && !explicitlyConfigured(environment, LOG_PATH_PROPERTY, null)) {
            if (repoRoot.isPresent()) {
                resolved.put(LOG_FILE_PROPERTY,
                        repoRoot.get().resolve("logs").resolve("backend.log").toString());
            } else {
                // 非仓库根形态：退化为启动目录（不猜、不写死）
                resolved.put(LOG_FILE_PROPERTY,
                        startDir.resolve("logs").resolve("backend.log").toString());
            }
        }

        // 取证用：把解析结果与来源一并挂到 /actuator/info
        resolved.put("info.app.workspace-root", effectiveWorkspaceRoot);
        resolved.put("info.app.workspace-root-source", workspaceRootSource);
        resolved.put("info.app.repo-root", repoRoot.map(Path::toString).orElse(""));
        resolved.put("info.app.start-dir", startDir.toString());

        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
    }

    /**
     * 判断某个键是否被<b>显式</b>声明（命令行 / JVM 系统属性 / 环境变量）。
     *
     * <p>刻意不查 {@code environment.getProperty(key)}：application.yml 里的
     * {@code ${…}} 占位符已经把它解析成「非空值」，查它会把 yml 默认值误判为显式声明，
     * 导致自推导永不生效。</p>
     *
     * @param environment 当前环境
     * @param propertyKey 属性键
     * @param envName     对应的环境变量名；无则传 {@code null}
     */
    private static boolean explicitlyConfigured(ConfigurableEnvironment environment,
                                                String propertyKey,
                                                String envName) {
        if (envName != null && isPresent(System.getenv(envName))) {
            return true;
        }
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof CommandLinePropertySource<?> && source.containsProperty(propertyKey)) {
                return true;
            }
        }
        // JVM -Dkey=value（systemProperties 里只有显式 -D 才出现该键）
        return isPresent(System.getProperty(propertyKey));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public int getOrder() {
        // 必须在 ConfigDataEnvironmentPostProcessor（application.yml 载入）之后运行，
        // 才能以最高优先级覆盖 ${user.dir} 解析出的默认值
        return Ordered.LOWEST_PRECEDENCE;
    }
}
