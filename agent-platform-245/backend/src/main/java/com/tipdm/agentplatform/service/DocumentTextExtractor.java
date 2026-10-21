package com.tipdm.agentplatform.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档文本抽取（2026-09-17 修复知识库上传 .docx 失败）。
 *
 * <p><b>背景</b>：旧实现把文件字节无条件按 UTF-8 解码当纯文本分块。.docx/.xlsx/.pptx 本质是
 * ZIP 二进制、.pdf 是二进制流，解码产物含 {@code 0x00} 等 NUL 字节，Postgres 文本写入直接报
 * {@code invalid byte sequence for encoding "UTF8": 0x00}。</p>
 *
 * <p><b>方案</b>：Tika {@link AutoDetectParser} 按真实文件类型抽取纯文本
 * （docx/pdf/xlsx/pptx/html/md/txt 全覆盖，tika-parsers-standard-package 提供 SPI 注册）。
 * 抽取结果统一 {@link #sanitize}：剔除 NUL 与 \t\n\r 之外的 C0 控制字符，并截断到
 * {@value #MAX_CHARS} 字符防止单文档撑爆分块流水线。解析失败时兜底按 UTF-8 解码后净化，
 * 行为不劣于旧实现。</p>
 */
@Service
public class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);

    /** 单文档抽取文本上限（约 200 万字符，超出截断）。 */
    private static final int MAX_CHARS = 2_000_000;

    private final AutoDetectParser parser = new AutoDetectParser();

    public String extract(String filename, byte[] bytes) {
        // 纯文本类型不走 Tika：其字符集探测可能被 NUL 等异常字节干扰选错编码（实测返回乱码），
        // 直接按 UTF-8 解码后净化即可。Tika 只负责 docx/pdf 等二进制格式的真实解析。
        if (isPlainText(filename)) {
            return sanitize(new String(bytes, StandardCharsets.UTF_8), MAX_CHARS);
        }
        String text;
        try {
            Metadata metadata = new Metadata();
            if (filename != null && !filename.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            // write limit 传 -1 关闭 BodyContentHandler 默认 100K 截断，由 sanitize 统一限长
            BodyContentHandler handler = new BodyContentHandler(-1);
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                parser.parse(in, handler, metadata, new ParseContext());
            }
            text = handler.toString();
        } catch (Exception e) {
            log.warn("Tika 文本抽取失败，兜底按 UTF-8 解码: file={}, err={}", filename, e.getMessage());
            text = new String(bytes, StandardCharsets.UTF_8);
        }
        return sanitize(text, MAX_CHARS);
    }

    /** 已知为纯文本的扩展名：与上传口 guessContentType 的文本类清单保持一致。 */
    private static boolean isPlainText(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        return switch (ext) {
            case "txt", "md", "markdown", "log", "csv", "json", "yaml", "yml" -> true;
            default -> false;
        };
    }

    /**
     * 剔除 Postgres 文本类型无法写入的 NUL（0x00）以及除 \t\n\r 外的 C0 控制字符，
     * 并截断到 maxChars。
     */
    static String sanitize(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), maxChars));
        for (int i = 0; i < s.length() && sb.length() < maxChars; i++) {
            char c = s.charAt(i);
            if (c == 0 || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
