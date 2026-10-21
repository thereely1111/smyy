package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 子进程环境构造（{@code RuntimeLocalFilesystemSpec.shellEnv}）回归。
 *
 * <p>这是「shell 覆盖」的唯一回归护栏，其价值全在一个不变量上：<b>未配置
 * {@code agent-platform.shell.path} 时返回与改造前逐字一致的单键表</b>——后端以 jar
 * 部署到 Linux 容器，只要这条成立，引入可覆盖 PATH 就是在生产路径上零风险；
 * 反过来，一旦有人"顺手"把默认值改成开发机路径或往默认分支里塞白名单变量，
 * 生产子进程环境就被污染了，必须在这里被拦住。</p>
 *
 * <p>另一半是安全边界：覆盖后只能多出「PATH + 白名单」这些键，绝不能把整份宿主环境
 * （API key、凭证）透传进模型可控的子进程。</p>
 */
class RuntimeLocalFilesystemSpecTest {

    private static final String CONFIGURED_PATH =
            "D:\\software\\python;D:\\software\\Git\\bin;D:\\software\\Git\\usr\\bin;C:\\Windows\\System32";

    @Test
    void blankPath_keepsProductionDefaultSingleKeyMap() {
        Map<String, String> expected = Map.of("PATH", RuntimeLocalFilesystemSpec.DEFAULT_SHELL_PATH);

        // 三种"未配置"形态（@ConfigurationProperties 绑定的空串、显式空串、纯空白）都必须等价，
        // 且返回表里只有 PATH 一个键——多一个键就改变了生产子进程环境。
        assertThat(RuntimeLocalFilesystemSpec.shellEnv("")).isEqualTo(expected);
        assertThat(RuntimeLocalFilesystemSpec.shellEnv(null)).isEqualTo(expected);
        assertThat(RuntimeLocalFilesystemSpec.shellEnv("   ")).isEqualTo(expected);
    }

    @Test
    void defaultPath_isProductionPosixSemantics() {
        // 默认值即生产（Linux 容器）语义：含 /usr/bin 与 /bin，且不含 Windows 专有路径
        assertThat(RuntimeLocalFilesystemSpec.DEFAULT_SHELL_PATH)
                .contains("/usr/bin")
                .contains("/bin")
                .doesNotContain("\\")
                .doesNotContain(";");
    }

    @Test
    void configuredPath_replacesPathVerbatim() {
        Map<String, String> env = RuntimeLocalFilesystemSpec.shellEnv(CONFIGURED_PATH);

        // 原样覆盖，不做任何拼装：PATH 的顺序与分隔符由使用者负责（Windows 上必须分号）
        assertThat(env).containsEntry("PATH", CONFIGURED_PATH);
    }

    @Test
    void configuredPath_onlyAddsWhitelistedEnvVars() {
        Map<String, String> env = RuntimeLocalFilesystemSpec.shellEnv(CONFIGURED_PATH);
        Set<String> allowed = allowedKeys();

        // 键集合必须被「PATH + 白名单」完全包住：宿主环境里的其余变量（含任何凭据）一律不进子进程
        assertThat(env.keySet()).allMatch(allowed::contains);

        // 白名单里有代表性但**未**列入的宿主变量：存在也不许透传
        assertThat(env.keySet()).doesNotContain("JAVA_HOME", "AGENT_PLATFORM_JWT_SECRET");
    }

    @Test
    void configuredPath_passesOnlyVarsThatActuallyExist() {
        Map<String, String> env = RuntimeLocalFilesystemSpec.shellEnv(CONFIGURED_PATH);

        // 只取宿主确实存在（且非空白）的白名单变量，值必须与宿主逐字一致——
        // 否则就是凭空捏造环境（会让子进程按错误的家目录 / 临时目录运行）
        env.forEach((key, value) -> {
            if ("PATH".equals(key)) {
                return;
            }
            assertThat(value).isEqualTo(System.getenv(key)).isNotBlank();
        });

        assertThat(env.size())
                .isLessThanOrEqualTo(RuntimeLocalFilesystemSpec.SHELL_ENV_PASSTHROUGH.size() + 1);
    }

    private static Set<String> allowedKeys() {
        Set<String> allowed = new HashSet<>(RuntimeLocalFilesystemSpec.SHELL_ENV_PASSTHROUGH);
        allowed.add("PATH");
        return allowed;
    }
}
