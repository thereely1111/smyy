package com.tipdm.agentplatform.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link ShellEnvResolver} 的单元用例：只断言「结构性事实」（键存在、取值形状），
 * 不写死本机盘符与绝对路径，任何设备上都能跑。
 */
class ShellEnvResolverTest {

    @Test
    @DisplayName("baseEnv 总是给出非空 PATH（壳层环境非空才会被框架 clear+putAll）")
    void baseEnvAlwaysHasPath() {
        Map<String, String> env = ShellEnvResolver.baseEnv();

        assertFalse(env.isEmpty(), "基础环境不能为空，否则框架不会 clear+putAll");
        String path = env.get("PATH");
        assertNotNull(path, "必须给 PATH");
        assertFalse(path.isBlank(), "PATH 不能为空串");
    }

    @Test
    @DisplayName("Windows 宿主：PATH 指向 System32 系，并补齐 SystemRoot/COMSPEC/PATHEXT/SystemDrive")
    void windowsBaseEnvProvidesSystemVars() {
        assumeTrue(ShellEnvResolver.isWindows(),
                "仅 Windows 宿主适用");

        Map<String, String> env = ShellEnvResolver.baseEnv();

        assertNotNull(env.get("SystemRoot"), "缺 SystemRoot ⇒ cmd 里 %SystemRoot% 会变成字面目录");
        assertEquals(env.get("SystemRoot"), env.get("windir"));
        assertNotNull(env.get("PATHEXT"), "缺 PATHEXT ⇒ cmd 无法把 foo 解析成 foo.exe/.cmd");
        assertNotNull(env.get("COMSPEC"));
        assertTrue(env.get("COMSPEC").toLowerCase().endsWith("cmd.exe"));
        assertNotNull(env.get("SystemDrive"));
        assertTrue(env.get("SystemDrive").endsWith(":"), "SystemDrive 形如 C:");
        assertTrue(env.get("PATH").toLowerCase().contains("system32"),
                "Windows PATH 必须包含 System32，否则 where/dir/findstr 不可用");
        assertFalse(env.get("PATH").contains("/usr/bin"),
                "Windows 上不应再塞 POSIX PATH");
    }

    @Test
    @DisplayName("POSIX 宿主：只有固定 PATH，不含 Windows 变量")
    void posixBaseEnvHasOnlyPath() {
        assumeTrue(!ShellEnvResolver.isWindows(),
                "仅 POSIX 宿主适用");

        Map<String, String> env = ShellEnvResolver.baseEnv();

        assertEquals(1, env.size());
        assertTrue(env.get("PATH").contains("/usr/bin"));
        assertNull(env.get("SystemRoot"));
    }

    @Test
    @DisplayName("会话级注入：SMYY_SESSION_DIR / SMYY_SKILLS_DIR，且都为绝对路径")
    void resolveInjectsSessionAndSkillsDir(@TempDir Path tmp) throws IOException {
        Path session = Files.createDirectories(tmp.resolve("runtimes").resolve("agent-1").resolve("main-abc"));
        Path skills = Files.createDirectories(tmp.resolve("runtimes").resolve("agent-1")
                .resolve(".harness").resolve("skills"));

        Map<String, String> env = ShellEnvResolver.resolve(session, skills);

        assertEquals(session.toAbsolutePath().normalize().toString(),
                env.get(ShellEnvResolver.ENV_SESSION_DIR));
        assertEquals(skills.toAbsolutePath().normalize().toString(),
                env.get(ShellEnvResolver.ENV_SKILLS_DIR));
        assertTrue(Path.of(env.get(ShellEnvResolver.ENV_SESSION_DIR)).isAbsolute());
    }

    @Test
    @DisplayName("会话 python_path.txt 首行 → SMYY_PYTHON；没有该文件时不注入")
    void resolveReadsPythonOverride(@TempDir Path tmp) throws IOException {
        Path session = Files.createDirectories(tmp.resolve("session"));
        assertNull(ShellEnvResolver.resolve(session, null).get(ShellEnvResolver.ENV_PYTHON),
                "没有 python_path.txt 时不应注入 SMYY_PYTHON");

        Files.writeString(session.resolve(ShellEnvResolver.SESSION_PYTHON_FILE),
                "\r\nD:\\Python311\\python.exe\r\n", StandardCharsets.UTF_8);

        assertEquals("D:\\Python311\\python.exe",
                ShellEnvResolver.resolve(session, null).get(ShellEnvResolver.ENV_PYTHON),
                "应取首个非空行并去掉引号/CR");
    }

    @Test
    @DisplayName("会话 .env.local：逐行 KEY=VALUE，跳过空行与 # 注释，剥掉成对引号")
    void resolveReadsSessionEnvFile(@TempDir Path tmp) throws IOException {
        Path session = Files.createDirectories(tmp.resolve("session"));
        Files.writeString(session.resolve(ShellEnvResolver.SESSION_ENV_FILE),
                "# 注释行\n"
                        + "\n"
                        + "MKL_NUM_THREADS=4\n"
                        + "DATA_DIR=\"D:/data sets\"\n"
                        + "NO_EQUALS_LINE\n"
                        + "PATH_WITH_EQ=a=b\n",
                StandardCharsets.UTF_8);

        Map<String, String> env = ShellEnvResolver.resolve(session, null);

        assertEquals("4", env.get("MKL_NUM_THREADS"));
        assertEquals("D:/data sets", env.get("DATA_DIR"), "成对引号应被剥掉");
        assertFalse(env.containsKey("NO_EQUALS_LINE"), "没有 = 的行应被忽略");
        assertEquals("a=b", env.get("PATH_WITH_EQ"), "只按第一个 = 切分");
        assertTrue(env.containsKey("PATH"), "会话变量不应覆盖掉基础 PATH");
    }

    @Test
    @DisplayName("会话参数为 null 时安全退化为基础环境")
    void resolveToleratesNullArguments() {
        Map<String, String> env = ShellEnvResolver.resolve(null, null);

        assertFalse(env.isEmpty());
        assertTrue(env.containsKey("PATH"));
        assertFalse(env.containsKey(ShellEnvResolver.ENV_SESSION_DIR));
        assertFalse(env.containsKey(ShellEnvResolver.ENV_SKILLS_DIR));
    }
}
