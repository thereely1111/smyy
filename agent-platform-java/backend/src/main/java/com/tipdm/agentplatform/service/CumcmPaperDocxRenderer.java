package com.tipdm.agentplatform.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Electronic DRAFT derived from the user-provided DOCX, not a competition-ready paper.
 * Its existing section/page setup and package styles are retained; sample/identity pages
 * and example paragraphs are removed. Generated paragraphs are rendered from real Markdown.
 * Complex LaTeX, charts and official submission layout still require manual verification.
 */
public final class CumcmPaperDocxRenderer {
    private static final String TEMPLATE = "/templates/cumcm-paper.docx";

    private CumcmPaperDocxRenderer() {
    }

    public static byte[] render(String markdown) throws IOException {
        if (markdown == null || markdown.isBlank()) {
            throw new IOException("论文初稿不能为空");
        }
        try (InputStream source = CumcmPaperDocxRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (source == null) {
                throw new IOException("论文模板资源不存在: " + TEMPLATE);
            }
            try (XWPFDocument template = new XWPFDocument(source);
                 XWPFDocument generated = new XWPFDocument(
                         new ByteArrayInputStream(MarkdownDocxRenderer.render(markdown, "")));
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                CTBody templateBody = template.getDocument().getBody();
                CTSectPr section = templateBody.isSetSectPr()
                        ? (CTSectPr) templateBody.getSectPr().copy() : null;
                // Replace ALL user-template example content, including cover and identity pages.
                // Never autofill legal commitments, names, team IDs, or sample result values.
                templateBody.set(generated.getDocument().getBody());
                if (section != null) {
                    templateBody.setSectPr(section);
                }
                template.write(out);
                return out.toByteArray();
            }
        }
    }
}
