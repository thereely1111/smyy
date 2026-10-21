package com.tipdm.agentplatform.service;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentTextExtractor} 单测：
 * 1) NUL / C0 控制字符净化（原线上故障点：Postgres 0x00 拒写）；
 * 2) .docx（ZIP 二进制）真实解析出正文，而非按字节乱码解码；
 * 3) 纯文本照常通过。
 */
class DocumentTextExtractorTest {

    @Test
    void sanitizeStripsNulAndControlChars() {
        String in = "a\u0000b\u0001c\u0002d\te\nf\rg";
        assertEquals("abcd\te\nf\rg", DocumentTextExtractor.sanitize(in, 1000));
    }

    @Test
    void sanitizeTruncates() {
        assertEquals("ab", DocumentTextExtractor.sanitize("abcdef", 2));
        assertEquals("", DocumentTextExtractor.sanitize(null, 10));
    }

    @Test
    void extractDocxReturnsRealText() throws Exception {
        byte[] docx = minimalDocx("智能体开发平台设计文档", "批量批注说明正文内容");
        String text = new DocumentTextExtractor().extract("设计文档.docx", docx);
        assertTrue(text.contains("批量批注说明正文内容"), "应抽出 docx 正文, got: " + text);
        assertFalse(text.contains("PK"), "不应是 ZIP 字节乱码");
    }

    @Test
    void extractPlainTextWithNul() {
        byte[] txt = "hello\u0000world".getBytes(StandardCharsets.UTF_8);
        String text = new DocumentTextExtractor().extract("a.txt", txt);
        assertEquals("helloworld", text);
    }

    /** 用 JDK ZipOutputStream 构造最小 OOXML(docx)：[Content_Types].xml + word/document.xml。 */
    private static byte[] minimalDocx(String title, String body) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                      <Default Extension="xml" ContentType="application/xml"/>
                      <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>""").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
                    </Relationships>""").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body>
                        <w:p><w:r><w:t>""" + title + """
                    </w:t></w:r></w:p>
                        <w:p><w:r><w:t>""" + body + """
                    </w:t></w:r></w:p>
                      </w:body>
                    </w:document>""").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }
}
