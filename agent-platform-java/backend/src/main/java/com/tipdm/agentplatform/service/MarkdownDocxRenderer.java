package com.tipdm.agentplatform.service;

import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBorder;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTFonts;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPBdr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTShd;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblBorders;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STBorder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Markdown → DOCX 渲染器（站点工作台的「分析报告导出」）。
 *
 * <p>为什么自己写而不是引库：平台要的是一个**只做转换、不出网**的纯函数。POI 随 Tika
 * 已在编译依赖里（{@code tika-parser-microsoft-module → poi-ooxml}），直接用它生成 OOXML
 * 即可，无需新增依赖，也无需外部进程（pandoc 之类）。代价是只覆盖模型产物里真正用到的
 * 那几种语法，见下。</p>
 *
 * <p><strong>支持的语法（够用即止）：</strong></p>
 * <ul>
 *   <li>{@code #}~{@code ####} 标题（手动排版，不依赖模板里的命名样式）</li>
 *   <li>段落（连续行合并为一段、行间保留软换行）、{@code >} 引用、{@code ---} 分隔线</li>
 *   <li>{@code -} / {@code *} / {@code +} 无序、{@code 1.} 有序列表（按前导空格推嵌套层级，
 *       序号用字面量前缀而非 Word 自动编号 —— 自动编号要写 numbering.xml，收益不抵复杂度）</li>
 *   <li>{@code ```} 围栏代码块（等宽字体 + 浅底纹）</li>
 *   <li>管道表格（识别第二行的 {@code |---|} 分隔行，带边框、首行加粗）</li>
 *   <li>行内 {@code **粗体**}、{@code *斜体*}、{@code `等宽`}、{@code $…$} 公式</li>
 * </ul>
 *
 * <p><strong>公式处理到什么程度：</strong>只做「去掉 {@code $} 定界符 + 把常见 LaTeX 命令
 * 换成 Unicode 符号」——不生成 OMML，Word 里看到的是普通文本。这样线性公式（
 * {@code \alpha \le 1}）可读，而 {@code \frac}、矩阵这类结构性公式会以源码形式保留，
 * 属**已知限制**：真要做原生公式得写 OMML，属后续独立课题。</p>
 *
 * <p><strong>中文字体：</strong>OOXML 的字形按脚本分字体槽，只设 {@code ascii} 会让中文
 * 落到默认字体（部分环境显示为方块）。这里每个 run 都显式写 {@code w:eastAsia}。</p>
 */
public final class MarkdownDocxRenderer {

    /** 正文西文 / 中文 */
    private static final String FONT_ASCII = "Times New Roman";
    private static final String FONT_EA_BODY = "宋体";
    /** 标题中文：黑体 */
    private static final String FONT_EA_HEAD = "黑体";
    /** 代码与行内等宽 */
    private static final String FONT_MONO = "Consolas";

    private static final String COLOR_INK = "1B2B31";
    private static final String COLOR_HEAD = "0B2E36";
    private static final String COLOR_QUOTE = "57727C";
    private static final String COLOR_RULE = "CDE6E3";
    private static final String FILL_CODE = "F4F6F8";
    private static final String COLOR_BORDER = "B9D6D3";

    /** 表格/引用等缩进单位：1 英寸 = 1440 twips */
    private static final int INDENT_UNIT = 360;

    private static final Pattern HEADING = Pattern.compile("^(#{1,4})\\s+(.*)$");
    private static final Pattern BULLET = Pattern.compile("^([-*+])\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^(-{3,}|\\*{3,}|_{3,})$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?$");
    /** 行内标记：粗体 → 等宽 → 公式 → 斜体（顺序即优先级） */
    private static final Pattern INLINE = Pattern.compile(
            "(\\*\\*[^*]+\\*\\*|__[^_]+__|`[^`]+`|\\$[^$]+\\$|\\*[^*]+\\*)");

    private MarkdownDocxRenderer() {
    }

    /** LaTeX 命令 → Unicode。只做线性符号，结构性命令（frac / begin 等）原样保留。 */
    private static final Map<String, String> LATEX_SYMBOLS = new LinkedHashMap<>();

    static {
        LATEX_SYMBOLS.put("\\alpha", "α");
        LATEX_SYMBOLS.put("\\beta", "β");
        LATEX_SYMBOLS.put("\\gamma", "γ");
        LATEX_SYMBOLS.put("\\delta", "δ");
        LATEX_SYMBOLS.put("\\epsilon", "ε");
        LATEX_SYMBOLS.put("\\varepsilon", "ε");
        LATEX_SYMBOLS.put("\\zeta", "ζ");
        LATEX_SYMBOLS.put("\\eta", "η");
        LATEX_SYMBOLS.put("\\theta", "θ");
        LATEX_SYMBOLS.put("\\lambda", "λ");
        LATEX_SYMBOLS.put("\\mu", "μ");
        LATEX_SYMBOLS.put("\\nu", "ν");
        LATEX_SYMBOLS.put("\\xi", "ξ");
        LATEX_SYMBOLS.put("\\pi", "π");
        LATEX_SYMBOLS.put("\\rho", "ρ");
        LATEX_SYMBOLS.put("\\sigma", "σ");
        LATEX_SYMBOLS.put("\\tau", "τ");
        LATEX_SYMBOLS.put("\\phi", "φ");
        LATEX_SYMBOLS.put("\\varphi", "φ");
        LATEX_SYMBOLS.put("\\chi", "χ");
        LATEX_SYMBOLS.put("\\psi", "ψ");
        LATEX_SYMBOLS.put("\\omega", "ω");
        LATEX_SYMBOLS.put("\\Delta", "Δ");
        LATEX_SYMBOLS.put("\\Theta", "Θ");
        LATEX_SYMBOLS.put("\\Lambda", "Λ");
        LATEX_SYMBOLS.put("\\Pi", "Π");
        LATEX_SYMBOLS.put("\\Sigma", "Σ");
        LATEX_SYMBOLS.put("\\Phi", "Φ");
        LATEX_SYMBOLS.put("\\Omega", "Ω");
        LATEX_SYMBOLS.put("\\times", "×");
        LATEX_SYMBOLS.put("\\cdot", "·");
        LATEX_SYMBOLS.put("\\div", "÷");
        LATEX_SYMBOLS.put("\\pm", "±");
        LATEX_SYMBOLS.put("\\mp", "∓");
        LATEX_SYMBOLS.put("\\le", "≤");
        LATEX_SYMBOLS.put("\\leq", "≤");
        LATEX_SYMBOLS.put("\\ge", "≥");
        LATEX_SYMBOLS.put("\\geq", "≥");
        LATEX_SYMBOLS.put("\\ne", "≠");
        LATEX_SYMBOLS.put("\\neq", "≠");
        LATEX_SYMBOLS.put("\\approx", "≈");
        LATEX_SYMBOLS.put("\\equiv", "≡");
        LATEX_SYMBOLS.put("\\propto", "∝");
        LATEX_SYMBOLS.put("\\infty", "∞");
        LATEX_SYMBOLS.put("\\partial", "∂");
        LATEX_SYMBOLS.put("\\sum", "∑");
        LATEX_SYMBOLS.put("\\prod", "∏");
        LATEX_SYMBOLS.put("\\int", "∫");
        LATEX_SYMBOLS.put("\\sqrt", "√");
        LATEX_SYMBOLS.put("\\in", "∈");
        LATEX_SYMBOLS.put("\\notin", "∉");
        LATEX_SYMBOLS.put("\\subset", "⊂");
        LATEX_SYMBOLS.put("\\cup", "∪");
        LATEX_SYMBOLS.put("\\cap", "∩");
        LATEX_SYMBOLS.put("\\forall", "∀");
        LATEX_SYMBOLS.put("\\exists", "∃");
        LATEX_SYMBOLS.put("\\rightarrow", "→");
        LATEX_SYMBOLS.put("\\to", "→");
        LATEX_SYMBOLS.put("\\Rightarrow", "⇒");
        LATEX_SYMBOLS.put("\\leftarrow", "←");
        LATEX_SYMBOLS.put("\\cdots", "⋯");
        LATEX_SYMBOLS.put("\\ldots", "…");
        LATEX_SYMBOLS.put("\\dots", "…");
        LATEX_SYMBOLS.put("\\%", "%");
        LATEX_SYMBOLS.put("\\{", "{");
        LATEX_SYMBOLS.put("\\}", "}");
    }

    /**
     * 渲染一份 Markdown 为 docx 字节。
     *
     * @param markdown Markdown 原文（UTF-8）
     * @param docTitle 文档标题；为空、或原文首行本身就是一级标题时不另加标题
     * @return docx 文件字节
     */
    public static byte[] render(String markdown, String docTitle) throws IOException {
        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<String> lines = splitLines(markdown == null ? "" : markdown);
            if (docTitle != null && !docTitle.isBlank() && !startsWithH1(lines)) {
                emitHeading(doc, 0, docTitle.trim());
            }

            List<String> paragraph = new ArrayList<>();
            int i = 0;
            while (i < lines.size()) {
                String line = lines.get(i);
                String trimmed = line.trim();

                if (trimmed.startsWith("```")) {
                    flushParagraph(doc, paragraph);
                    List<String> code = new ArrayList<>();
                    i++;
                    while (i < lines.size() && !lines.get(i).trim().startsWith("```")) {
                        code.add(lines.get(i));
                        i++;
                    }
                    i++; // 越过收尾围栏
                    emitCodeBlock(doc, code);
                    continue;
                }

                if (trimmed.startsWith("|") && isTableRow(lines, i)) {
                    flushParagraph(doc, paragraph);
                    int j = i;
                    List<String> rows = new ArrayList<>();
                    while (j < lines.size() && lines.get(j).trim().startsWith("|")) {
                        rows.add(lines.get(j).trim());
                        j++;
                    }
                    emitTable(doc, rows);
                    i = j;
                    continue;
                }

                if (trimmed.isEmpty()) {
                    flushParagraph(doc, paragraph);
                    i++;
                    continue;
                }

                if (RULE.matcher(trimmed).matches()) {
                    flushParagraph(doc, paragraph);
                    emitRule(doc);
                    i++;
                    continue;
                }

                Matcher h = HEADING.matcher(trimmed);
                if (h.matches()) {
                    flushParagraph(doc, paragraph);
                    emitHeading(doc, h.group(1).length(), h.group(2).trim());
                    i++;
                    continue;
                }

                if (trimmed.startsWith(">")) {
                    flushParagraph(doc, paragraph);
                    emitQuote(doc, trimmed.replaceFirst("^>+\\s*", ""));
                    i++;
                    continue;
                }

                int indent = indentOf(line);
                Matcher b = BULLET.matcher(trimmed);
                Matcher o = ORDERED.matcher(trimmed);
                if (b.matches()) {
                    flushParagraph(doc, paragraph);
                    emitListItem(doc, "•  ", b.group(2), indent);
                    i++;
                    continue;
                }
                if (o.matches()) {
                    flushParagraph(doc, paragraph);
                    emitListItem(doc, o.group(1) + ". ", o.group(2), indent);
                    i++;
                    continue;
                }

                paragraph.add(trimmed);
                i++;
            }
            flushParagraph(doc, paragraph);

            doc.write(out);
            return out.toByteArray();
        }
    }

    // -----------------------------------------------------------------
    //  块级
    // -----------------------------------------------------------------

    private static void emitHeading(XWPFDocument doc, int level, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(level <= 1 ? 240 : 200);
        p.setSpacingAfter(level <= 1 ? 160 : 120);
        int size = switch (level) {
            case 0, 1 -> 36;   // 18pt
            case 2 -> 30;      // 15pt
            case 3 -> 26;      // 13pt
            default -> 24;     // 12pt
        };
        addInline(p, text, true, size, FONT_EA_HEAD, COLOR_HEAD);
    }

    private static void flushParagraph(XWPFDocument doc, List<String> buf) {
        if (buf.isEmpty()) {
            return;
        }
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.BOTH);
        p.setSpacingBefore(60);
        p.setSpacingAfter(60);
        for (int k = 0; k < buf.size(); k++) {
            // 段内换行是 Markdown 的「软换行」（模型把一句话折成两行），不是硬换行 ——
            // 折成硬回车会让 Word 里出现一堆半行。西文之间补一个空格（否则 two 与 words
            // 会黏成一个词），中文之间不补（中文字之间本来就没有空格）。
            if (k > 0 && needsSpaceBetween(buf.get(k - 1), buf.get(k))) {
                p.createRun().setText(" ");
            }
            addInline(p, buf.get(k), false, 22, FONT_EA_BODY, COLOR_INK);
        }
        buf.clear();
    }

    private static void emitListItem(XWPFDocument doc, String marker, String text, int indent) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.LEFT);
        p.setSpacingBefore(30);
        p.setSpacingAfter(30);
        p.setIndentationLeft(INDENT_UNIT * (indent + 1));
        XWPFRun bullet = newRun(p, FONT_EA_BODY, 22, false, false, COLOR_INK);
        bullet.setText(marker);
        addInline(p, text, false, 22, FONT_EA_BODY, COLOR_INK);
    }

    private static void emitQuote(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(60);
        p.setSpacingAfter(60);
        p.setIndentationLeft(INDENT_UNIT);
        p.setIndentationRight(INDENT_UNIT);
        shade(p, "F7FAFA");
        leftBorder(p, COLOR_BORDER);
        addInline(p, text, false, 21, FONT_EA_BODY, COLOR_QUOTE);
    }

    private static void emitRule(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(120);
        p.setSpacingAfter(120);
        bottomBorder(p, COLOR_RULE, 6);
    }

    private static void emitCodeBlock(XWPFDocument doc, List<String> code) {
        if (code.isEmpty()) {
            return;
        }
        for (int k = 0; k < code.size(); k++) {
            XWPFParagraph p = doc.createParagraph();
            p.setAlignment(ParagraphAlignment.LEFT);
            p.setSpacingBefore(k == 0 ? 100 : 0);
            p.setSpacingAfter(k == code.size() - 1 ? 100 : 0);
            p.setIndentationLeft(INDENT_UNIT);
            shade(p, FILL_CODE);
            XWPFRun r = newRun(p, FONT_MONO, 19, false, false, COLOR_INK);
            r.setText(code.get(k));
        }
    }

    /**
     * 管道表格：首行为表头，第二行是分隔行则跳过，其余为数据行。列数以表头为准，缺列补空。
     */
    private static void emitTable(XWPFDocument doc, List<String> rows) {
        List<List<String>> cells = new ArrayList<>();
        int skip = -1;
        for (int k = 0; k < rows.size(); k++) {
            if (k == 1 && TABLE_DIVIDER.matcher(rows.get(k)).matches()) {
                skip = k;
                continue;
            }
            cells.add(splitRow(rows.get(k)));
        }
        if (cells.isEmpty()) {
            return;
        }
        int cols = 0;
        for (List<String> r : cells) {
            cols = Math.max(cols, r.size());
        }
        if (cols == 0) {
            return;
        }
        XWPFTable table = doc.createTable(cells.size(), cols);
        table.setWidth("100%");
        tableBorders(table);
        for (int r = 0; r < cells.size(); r++) {
            XWPFTableRow row = table.getRow(r);
            List<String> line = cells.get(r);
            for (int c = 0; c < cols; c++) {
                XWPFTableCell cell = row.getCell(c);
                if (cell == null) {
                    cell = row.createCell();
                }
                String text = c < line.size() ? line.get(c) : "";
                XWPFParagraph p = cell.getParagraphs().isEmpty()
                        ? cell.addParagraph() : cell.getParagraphs().get(0);
                p.setSpacingBefore(20);
                p.setSpacingAfter(20);
                addInline(p, text, r == 0 && skip >= 0, 21, FONT_EA_BODY, COLOR_INK);
            }
        }
        // 表格后面补一个空段，否则紧跟的正文会黏进表格
        doc.createParagraph();
    }

    // -----------------------------------------------------------------
    //  行内
    // -----------------------------------------------------------------

    /** 把一行按行内标记切成若干 run 写进段落 */
    private static void addInline(XWPFParagraph p, String text, boolean bold,
                                  int size, String eaFont, String color) {
        String rest = text == null ? "" : text;
        Matcher m = INLINE.matcher(rest);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                newRun(p, eaFont, size, bold, false, color).setText(rest.substring(last, m.start()));
            }
            String token = m.group();
            if (token.startsWith("**") || token.startsWith("__")) {
                newRun(p, eaFont, size, true, false, color)
                        .setText(token.substring(2, token.length() - 2));
            } else if (token.startsWith("`")) {
                newRun(p, FONT_MONO, size - 2, bold, false, color)
                        .setText(token.substring(1, token.length() - 1));
            } else if (token.startsWith("$")) {
                newRun(p, eaFont, size, bold, false, color)
                        .setText(latexToUnicode(token.substring(1, token.length() - 1)));
            } else {
                newRun(p, eaFont, size, bold, true, color)
                        .setText(token.substring(1, token.length() - 1));
            }
            last = m.end();
        }
        if (last < rest.length()) {
            newRun(p, eaFont, size, bold, false, color).setText(rest.substring(last));
        }
        if (rest.isEmpty()) {
            newRun(p, eaFont, size, bold, false, color).setText("");
        }
    }

    /** {@code $…$} 内的 LaTeX → Unicode。长命令先替换，避免 {@code \le} 吃掉 {@code \leq} 的前缀。 */
    static String latexToUnicode(String s) {
        String out = s;
        List<String> keys = new ArrayList<>(LATEX_SYMBOLS.keySet());
        keys.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String key : keys) {
            String symbol = LATEX_SYMBOLS.get(key);
            // 带花括号的写法（\sqrt{2}）要**连闭合花括号一起**换，否则会留下一个孤零零的
            // }（\sqrt{2} 变成 √2}）—— 这是渲染后 Word 里最容易看出来的脏字。
            // 只吃最内一层：里面若还有花括号（\sqrt{\alpha+1}）由后续轮次处理。
            out = out.replaceAll(
                    Pattern.quote(key) + "\\{([^{}]*)\\}",
                    Matcher.quoteReplacement(symbol) + "$1");
            // 不带花括号的裸命令（\alpha）
            out = out.replace(key, symbol);
        }
        return out;
    }

    // -----------------------------------------------------------------
    //  底层工具
    // -----------------------------------------------------------------

    private static List<String> splitLines(String markdown) {
        String normalized = markdown.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            out.add(line);
        }
        return out;
    }

    private static boolean startsWithH1(List<String> lines) {
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                continue;
            }
            return HEADING.matcher(t).matches() && t.startsWith("# ");
        }
        return false;
    }

    /** 前导空格数 → 嵌套层级（2 空格一层，最多 4 层） */
    private static int indentOf(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return Math.min(spaces / 2, 4);
    }

    /**
     * 软换行的两侧要不要补空格：两侧只要有一边是中日韩字符就不补。
     *
     * <p>判据取「前一行最后一个字符 / 后一行第一个字符」，两者都是 CJK 时不补。这样
     * {@code 第一行 + 第二行} 合成 {@code 第一行第二行}（中文段落的常态），而
     * {@code machine + learning} 合成 {@code machine learning}（西文不能被黏成一个词）。</p>
     */
    private static boolean needsSpaceBetween(String prev, String next) {
        if (prev.isEmpty() || next.isEmpty()) {
            return false;
        }
        char left = prev.charAt(prev.length() - 1);
        char right = next.charAt(0);
        return !isCjk(left) && !isCjk(right);
    }

    /** CJK 统一表意文字 + 中文标点（全角区间）+ 假名。判据粗一点没关系：只影响软换行处补不补空格。 */
    private static boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)     // 统一表意文字
                || (c >= 0x3400 && c <= 0x4DBF) // 扩展 A
                || (c >= 0x3000 && c <= 0x303F) // 中文标点
                || (c >= 0xFF00 && c <= 0xFFEF) // 全角字符
                || (c >= 0x3040 && c <= 0x30FF); // 假名
    }

    private static boolean isTableRow(List<String> lines, int index) {
        if (index + 1 >= lines.size()) {
            return false;
        }
        return TABLE_DIVIDER.matcher(lines.get(index + 1).trim()).matches();
    }

    /** 拆一行管道表格：去掉首尾空列，单元格内不做行内解析之外的清洗 */
    private static List<String> splitRow(String row) {
        String s = row.trim();
        if (s.startsWith("|")) {
            s = s.substring(1);
        }
        if (s.endsWith("|")) {
            s = s.substring(0, s.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String cell : s.split("\\|", -1)) {
            out.add(cell.trim());
        }
        return out;
    }

    private static XWPFRun newRun(XWPFParagraph p, String eaFont, int sizeHalfPt,
                                  boolean bold, boolean italic, String color) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT_MONO.equals(eaFont) ? FONT_MONO : FONT_ASCII);
        r.setFontSize(Math.max(sizeHalfPt, 8));
        r.setBold(bold);
        r.setItalic(italic);
        if (color != null) {
            r.setColor(color);
        }
        applyEastAsia(r, eaFont);
        return r;
    }

    /**
     * 显式写 {@code w:eastAsia} 字体槽 —— 只设 {@code ascii} 时中文会落到默认字体，
     * 在没装对应字体的环境里显示成方块，看起来像「乱码」。
     */
    private static void applyEastAsia(XWPFRun r, String font) {
        CTRPr pr = r.getCTR().isSetRPr() ? r.getCTR().getRPr() : r.getCTR().addNewRPr();
        // 该 schema 的 rFonts 只暴露数组式存取（getRFontsList / addNewRFonts），
        // setFontFamily 已经写过一次，这里复用已有的那个，避免出现两个 rFonts 元素
        CTFonts fonts = pr.sizeOfRFontsArray() > 0 ? pr.getRFontsArray(0) : pr.addNewRFonts();
        fonts.setEastAsia(font);
    }

    private static CTPPr pPrOf(XWPFParagraph p) {
        return p.getCTP().isSetPPr() ? p.getCTP().getPPr() : p.getCTP().addNewPPr();
    }

    private static void shade(XWPFParagraph p, String fill) {
        CTPPr pr = pPrOf(p);
        CTShd shd = pr.isSetShd() ? pr.getShd() : pr.addNewShd();
        shd.setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STShd.CLEAR);
        shd.setColor("auto");
        shd.setFill(fill);
    }

    private static void leftBorder(XWPFParagraph p, String color) {
        CTPPr pr = pPrOf(p);
        CTPBdr bdr = pr.isSetPBdr() ? pr.getPBdr() : pr.addNewPBdr();
        var left = bdr.isSetLeft() ? bdr.getLeft() : bdr.addNewLeft();
        left.setVal(STBorder.SINGLE);
        left.setSz(BigInteger.valueOf(12));
        left.setColor(color);
        left.setSpace(BigInteger.valueOf(4));
    }

    private static void bottomBorder(XWPFParagraph p, String color, int sz) {
        CTPPr pr = pPrOf(p);
        CTPBdr bdr = pr.isSetPBdr() ? pr.getPBdr() : pr.addNewPBdr();
        var bottom = bdr.isSetBottom() ? bdr.getBottom() : bdr.addNewBottom();
        bottom.setVal(STBorder.SINGLE);
        bottom.setSz(BigInteger.valueOf(sz));
        bottom.setColor(color);
        bottom.setSpace(BigInteger.valueOf(1));
    }

    private static void tableBorders(XWPFTable table) {
        CTTblPr pr = table.getCTTbl().getTblPr() != null
                ? table.getCTTbl().getTblPr() : table.getCTTbl().addNewTblPr();
        CTTblBorders borders = pr.isSetTblBorders() ? pr.getTblBorders() : pr.addNewTblBorders();
        setBorder(borders.isSetTop() ? borders.getTop() : borders.addNewTop());
        setBorder(borders.isSetLeft() ? borders.getLeft() : borders.addNewLeft());
        setBorder(borders.isSetBottom() ? borders.getBottom() : borders.addNewBottom());
        setBorder(borders.isSetRight() ? borders.getRight() : borders.addNewRight());
        setBorder(borders.isSetInsideH() ? borders.getInsideH() : borders.addNewInsideH());
        setBorder(borders.isSetInsideV() ? borders.getInsideV() : borders.addNewInsideV());
    }

    private static void setBorder(CTBorder border) {
        border.setVal(STBorder.SINGLE);
        border.setSz(BigInteger.valueOf(4));
        border.setColor(COLOR_BORDER);
    }
}
