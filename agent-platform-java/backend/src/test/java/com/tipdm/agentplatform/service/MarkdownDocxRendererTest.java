package com.tipdm.agentplatform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → DOCX 渲染器的回归（P2 分析报告导出）。
 *
 * <p>断言方式刻意朴素：把渲染结果**重新读回** POI 文档模型再断言文本，而不是比对
 * OOXML 字节 —— 字节里塞满了命名空间前缀与属性顺序，改一处排版就会整片红，测不出
 * 真正关心的东西（中文在不在、层级对不对、表格有没有成表）。</p>
 */
class MarkdownDocxRendererTest {

    private static XWPFDocument open(byte[] docx) throws Exception {
        assertNotNull(docx);
        assertTrue(docx.length > 0, "渲染结果不能为空");
        // docx 是 OOXML 包（ZIP），前两字节固定是 PK
        assertEquals('P', docx[0]);
        assertEquals('K', docx[1]);
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private static List<String> paragraphTexts(XWPFDocument doc) {
        List<String> out = new ArrayList<>();
        for (XWPFParagraph p : doc.getParagraphs()) {
            out.add(p.getText());
        }
        return out;
    }

    private static boolean anyContains(XWPFDocument doc, String needle) {
        return paragraphTexts(doc).stream().anyMatch(t -> t.contains(needle));
    }

    /** 每个 run 都要显式写 eastAsia 字体槽，否则中文在某些环境会显示成方块 */
    private static void assertEveryRunHasEastAsia(XWPFDocument doc) {
        for (XWPFParagraph p : doc.getParagraphs()) {
            for (XWPFRun r : p.getRuns()) {
                var pr = r.getCTR().getRPr();
                assertNotNull(pr, "run 应带 rPr");
                assertTrue(pr.sizeOfRFontsArray() > 0, "run 应带 rFonts");
                assertNotNull(pr.getRFontsArray(0).getEastAsia(), "run 应带 eastAsia 字体");
            }
        }
    }

    @Test
    void keepsChineseTextIntact() throws Exception {
        String md = "# 分析报告\n\n本题是**组合优化**问题，目标是最小化总成本。\n";

        XWPFDocument doc = open(MarkdownDocxRenderer.render(md, "兜底标题"));

        assertTrue(anyContains(doc, "分析报告"), paragraphTexts(doc).toString());
        assertTrue(anyContains(doc, "本题是组合优化问题，目标是最小化总成本。"),
                paragraphTexts(doc).toString());
        assertEveryRunHasEastAsia(doc);
        doc.close();
    }

    @Test
    void rendersHeadingLevelsInOrder() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render(
                "# 一级\n## 二级\n### 三级\n#### 四级\n", null));

        List<String> texts = paragraphTexts(doc);
        assertEquals("一级", texts.get(0));
        assertEquals("二级", texts.get(1));
        assertEquals("三级", texts.get(2));
        assertEquals("四级", texts.get(3));
        doc.close();
    }

    /** 原文首行是一级标题时不再叠加兜底标题，否则文档会顶两个大标题 */
    @Test
    void addsFallbackTitleOnlyWhenNoH1() throws Exception {
        XWPFDocument withH1 = open(MarkdownDocxRenderer.render("# 已有标题\n正文\n", "分析报告"));
        List<String> texts = paragraphTexts(withH1);
        assertEquals("已有标题", texts.get(0));
        assertFalse(texts.contains("分析报告"), texts.toString());
        withH1.close();

        XWPFDocument noH1 = open(MarkdownDocxRenderer.render("正文而已\n", "分析报告"));
        assertEquals("分析报告", paragraphTexts(noH1).get(0));
        noH1.close();
    }

    @Test
    void rendersListsWithLiteralMarkers() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render(
                "- 甲\n- 乙\n  - 乙一\n\n1. 丙\n2. 丁\n", null));

        List<String> texts = paragraphTexts(doc);
        assertTrue(texts.contains("•  甲"), texts.toString());
        assertTrue(texts.contains("•  乙"), texts.toString());
        // 有序列表保留原编号（不交给 Word 自动编号）
        assertTrue(texts.contains("1. 丙"), texts.toString());
        assertTrue(texts.contains("2. 丁"), texts.toString());
        doc.close();
    }

    /** 连续非空行属同一段（中文换行多为软换行），不拆成两段 */
    @Test
    void mergesConsecutiveLinesIntoOneParagraph() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render("第一行\n第二行\n\n另一段\n", null));

        List<String> texts = paragraphTexts(doc);
        assertEquals("第一行第二行", texts.get(0));
        assertEquals("另一段", texts.get(1));
        doc.close();
    }

    /** 软换行两侧是西文时要补一个空格，否则 machine 与 learning 会黏成一个词 */
    @Test
    void joinsLatinSoftBreakWithSpace() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render("machine\nlearning model\n", null));
        assertEquals("machine learning model", paragraphTexts(doc).get(0));
        doc.close();
    }

    @Test
    void rendersPipeTableWithHeaderRow() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render(
                "| 选项 | 说明 |\n| --- | --- |\n| A | 线性规划 |\n| B | 动态规划 |\n", null));

        List<XWPFTable> tables = doc.getTables();
        assertEquals(1, tables.size(), "管道表格应渲染成真表格");
        XWPFTable t = tables.get(0);
        assertEquals(3, t.getRows().size(), "分隔行不占行");
        assertEquals("选项", t.getRow(0).getCell(0).getText());
        assertEquals("说明", t.getRow(0).getCell(1).getText());
        assertEquals("A", t.getRow(1).getCell(0).getText());
        assertEquals("动态规划", t.getRow(2).getCell(1).getText());
        doc.close();
    }

    /** 表格后面要留空段，否则紧跟的正文会黏进表格里 */
    @Test
    void leavesParagraphAfterTable() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render(
                "| a | b |\n| --- | --- |\n| 1 | 2 |\n\n表后正文\n", null));
        assertTrue(anyContains(doc, "表后正文"), paragraphTexts(doc).toString());
        doc.close();
    }

    @Test
    void rendersCodeBlockInMonoFont() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render(
                "```python\nprint(1)\n```\n", null));

        XWPFParagraph code = doc.getParagraphs().stream()
                .filter(p -> p.getText().contains("print(1)")).findFirst().orElse(null);
        assertNotNull(code, "代码块内容应存在");
        assertEquals("Consolas", code.getRuns().get(0).getFontFamily());
        doc.close();
    }

    /** {@code $…$} 里的常见 LaTeX 命令换 Unicode；结构性命令（frac）原样保留 */
    @Test
    void convertsLatexSymbolsInsideDollarMath() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render(
                "目标 $\\alpha \\le \\beta \\times 2$ 且 $\\sqrt{2} \\approx 1.41$\n", null));

        List<String> texts = paragraphTexts(doc);
        assertTrue(anyContains(doc, "目标 α ≤ β × 2"), texts.toString());
        // \sqrt 带花括号：符号与括号一起替换
        assertTrue(anyContains(doc, "√2 ≈ 1.41"), texts.toString());
        doc.close();
    }

    /** 结构性命令不猜（{\@code \frac} 没有 Unicode 对应），但它后面的花括号要留着 */
    @Test
    void keepsStructuralLatexVerbatim() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render("$\\frac{a}{b}$\n", null));
        assertTrue(anyContains(doc, "\\frac{a}{b}"), paragraphTexts(doc).toString());
        doc.close();
    }

    @Test
    void rendersInlineBoldAndCode() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render("这是**重点**与 `x_1`\n", null));

        XWPFParagraph p = doc.getParagraphs().get(0);
        assertEquals("这是重点与 x_1", p.getText());
        // 普通 / 粗体 / 普通 / 等宽 各成一个 run
        assertEquals(4, p.getRuns().size(), p.getText());
        assertTrue(p.getRuns().get(1).isBold(), "第二段应是粗体");
        assertEquals("Consolas", p.getRuns().get(3).getFontFamily());
        doc.close();
    }

    @Test
    void rendersQuoteAndRuleAsParagraphs() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render("> 注意\n\n---\n\n正文\n", null));

        List<String> texts = paragraphTexts(doc);
        assertTrue(texts.contains("注意"), texts.toString());
        assertTrue(texts.contains("正文"), texts.toString());
        assertEveryRunHasEastAsia(doc);
        doc.close();
    }

    @Test
    void emptyInputStillProducesOpenableDocx() throws Exception {
        XWPFDocument doc = open(MarkdownDocxRenderer.render("", null));
        assertEquals(0, doc.getParagraphs().size());
        doc.close();
        XWPFDocument doc2 = open(MarkdownDocxRenderer.render(null, null));
        doc2.close();
    }
}
