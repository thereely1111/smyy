#!/usr/bin/env python3
"""
jc-4-md转word — 通用 Markdown → 泰迪图书排版 .docx 转换器

用法：python md2docx.py <md路径> <章号> <版本号> [输出目录]
"""

import re
import sys
import zipfile
from pathlib import Path

try:
    from docx import Document
    from docx.shared import Pt, Cm, RGBColor
    from docx.oxml.ns import qn, nsdecls
    from docx.oxml import parse_xml, OxmlElement
except ImportError:
    print("错误: 请先运行 install.py 安装 python-docx")
    sys.exit(1)

# ── 公式转换（可选依赖，未安装时降级为纯文本）──
try:
    from latex2mathml.converter import convert as latex2mathml_convert
    from lxml import etree
    HAS_FORMULA = True
except ImportError:
    HAS_FORMULA = False

# ==================== 排版常量（模板 样式.docx 已定义绝大部分样式） ====================

# 以下常量仅用于模板未覆盖的场景（如代码等宽字体、代码底纹等）
FONT_CODE = "Consolas"   # 仅用于行内代码 run（正文中的 `code`）
SZ_CODE = Pt(9)

# 行距（磅）— 仅代码块用
LINE_CODE = Pt(14)

CODE_BG = "F5F5F5"

# A4 页面尺寸（厘米）— 兜底确保，模板已设 A4
PAGE_W = Cm(21.0)
PAGE_H = Cm(29.7)
MARGIN_TB = Cm(2.54)
MARGIN_LR = Cm(3.18)

# 图片最大宽度
IMG_MAX_WIDTH = Cm(14.65)

# 首行缩进（0.74cm ≈ 2字符）
INDENT = Cm(0.74)

# ── 公式 OMML 命名空间 ──
M_OMATH = "http://schemas.openxmlformats.org/officeDocument/2006/math"

# ==================== MathML → OMML 转换引擎 ====================

if HAS_FORMULA:
    # MathML → OMML 标签映射
    _MATHML_TO_OMML = {
        "mfrac": "f", "msub": "sSub", "msup": "sSup", "msubsup": "sSubSup",
        "mover": "acc", "munder": "acc", "munderover": "nary",
        "mstyle": None, "mphantom": None, "mpadded": None,
        "menclose": None, "merror": None,
    }
    _FRAC_CHILDREN = {"num", "den"}
    _SUBSUP_CHILDREN = {"e", "sub", "sup"}
    _ACC_CHILDREN = {"chr", "accPr", "e"}
    _DISPLAY_MODE = "inline"

    def _omml_attrs(elem) -> str:
        return "".join(f' m:{k}="{v}"' for k, v in elem.attrib.items())

    def _omml_escape_text(text: str) -> str:
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    def _omml_render_children(elem) -> str:
        result = (elem.text or "")
        for c in elem:
            result += _omml_convert(c)
            if c.tail:
                result += c.tail
        return result

    def _omml_render_arg_wrapped(elem) -> str:
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        if tag == "mrow":
            return _omml_render_children(elem)
        return _omml_convert(elem)

    def _omml_render_with_nary_merge(mrow_elem) -> str:
        children = list(mrow_elem)
        result = (mrow_elem.text or "")
        i = 0
        while i < len(children):
            c = children[i]
            c_tag = c.tag.split("}")[-1] if "}" in c.tag else c.tag
            nary_op = None
            if c_tag in ("msubsup", "munderover") and list(c) and list(c)[0].tag.endswith("mo"):
                op_text = list(c)[0].text or ""
                if op_text in ("∑", "∫", "∏", "∐"):
                    nary_op = op_text
            if nary_op:
                sub_children = list(c)
                attrs = _omml_attrs(c)
                lim_loc = "undOvr" if _DISPLAY_MODE == "block" else "subSup"
                nary = f'<m:naryPr><m:chr m:val="{nary_op}"/><m:limLoc m:val="{lim_loc}"/><m:grow m:val="1"/></m:naryPr>'
                nary += f"<m:sub>{_omml_render_arg_wrapped(sub_children[1])}</m:sub>" if len(sub_children) > 1 else ""
                nary += f"<m:sup>{_omml_render_arg_wrapped(sub_children[2])}</m:sup>" if len(sub_children) > 2 else ""
                e_content = ""
                j = i + 1
                while j < len(children):
                    e_content += _omml_convert(children[j])
                    j += 1
                if e_content:
                    nary += f"<m:e>{e_content}</m:e>"
                else:
                    nary += '<m:e><m:r><m:t/></m:r></m:e>'
                result += f"<m:nary{attrs}>{nary}</m:nary>"
                if c.tail:
                    result += c.tail
                i = j
                continue
            result += _omml_convert(c)
            if c.tail:
                result += c.tail
            i += 1
        return result

    def _omml_convert(elem) -> str:
        """递归将 MathML 元素转为 Word OMML XML 字符串"""
        tag = elem.tag.split("}")[-1] if "}" in elem.tag else elem.tag
        children = list(elem)

        if tag == "mfrac":
            attrs = _omml_attrs(elem)
            inner = f"<m:num>{_omml_convert(children[0])}</m:num>" if children else ""
            inner += f"<m:den>{_omml_convert(children[1])}</m:den>" if len(children) > 1 else ""
            return f"<m:f{attrs}>{inner}</m:f>"

        if tag == "msub":
            attrs = _omml_attrs(elem)
            inner = f"<m:e>{_omml_convert(children[0])}</m:e>" if children else ""
            inner += f"<m:sub>{_omml_convert(children[1])}</m:sub>" if len(children) > 1 else ""
            return f"<m:sSub{attrs}>{inner}</m:sSub>"

        if tag == "msup":
            attrs = _omml_attrs(elem)
            inner = f"<m:e>{_omml_convert(children[0])}</m:e>" if children else ""
            inner += f"<m:sup>{_omml_convert(children[1])}</m:sup>" if len(children) > 1 else ""
            return f"<m:sSup{attrs}>{inner}</m:sSup>"

        if tag == "msqrt":
            attrs = _omml_attrs(elem)
            inner = _omml_render_children(elem)
            radPr = '<m:radPr><m:degHide m:val="1"/></m:radPr>'
            return f"<m:rad{attrs}>{radPr}<m:deg><m:r><m:t/></m:r></m:deg><m:e>{inner}</m:e></m:rad>"

        if tag == "msubsup":
            if _DISPLAY_MODE == "block" and children and children[0].tag.endswith("mo"):
                op_text = children[0].text or ""
                if op_text in ("∑", "∫", "∏", "∐"):
                    attrs = _omml_attrs(elem)
                    nary_str = f'<m:naryPr><m:chr m:val="{op_text}"/><m:limLoc m:val="undOvr"/><m:grow m:val="1"/></m:naryPr>'
                    inner = nary_str
                    inner += f"<m:sub>{_omml_render_arg_wrapped(children[1])}</m:sub>" if len(children) > 1 else ""
                    inner += f"<m:sup>{_omml_render_arg_wrapped(children[2])}</m:sup>" if len(children) > 2 else ""
                    inner += '<m:e><m:r><m:t/></m:r></m:e>'
                    return f"<m:nary{attrs}>{inner}</m:nary>"
            attrs = _omml_attrs(elem)
            inner = f"<m:e>{_omml_convert(children[0])}</m:e>" if children else ""
            inner += f"<m:sub>{_omml_convert(children[1])}</m:sub>" if len(children) > 1 else ""
            inner += f"<m:sup>{_omml_convert(children[2])}</m:sup>" if len(children) > 2 else ""
            return f"<m:sSubSup{attrs}>{inner}</m:sSubSup>"

        if tag == "mover":
            attrs = _omml_attrs(elem)
            chr_val = ""
            if len(children) >= 2 and children[1].text:
                chr_val = children[1].text
            acc_str = f'<m:accPr><m:chr m:val="{chr_val}"/></m:accPr>' if chr_val else ""
            inner = acc_str + (f"<m:e>{_omml_convert(children[0])}</m:e>" if children else "")
            return f"<m:acc{attrs}>{inner}</m:acc>"

        if tag == "munderover":
            attrs = _omml_attrs(elem)
            chr_val = children[0].text if children and children[0].text else ""
            if _DISPLAY_MODE == "block" and chr_val in ("∑", "∫", "∏", "∐"):
                nary_str = f'<m:naryPr><m:chr m:val="{chr_val}"/><m:limLoc m:val="undOvr"/><m:grow m:val="1"/></m:naryPr>'
                inner = nary_str
                if len(children) >= 3:
                    inner += f"<m:sub>{_omml_render_arg_wrapped(children[1])}</m:sub>"
                    inner += f"<m:sup>{_omml_render_arg_wrapped(children[2])}</m:sup>"
                inner += '<m:e><m:r><m:t/></m:r></m:e>'
                return f"<m:nary{attrs}>{inner}</m:nary>"
            # 非 nary 操作符：使用 limLow
            inner = f"<m:e>{_omml_convert(children[0])}</m:e>" if children else ""
            if len(children) >= 3:
                inner += f"<m:lim>{_omml_render_arg_wrapped(children[1])}</m:lim>"
                inner += f"<m:lim>{_omml_render_arg_wrapped(children[2])}</m:lim>"
            return f"<m:limUpp{attrs}>{inner}</m:limUpp>"

        if tag == "munder":
            attrs = _omml_attrs(elem)
            chr_val = children[0].text if children and children[0].text else ""
            if _DISPLAY_MODE == "block" and chr_val in ("∑", "∫", "∏", "∐"):
                nary_str = f'<m:naryPr><m:chr m:val="{chr_val}"/><m:limLoc m:val="undOvr"/><m:grow m:val="1"/></m:naryPr>'
                inner = nary_str
                if len(children) >= 2:
                    inner += f"<m:sub>{_omml_render_arg_wrapped(children[1])}</m:sub>"
                inner += '<m:e><m:r><m:t/></m:r></m:e>'
                return f"<m:nary{attrs}>{inner}</m:nary>"
            # nary 操作符在 inline 模式：使用普通下标
            if chr_val in ("∑", "∫", "∏", "∐"):
                inner = f"<m:e>{_omml_convert(children[0])}</m:e>" if children else ""
                if len(children) >= 2:
                    inner += f"<m:sub>{_omml_render_arg_wrapped(children[1])}</m:sub>"
                return f"<m:sSub{attrs}>{inner}</m:sSub>"
            # 非 nary 操作符（如 \lim）：使用 limLow
            inner = f"<m:e>{_omml_convert(children[0])}</m:e>" if children else ""
            if len(children) >= 2:
                inner += f"<m:lim>{_omml_render_arg_wrapped(children[1])}</m:lim>"
            return f"<m:limLow{attrs}>{inner}</m:limLow>"

        if tag == "mtext":
            attrs = _omml_attrs(elem)
            text = elem.text or ""
            return f"<m:r{attrs}><m:t>{_omml_escape_text(text)}</m:t></m:r>"

        # 矩阵: mtable/mtr/mtd → <m:m>/<m:mr>/<m:e>
        if tag == "mtable":
            attrs = _omml_attrs(elem)
            return f"<m:m{attrs}>{_omml_render_children(elem)}</m:m>"
        if tag == "mtr":
            attrs = _omml_attrs(elem)
            return f"<m:mr{attrs}>{_omml_render_children(elem)}</m:mr>"
        if tag == "mtd":
            attrs = _omml_attrs(elem)
            return f"<m:e{attrs}>{_omml_render_children(elem)}</m:e>"

        # 跳过容器
        if tag in ("mrow", "math", "semantics", "mstyle", "mphantom", "mpadded"):
            if tag == "mrow":
                if _DISPLAY_MODE == "block":
                    return _omml_render_with_nary_merge(elem)
                return _omml_render_children(elem)
            return _omml_render_children(elem)

        # 叶子节点
        if tag in ("mi", "mn", "mo"):
            text = elem.text or ""
            return f"<m:r><m:t>{_omml_escape_text(text)}</m:t></m:r>"

        return _omml_render_children(elem)

    def _escape_xml_ampersands(s: str) -> str:
        """转义 MathML 文本中未转义的 &"""
        return re.sub(
            r'&(?!(?:#\d+|#x[0-9a-fA-F]+|[a-zA-Z_:][a-zA-Z0-9._-]*;))',
            '&amp;', s
        )

    def _latex_to_omml(latex: str, display: str = "inline") -> str:
        """LaTeX → OMML XML 字符串"""
        global _DISPLAY_MODE
        _DISPLAY_MODE = display
        mathml = _escape_xml_ampersands(latex2mathml_convert(latex.strip(), display=display))
        root = etree.fromstring(mathml.encode())
        return _omml_convert(root)


def _extract_formulas(md_text: str):
    """提取并保护公式，返回 (protected_md, blocks_dict, inlines_dict)"""
    blocks, inlines = {}, {}

    def _save_block(m):
        k = f"ZZBLOCK{len(blocks)}ZZ"
        blocks[k] = m.group(1).strip()
        return k

    def _save_inline(m):
        k = f"ZZINLINE{len(inlines)}ZZ"
        inlines[k] = m.group(1).strip()
        return k

    protected = re.sub(r"\$\$(.+?)\$\$", _save_block, md_text, flags=re.DOTALL)
    protected = re.sub(r"(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)", _save_inline, protected)
    return protected, blocks, inlines


def _inject_omml(docx_path: str, blocks: dict, inlines: dict):
    """向已保存的 .docx 中注入 OMML 公式并修复行距"""
    if not blocks and not inlines:
        return

    with zipfile.ZipFile(docx_path, "r") as z:
        files = {n: z.read(n) for n in z.namelist()}

    xml_str = files["word/document.xml"].decode("utf-8")
    W_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    X_NS = "http://www.w3.org/XML/1998/namespace"

    # 块公式：简单占位符替换
    for fid, latex in blocks.items():
        inner = _latex_to_omml(latex, display="block")
        oml = f"<m:oMathPara><m:oMath>{inner}</m:oMath></m:oMathPara>"
        xml_str = xml_str.replace(f"<w:t>{fid}</w:t>", oml)
        xml_str = xml_str.replace(f'<w:t xml:space="preserve">{fid}</w:t>', oml)

    # === 修复块公式：将 m:oMathPara 从 w:r 内移到 w:p 直接子元素 ===
    tree = etree.fromstring(xml_str.encode())
    for r in list(tree.iter(f"{{{W_NS}}}r")):
        omp = r.find(f"{{{M_OMATH}}}oMathPara")
        if omp is not None:
            p = r.getparent()
            # 将 oMathPara 移到 w:p 下
            r.remove(omp)
            p.append(omp)
            # 删除已清空的包装 w:r
            if len(list(r)) == 0 and (r.text or "").strip() == "":
                tail = r.tail
                prev = r.getprevious()
                p.remove(r)
                if tail and prev is not None:
                    prev.tail = (prev.tail or "") + tail
    # 块公式段落：去除首行缩进并居中
    for p in tree.iter(f"{{{W_NS}}}p"):
        # 检查是否有 m:oMathPara（已经移出的或 lxml 自动修复的）
        omp_direct = p.find(f"{{{M_OMATH}}}oMathPara")
        if omp_direct is not None:
            pPr = p.find(f"{{{W_NS}}}pPr")
            if pPr is not None:
                ind = pPr.find(f"{{{W_NS}}}ind")
                if ind is not None:
                    # 去除首行缩进
                    fl_attr = f"{{{W_NS}}}firstLine"
                    if fl_attr in ind.attrib:
                        del ind.attrib[fl_attr]
                    if not ind.attrib:
                        pPr.remove(ind)
                # 段落居中
                jc = pPr.find(f"{{{W_NS}}}jc")
                if jc is None:
                    jc = etree.SubElement(pPr, f"{{{W_NS}}}jc")
                jc.set(f"{{{W_NS}}}val", "center")
    xml_str = etree.tostring(tree, encoding="unicode")

    # 行内公式：用 lxml 拆分 <w:t> 节点
    if inlines:
        tree = etree.fromstring(xml_str.encode())
        for fid, latex in inlines.items():
            inner = _latex_to_omml(latex, display="inline")
            omml_elem = etree.fromstring(
                f'<m:oMath xmlns:m="{M_OMATH}">{inner}</m:oMath>'.encode()
            )
            for t in tree.iter(f"{{{W_NS}}}t"):
                if t.text and fid in t.text:
                    parts = t.text.split(fid, 1)
                    run = t.getparent()
                    if parts[0]:
                        t.text = parts[0]
                    else:
                        run.remove(t)
                    run.append(omml_elem)
                    if len(parts) > 1 and parts[1]:
                        new_r = etree.SubElement(run.getparent(), f"{{{W_NS}}}r")
                        new_t = etree.SubElement(new_r, f"{{{W_NS}}}t")
                        new_t.set(f"{{{X_NS}}}space", "preserve")
                        new_t.text = parts[1]
                    break
        xml_str = etree.tostring(tree, encoding="unicode")

    # 修复行距：含公式的段落 → auto
    tree = etree.fromstring(xml_str.encode())
    for p in tree.iter(f"{{{W_NS}}}p"):
        has_omml = p.find(f"{{{M_OMATH}}}oMathPara") is not None
        if not has_omml:
            for r in p.iter(f"{{{W_NS}}}r"):
                if any(c.tag == f"{{{M_OMATH}}}oMath" for c in r):
                    has_omml = True
                    break
        if has_omml:
            pPr = p.find(f"{{{W_NS}}}pPr")
            if pPr is None:
                pPr = etree.SubElement(p, f"{{{W_NS}}}pPr")
            # 查找或创建 spacing
            sp = pPr.find(f"{{{W_NS}}}spacing")
            if sp is None:
                sp = etree.SubElement(pPr, f"{{{W_NS}}}spacing")
            sp.set(f"{{{W_NS}}}lineRule", "auto")
            sp.set(f"{{{W_NS}}}line", "600")
    xml_str = etree.tostring(tree, encoding="unicode")

    files["word/document.xml"] = xml_str.encode("utf-8")

    # 重新保存
    with zipfile.ZipFile(docx_path, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in files.items():
            z.writestr(name, data)

    # 统计输出
    omp = xml_str.count("<m:oMathPara")
    om = xml_str.count("<m:oMath")
    left = xml_str.count("ZZBLOCK") + xml_str.count("ZZINLINE")
    acc = xml_str.count("<m:acc")
    f_frac = xml_str.count("<m:f>")
    nary = xml_str.count("<m:nary")
    radPr = xml_str.count("<m:radPr>")
    line_auto = xml_str.count('lineRule="auto"')
    print(f"公式: 块={omp}  行内={om - omp}  残留={left}")
    print(f"OMML: acc={acc} f={f_frac} nary={nary} radPr={radPr} 行距auto={line_auto}")

# ==================== 样式初始化 ====================

def init_styles(doc: Document):
    """去除模板 Heading 样式自带的多级列表编号（章节编号由 MD 文字提供，避免重复）"""
    for level in range(1, 5):
        s = doc.styles[f"Heading {level}"]
        pPr = s.element.find(qn("w:pPr"))
        if pPr is not None:
            numPr = pPr.find(qn("w:numPr"))
            if numPr is not None:
                pPr.remove(numPr)


# ==================== 行内格式解析 ====================

def parse_runs(text: str) -> list:
    """解析 Markdown 行内格式，返回 run 描述列表"""
    runs = []
    remaining = text
    while remaining:
        earliest = None
        # Inline code: `...`
        m = re.search(r"`([^`]+)`", remaining)
        if m:
            earliest = ("code", m.group(1), m.start(), m.end())
        # Bold: **...** or __...__
        m = re.search(r"\*\*(.+?)\*\*|__(.+?)__", remaining)
        if m:
            inner = m.group(1) or m.group(2)
            if not earliest or m.start() < earliest[2]:
                earliest = ("bold", inner, m.start(), m.end())
        # Italic: *...* or _..._
        m = re.search(r"\*(.+?)\*|_(.+?)_", remaining)
        if m:
            inner = m.group(1) or m.group(2)
            if not earliest or m.start() < earliest[2]:
                earliest = ("italic", inner, m.start(), m.end())
        if not earliest:
            runs.append({"text": remaining})
            break
        ttype, inner, start, end = earliest
        if start > 0:
            runs.append({"text": remaining[:start]})
        r = {"text": inner}
        if ttype == "code":
            r["code"] = True
        elif ttype == "bold":
            r["bold"] = True
        elif ttype == "italic":
            r["italic"] = True
        runs.append(r)
        remaining = remaining[end:]
    return [r for r in runs if "text" in r and r["text"] is not None]


def add_runs(paragraph, runs: list):
    """向段落添加格式化文本。模板已通过段落样式提供字体/字号，仅代码 run 需手动设等宽字体"""
    for r in runs:
        is_code = r.get("code", False)
        run = paragraph.add_run(r["text"])
        run.bold = r.get("bold", False)
        run.italic = r.get("italic", False)
        if is_code:
            run.font.name = FONT_CODE
            run.font.size = SZ_CODE


# ==================== 边框工具 ====================

def set_cell_border(cell, **kwargs):
    """设置单元格边框

    支持两种传参方式：
    1. 字典：set_cell_border(cell, top={"sz": "12", "val": "single", "color": "000000"})
    2. 简化：set_cell_border(cell, top="12")  # 默认 single, color=000000
    """
    tc = cell._tc
    tcPr = tc.get_or_add_tcPr()
    tcBorders = parse_xml(f'<w:tcBorders {nsdecls("w")}></w:tcBorders>')
    for edge in ("top", "left", "bottom", "right"):
        if edge in kwargs:
            arg = kwargs[edge]
            if isinstance(arg, dict):
                sz = str(arg.get("sz", "4"))
                val = arg.get("val", "single")
                color = arg.get("color", "000000")
            else:
                sz = str(arg)
                val = "single"
                color = kwargs.get(edge + "_color", "000000")
            element = parse_xml(
                f'<w:{edge} {nsdecls("w")} w:val="{val}" w:sz="{sz}" w:color="{color}"/>'
            )
            tcBorders.append(element)
    tcPr.append(tcBorders)


def set_para_border(paragraph, top=None, bottom=None, left=None, right=None, color="999999", sz=2):
    """设置段落边框"""
    pPr = paragraph._p.get_or_add_pPr()
    pBdr = parse_xml(f'<w:pBdr {nsdecls("w")}></w:pBdr>')
    for edge, val in [("top", top), ("bottom", bottom), ("left", left), ("right", right)]:
        if val:
            el = parse_xml(
                f'<w:{edge} {nsdecls("w")} w:val="single" w:sz="{sz}" w:space="1" w:color="{color}"/>'
            )
            pBdr.append(el)
    pPr.append(pBdr)


def set_shading(paragraph, color):
    """设置段落底色"""
    pPr = paragraph._p.get_or_add_pPr()
    shd = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{color}" w:val="clear"/>')
    pPr.append(shd)


# ==================== 元素工厂 ====================

def add_body(doc: Document, text: str, no_indent=False):
    """添加正文段落（模板 书写正文 样式：1.5倍行距、首行缩进）"""
    runs = parse_runs(text)
    para = doc.add_paragraph(style='书写正文')
    para.paragraph_format.space_before = Pt(0)
    para.paragraph_format.space_after = Pt(0)
    if no_indent:
        para.paragraph_format.first_line_indent = Pt(0)
    else:
        # 显式设 first_line_indent：跨引用逻辑依赖此属性识别正文段落
        para.paragraph_format.first_line_indent = INDENT
    if runs:
        add_runs(para, runs)
    else:
        para.add_run(text)
    return para


def add_heading(doc: Document, level: int, text: str):
    """添加标题。模板 Heading 1~4 样式已定义好字体/字号/行距/段间距"""
    para = doc.add_heading(text, level=level)
    # 禁止标题编号显示为项目列表符号
    pPr = para._element.get_or_add_pPr()
    numPr = pPr.find(qn("w:numPr"))
    if numPr is not None:
        pPr.remove(numPr)
    for run in para.runs:
        run.font.color.rgb = RGBColor(0, 0, 0)
        run.font.italic = False
    return para


def add_code_caption(doc: Document, chapter_num: int, code_num: int, desc: str = ""):
    """添加代码题注（模板 Caption 样式：黑体、10pt、居中、加粗）"""
    text = f"代码{chapter_num}-{code_num}"
    if desc:
        text += f" {desc}"
    para = doc.add_paragraph(style='Caption')
    run = para.add_run(text)
    run.bold = True
    return para


def add_blockquote(doc: Document, lines: list):
    """添加引用/提示词块（单列表格法，带四边框）"""
    if not lines:
        return

    table = doc.add_table(rows=1, cols=1)
    # 设置单元格四边框
    cell = table.rows[0].cells[0]
    cell.text = ""
    set_cell_border(cell,
        top={"sz": "4", "val": "single", "color": "999999"},
        bottom={"sz": "4", "val": "single", "color": "999999"},
        left={"sz": "4", "val": "single", "color": "999999"},
        right={"sz": "4", "val": "single", "color": "999999"})

    # 清除单元格默认段落
    pPr = cell.paragraphs[0]._element.getparent()
    for p in cell.paragraphs:
        p._element.getparent().remove(p._element)

    for line in lines:
        p = cell.add_paragraph()
        p.style = doc.styles['书写正文']
        p.paragraph_format.first_line_indent = Pt(0)
        p.paragraph_format.space_before = Pt(2)
        p.paragraph_format.space_after = Pt(2)
        runs = parse_runs(line)
        if runs:
            add_runs(p, runs)
        else:
            p.add_run(line)


def add_code_box(doc: Document, lines: list, chapter_num=0, code_num=0, caption_desc="", in_out=False):
    """添加代码块（单列表格法，模板 代码样式 + 代码字体 样式）"""
    if chapter_num and code_num:
        add_code_caption(doc, chapter_num, code_num, caption_desc)

    table = doc.add_table(rows=1, cols=1)
    table.style = doc.styles['代码样式']
    cell = table.rows[0].cells[0]
    cell.text = ""

    # 清除单元格默认段落，每行代码一个段落
    for p in cell.paragraphs:
        p._element.getparent().remove(p._element)

    for line in lines:
        is_label = in_out and bool(re.match(r"^(In\[\d+\]:|Out\[\d+\]:)", line))
        p = cell.add_paragraph()
        p.style = doc.styles['代码字体']
        p.paragraph_format.line_spacing = LINE_CODE
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.first_line_indent = Pt(0)

        run = p.add_run(line or " ")
        run.bold = is_label

    # 灰色底纹
    shading = OxmlElement("w:shd")
    shading.set(qn("w:fill"), CODE_BG)
    shading.set(qn("w:val"), "clear")
    cell._element.get_or_add_tcPr().append(shading)


def add_syntax_block(doc: Document, code: str):
    """添加语法格式块（纯灰底，无边框，模板 代码字体 样式）"""
    para = doc.add_paragraph(style='代码字体')
    para.paragraph_format.line_spacing = LINE_CODE
    para.paragraph_format.space_before = Pt(3)
    para.paragraph_format.space_after = Pt(3)
    para.paragraph_format.first_line_indent = Pt(0)
    set_shading(para, CODE_BG)
    para.add_run(code)


def add_table_caption(doc: Document, chapter_num: int, tab_num: int, caption_name: str = ""):
    """添加表题注（模板 Caption 样式，加粗）"""
    text = f"表{chapter_num}-{tab_num}"
    if caption_name:
        text += f" {caption_name}"
    para = doc.add_paragraph(style='Caption')
    run = para.add_run(text)
    run.bold = True


def add_loose_caption(doc: Document, text: str):
    """添加孤立的图/代码题注（模板 Caption 样式，加粗）—— 未被专用处理器捕获的题注行"""
    para = doc.add_paragraph(style='Caption')
    runs = parse_runs(text)
    for r in runs:
        run = para.add_run(r["text"])
        run.bold = True


def add_prompt_caption(doc: Document, text: str):
    """添加提示词/结果题注（模板 Caption 样式，居中加粗）—— 提示词 X-N / 结果 X-N"""
    para = doc.add_paragraph(style='Caption')
    runs = parse_runs(text)
    for r in runs:
        run = para.add_run(r["text"])
        run.bold = True


def add_data_table(doc: Document, header_row: list, data_rows: list,
                   chapter_num=0, tab_num=0, caption_name=""):
    """添加数据表格 — 使用模板 写书表格样式（三线表）+ 表头字体 + 表格字体"""
    hdr = [c.strip() for c in header_row if c.strip()]
    rows = [[c.strip() for c in r if c.strip()] for r in data_rows]
    rows = [r for r in rows if r]

    if not hdr and not rows:
        return add_body(doc, "")
    if not hdr:
        hdr = [""] * max(len(rows[0]) if rows else 1, 1)

    # 表题注
    if chapter_num and tab_num:
        add_table_caption(doc, chapter_num, tab_num, caption_name)

    ncols = max(len(hdr), 1)
    table = doc.add_table(rows=1 + len(rows), cols=ncols)
    table.style = doc.styles['写书表格样式']
    table.autofit = True

    # 表头行：使用 表头字体 样式（加粗居中）
    for j, text in enumerate(hdr):
        cell = table.rows[0].cells[j]
        cell.text = ""
        para = cell.paragraphs[0]
        para.style = doc.styles['表头字体']
        para.paragraph_format.space_before = Pt(0)
        para.paragraph_format.space_after = Pt(0)
        para.add_run(text or " ")

    # 数据行：使用 表格字体 样式
    for i, row in enumerate(rows):
        for j, text in enumerate(row):
            if j >= ncols:
                break
            cell = table.rows[i + 1].cells[j]
            cell.text = ""
            para = cell.paragraphs[0]
            para.style = doc.styles['表格字体']
            para.paragraph_format.space_before = Pt(0)
            para.paragraph_format.space_after = Pt(0)
            para.add_run(text or " ")

    # 补全表头缺失的列
    for j in range(len(hdr), ncols):
        cell = table.rows[0].cells[j]
        cell.text = ""
        para = cell.paragraphs[0]
        para.style = doc.styles['表头字体']
        para.paragraph_format.space_before = Pt(0)
        para.paragraph_format.space_after = Pt(0)
        para.add_run(" ")

    return table


# ==================== Markdown 格式清理 ====================

def strip_md_delimiters(text: str) -> str:
    """去除字符串首/尾的 Markdown 行内格式定界符（**、*、__、_、~~、`），用于题注检测前的清理

    支持以下形式：
    - 两端有匹配定界符（如 **整行加粗**）：两端都去
    - 仅开头有定界符（如题注规范 **X-N** 描述）：剥掉 **X-N** 形式的开闭定界符
    """
    text = text.strip()
    # 第一遍：两端都剥（如 **整行加粗**）
    while True:
        old = text
        for delim in ["**", "__", "~~", "*", "_", "`"]:
            if text.startswith(delim) and text.endswith(delim) and len(text) >= 2 * len(delim):
                text = text[len(delim):-len(delim)]
                break
        if text == old:
            break
    # 第二遍：仅剥开头（如 **X-N** 描述），并将紧接着的同定界符也一并剥掉
    for delim in ["**", "__", "~~", "*", "_", "`"]:
        if text.startswith(delim) and len(text) > len(delim):
            rest = text[len(delim):]
            # 跳过非定界符内容（即 X-N 部分），遇到下一个同定界符就剥掉
            i = 0
            while i < len(rest) and rest[i] != delim[0]:
                i += 1
            if i > 0 and i < len(rest) and rest[i:i + len(delim)] == delim:
                # 找到匹配的关闭定界符，剥掉它
                text = rest[:i] + rest[i + len(delim):]
            else:
                text = rest
            break
    return text.strip()


# ==================== 解析器 ====================

def is_syntax_table(rows: list) -> bool:
    """检测语法伪表格：单列、空表头、内容含inline code → 渲染为 syntax_block"""
    if len(rows) < 2:
        return False
    hdr = [c for c in rows[0] if c]
    data = rows[1:]
    if len(hdr) > 1:
        return False
    has_multi = any(len([c for c in r if c]) > 1 for r in data)
    if has_multi:
        return False
    has_code = any(any("`" in c for c in r) for r in data)
    return has_code


def is_table_separator(line: str) -> bool:
    t = re.sub(r"[\s\u3000]", "", line)
    return bool(re.match(r"^[:\-|\uff5c]+$", t) and "-" in t and len(t) >= 3)


def split_table_row(line: str) -> list:
    parts = re.split(r"[|\uff5c]", line)
    parts = [p.strip() for p in parts]
    if parts and parts[0] == "":
        parts.pop(0)
    if parts and parts[-1] == "":
        parts.pop()
    return parts


def add_image_with_caption(doc: Document, img_path: Path, caption: str):
    """插入图片与题注（图片独立段落居中，仅设 width 保原比例）"""
    # ── 图片段落（模板 图片段落 样式：居中、1.5倍行距）──
    img_para = doc.add_paragraph(style='图片段落')
    img_para.paragraph_format.first_line_indent = Pt(0)
    img_para.paragraph_format.space_before = Pt(8)
    img_para.paragraph_format.space_after = Pt(2)

    run = img_para.add_run()
    run.add_picture(str(img_path), width=IMG_MAX_WIDTH)

    # ── 题注段落 ──
    _add_caption_paragraph(doc, caption)


def _add_caption_paragraph(doc: Document, caption: str):
    """添加正常图片题注段落（模板 Caption 样式），供 _add_cross_references 识别打书签"""
    cap_para = doc.add_paragraph(style='Caption')
    cap_run = cap_para.add_run(caption)
    cap_run.bold = True


def parse_markdown(doc: Document, md_text: str, chapter_num: int, md_dir: Path = None):
    """解析 Markdown 并写入文档

    Args:
        doc: python-docx Document 对象
        md_text: Markdown 文本内容
        chapter_num: 章节编号
        md_dir: Markdown 文件所在目录（用于解析图片相对路径）
    """
    lines = md_text.splitlines()
    i = 0
    tab_num = 0
    code_num = 0
    first_h1 = False
    pending_table_caption = ""
    last_loose_caption = ""  # 最近一次松散题注（图/代码）的内容，供图片去重

    while i < len(lines):
        raw = lines[i]
        ln = raw.strip()

        # 跳过空行和分隔线
        if not ln or ln in ("---", "***", "___"):
            i += 1
            continue
        if re.match(r"^版本[：:]", ln):
            i += 1
            continue
        # 跳过 HTML 注释（如 <!-- 截图来源: ... -->）
        if ln.startswith("<!--"):
            i += 1
            continue
        # 自动跳过 "本章参考资料" 之后的全部内容（防御性清理）
        if re.match(r"^## 本章参考资料", ln):
            break  # 停止解析后续行
        # 自动跳过行内的参考资料标注（如 "（参考资料：...）"）
        if re.match(r"^（参考资料[：:].*[)）]$", ln) or ln.startswith("（参考资料"):
            i += 1
            continue

        # 清理 Markdown 行内格式标记（用于题注检测，不影响真实输出）
        ln_clean = strip_md_delimiters(ln)

        # Reset code numbering on each ## heading
        if raw.startswith("## "):
            code_num = 0

        # 代码题注：代码X-N or 代码X-N 描述（兼容 **代码X-N** 加粗写法）
        cap_match = re.match(r"^代码(\d+)-(\d+)\s*(.*)", ln_clean)
        if cap_match and i + 1 < len(lines) and lines[i + 1].strip().startswith("```"):
            cap_desc = cap_match.group(3) or ""
            code_num = int(cap_match.group(2))
            i += 1  # skip caption
            code_lines = []
            i += 1  # skip opening ```
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1  # skip closing ```
            in_out = any(bool(re.match(r"^(In\[\d+\]:|Out\[\d+\]:)", l)) for l in code_lines)
            add_code_box(doc, code_lines, chapter_num, code_num, cap_desc, in_out)
            continue

        # 提示词/结果题注：提示词X-N or 结果X-N 描述（兼容 **提示词X-N** 加粗写法）
        prompt_cap = re.match(r"^(提示词|结果)(\d+)-(\d+)\b", ln_clean)
        if prompt_cap:
            add_prompt_caption(doc, ln)
            i += 1
            continue

        # 代码块（无题注）
        if ln.startswith("```"):
            code_lines = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith("```"):
                code_lines.append(lines[i])
                i += 1
            i += 1
            in_out = any(bool(re.match(r"^(In\[\d+\]:|Out\[\d+\]:)", l)) for l in code_lines)
            if in_out:
                code_num += 1
            add_code_box(doc, code_lines, chapter_num, code_num if in_out else 0, in_out=in_out)
            continue

        # 标题
        if raw.startswith("#### "):
            add_heading(doc, 4, raw[5:].strip())
            i += 1
            continue
        if raw.startswith("### "):
            add_heading(doc, 3, raw[4:].strip())
            i += 1
            continue
        if raw.startswith("## "):
            add_heading(doc, 2, raw[3:].strip())
            i += 1
            continue
        if raw.startswith("# "):
            title = raw[2:].strip()
            if not first_h1:
                first_h1 = True
                add_heading(doc, 1, title)
            else:
                title = re.sub(r"^第\d+章\s*", "", title)
                add_heading(doc, 2, title)
            i += 1
            continue

        # 表题注：表X-N 名称（兼容 **表X-N** 加粗写法）
        tbl_cap = re.match(r"^表(\d+)-(\d+)\s+(.+)", ln_clean)
        if tbl_cap:
            tab_num = int(tbl_cap.group(2))
            pending_table_caption = tbl_cap.group(3).strip()
            i += 1
            continue

        # 表格
        if ln.startswith("|") or ln.startswith("\uff5c"):
            rows_data = []
            has_caption = bool(pending_table_caption)
            cap_name = pending_table_caption
            pending_table_caption = ""
            tn = tab_num if has_caption else 0

            next_ln = lines[i + 1].strip() if i + 1 < len(lines) else ""
            if is_table_separator(next_ln):
                rows_data.append(split_table_row(ln))
                i += 2  # skip header + separator
                while i < len(lines) and (lines[i].strip().startswith("|") or
                                          lines[i].strip().startswith("\uff5c")):
                    rows_data.append(split_table_row(lines[i].strip()))
                    i += 1
                if len(rows_data) >= 2:
                    if is_syntax_table(rows_data):
                        syntax_text = "\n".join(
                            " ".join(r).replace("`", "") for r in rows_data[1:]
                        )
                        add_syntax_block(doc, syntax_text)
                    else:
                        add_data_table(doc, rows_data[0], rows_data[1:],
                                       chapter_num, tn, cap_name)
            else:
                rows_data.append(split_table_row(ln))
                i += 1
                if rows_data[0] and len(rows_data[0]) >= 2:
                    add_data_table(doc, rows_data[0], [], chapter_num, tn, cap_name)
            continue

        # 引用/提示词：收集连续 > 行，整体作为一个带边框的提示框
        if raw.startswith("> "):
            quote_lines = []
            while i < len(lines) and lines[i].strip().startswith("> "):
                quote_lines.append(lines[i][2:])  # 去掉 "> " 前缀
                i += 1
            add_blockquote(doc, quote_lines)
            continue

        # 无序列表
        if re.match(r"^[-*+]\s", ln):
            add_body(doc, re.sub(r"^[-*+]\s*", "", ln))
            i += 1
            continue

        # 有序列表
        if re.match(r"^\d+[.、]\s", ln):
            add_body(doc, ln)
            i += 1
            continue

        # 水平线
        if re.match(r"^[-*_]{3,}\s*$", ln):
            i += 1
            continue

        # 图片: ![图X-N 描述](路径)
        img_match = re.match(r"^!\[(.+?)\]\((.+?)\)\s*$", ln)
        if img_match:
            caption = img_match.group(1).strip()
            img_rel_path = img_match.group(2).strip()
            img_path = md_dir / img_rel_path if md_dir else Path(img_rel_path)
            # 占位图片（_待替换.png）：生成带提示的占位框，不报错
            is_placeholder = "_待替换" in img_rel_path or "_待替换" in img_path.name
            # 检测"题注在前 → 图片在后"模式：若最近一次松散题注内容与图片 caption 相同，跳过图片的 Caption 输出，避免重复
            prev_is_same_caption = (last_loose_caption == caption)
            if img_path.exists():
                try:
                    if prev_is_same_caption:
                        # 题注已在前面作为 loose caption 输出，仅插图，不再加 Caption
                        img_para = doc.add_paragraph(style='图片段落')
                        img_para.paragraph_format.first_line_indent = Pt(0)
                        img_para.paragraph_format.space_before = Pt(8)
                        img_para.paragraph_format.space_after = Pt(2)
                        run = img_para.add_run()
                        run.add_picture(str(img_path), width=IMG_MAX_WIDTH)
                    else:
                        add_image_with_caption(doc, img_path, caption)
                except Exception:
                    # 图片加载异常：仍然输出正常题注（含【待补齐】后缀），供交叉引用识别
                    _add_caption_paragraph(doc, caption)
            elif is_placeholder:
                # 占位图片（_待替换.png）：输出正常题注，供交叉引用识别
                if not prev_is_same_caption:
                    _add_caption_paragraph(doc, caption)
            else:
                # 图片文件不存在：输出正常题注，供交叉引用识别
                if not prev_is_same_caption:
                    _add_caption_paragraph(doc, caption)
            i += 1
            # 跳过图片后紧接的冗余题注行
            if i < len(lines):
                next_ln = lines[i].strip()
                if strip_md_delimiters(next_ln) == caption:
                    i += 1
            continue

        # 松散题注：未被图片/代码块处理器捕获的孤立题注行（如 "图1-1 描述"、"代码1-2 描述"），强制居中加粗
        loose_cap = re.match(r"^(图|代码)(\d+)-(\d+)\b", ln_clean)
        if loose_cap:
            add_loose_caption(doc, ln)
            last_loose_caption = ln_clean
            i += 1
            continue

        # 普通文本
        add_body(doc, ln)
        i += 1


# ==================== 交叉引用后处理 ====================

def _add_cross_references(doc: Document, chapter_num: int):
    """自动添加图/表/代码交叉引用（bookmark + REF 字段）。

    第一遍：扫描题注段落，打书签。
    第二遍：扫描正文段落，"如图X-N所示/如表X-N所示/如代码X-N所示" → REF 字段。
    """
    if chapter_num == 0:
        return

    # ── 第一遍：收集题注并打书签 ──
    bookmarks = {}  # name -> display_text
    caption_paras = set()  # 记录题注段落对象 id，第二遍跳过避免被当作裸引用处理
    caption_patterns = [
        (r"^图(\d+)-(\d+)\b", "_Fig", "图"),
        (r"^表(\d+)-(\d+)\b", "_Tab", "表"),
        (r"^代码(\d+)-(\d+)\b", "_Code", "代码"),
        (r"^提示词(\d+)-(\d+)\b", "_Prompt", "提示词"),
        (r"^结果(\d+)-(\d+)\b", "_Result", "结果"),
    ]

    for para in doc.paragraphs:
        full_text = para.text.strip()
        if not full_text:
            continue
        for pattern, prefix, label in caption_patterns:
            m = re.match(pattern, full_text)
            if m and para.runs:
                ch, num = int(m.group(1)), int(m.group(2))
                bn = f"{prefix}{ch}-{num}"
                # 打书签
                bm_s = OxmlElement('w:bookmarkStart')
                bm_s.set(qn('w:id'), '0')
                bm_s.set(qn('w:name'), bn)
                bm_e = OxmlElement('w:bookmarkEnd')
                bm_e.set(qn('w:id'), '0')
                para.runs[0]._element.addprevious(bm_e)
                para.runs[0]._element.addprevious(bm_s)
                bookmarks[bn] = f"{label}{ch}-{num}"
                caption_paras.add(id(para))  # 记录题注段落
                break

    if not bookmarks:
        return

    # ── 第二遍：替换正文引用句 ──
    # 匹配"如XX所示"格式，保留"如"和"所示"为普通文字
    ref_re = re.compile(r'如(图|表|代码|提示词|结果)(\d+)-(\d+)所示')

    def _map_label(label_str):
        """标签字符 → 前缀+显示标签"""
        if label_str == "图":
            return "_Fig", "图"
        elif label_str == "表":
            return "_Tab", "表"
        elif label_str == "代码":
            return "_Code", "代码"
        elif label_str == "提示词":
            return "_Prompt", "提示词"
        else:
            return "_Result", "结果"

    def _inject_ref_field(para, bn, display):
        """在当前段落末尾插入一个 REF 字段 run"""
        # fldChar begin
        r = para.add_run()
        f = OxmlElement('w:fldChar'); f.set(qn('w:fldCharType'), 'begin')
        r._element.append(f)
        # instrText
        r = para.add_run()
        it = OxmlElement('w:instrText'); it.set(qn('xml:space'), 'preserve')
        it.text = f' REF {bn} \\h '
        r._element.append(it)
        # fldChar separate
        r = para.add_run()
        f = OxmlElement('w:fldChar'); f.set(qn('w:fldCharType'), 'separate')
        r._element.append(f)
        # display text
        r = para.add_run(display)
        # fldChar end
        r = para.add_run()
        f = OxmlElement('w:fldChar'); f.set(qn('w:fldCharType'), 'end')
        r._element.append(f)

    for para in doc.paragraphs:
        # 跳过题注段落（已打书签），避免「如X-N所示」被错误地插入到题注行
        if id(para) in caption_paras:
            continue
        if para.paragraph_format.first_line_indent is None:
            continue
        runs = para.runs
        if not runs:
            continue
        full = "".join(r.text for r in runs if r.text)

        # ── 匹配"如XX所示"标准格式 ──
        m = ref_re.search(full)
        if m:
            label_str = m.group(1)   # "图"/"表"/"代码"
            ch = int(m.group(2))
            num = int(m.group(3))
            prefix, label = _map_label(label_str)
            bn = f"{prefix}{ch}-{num}"
            if bn not in bookmarks:
                continue
            display = f"{label}{ch}-{num}"
            s, e = m.span()          # "如图6-1所示" 整体
            before = full[:s] + "如"
            after_text = "所示" + full[e:]
        else:
            # ── 匹配裸引用（如"表6-2列出了"）→ 自动包裹为"如XX所示" ──
            bare_re = re.compile(r'(?<!\d)(图|表|代码|提示词|结果)(\d+)-(\d+)(?!所示)')
            m = bare_re.search(full)
            if not m:
                continue
            label_str = m.group(1)
            ch = int(m.group(2))
            num = int(m.group(3))
            prefix, label = _map_label(label_str)
            bn = f"{prefix}{ch}-{num}"
            if bn not in bookmarks:
                continue
            display = f"{label}{ch}-{num}"
            s, e = m.span()          # "表6-2" 裸引用
            before = full[:s] + "如"
            after_text = "所示" + full[e:]

        # 清空段落 runs，重建
        for r in runs:
            r._element.getparent().remove(r._element)

        if before:
            para.add_run(before)
        _inject_ref_field(para, bn, display)
        if after_text:
            para.add_run(after_text)


# ==================== 主函数 ====================

TEMPLATE_PATH = Path(__file__).parent.parent / "样式.docx"

def convert(md_path: str, chapter_num: int, version: str = "", out_dir: str = "") -> str:
    """将 Markdown 文件转为 .docx（含公式 OMML 渲染），以 样式.docx 为样式模板"""
    md_file = Path(md_path)
    if not md_file.exists():
        raise FileNotFoundError(f"文件不存在: {md_path}")

    md_text = md_file.read_text(encoding="utf-8")

    # ── 防御性清理：去除所有行内参考资料标注 ──
    md_text = re.sub(r"[（(]参考资料[：:][^)）]*[)）]", "", md_text)
    md_text = re.sub(r"\n（参考资料[：:].*[)）]\s*\n", "\n", md_text)

    # ── 提取公式并保护 ──
    if HAS_FORMULA:
        protected_md, formula_blocks, formula_inlines = _extract_formulas(md_text)
    else:
        protected_md, formula_blocks, formula_inlines = md_text, {}, {}

    # 以样式模板创建文档（样式.docx 已含全部段落/表格/字符样式）
    doc = Document(str(TEMPLATE_PATH))

    # 页面设置（模板已设 A4，兜底确保）
    section = doc.sections[0]
    section.page_width = PAGE_W
    section.page_height = PAGE_H
    section.top_margin = MARGIN_TB
    section.bottom_margin = MARGIN_TB
    section.left_margin = MARGIN_LR
    section.right_margin = MARGIN_LR

    # 初始化样式（模板已覆盖全部，仅保留扩展入口）
    init_styles(doc)

    # 解析并写入内容（用保护后的文本，公式占位符会被视为普通文本）
    parse_markdown(doc, protected_md, chapter_num, md_file.parent)

    # 添加交叉引用（图/表/代码题注 → 正文引用句）
    _add_cross_references(doc, chapter_num)

    # 输出路径
    base_name = md_file.stem
    base_name = re.sub(r"_v\d+\.\d+$", "", base_name)
    # 版本号统一处理：若已含 v 前缀则不再加，避免 vv 前缀
    if version:
        ver_norm = version if version.lower().startswith("v") else f"v{version}"
    else:
        ver_norm = ""
    file_name = f"{base_name}_{ver_norm}.docx" if ver_norm else f"{base_name}.docx"

    if out_dir:
        out_path = Path(out_dir) / file_name
    else:
        out_path = md_file.parent / file_name

    doc.save(str(out_path))

    # ── 注入 OMML 公式 ──
    if HAS_FORMULA and (formula_blocks or formula_inlines):
        _inject_omml(str(out_path), formula_blocks, formula_inlines)

    return str(out_path)


# ==================== CLI ====================

if __name__ == "__main__":
    if len(sys.argv) < 3:
        print("用法: python md2docx.py <md路径> <章号> <版本号> [输出目录]")
        sys.exit(1)

    md_path = sys.argv[1]
    ch_num = int(sys.argv[2]) if sys.argv[2] else 0
    ver = sys.argv[3] if len(sys.argv) > 3 else ""
    out = sys.argv[4] if len(sys.argv) > 4 else ""

    try:
        result = convert(md_path, ch_num, ver, out)
        print(result)
    except Exception as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        sys.exit(1)
