package com.tipdm.agentplatform.service;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行态文件系统：{@code runtimes/{agentId}} 为可写根、SESSION 命名空间，智能体目录只读下层。
 *
 * <p><b>为什么由平台直接构造 {@link AbstractFilesystem} 并以
 * {@code builder.abstractFilesystem(...)} 交给 harness</b>（而不是交
 * {@code LocalFilesystemSpec} 让 harness 自行推导）：官方 harness 只在子智能体
 * {@code WorkspaceMode.SHARED} 且父级存在<b>后端</b>（sandbox / abstractFilesystem）时
 * 才把后端传给子智能体，ISOLATED 分支才会克隆父级 {@code LocalFilesystemSpec}
 * （且只克隆 project / mode / additionalRoots，丢失 SESSION 命名空间与只读定义层）。
 * 本平台的子智能体是 SHARED（与父共享同一根、各自会话命名空间），因此必须由平台
 * 构造好后端交给 harness —— 这正是框架为本地文件系统提供的官方入口。</p>
 */
final class RuntimeLocalFilesystemSpec extends LocalFilesystemSpec {

    /**
     * shell 命令的 PATH <b>默认值</b>：真实 shell 需要它才能解析 {@code python3} / {@code ls} 等命令名。
     * 覆盖 macOS（含 /opt/homebrew）与 Linux 常见位置。
     *
     * <p><b>这是生产语义</b>：后端以 jar 部署到 Linux 容器，本默认值即生产行为。
     * Windows 开发机不满足该语义时，应由 {@code agent-platform.shell.path} 覆盖，
     * <b>而不是修改本默认值</b>（避免 Windows 专有路径污染生产；见
     * {@link com.tipdm.agentplatform.config.ShellProperties}）。</p>
     *
     * <p>包级可见以便单测按符号引用（不是重复字面量），值本身即生产语义。</p>
     */
    static final String DEFAULT_SHELL_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin";

    /**
     * 覆盖 PATH 时一并透传的宿主环境变量白名单。
     *
     * <p>Windows 子进程缺这些变量会导致 {@code cmd.exe} / 原生程序与临时目录解析异常
     * ——这正是开发机上 {@code execute} 恒返回 {@code Exit code: 255 / <no output>}
     * 的并发原因之一（另一原因是框架固定 {@code cmd.exe /c} 与 POSIX 命令不兼容）。</p>
     *
     * <p>仍不透传整份宿主环境：保持原实现"避免 API key 等敏感变量泄漏进子进程"的意图。</p>
     *
     * <p>包级可见以便单测断言「返回的环境只含 PATH + 本白名单」。</p>
     */
    static final List<String> SHELL_ENV_PASSTHROUGH = List.of(
            "SystemRoot", "SystemDrive", "windir", "ComSpec", "PATHEXT",
            "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA",
            "HOMEDRIVE", "HOMEPATH", "HOME", "NUMBER_OF_PROCESSORS",
            "PROCESSOR_ARCHITECTURE", "OS", "LANG");

    /** 智能体目录只读层单文件读取上限（MB）：AGENTS.md / skills 均为小文本，10MB 足够且防异常大文件。 */
    private static final int DEFINITION_READ_MAX_FILE_SIZE_MB = 10;

    private final Path runtimeRoot;
    private final Path definitionDir;

    /** 配置覆盖的 PATH（{@code agent-platform.shell.path}）；空 = 用 {@link #DEFAULT_SHELL_PATH}。 */
    private final String configuredShellPath;

    RuntimeLocalFilesystemSpec(Path runtimeRoot, Path definitionDir, String configuredShellPath) {
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.definitionDir = definitionDir.toAbsolutePath().normalize();
        this.configuredShellPath = configuredShellPath == null ? "" : configuredShellPath;
        project(this.runtimeRoot);
        additionalRoots(List.of(this.definitionDir));
        isolationScope(IsolationScope.SESSION);
    }

    /**
     * 构造子进程环境。
     *
     * <p>壳层环境必须<b>非空</b>才会被框架清空后重建
     * （{@code LocalFilesystemWithShell} 只在 env 非空时 clear+putAll）；空表会让子进程
     * 直接继承后端进程的全部环境变量（含 API key 之类），那是必须避免的。</p>
     *
     * <p>未配置 {@code shell.path}（null / 空白 / 空串）时返回与改造前<b>逐字一致</b>的单键表
     * （生产零回归）；配置后返回「PATH + 白名单变量」，且只取宿主确实存在的项。</p>
     *
     * @param configuredShellPath {@code agent-platform.shell.path} 的绑定量；空 = 用生产默认
     */
    static Map<String, String> shellEnv(String configuredShellPath) {
        if (configuredShellPath == null || configuredShellPath.isBlank()) {
            return Map.of("PATH", DEFAULT_SHELL_PATH);
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", configuredShellPath);
        for (String key : SHELL_ENV_PASSTHROUGH) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) {
                env.put(key, value);
            }
        }
        return env;
    }

    @Override
    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory nsFactory) {
        PathPolicy runtimePolicy = PathPolicy.of(List.of(runtimeRoot));
        // 子进程环境：默认仅 PATH（与改造前逐字一致）；配置 agent-platform.shell.path 后
        // 为「PATH + 白名单系统变量」。构造与理由见 shellEnv(String)。
        Map<String, String> env = shellEnv(configuredShellPath);
        LocalFilesystemWithShell upper = new LocalFilesystemWithShell(
                runtimeRoot,
                LocalFsMode.ROOTED,
                runtimePolicy,
                PlatformShellExecuteTool.SHELL_EXECUTE_TIMEOUT_SECONDS, // execute 超时（秒）
                PlatformShellExecuteTool.SHELL_EXECUTE_MAX_OUTPUT_BYTES, // 输出上限（字节）
                env,            // 仅 PATH，不透传宿主机环境
                false,          // inheritEnv=false
                nsFactory,      // SESSION 命名空间
                runtimeRoot);   // shell cwd：execute 的真实工作目录
        // 智能体目录只读下层：PathPolicy 必须把 runtimeRoot 一并列入允许根——框架
        // OverlayFilesystem 合并 ls/read 时对上下层同时求值，下层 LocalFilesystem 对
        // 「不在自己 allowed roots 内的绝对路径」抛 SecurityException 而非返回
        // not-found，会把跨层读取（如父编排读子会话产物）变成文件工具失败。把
        // runtimeRoot 列入 lower 允许根后：跨层读取由上层命中、下层求值静默返回
        // 不存在；智能体目录仍受 ROOTED + 该 policy 约束（只允许 definitionDir 与
        // runtimeRoot 两个根，其余宿主路径依旧拒绝）。
        PathPolicy definitionPolicy = PathPolicy.of(List.of(definitionDir, runtimeRoot));
        LocalFilesystem lower =
                new LocalFilesystem(
                        definitionDir,
                        LocalFsMode.ROOTED,
                        definitionPolicy,
                        DEFINITION_READ_MAX_FILE_SIZE_MB,
                        null);
        return OverlayFilesystem.of(upper, lower);
    }
}
