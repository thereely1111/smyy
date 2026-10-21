package com.tipdm.agentplatform.service;

import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.OverlayFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystemWithShell;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 运行态文件系统：{@code runtimes/{agentId}} 为可写根、SESSION 命名空间，定义区只读下层。
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

    /** 定义区只读层单文件读取上限（MB）：AGENTS.md / skills 均为小文本，10MB 足够且防异常大文件。 */
    private static final int DEFINITION_READ_MAX_FILE_SIZE_MB = 10;

    /** 物化技能目录（相对运行态根）：与 {@code AgentAssemblyService#copyDefinitionSkills} 的落点一致。 */
    private static final String HARNESS_SKILLS_DIR = ".harness/skills";

    private final Path runtimeRoot;
    private final Path definitionDir;

    /**
     * 框架在 {@link #toFilesystem} 里给的 SESSION 命名空间工厂。留着是为了在
     * {@link #shellFor(Path)} 里按会话再造一个 shell 后端（每会话一份环境）。
     */
    private volatile NamespaceFactory namespaceFactory;

    RuntimeLocalFilesystemSpec(Path runtimeRoot, Path definitionDir) {
        this.runtimeRoot = runtimeRoot.toAbsolutePath().normalize();
        this.definitionDir = definitionDir.toAbsolutePath().normalize();
        project(this.runtimeRoot);
        additionalRoots(List.of(this.definitionDir));
        isolationScope(IsolationScope.SESSION);
    }

    @Override
    public AbstractFilesystem toFilesystem(Path workspace, NamespaceFactory nsFactory) {
        this.namespaceFactory = nsFactory;
        PathPolicy runtimePolicy = PathPolicy.of(List.of(runtimeRoot));
        // 壳层环境必须**非空**才会被框架清空后重建
        // （LocalFilesystemWithShell 只在 env 非空时 clear+putAll），空表会让子进程
        // 直接继承后端进程的全部环境变量（含 API key 之类），那是必须避免的。
        // 这里只给「宿主 OS 相关的基础环境」（Windows 补 System32 系 PATH +
        // SystemRoot/COMSPEC/PATHEXT 等；POSIX 给固定 PATH），不含任何会话变量——
        // 会话级变量（SMYY_*）由 shellFor(...) 按会话现算，见下。
        Map<String, String> env = ShellEnvResolver.baseEnv();
        LocalFilesystemWithShell upper = new LocalFilesystemWithShell(
                runtimeRoot,
                LocalFsMode.ROOTED,
                runtimePolicy,
                PlatformShellExecuteTool.SHELL_EXECUTE_TIMEOUT_SECONDS, // execute 超时（秒）
                PlatformShellExecuteTool.SHELL_EXECUTE_MAX_OUTPUT_BYTES, // 输出上限（字节）
                env,            // 白名单合成的基础环境，不透传宿主敏感变量
                false,          // inheritEnv=false
                nsFactory,      // SESSION 命名空间
                runtimeRoot);   // shell cwd：execute 的真实工作目录
        // 定义区只读下层：PathPolicy 必须把 runtimeRoot 一并列入允许根——框架
        // OverlayFilesystem 合并 ls/read 时对上下层同时求值，下层 LocalFilesystem 对
        // 「不在自己 allowed roots 内的绝对路径」抛 SecurityException 而非返回
        // not-found，会把跨层读取（如父编排读子会话产物）变成文件工具失败。把
        // runtimeRoot 列入 lower 允许根后：跨层读取由上层命中、下层求值静默返回
        // 不存在；定义区仍受 ROOTED + 该 policy 约束（只允许 definitionDir 与
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

    /**
     * 造一个<b>属于某个会话</b>的 shell 后端：{@code execute} 的 cwd ＝ 该会话目录，
     * 环境 ＝ {@link ShellEnvResolver#resolve(Path, Path) 基础环境 + 本会话 SMYY_*}。
     *
     * <p><b>为什么不能复用 {@link #toFilesystem} 那个后端</b>：后端与 {@code HarnessAgent}
     * 同生命周期（{@code AgentAssemblyService} 按 agentId 缓存装配结果），env 表在构造时就固定了，
     * 而 {@code SMYY_SESSION_DIR} / {@code SMYY_PYTHON} 是<b>每会话不同</b>的；框架的
     * {@code resolveExecuteCwd} 只在 {@code shellCwd == null} 时才会按命名空间推会话目录，
     * 拿不到 env 侧的同款能力。所以由调用方（{@code PlatformShellExecuteTool}）在每次
     * {@code execute} 时按 {@code RuntimeContext.sessionId} 现造一个轻量 shell 后端。
     * 只用于 {@code execute}，不参与文件工具的挂载（文件工具仍走 {@link #toFilesystem} 的 overlay）。</p>
     *
     * @param sessionDir 会话工作目录（{@code runtimes/{agentId}/{sessionId}}），非空
     */
    AbstractSandboxFilesystem shellFor(Path sessionDir) {
        Path session = sessionDir.toAbsolutePath().normalize();
        PathPolicy policy = PathPolicy.of(List.of(runtimeRoot));
        Map<String, String> env = ShellEnvResolver.resolve(session, runtimeRoot.resolve(HARNESS_SKILLS_DIR));
        // project 仍是 runtimeRoot（相对路径的基准根），末参 shellCwd = 会话目录：
        // resolveExecuteCwd 见 shellCwd 非空即直接返回，故 execute 的 cwd 就是本会话目录。
        return new LocalFilesystemWithShell(
                runtimeRoot,
                LocalFsMode.ROOTED,
                policy,
                PlatformShellExecuteTool.SHELL_EXECUTE_TIMEOUT_SECONDS,
                PlatformShellExecuteTool.SHELL_EXECUTE_MAX_OUTPUT_BYTES,
                env,               // 基础环境 + 本会话 SMYY_*
                false,             // inheritEnv=false
                namespaceFactory,  // SESSION 命名空间（此处 shellCwd 非空，实际不参与解析）
                session);          // shell cwd ＝ 本会话目录
    }
}
