package com.tipdm.agentplatform.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code execute}（真实 shell）的环境变量解析器 —— 客服「一台设备一个路径」与「每个任务一份环境」。
 *
 * <p><b>为什么要自己构造环境</b>：{@code LocalFilesystemWithShell} 的 {@code inheritEnv=false}，
 * 交给 {@code ProcessBuilder} 的就是本类产出的这张表（框架在 {@code execute} 里
 * {@code environment().clear(); environment().putAll(env);}）。宿主环境<b>不能整体继承</b>——
 * 那会把宿主的凭据类变量（各类 API key、代理口令）泄给模型起的任意进程。所以这里只<b>白名单</b>
 * 地合成 shell 真正需要的几个通用变量。</p>
 *
 * <p><b>两层</b>：</p>
 * <ol>
 *   <li>{@link #baseEnv()} —— 只跟宿主 OS 有关，与设备无关（不含任何盘符/用户名硬编码）：
 *       <ul>
 *         <li>POSIX：固定 {@code PATH=/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin}</li>
 *         <li>Windows：{@code PATH} 指向 {@code %SystemRoot%\system32} 系，并补
 *             {@code SystemRoot} / {@code windir} / {@code SystemDrive} / {@code COMSPEC} /
 *             {@code PATHEXT} ⇒ {@code where} / {@code dir} / {@code findstr} 等系统命令可用。
 *             {@code SystemRoot} 取自宿主同名变量（非敏感），取不到才回落 {@code C:\Windows}。</li>
 *       </ul>
 *     </li>
 *   <li>{@link #resolve(Path, Path)} —— 在 {@link #baseEnv()} 之上叠加<b>本会话</b>的变量：
 *       {@code SMYY_SESSION_DIR}、{@code SMYY_SKILLS_DIR}、{@code SMYY_PYTHON}
 *       （仅当会话目录里有 {@code python_path.txt}）以及会话目录 {@code .env.local} 里的自定义项。</li>
 * </ol>
 *
 * <p><b>为什么原来的写法会产出 {@code %D%} / {@code %SystemDrive%} 这种垃圾目录</b>：
 * 旧实现只给了一个 POSIX 值当 {@code PATH}，Windows 下 {@code SystemRoot} 等标准变量一个都没有，
 * cmd.exe 遇到未定义的 {@code %XXX%} 会把字面量原样当路径 ⇒ 就地在运行态根建出
 * {@code %SystemDrive%} 之类的目录。</p>
 *
 * <p><b>契约（勿改）</b>：{@code SMYY_SESSION_DIR} / {@code SMYY_SKILLS_DIR} / {@code SMYY_PYTHON}
 * 三个变量名与语义已在 {@code workspace/agents/数学建模解答助手} 的
 * {@code AGENTS.md} 与 {@code skills/mm-0-路径与会话隔离/SKILL.md}、
 * 以及 {@code mm-2-*}/{@code mm-4-*} 的 {@code run.cmd} / {@code run.sh} 中写入契约；
 * 改名字必须同步那几处。</p>
 */
public final class ShellEnvResolver {

    /** 本会话工作目录的绝对路径（也是 {@code execute} 的 cwd）。 */
    public static final String ENV_SESSION_DIR = "SMYY_SESSION_DIR";

    /** 物化到运行态根的技能目录绝对路径（{@code <runtimeRoot>/.harness/skills}）。 */
    public static final String ENV_SKILLS_DIR = "SMYY_SKILLS_DIR";

    /** 仅当会话目录写了 {@code python_path.txt} 时注入：解释器绝对路径。 */
    public static final String ENV_PYTHON = "SMYY_PYTHON";

    /** 会话级自定义变量文件（逐行 {@code KEY=VALUE}，{@code #} 开头为注释）。 */
    static final String SESSION_ENV_FILE = ".env.local";

    /** 会话级解释器覆盖文件（首行＝解释器绝对路径）。 */
    static final String SESSION_PYTHON_FILE = "python_path.txt";

    /**
     * POSIX 宿主的固定 {@code PATH}：刻意只给这一串（不给宿主 PATH，防凭据泄漏），
     * 覆盖 macOS（含 /opt/homebrew）与 Linux 常见位置。
     */
    private static final String POSIX_PATH = "/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin";

    /** Windows {@code PATHEXT}：让 cmd.exe 能把 {@code foo} 解析成 {@code foo.exe} / {@code .cmd}。 */
    private static final String WINDOWS_PATHEXT =
            ".COM;.EXE;.BAT;.CMD;.VBS;.VBE;.JS;.JSE;.WSF;.WSH;.MSC";

    /** 取不到宿主 {@code SystemRoot} 时的回落值（Windows 默认安装位置）。 */
    private static final String WINDOWS_DEFAULT_SYSTEM_ROOT = "C:\\Windows";

    private ShellEnvResolver() {
    }

    /**
     * 宿主 OS 相关的基础环境（无会话信息）。任何设备上都不产生盘符/用户名硬编码
     * （Windows 侧只从宿主读非敏感的 {@code SystemRoot}）。
     */
    public static Map<String, String> baseEnv() {
        return isWindows() ? windowsBaseEnv() : Map.of("PATH", POSIX_PATH);
    }

    /**
     * 会话级环境 = {@link #baseEnv()} + 本会话变量。
     *
     * @param sessionDir 会话工作目录（{@code runtimes/{agentId}/{sessionId}}），可为 null
     * @param skillsDir  物化技能目录（{@code runtimes/{agentId}/.harness/skills}），可为 null
     */
    public static Map<String, String> resolve(Path sessionDir, Path skillsDir) {
        Map<String, String> env = new LinkedHashMap<>(baseEnv());
        if (sessionDir != null) {
            Path session = sessionDir.toAbsolutePath().normalize();
            env.put(ENV_SESSION_DIR, session.toString());
            env.putAll(readSessionEnvFile(session.resolve(SESSION_ENV_FILE)));
            String python = firstNonBlankLine(session.resolve(SESSION_PYTHON_FILE));
            if (python != null) {
                env.put(ENV_PYTHON, python);
            }
        }
        if (skillsDir != null) {
            env.put(ENV_SKILLS_DIR, skillsDir.toAbsolutePath().normalize().toString());
        }
        return env;
    }

    /** 宿主是否 Windows（与框架 {@code LocalFilesystemWithShell} 判据保持一致）。 */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static Map<String, String> windowsBaseEnv() {
        String systemRoot = firstNonBlank(System.getenv("SystemRoot"), System.getenv("windir"));
        if (systemRoot == null) {
            systemRoot = WINDOWS_DEFAULT_SYSTEM_ROOT;
        }
        while (systemRoot.endsWith("\\") || systemRoot.endsWith("/")) {
            systemRoot = systemRoot.substring(0, systemRoot.length() - 1);
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("SystemRoot", systemRoot);
        env.put("windir", systemRoot);
        env.put("SystemDrive", systemDriveOf(systemRoot));
        env.put("COMSPEC", systemRoot + "\\system32\\cmd.exe");
        env.put("PATHEXT", WINDOWS_PATHEXT);
        env.put("PATH", String.join(";",
                systemRoot + "\\system32",
                systemRoot + "\\System32\\Wbem",
                systemRoot + "\\System32\\WindowsPowerShell\\v1.0",
                systemRoot));
        return env;
    }

    /** 从 {@code C:\Windows} 取出 {@code C:}；取不到时回落 {@code C:}。 */
    private static String systemDriveOf(String systemRoot) {
        int colon = systemRoot.indexOf(':');
        return colon > 0 ? systemRoot.substring(0, colon + 1) : "C:";
    }

    /**
     * 读 {@code .env.local}：逐行 {@code KEY=VALUE}，忽略空行与 {@code #} 注释，
     * 值两侧的单/双引号会被剥掉。文件不存在或不可读时返回空表（不抛）。
     */
    static Map<String, String> readSessionEnvFile(Path file) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (file == null || !Files.isRegularFile(file)) {
            return vars;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return vars;
        }
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = stripQuotes(line.substring(eq + 1).strip());
            if (!key.isEmpty()) {
                vars.put(key, value);
            }
        }
        return vars;
    }

    /** 读文件首行并去掉 BOM/空白；空行或文件不存在时返回 null。 */
    static String firstNonBlankLine(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String value = stripQuotes(line.replace("\uFEFF", "").strip());
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }
}
