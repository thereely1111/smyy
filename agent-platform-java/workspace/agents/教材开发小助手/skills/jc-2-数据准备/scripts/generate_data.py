#!/usr/bin/env python3
"""
jc-2-数据准备 — 案例数据集生成器

用法:
  python generate_data.py --schema schema.json --output 数据.csv --rows 30
  python generate_data.py --json '{"案例名称":"xx","字段":[...]}' --output 数据.csv

Schema JSON 格式:
{
  "案例名称": "电商用户行为分析",
  "字段": [
    {"name": "用户ID", "type": "id", "prefix": "U"},
    {"name": "消费金额/元", "type": "float", "min": 10, "max": 5000, "decimals": 2},
    {"name": "商品类别", "type": "category", "values": ["服装","电子","食品","家居"]},
    {"name": "购买日期", "type": "date", "start": "2024-01-01", "end": "2024-12-31"},
    {"name": "年龄", "type": "int", "min": 18, "max": 65},
    {"name": "是否复购", "type": "bool"},
    {"name": "备注", "type": "string", "missing_rate": 0.1}
  ]
}

支持类型:
- id:      自增ID，可选 prefix 前缀
- int:     整数，min~max 范围，可选 weights 按权重分布
- float:   浮点数，min~max，decimals 小数位数，可选 weights
- category:从 values 列表按 weights 随机取值
- date:    日期，start~end 范围，format 输出格式
- bool:    布尔值 (True/False 或 是/否)
- string:  随机文本，可选 values 列表
- email/phone/name: 常用模拟数据
- 所有类型可选 missing_rate 控制缺失比例
"""

import argparse
import json
import random
import sys
from datetime import datetime, timedelta
from pathlib import Path

try:
    import pandas as pd
    import numpy as np
except ImportError:
    print("错误: 请先运行 install.py 安装 pandas", file=sys.stderr)
    sys.exit(1)


# ── 模拟数据模板 ──────────────────
CHARS_CN = "的一是在不了有和人这中大为上个国我以要他时来用们生到作地于出就分对成会可主发年动同工也能下过子说产种面而方后多定行学法所民得经十三之进着等部度家电力里如水化高自二理起小物现实加量都两体制机当使点从业本去把性好应开它合还因由其些然前外天政四日那社义事平形相全表间样与关各重新线内数正心反你明看原又么利比或但质气第向道命此变条只没结解问意建月公无系军很情者最立代想已通并提直题党程展五果料象员革位入常文总次品式活设及管特件长求老头基资边流路级少图山统接知较将组见计别她手角期根论运农指几九区强放决西被干做必战先回则任取据处队南给色光门即保治北造百规热领七海口东导器压志世金增争济阶油思术极交受联什认六共权收证改清己美再采转更单风切打白教速花带安场身车例真务具万每目至达走积示议声报斗完类八离华名确才科张信马节话米整空元况今集温传土许步群广石记需段研界拉林律叫且究观越织装影算低持音众书布复容儿须际商非验连断深难近矿千周委素技备半办青省列习响约支般史感劳便团往酸历市克何除消构府称太准精值号率族维划选标写存候毛亲快效斯院查江型眼王按格养易置派层片始却专状育厂京识适属圆包火住调满县局照参红细引听该铁价严龙飞"

PREFIX_CN = ["李","王","张","刘","陈","杨","赵","黄","周","吴","徐","孙","胡","朱","高","林","何","郭","马","罗"]

EMAIL_DOMAINS = ["qq.com", "163.com", "126.com", "gmail.com", "outlook.com", "sina.com"]

CITIES = ["北京","上海","广州","深圳","杭州","成都","武汉","南京","西安","重庆","苏州","天津","长沙","郑州","东莞","青岛","合肥","佛山","宁波","昆明","沈阳","济南","无锡","厦门","福州","温州","大连","哈尔滨","长春","泉州"]

PRODUCTS = ["服装","电子产品","食品饮料","家居用品","运动户外","美妆个护","图书","玩具","汽车用品","办公用品"]


def rand_text(min_len: int = 2, max_len: int = 16) -> str:
    length = random.randint(min_len, max_len)
    return "".join(random.choices(CHARS_CN, k=length))


def rand_date(start: str, end: str) -> datetime:
    d1 = datetime.strptime(start, "%Y-%m-%d")
    d2 = datetime.strptime(end, "%Y-%m-%d")
    return d1 + timedelta(days=random.randint(0, (d2 - d1).days))


def rand_email(name: str = "") -> str:
    prefix = name or rand_text(4, 10)
    return f"{prefix}@{random.choice(EMAIL_DOMAINS)}"


def rand_phone() -> str:
    prefixes = ["130","131","132","133","134","135","136","137","138","139",
                "150","151","152","153","155","156","157","158","159",
                "180","181","182","183","184","185","186","187","188","189"]
    return random.choice(prefixes) + "".join(random.choices("0123456789", k=8))


def rand_name() -> str:
    return random.choice(PREFIX_CN) + rand_text(1, 2)


def maybe_nan(value, rate: float = 0):
    if rate > 0 and random.random() < rate:
        return np.nan
    return value


def generate_row(fields: list[dict], idx: int) -> dict:
    row = {}
    for field in fields:
        name = field["name"]
        ftype = field.get("type", "string")
        missing = field.get("missing_rate", 0)
        val = None

        if ftype == "id":
            prefix = field.get("prefix", "")
            val = f"{prefix}{idx:04d}"

        elif ftype == "int":
            lo, hi = field.get("min", 0), field.get("max", 100)
            weights = field.get("weights")
            if weights:
                values = list(range(lo, hi + 1))
                # 截断 weights 到与 values 等长
                w = weights[:len(values)] if len(weights) >= len(values) else weights + [1] * (len(values) - len(weights))
                val = random.choices(values, weights=w, k=1)[0]
            else:
                val = random.randint(lo, hi)

        elif ftype == "float":
            lo, hi = field.get("min", 0.0), field.get("max", 1000.0)
            decimals = field.get("decimals", 2)
            val = round(random.uniform(lo, hi), decimals)

        elif ftype == "category":
            values = field.get("values", ["选项A", "选项B", "选项C"])
            weights = field.get("weights")
            if weights:
                val = random.choices(values, weights=weights[:len(values)], k=1)[0] if len(weights) >= len(values) else random.choice(values)
            else:
                val = random.choice(values)

        elif ftype == "date":
            fmt = field.get("format", "%Y-%m-%d")
            val = rand_date(
                field.get("start", "2024-01-01"),
                field.get("end", "2024-12-31"),
            ).strftime(fmt)

        elif ftype == "bool":
            choices = field.get("values", ["是", "否"])
            val = random.choice(choices)

        elif ftype == "string":
            values = field.get("values")
            if values:
                val = random.choice(values)
            else:
                val = rand_text()

        elif ftype == "name":
            val = rand_name()

        elif ftype == "email":
            val = rand_email()

        elif ftype == "phone":
            val = rand_phone()

        elif ftype == "city":
            val = random.choice(field.get("values", CITIES))

        else:
            val = ""

        row[name] = maybe_nan(val, missing)

    return row


def generate_data(schema: dict, output_path: str, rows: int, seed: int = 42):
    case_name = schema.get("案例名称", "未命名案例")
    fields = schema.get("字段", [])

    if not fields:
        print("[错误] schema 中字段列表为空", file=sys.stderr)
        return False

    random.seed(seed)
    np.random.seed(seed)

    data = [generate_row(fields, i + 1) for i in range(rows)]
    df = pd.DataFrame(data)

    output = Path(output_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    ext = output.suffix.lower()

    if ext == ".csv":
        df.to_csv(output, index=False, encoding="utf-8-sig")
    elif ext in (".xlsx", ".xls"):
        df.to_excel(output, index=False, sheet_name=case_name[:31])
    else:
        output = output.with_suffix(".csv")
        df.to_csv(output, index=False, encoding="utf-8-sig")

    # 数据字典
    dict_path = output.with_name(f"{output.stem}_字典.md")
    lines = [f"# 数据字典: {case_name}", ""]
    lines.append(f"- 案例名称: {case_name}")
    lines.append(f"- 行数: {rows}")
    lines.append(f"- 随机种子: {seed}")
    lines.append(f"- 生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    lines.append("")
    lines.append("| 序号 | 字段名 | 类型 | 取值范围/说明 |")
    lines.append("|------|--------|------|---------------|")
    for i, f in enumerate(fields, 1):
        ftype = f.get("type", "string")
        detail = f.get("description", "")
        if ftype in ("int", "float"):
            detail = f"{f.get('min','')}~{f.get('max','')}"
        elif ftype == "category":
            detail = " / ".join(f.get("values", [])[:6])
        elif ftype == "date":
            detail = f"{f.get('start','')}~{f.get('end','')}"
        lines.append(f"| {i} | {f['name']} | {ftype} | {detail} |")

    dict_path.write_text("\n".join(lines), encoding="utf-8")

    # 预览
    print(f"[jc-2] 已生成: {output}")
    print(f"[jc-2] 字典: {dict_path}")
    print(f"[jc-2] 行数: {rows}, 字段: {len(fields)}")
    print(f"[jc-2] 前5行预览:")
    print(df.head(5).to_string(index=False))
    return True


# ── CLI ───────────────────────────
def main():
    parser = argparse.ArgumentParser(description="生成案例数据集 CSV/XLSX")
    parser.add_argument("--schema", default="", help="Schema JSON 文件路径")
    parser.add_argument("--json", default="", help="Schema JSON 字符串（直接传入）")
    parser.add_argument("--output", required=True, help="输出文件路径 (.csv 或 .xlsx)")
    parser.add_argument("--rows", type=int, default=30, help="数据行数，默认 30")
    parser.add_argument("--seed", type=int, default=42, help="随机种子，默认 42")
    args = parser.parse_args()

    if args.json:
        schema = json.loads(args.json)
    elif args.schema:
        with open(args.schema, "r", encoding="utf-8") as f:
            schema = json.load(f)
    else:
        print("[错误] 需要 --schema 或 --json 参数", file=sys.stderr)
        sys.exit(1)

    ok = generate_data(schema, args.output, args.rows, args.seed)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
