package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.ShellProperties;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.harness.agent.filesystem.model.ExecuteResponse;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 平台版 {@code execute} 工具（替代框架 {@code ShellExecuteTool}）。框架版把默认
 * 超时写死为 <b>30 秒</b>（{@code ShellExecuteTool.execute}：{@code timeout == null ? 30 : timeout}），
 * 模型漏传timeout 时任何超过 30 秒的命令（依赖安装、构建、数据处理）都会被
 * {@code destroyForcibly()}杀掉；平台版默认取 {@link #SHELL_EXECUTE_TIMEOUT_SECONDS}（600 秒），
 * 与平台工具执行兜底（30 分钟）同量级，模型显式传入的 timeout 仍然优先。输出格式与框架版保持一致
 * （Exit code + 输出 + truncated 标记），不改变任何下游解析。
 *
 * <p>装配：{@code AgentAssemblyService} 在文件系统后端构造后注册本工具，并调用
 * {@code builder.disableShellTool()} 阻止框架再注册同名工具（同名注册/覆盖在任何
 * 时机都不可靠，直接禁用框架版）。</p>
 *
 * <p><b>命令前缀（可选，开发机用）</b>：框架在 Windows 上固定用 {@code cmd.exe /c} 执行命令
 * （{@code LocalFilesystemWithShell} 依据 {@code os.name} 选择 shell），而模型与技能脚本
 * 按 POSIX 语境书写命令（{@code ls} / {@code pwd} / {@code ;} 分隔 / {@code python3 scripts/…}），
 * 于是 Windows 开发机上 {@code execute} 恒返回 {@code Exit code: 255 / <no output>}。
 * 配置 {@code agent-platform.shell.command-prefix}（见 {@link ShellProperties}）后，本工具会把命令
 * 先落成临时 {@code .sh} 文件，再以 {@code <prefix> <script>} 形式交由该 shell 执行，从而在开发机上
 * 恢复 POSIX 语义；<b>前缀为空时行为与未引入本能力前完全一致</b>（生产 Linux 即空）。</p>
 *
 * <p><b>为什么不直接拼 {@code <prefix> -c "<command>"}</b>：会陷入 cmd.exe → bash 的
 * 引号与转义叠加问题；落临时脚本可完全规避，且执行后即清理。</p>
 */
public final class PlatformShellExecuteTool {

    /**
     * execute 默认超时（秒）：与工具执行兜底同量级，更长任务由模型显式声明timeout。
     * 运行态文件系统（{@code RuntimeLocalFilesystemSpec}）构造 shell 后端时复用同一值。
     */
    public static final int SHELL_EXECUTE_TIMEOUT_SECONDS = 600;

    /** execute 输出捕获上限（字节）：超限截断并附 truncated 标记，防单条命令撑爆上下文。 */
    static final int SHELL_EXECUTE_MAX_OUTPUT_BYTES = 100_000;

    private final AbstractSandboxFilesystem sandbox;

    /**
     * 命令前缀（{@code agent-platform.shell.command-prefix}）：非空时命令先落成临时
     * {@code .sh} 文件，再以 {@code <prefix> <script>} 执行；为空则沿用框架原生行为
     * （Linux {@code sh -c} / Windows {@code cmd.exe /c}）。生产环境应保持为空。
     */
    private final String commandPrefix;

    /** 模型未显式传 timeout 时使用的默认超时（秒）。 */
    private final int defaultTimeoutSeconds;

    /**
     * @param sandbox 提供 shell 执行能力的文件系统后端
     * @param commandPrefix 命令前缀；null/空白 = 不启用（生产默认）
     * @param defaultTimeoutSeconds 默认超时（秒）
     */
    public PlatformShellExecuteTool(
            AbstractSandboxFilesystem sandbox, String commandPrefix, int defaultTimeoutSeconds) {
        this.sandbox = sandbox;
        this.commandPrefix = commandPrefix == null ? "" : commandPrefix;
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
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
        Path tempScript = null;
        if (!commandPrefix.isBlank()) {
            // 配置了命令前缀：把命令落成临时 .sh，交由配置的 shell（如 Git Bash）承载 POSIX 语义。
            // 直接拼 "<prefix> -c \"<command>\"" 会陷入 cmd.exe → bash 的引号/转义地狱；
            // 落文件可完全规避（框架在 Windows 固定用 cmd.exe /c，不可改）。
            try {
                tempScript = writeTempScript(command);
                effectiveCommand = commandPrefix + " \"" + toForwardSlash(tempScript) + "\"";
            } catch (IOException e) {
                return "Error: failed to prepare command script for the configured shell prefix"
                        + " (agent-platform.shell.command-prefix): " + e.getMessage();
            }
        }
        if (workingDirectory != null && !workingDirectory.isBlank()) {
            String wd = workingDirectory.strip();
            if (wd.startsWith("/") || wd.startsWith("~") || wd.contains("..")) {
                deleteQuietly(tempScript);
                return "Error: working_directory must be a relative path within the workspace"
                        + " (absolute paths, '~', and '..' are not allowed).";
            }
            effectiveCommand = commandWithWorkingDirectory(wd, effectiveCommand, isWindows());
        }

        int timeoutSeconds = timeout != null && timeout > 0 ? timeout : defaultTimeoutSeconds;
        ExecuteResponse result;
        try {
            result = sandbox.execute(runtimeContext, effectiveCommand, timeoutSeconds);
        } finally {
            deleteQuietly(tempScript);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(result.exitCode()).append("\n");
        if (result.output() != null && !result.output().isBlank()) {
            sb.append("\n").append(result.output());
        }
        if (result.truncated()) {
            sb.append("\n(output was truncated)");
        }
        if (tempScript != null && looksLikeCommandNotFound(result)) {
            // 有前缀却"命令未找到" → 绝大多数是前缀指向的 shell 不存在；给出可诊断的提示，
            // 不做静默回退（回退到原生 shell 只会重复同一次失败，反而掩盖配置错误）。
            sb.append("\n[hint] The configured shell prefix was not resolvable: ")
                    .append(commandPrefix)
                    .append(" — check agent-platform.shell.command-prefix.");
        }
        return sb.toString();
    }

    /**
     * 平台解码子进程输出所用的字符集——必须与框架口径**同源**，否则中文文件名 / 提示会乱码。
     *
     * <p>框架 {@code LocalFilesystemWithShell.outputCharset} 的规则：非 Windows 固定
     * {@link StandardCharsets#UTF_8}；Windows 取系统属性 {@code native.encoding}（简中 Windows
     * 即 GBK/936），该值不被支持时退回 {@link Charset#defaultCharset()}。这里逐条照抄，
     * 因此开发机是 en-US Windows 或 JVM 改过默认编码时也不需要改代码。</p>
     *
     * <p>仅 {@code command-prefix} 非空（开发机）时参与转码，生产路径不经过这里。</p>
     */
    static final Charset OUTPUT_CHARSET = resolveOutputCharset();

    /** 与框架 {@code outputCharset(osName, nativeEncoding)} 同规则（{@link #isWindows()} 亦同口径）。 */
    static Charset resolveOutputCharset() {
        if (!isWindows()) {
            return StandardCharsets.UTF_8;
        }
        String nativeEncoding = System.getProperty("native.encoding", "");
        if (!nativeEncoding.isBlank() && Charset.isSupported(nativeEncoding)) {
            return Charset.forName(nativeEncoding);
        }
        return Charset.defaultCharset();
    }

    /**
     * 把命令写入临时 {@code .sh}（UTF-8、无 BOM、LF）。
     *
     * <p>落在 {@code java.io.tmpdir}：该目录属宿主临时区，既能被配置的 shell 读取，
     * 又不进入运行态工作区（不污染产物树、不被文件工具的 PathPolicy 影响）。</p>
     *
     * <p><b>输出转码</b>：Git Bash（MSYS2）以 UTF-8 输出，而平台按 {@link #OUTPUT_CHARSET}
     * （Windows 上 = {@code native.encoding}，简中即 GBK）解码 —— 口径不一致会让中文文件名 /
     * 提示变成乱码（同一份字节两种解码逐字对照：UTF-8 得 {@code python数据分析}，
     * GBK 得 {@code python鍛版嵁鍒嗘瀽}；平台实测乱码 {@code 9鏈?26} =「9月 26」同型）。
     * 故这里用 {@code iconv} 把整段输出转成平台解码口径。</p>
     */
    private static Path writeTempScript(String command) throws IOException {
        Path script = Files.createTempFile("agent-exec-", ".sh");
        Files.writeString(script, wrapForOutputCharset(command, OUTPUT_CHARSET),
                StandardCharsets.UTF_8);
        return script;
    }

    /**
     * 包装命令：合并 stdout+stderr → 转成平台解码口径 → 还原原命令退出码。
     *
     * <p>要点：</p>
     * <ul>
     *   <li>{@code charset} 为 UTF-8（非 Windows 平台即如此）时<b>原样返回</b>：本就是同口径，
     *       多余的转码只会多一个失败面；</li>
     *   <li>管道会吃掉退出码，故用 {@code ${PIPESTATUS[0]}} 还原（脚本由 bash 承载）；</li>
     *   <li>{@code iconv -c}：遇到非 UTF-8 字节（被调用的原生 Windows 工具可能直接吐 GBK）
     *       跳过该字符而不是中断，避免整段输出丢失；</li>
     *   <li>转码前先用空输入探一次 {@code iconv -f UTF-8 -t <charset>}：宿主没有 {@code iconv}、
     *       或它不认识该字符集名时<b>原样执行</b>——宁可不转码，也不能因转码器报错把输出丢成空。</li>
     * </ul>
     *
     * @param command 模型给出的原始命令
     * @param charset 平台解码子进程输出所用的字符集（见 {@link #OUTPUT_CHARSET}）
     * @return 可直接交给 bash 执行的脚本体
     */
    static String wrapForOutputCharset(String command, Charset charset) {
        if (StandardCharsets.UTF_8.equals(charset)) {
            return command + "\n";
        }
        String name = charset.name();
        String run = "{ " + command + "\n} 2>&1";
        return "if command -v iconv >/dev/null 2>&1"
                + " && iconv -f UTF-8 -t " + name + " </dev/null >/dev/null 2>&1; then\n"
                + "  " + run + " | iconv -c -f UTF-8 -t " + name + " 2>/dev/null\n"
                + "  exit ${PIPESTATUS[0]}\n"
                + "fi\n"
                + run + "\n"
                + "exit $?\n";
    }

    /** 供 Git Bash 解析的路径形态（反斜杠会被 bash 当转义符）。 */
    private static String toForwardSlash(Path path) {
        return path.toAbsolutePath().normalize().toString().replace('\\', '/');
    }

    /** 尽力清理临时脚本：清理失败不影响工具结果。 */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件残留可接受；不改变命令本身的成败语义
        }
    }

    /** Windows 判定（与框架 LocalFilesystemWithShell 的 os.name 口径保持一致）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** cmd.exe 解析不到命令的典型特征（exit 9009 或 "not recognized" 文案）。 */
    private static boolean looksLikeCommandNotFound(ExecuteResponse result) {
        if (result == null) {
            return false;
        }
        if (result.exitCode() == 9009) {
            return true;
        }
        String output = result.output();
        return output != null
                && (output.contains("is not recognized as an internal or external command")
                        || output.contains("The system cannot find the path specified"));
    }

    static String commandWithWorkingDirectory(
            String workingDirectory, String command, boolean windows) {
        if (windows) {
            return "cd /d \"" + workingDirectory.replace("\"", "\"\"") + "\" && " + command;
        }
        return "cd '" + workingDirectory.replace("'", "'\\''") + "' && " + command;
    }
}
