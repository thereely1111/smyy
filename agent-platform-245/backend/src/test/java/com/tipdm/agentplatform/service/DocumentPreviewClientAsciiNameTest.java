package com.tipdm.agentplatform.service;

import com.tipdm.agentplatform.config.DocumentPreviewProperties;
import com.tipdm.agentplatform.util.OutboundUrlGuard;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预览地址里的 {@code fullfilename} 判据回归。
 *
 * <p><b>为什么这个纯函数值得钉死</b>：TipDM-FV 会把源 URL <b>解码两次</b>才拿去回拉文件，
 * 于是任何非 ASCII 字符（中文名、空格、以及被 {@code URLDecoder} 从 {@code +} 还原的空格）
 * 都会以<b>裸形态</b>进request line，Tomcat 在解析请求行阶段就按 RFC 7230 拒掉——
 * 表现为 400 +「Invalid character found in the request target」，<b>连 Controller 都进不去</b>。
 * 这类故障的表象（后端 400）离根因（URL 里混了中文）很远，
 * 没有单测守住的话，下一个人很容易改回"传真实中文名"的直觉写法。</p>
 */
class DocumentPreviewClientAsciiNameTest {

    private static String asciiName(String fileName) throws Exception {
        Method m = DocumentPreviewClient.class.getDeclaredMethod("asciiNameFor", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, fileName);
    }

    /**
     * 真实场景回归：中文名含空格，必须产出纯 ASCII 且保住 {@code .png}。
     *
     * <p>这是线上实际报错的文件名形态。</p>
     */
    @Test
    void chineseNameWithSpacesCollapsesToAsciiButKeepsExtension() throws Exception {
        assertEquals("file.png", asciiName("图2-1 项目2 思维导图.png"));
    }

    /**
     * 产物名不含扩展名时退回 {@code file}。
     *
     * <p>此时预览服务认不出格式会自行拒绝，与「扩展名被清洗掉」结果一致，
     * 不应在这里抛异常——让它按上游不该放行的输入处理即可。</p>
     */
    @Test
    void noExtensionFallsBackToFile() throws Exception {
        assertEquals("file", asciiName("思维导图"));
        assertEquals("file", asciiName("README"));
        // 结尾的点不算扩展名
        assertEquals("file", asciiName("weird."));
        // null 与空串不能崩（调用方可能拿到空标题）
        assertEquals("file", asciiName(null));
        assertEquals("file", asciiName(""));
    }

    /**
     * 扩展名含非法字符时<b>整体作废</b>，绝不截断。
     *
     * <p>截断会产出「合法但错误」的扩展名（{@code 报告.pn g} → {@code file.pn}），
     * 预览服务会拿它匹配一个不存在的格式，比老实退回 {@code file} 更难排查。</p>
     */
    @Test
    void sanitizesIllegalCharsInExtension() throws Exception {
        // 空格会被 URLDecoder 从 '+' 还原成裸空格，整个扩展名作废
        assertEquals("file", asciiName("报告.pn g"));
        // 扩展名首位就非法 → 作废
        assertEquals("file", asciiName("报告.p+g"));
        // query 分隔符属于 URL 结构，不能混进文件名
        assertEquals("file", asciiName("report.png?x=1"));
        // 正常多段扩展名取最后一段
        assertEquals("file.gz", asciiName("archive.tar.gz"));
        // 数字扩展名合法
        assertEquals("file.mp4", asciiName("演示.mp4"));
    }

    /**
     * <b>核心不变量</b>：任何输入产出的名字都必须只含 URL 安全字符。
     *
     * <p>这是「解码多少次都不变形」的前提——ASCII 且不含 {@code %} 与 {@code +}
     * 的字符串是 {@code URLDecoder.decode} 的不动点。</p>
     */
    @Test
    void outputIsAlwaysDecodeInvariant() throws Exception {
        String[] inputs = {
                "图2-1 项目2 思维导图.png",
                "报告.docx",
                "a+b.png",
                "100%.xlsx",
                "name with  spaces.pdf",
                "中文.pptx",
                "x",
                "../etc/passwd",
        };
        for (String in : inputs) {
            String out = asciiName(in);
            assertTrue(out.matches("[A-Za-z0-9._-]+"), "含非法字符: " + out + "（输入 " + in + "）");
            assertFalse(out.contains("%"), "含百分号: " + out);
            assertFalse(out.contains("+"), "含加号: " + out);
        }
    }

    /**
     * 拼出的完整预览地址必须是纯 ASCII。
     *
     * <p>直接验证对外契约：{@code /onlinePreview?url=…} 整条字符串会原样出现在
     * 浏览器地址栏并被 TipDM-FV 拿去解码，中文混入任何一个环节都会导致回拉 400。</p>
     */
    @Test
    void previewUrlIsPureAscii() throws Exception {
        DocumentPreviewClient client = new DocumentPreviewClient(
                new DocumentPreviewProperties(true, "http://127.0.0.1:30182/",
                        "http://platform.internal", 300),
                new OutboundUrlGuard(true));

        String url = client.buildPreviewUrl(
                "http://platform.internal/api/doc-preview/raw?ticket=AAA.BBB.CCC",
                "图2-1 项目2 思维导图.png");

        assertTrue(url.matches("[\\x20-\\x7E]+"),
                "预览地址含非 ASCII 字符: " + url);
        // 外层 url 参数必须把整条源地址转义掉：否则 & 与 = 会被解析成额外参数
        assertTrue(url.contains("url=http%3A%2F%2Fplatform.internal%2Fapi%2Fdoc-preview%2Fraw"),
                "源地址未正确转义: " + url);
    }
}