package com.tipdm.agentplatform.service;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.core.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 平台版 {@code execute} 工具（替代框架 {@code ShellExecuteTool}）。框架版把默认
 * 超时写死为 <b>30 秒</b>（{@code timeout == null ? 30 : timeout}），模型漏传 timeout
 * 时任何超过 30 秒的命令（依赖安装、构建、数据处理）都会被 {@code destroyForcibly()}
 * 杀掉；平台版默认取 {@link #SHELL_EXECUTE_TIMEOUT_SECONDS}（600 秒），与平台工具执行
 * 兜底（30 分钟）同量级，模型显式传入的 timeout 仍然优先。输出格式与框架版保持一致
 * （Exit code + 输出 + truncated 标记），不改变任何下游解析。
 *
 * <p>装配：{@code AgentAssemblyService} 在文件系统后端构造后注册本工具，并调用
 * {@code builder.disableShellTool()} 阻止框架再注册同名工具（同名注册/覆盖在任何
 * 时机都不可靠，直接禁用框架版）。</p>
 */
public final class PlatformShellExecuteTool {

    private static final Logger log = LoggerFactory.getLogger(PlatformShellExecuteTool.class);

    /**
     * execute 默认超时（秒）：与工具执行兜底同量级，更长任务由模型显式声明 timeout。
     * 运行态文件系统（{@code RuntimeLocalFilesystemSpec}）构造 shell 后端时复用同一值。
     */
    public static final int SHELL_EXECUTE_TIMEOUT_SECONDS = 600;

    /** execute 输出捕获上限（字节）：超限截断并附 truncated 标记，防单条命令撑爆上下文。 */
    static final int SHELL_EXECUTE_MAX_OUTPUT_BYTES = 100_000;

    private final AbstractSandboxFilesystem sandbox;

    /**
     * 按会话现造 shell 后端的规格；为 null 时退化为共用后端（只带宿主 OS 基础环境，无 SMYY_*）。
     * @see RuntimeLocalFilesystemSpec#shellFor(Path)
     */
    private final RuntimeLocalFilesystemSpec perSessionSpec;

    /** 本工具所属智能体的运行态根；与 {@link #perSessionSpec} 成对给出。 */
    private final Path runtimeRoot;

    PlatformShellExecuteTool(AbstractSandboxFilesystem sandbox) {
        this(sandbox, null, null);
    }

    PlatformShellExecuteTool(AbstractSandboxFilesystem sandbox,
                             RuntimeLocalFilesystemSpec perSessionSpec,
                             Path runtimeRoot) {
        this.sandbox = sandbox;
        this.perSessionSpec = perSessionSpec;
        this.runtimeRoot = runtimeRoot == null ? null : runtimeRoot.toAbsolutePath().normalize();
    }

    /**
     * @param runtimeContext 框架注入的每轮运行上下文（非模型参数），可能为 null
     */
    @Tool(
            name = "execute",
            description =
                    "Execute a shell command. Use for git, npm, build, test, and other terminal"
                        + " operations. Returns combined output and exit code. If a dedicated tool"
                        + " exists (e.g., read_file, write_file), you MUST use it instead of shell"
                        + " commands. For long-running commands pass an explicit timeout (seconds);"
                        + " the default is 600s.")
    public String execute(
            RuntimeContext runtimeContext,
            @ToolParam(name = "command", description = "Shell command to execute") String command,
            @ToolParam(
                            name = "working_directory",
                            description =
                                    "Working directory (relative to workspace root, optional)",
                            required = false)
                    String workingDirectory,
            @ToolParam(
                            name = "timeout",
                            description =
                                    "Timeout in seconds (default: 600, max: 3600)",
                            required = false)
                    Integer timeout) {
        if (command == null || command.isBlank()) {
            return "Error: command must be a non-empty string.";
        }
        String effectiveCommand = command;
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String wd = workingDirectory.strip();
            if (wd.startsWith("/") || wd.startsWith("~") || wd.contains("..")) {
                return "Error: working_directory must be a relative path within the workspace"
                        + " (absolute paths, '~', and '..' are not allowed).";
            }
            effectiveCommand = commandWithWorkingDirectory(
                    wd, command, System.getProperty("os.name").toLowerCase().contains("win"));
        }

        int timeoutSeconds = timeout != null && timeout > 0 ? timeout : SHELL_EXECUTE_TIMEOUT_SECONDS;
        ExecuteResponse result = backendFor(runtimeContext)
                .execute(runtimeContext, effectiveCommand, timeoutSeconds);

        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(result.exitCode()).append("\n");
        if (result.output() != null && !result.output().isBlank()) {
            sb.append("\n").append(result.output());
        }
        if (result.truncated()) {
            sb.append("\n(output was truncated)");
        }
        return sb.toString();
    }

    /**
     * 选本次 {@code execute} 用的 shell 后端。
     *
     * <p>能拿到本会话的 {@code sessionId} 时用<b>会话专属后端</b>：cwd ＝
     * {@code runtimes/{agentId}/{sessionId}}，环境带 {@code SMYY_SESSION_DIR} /
     * {@code SMYY_SKILLS_DIR} / {@code SMYY_PYTHON} 与会话 {@code .env.local}——
     * 这正是 {@code AGENTS.md} 与 {@code mm-0-路径与会话隔离} 对模型承诺的语义。
     * 拿不到（{@code runtimeContext} 为空、sessionId 非法、或装配时未挂规格）时退回共用后端，
     * 行为与改造前一致（只是基础环境换成了宿主 OS 对应的那份）。</p>
     */
    private AbstractSandboxFilesystem backendFor(RuntimeContext runtimeContext) {
        Path sessionDir = resolveSessionDir(runtimeContext);
        if (sessionDir == null) {
            return sandbox;
        }
        try {
            Files.createDirectories(sessionDir);
        } catch (IOException e) {
            log.warn("Failed to prepare session dir as shell cwd {}: {}", sessionDir, e.getMessage());
            return sandbox;
        }
        return perSessionSpec.shellFor(sessionDir);
    }

    /**
     * {@code runtimes/{agentId}/{sessionId}}；拿不到或名字不像会话目录时返回 null。
     * 只做保守字符校验（不含分隔符与 {@code ..}），不做白名单匹配——会话名由平台生成
     * （{@code main-<uuid>} / {@code sub-<uuid>}），此处只需挡住穿越。
     */
    private Path resolveSessionDir(RuntimeContext runtimeContext) {
        if (perSessionSpec == null || runtimeRoot == null || runtimeContext == null) {
            return null;
        }
        String sessionId = runtimeContext.getSessionId();
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String id = sessionId.strip();
        if (id.equals(".") || id.equals("..")
                || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0
                || id.indexOf(':') >= 0 || id.contains("..")) {
            return null;
        }
        return runtimeRoot.resolve(id);
    }

    static String commandWithWorkingDirectory(
            String workingDirectory, String command, boolean windows) {
        if (windows) {
            return "cd /d \"" + workingDirectory.replace("\"", "\"\"") + "\" && " + command;
        }
        return "cd '" + workingDirectory.replace("'", "'\\''") + "' && " + command;
    }
}
