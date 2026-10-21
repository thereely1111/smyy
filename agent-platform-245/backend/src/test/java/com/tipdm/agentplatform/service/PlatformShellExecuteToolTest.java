package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code execute} 命令包装与输出字符集回归（仅开发机路径：{@code command-prefix} 非空）。
 *
 * <p>两件事必须同时成立，否则这个开发机开关会静默地把工具变坏：</p>
 * <ol>
 *   <li><b>非 Windows 平台零加工</b>：框架在非 Windows 上固定按 UTF-8 解码子进程输出，
 *       本就是同口径，包装脚本必须原样返回命令——多一次 iconv 就多一个失败面；</li>
 *   <li><b>Windows 上转码失败不能吞输出</b>：iconv 不存在（或它不认识目标字符集名）时必须
 *       退回原样执行。历史教训是"转码器报错 → 整段输出变空"，比乱码更难排查。</li>
 * </ol>
 */
class PlatformShellExecuteToolTest {

    private static final Charset GBK = Charset.forName("GBK");

    @Test
    void utf8Charset_returnsCommandVerbatim() {
        // 非 Windows：与框架原生 {sh -c <command>} 逐字等价，不留任何转码痕迹
        assertThat(PlatformShellExecuteTool.wrapForOutputCharset("ls -la", StandardCharsets.UTF_8))
                .isEqualTo("ls -la\n");
    }

    @Test
    void nonUtf8Charset_transcodesIntoPlatformDecodingCharset() {
        String script = PlatformShellExecuteTool.wrapForOutputCharset("python3 scripts/x.py", GBK);

        // 合并 stdout+stderr 后整段转成平台解码口径，并用 PIPESTATUS 还原命令自身的退出码
        assertThat(script)
                .contains("iconv -c -f UTF-8 -t GBK")
                .contains("exit ${PIPESTATUS[0]}")
                .contains("{ python3 scripts/x.py\n} 2>&1");
    }

    @Test
    void nonUtf8Charset_probesIconvCharsetSupportBeforeTranscoding() {
        String script = PlatformShellExecuteTool.wrapForOutputCharset("echo hi", GBK);

        // 转码前先空输入探一次「iconv 存在且认识该字符集」，否则目标字符集名不被支持时输出会被丢空
        assertThat(script).contains("iconv -f UTF-8 -t GBK </dev/null >/dev/null 2>&1");
        // 探测不通过时的兜底分支：原样执行 + 原样退出码（命令体因此出现两次）
        assertThat(countOccurrences(script, "{ echo hi\n} 2>&1")).isEqualTo(2);
        assertThat(script).endsWith("exit $?\n");
    }

    @Test
    void nonUtf8Charset_keepsMultiLineCommandIntact() {
        String script = PlatformShellExecuteTool.wrapForOutputCharset("cd x\nls", GBK);

        assertThat(script).contains("{ cd x\nls\n} 2>&1");
    }

    @Test
    void outputCharset_mirrorsFrameworkRule() {
        // 框架规则（LocalFilesystemWithShell.outputCharset）：非 Windows 固定 UTF-8；
        // Windows 取 native.encoding，不被支持时退回 JVM 默认字符集。这里不写死 GBK，
        // 否则 en-US Windows 或改过默认编码的 JVM 上会与框架解码口径错开。
        String original = System.getProperty("native.encoding");
        try {
            System.setProperty("native.encoding", "GBK");
            assertThat(PlatformShellExecuteTool.resolveOutputCharset())
                    .isEqualTo(isWindows() ? GBK : StandardCharsets.UTF_8);

            if (isWindows()) {
                System.setProperty("native.encoding", "definitely-not-a-charset");
                assertThat(PlatformShellExecuteTool.resolveOutputCharset())
                        .isEqualTo(Charset.defaultCharset());
            }
        } finally {
            if (original == null) {
                System.clearProperty("native.encoding");
            } else {
                System.setProperty("native.encoding", original);
            }
        }
    }

    /** Windows 判定与生产实现同口径（框架 {@code LocalFilesystemWithShell} 亦用 os.name）。 */
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }
}
