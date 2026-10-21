package com.tipdm.agentplatform.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分块：按行聚合，达到 {@code maxChars} 即截断为新块，并在新块前缀保留一定重叠以维持上下文连续性。
 * 面向知识库文档（md / txt / 代码 / 文本类），对超长单行做兜底硬切。
 */
@Component
public class KnowledgeChunker {

    private final int maxChars;
    private final int overlap;

    public KnowledgeChunker() {
        this(1000, 200);
    }

    public KnowledgeChunker(int maxChars, int overlap) {
        this.maxChars = maxChars;
        this.overlap = Math.max(0, Math.min(overlap, maxChars / 2));
    }

    public List<String> chunk(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }
        String[] lines = text.split("\n", -1);
        StringBuilder cur = new StringBuilder();
        for (String line : lines) {
            String add = line;
            if (cur.length() == 0) {
                cur.append(add);
            } else if (cur.length() + 1 + add.length() <= maxChars) {
                cur.append('\n').append(add);
            } else {
                chunks.add(cur.toString());
                String tail = cur.substring(Math.max(0, cur.length() - overlap));
                cur.setLength(0);
                if (!tail.isBlank()) {
                    cur.append(tail).append('\n');
                }
                cur.append(add);
            }
        }
        if (cur.length() > 0) {
            chunks.add(cur.toString());
        }
        // 兜底：超长单块（无换行）硬切
        List<String> result = new ArrayList<>();
        for (String c : chunks) {
            if (c.length() <= maxChars) {
                result.add(c);
                continue;
            }
            for (int i = 0; i < c.length(); i += maxChars) {
                result.add(c.substring(i, Math.min(c.length(), i + maxChars)));
            }
        }
        return result;
    }
}
