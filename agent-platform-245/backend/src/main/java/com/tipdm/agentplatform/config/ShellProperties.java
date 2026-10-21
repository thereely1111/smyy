package com.tipdm.agentplatform.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 子进程 shell 环境与命令包装配置（**可选**，默认空 = 保持平台内置语义）。
 *
 * <p><b>为什么需要它</b>：平台给 {@code execute} 工具的子进程只注入一个 PATH
 * （见 {@code RuntimeLocalFilesystemSpec}），且该字面量是按<b>生产语义</b>（Linux / macOS）写的；框架在 Windows 上固定用 {@code cmd.exe /c}
 * 执行命令（{@code LocalFilesystemWithShell}），而模型与技能脚本按 POSIX 语境书写
 * （{@code ls} / {@code pwd} / {@code head} / {@code ;} 分隔 / {@code python3 scripts/…}），
 * 于是 Windows 开发机上 `execute` 恒返回 {@code Exit code: 255 / <no output>}。</p>
 *
 * <p><b>为什么不直接改默认值</b>：后端以 jar 形态部署到 Linux 容器，把 Windows 专有
 * 路径写进默认值会污染生产语义。因此改为<b>可选覆盖</b>：容器不设这两个配置 → 行为与
 * 改造前逐字一致；仅在开发机（或需要显式指定 shell 的场景）通过环境变量注入。</p>
 *
 * <p>用法示例（Windows 开发机，指向 Git Bash 承载 POSIX 命令；2026-09-28 已用 Java
 * ProcessBuilder 实测通过）：</p>
 * <pre>
 * AGENT_PLATFORM_SHELL_PATH=&lt;python目录&gt;;C:\work\Git\bin;C:\work\Git&#92;usr&#92;bin;C:\Windows\System32;C:\Windows
 * AGENT_PLATFORM_SHELL_COMMAND_PREFIX=C:\work\Git\bin\bash.exe
 * </pre>
 *
 * <p>维护提示：本文件 Javadoc 中的反斜杠一律写成 <code>&amp;#92;</code>——直接写「反斜杠 + u」
 * 开头的路径片段（如 Git 的 usr 目录）会被 Java 词法器当作 Unicode 转义，编译报「非法的 Unicode 逃逸」。</p>
 *
 * <p>两个要点：{@code path} 必须含 Git 的 {@code usr\bin}（{@code ls}/{@code head}/{@code pwd}
 * 等 POSIX 命令在其下）与 {@code System32}；{@code commandPrefix} <b>不要加 {@code -l}</b>
 * ——登录模式会按 {@code HOMEDRIVE}/{@code HOMEPATH} 改动家目录与工作目录，导致工作目录漂移。</p>
 *
 * <p>注意：{@code commandPrefix} 按<b>原样拼接</b>在命令之前（命令本体落临时 {@code .sh}
 * 文件后作为参数传入），因此路径含空格时请自行加引号，例如
 * {@code "C:\Program Files\Git\bin\bash.exe"}。</p>
 */
@Component
@ConfigurationProperties(prefix = "agent-platform.shell")
public class ShellProperties {

    private static final Logger log = LoggerFactory.getLogger(ShellProperties.class);

    /**
     * 子进程 PATH 覆盖：为空则沿用平台内置默认（Linux / macOS 语义，生产不变）。
     * 非空时平台会以「PATH + 一组必要系统变量白名单」构造子进程环境（不透传全部环境变量）。
     */
    private String path = "";

    /**
     * 命令前缀（可选）：非空时，{@code execute} 的命令会先落成临时 {@code .sh} 文件，
     * 再以 {@code <commandPrefix> <script>} 形式交给 shell 执行。
     * 为空则沿用框架默认行为（Linux: {@code sh -c}；Windows: {@code cmd.exe /c}）。
     */
    private String commandPrefix = "";

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path == null ? "" : path;
    }

    public String getCommandPrefix() {
        return commandPrefix;
    }

    public void setCommandPrefix(String commandPrefix) {
        this.commandPrefix = commandPrefix == null ? "" : commandPrefix;
    }

    /**
     * 启动时把「生效的 shell 覆盖」打进日志：两个配置都是可选的开发机开关，生产本应留空，
     * 一旦有人把开发机的环境变量带进容器，这里是唯一能直接看出来的地方；
     * 只配一半（只配 path / 只配 command-prefix）也会在此显形。
     *
     * <p>未配置时<b>不打任何日志</b>——生产日志与改造前保持一致。</p>
     */
    @PostConstruct
    void logEffectiveOverride() {
        if (path.isBlank() && commandPrefix.isBlank()) {
            return;
        }
        log.info("子进程 shell 覆盖已启用（应仅出现在开发机）: path={}, command-prefix={}",
                path.isBlank() ? "(未覆盖，沿用平台默认)" : path,
                commandPrefix.isBlank() ? "(未覆盖，沿用框架默认)" : commandPrefix);
    }
}
