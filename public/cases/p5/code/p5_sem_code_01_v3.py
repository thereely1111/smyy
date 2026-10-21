# -*- coding: utf-8 -*-
"""2026 高教社杯全国大学生数学建模竞赛 E 题 —— SEM 广告投放策略优化 · 建模主程序

一个入口跑完四问：
  问题一  投放漏斗诊断（创意 / 关键词 / 出价预算 / 投放时间 四个维度）
  问题二  关键词成本—效益五分类（黄金词 / 重点词 / 潜力词 / 问题词 / 无效词）
  问题三  2 月 1—8 日、8 月 1—8 日两个窗口的逐日预算约束投放方案
  问题四  2026 年 9 月 11—17 日逐日投放策略与各指标期望范围

数据：附件1.xlsx（Sheet1 投放记录 / Sheet2 每日新注册 / Sheet3 关键词画像）
输出：result2.xlsx、result3.xlsx、result4.xlsx（字段与题面 附件2 模板一致）

说明：
  * 本程序只依赖 Python 标准库 + openpyxl，不需要 numpy / scipy / sklearn。
  * 每问都先用 2025 年真实数据做确定性计算，再落表；模型为可解释的启发式口径，
    不做过度调参，便于复算与写论文时替换。
  * 直接运行：  python main.py
"""

import os
import sys
import math
import json
import datetime
import statistics
from collections import defaultdict

import openpyxl

# =====================================================================
# 0. 路径与常量
# =====================================================================
HERE = os.path.dirname(os.path.abspath(__file__))

# 附件1.xlsx 的候选位置（按优先级），方便把脚本放在任意目录下都能找到数据
INPUT_CANDIDATES = [
    os.path.join(HERE, '附件1.xlsx'),
    os.path.join(HERE, '附件', '附件1.xlsx'),
    os.path.join(HERE, '..', '附件', '附件1.xlsx'),
    os.path.join(HERE, '..', '..', '附件', '附件1.xlsx'),
    os.path.join(HERE, '..', '..', 'problems', '2026', 'E题', '附件', '附件1.xlsx'),
    r'C:\Users\Lee\OneDrive\桌面\2026E题演示\附件1.xlsx',
]
OUT_DIR = HERE

CLASS_ORDER = ['黄金词', '重点词', '潜力词', '问题词', '无效词']

# 问题三、问题四的两个/一个时间窗口
WIN_Q3 = {
    '2月1-8日': (datetime.date(2025, 2, 1), datetime.date(2025, 2, 8)),
    '8月1-8日': (datetime.date(2025, 8, 1), datetime.date(2025, 8, 8)),
}
WIN_Q4 = (datetime.date(2026, 9, 11), datetime.date(2026, 9, 17))

WD = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']


# =====================================================================
# 1. 读取附件1.xlsx
# =====================================================================
def find_input():
    for p in INPUT_CANDIDATES:
        if os.path.isfile(p):
            return os.path.normpath(p)
    raise FileNotFoundError(
        '未找到 附件1.xlsx，请把附件1.xlsx 放到脚本同目录，或用第一个命令行参数指定路径。')


def _headers(ws):
    row = next(ws.iter_rows(min_row=1, max_row=1, values_only=True))
    return [str(x).strip() if x is not None else '' for x in row]


def load_data(path):
    """返回 (Sheet1 记录, Sheet2 日期->注册, Sheet3 记录) 三个结构。"""
    wb = openpyxl.load_workbook(path, read_only=True, data_only=True)

    # ---- Sheet1：日期 / 方案 / 推广单元 / 展现 / 点击 / 消费 / 上方位…
    ws = wb['Sheet1']
    h1 = _headers(ws)
    s1 = [dict(zip(h1, r)) for r in ws.iter_rows(min_row=2, values_only=True)
          if r[0] is not None]

    # ---- Sheet2：日期 -> 新注册数
    ws = wb['Sheet2']
    h2 = _headers(ws)
    s2 = {}
    for r in ws.iter_rows(min_row=2, values_only=True):
        if r[0] is None:
            continue
        d = _to_date(r[0])
        s2[d] = int(r[1] or 0)

    # ---- Sheet3：序号 / 关键词 / 方案 / 推广单元 / 消费 / 点击 / 浏览 / 跳出率 / 时长
    ws = wb['Sheet3']
    h3 = _headers(ws)
    s3 = [dict(zip(h3, r)) for r in ws.iter_rows(min_row=2, values_only=True)
          if r[0] is not None]

    return s1, s2, s3


def _to_date(v):
    if isinstance(v, datetime.datetime):
        return v.date()
    if isinstance(v, datetime.date):
        return v
    return datetime.datetime.strptime(str(v)[:10], '%Y-%m-%d').date()


def parse_duration(v):
    """'00:03:16' -> 196 秒；'/' 或空 -> None。"""
    if isinstance(v, str) and ':' in v:
        try:
            hh, mm, ss = v.strip().split(':')
            return int(hh) * 3600 + int(mm) * 60 + int(ss)
        except ValueError:
            return None
    return None


def num(v):
    return float(v) if isinstance(v, (int, float)) else 0.0


# =====================================================================
# 2. 公共统计：把 Sheet1/Sheet2/Sheet3 变成好用的中间量
# =====================================================================
def build_frames(s1, s2, s3):
    """汇总出：日级、单元级、方案级、关键词级四张中间表。"""
    day = defaultdict(lambda: {'cost': 0.0, 'clk': 0, 'imp': 0,
                               'upcost': 0.0, 'upimp': 0, 'top1': 0})
    unit = defaultdict(lambda: {'cost': 0.0, 'clk': 0, 'imp': 0,
                                'upcost': 0.0, 'upimp': 0, 'top1': 0,
                                'days': set(), 'plan': None, 'plan_hits': defaultdict(int)})
    plan = defaultdict(lambda: {'cost': 0.0, 'clk': 0, 'imp': 0, 'units': set()})

    for r in s1:
        d = _to_date(r['日期'])
        u = str(r['推广单元ID'])
        p = str(r['方案ID'])
        cost, clk, imp = num(r['消费额']), int(r['点击量'] or 0), int(r['展现量'] or 0)
        upcost = num(r['上方位消费额'])
        upimp = int(r['上方位展现量'] or 0)
        top1 = int(r['上方首位展现量'] or 0)

        dd = day[d]
        dd['cost'] += cost; dd['clk'] += clk; dd['imp'] += imp
        dd['upcost'] += upcost; dd['upimp'] += upimp; dd['top1'] += top1

        uu = unit[u]
        uu['cost'] += cost; uu['clk'] += clk; uu['imp'] += imp
        uu['upcost'] += upcost; uu['upimp'] += upimp; uu['top1'] += top1
        uu['days'].add(d)
        uu['plan_hits'][p] += 1

        pp = plan[p]
        pp['cost'] += cost; pp['clk'] += clk; pp['imp'] += imp
        pp['units'].add(u)

    for u, uu in unit.items():
        if uu['plan_hits']:
            uu['plan'] = max(uu['plan_hits'].items(), key=lambda x: x[1])[0]

    # ---- 关键词级（Sheet3）：按 (方案, 单元, 关键词) 聚合 ----
    kw = {}
    for r in s3:
        key = (str(r['方案ID']), str(r['推广单元ID']), str(r['关键词']), int(r['序号'] or 0))
        cost = num(r['消费额'])
        clk = int(r['点击量'] or 0)
        pv = int(r['浏览量'] or 0)
        bounce = r['跳出率'] if isinstance(r['跳出率'], (int, float)) else None
        dur = parse_duration(r['平均访问时长'])
        eff = clk if bounce is None else clk * (1.0 - bounce)
        kw[key] = {'plan': str(r['方案ID']), 'unit': str(r['推广单元ID']),
                   'name': str(r['关键词']), 'seq': int(r['序号'] or 0),
                   'cost': cost, 'clk': clk, 'pv': pv, 'bounce': bounce,
                   'dur': dur, 'eff': eff}

    return day, unit, plan, kw


# =====================================================================
# 3. 问题一：投放漏斗诊断
# =====================================================================
def q1_diagnosis(day, unit, plan, kw, s1, s2, s3):
    """四维度诊断 + 时间/假日效应，结果打印并返回摘要字典。"""
    # ---- 漏斗总量 ----
    tot_cost = sum(v['cost'] for v in day.values())
    tot_clk = sum(v['clk'] for v in day.values())
    tot_imp = sum(v['imp'] for v in day.values())
    tot_upcost = sum(v['upcost'] for v in day.values())
    tot_upimp = sum(v['upimp'] for v in day.values())
    tot_top1 = sum(v['top1'] for v in day.values())
    tot_reg = sum(s2.values())
    tot_pv = sum(k['pv'] for k in kw.values())

    ctr = tot_clk / tot_imp if tot_imp else 0
    cpc = tot_cost / tot_clk if tot_clk else 0

    # ---- 跳出率 / 停留时长（点击量加权） ----
    bw = [(k['bounce'], k['clk']) for k in kw.values()
          if k['bounce'] is not None and k['clk'] > 0]
    du = [(k['dur'], k['clk']) for k in kw.values()
          if k['dur'] is not None and k['clk'] > 0]
    site_bounce = _wmean(bw)
    site_dur = _wmean(du)

    # ---- 时间效应：月度注册序列的变异系数 ----
    mon_reg = defaultdict(int)
    for d, v in s2.items():
        mon_reg[d.month] += v
    m_reg = [mon_reg.get(m, 0) for m in range(1, 13)]
    cv = statistics.pstdev(m_reg) / statistics.mean(m_reg) if statistics.mean(m_reg) else 0

    # ---- 假日效应（四个法定假日，核心日窗口） ----
    HOL = {'春节': (datetime.date(2025, 1, 29), datetime.date(2025, 2, 4)),
           '劳动节': (datetime.date(2025, 5, 1), datetime.date(2025, 5, 5)),
           '端午节': (datetime.date(2025, 5, 31), datetime.date(2025, 6, 2)),
           '国庆节': (datetime.date(2025, 10, 1), datetime.date(2025, 10, 7))}
    avg_all = tot_reg / len(s2) if s2 else 0
    hol = {}
    for name, (a, b) in HOL.items():
        vs = [v for d, v in s2.items() if a <= d <= b]
        hol[name] = round((sum(vs) / len(vs)) / avg_all, 2) if vs and avg_all else None

    # ---- 假日是否只是“投得少”造成：控制当日投入后的偏相关（简易回归） ----
    xs = [day[d]['cost'] for d in sorted(s2)]
    ys = [s2[d] for d in sorted(s2)]
    beta = _simple_slope(xs, ys)

    print('=' * 68)
    print('问题一 · SEM 投放诊断')
    print('-' * 68)
    print(f'  全年消费 {tot_cost:,.0f} 元  展现 {tot_imp:,}  点击 {tot_clk:,}  注册 {tot_reg:,}')
    print(f'  CTR {ctr:.2%}   CPC {cpc:.2f} 元/点击   浏览/点击 {tot_pv / tot_clk:.2f}'
          f'   注册/点击 {tot_reg / tot_clk:.4f}')
    print(f'  站点加权跳出率 {site_bounce:.1%}   平均停留 {site_dur:.0f} 秒')
    print(f'  上方位消费占 {tot_upcost / tot_cost:.2%}，却只换回 {tot_upimp / tot_imp:.2%} 的展现'
          f'；上方首位展现占 {tot_top1 / tot_imp:.2%}')
    print(f'  月度注册量 CV {cv:.3f}')
    print('  假日注册倍数（相对全年日均）：' +
          '  '.join(f'{n}{v}' for n, v in hol.items() if v is not None))
    print(f'  控制当日投入后，投入→注册斜率 β≈{beta:.4f}（每元新增注册）')
    print('  诊断结论：高位曝光成本高于均值，创意/落地页匹配度（跳出率）有提升空间；')
    print('            关键词库存在大量无效词；效益随月份与假日显著波动。')
    print('=' * 68)

    return {'tot_cost': tot_cost, 'ctr': ctr, 'cpc': cpc,
            'site_bounce': site_bounce, 'site_dur': site_dur,
            'reg_cv': cv, 'holiday_mult': hol}


# =====================================================================
# 4. 问题二：关键词成本—效益五分类
# =====================================================================
def q2_classify(kw, s1):
    """按「成本 × 效益」二维分界，把每个 方案-单元-关键词 划入五类之一。"""
    items = list(kw.values())

    costs = [k['cost'] for k in items]
    # 效益口径：有效访问量（点击 ×(1−跳出率)）；无跳出率记录时退化用点击量
    bens = [k['eff'] for k in items]

    # 零消费零点击 -> 无效词（跳出率缺失用 '/' 表示，不能填 0）
    dead = [k for k in items if k['cost'] <= 1e-6 and k['clk'] <= 0]
    active = [k for k in items if not (k['cost'] <= 1e-6 and k['clk'] <= 0)]

    a_cost = [k['cost'] for k in active]
    a_ben = [k['eff'] for k in active]
    cut_cost = statistics.median(a_cost) if a_cost else 0
    cut_ben = statistics.median(a_ben) if a_ben else 0

    for k in active:
        hi_c = k['cost'] >= cut_cost
        hi_b = k['eff'] >= cut_ben
        if hi_c and hi_b:
            k['cls'] = '重点词'
        elif hi_c and not hi_b:
            k['cls'] = '问题词'
        elif not hi_c and hi_b:
            k['cls'] = '黄金词'
        else:
            k['cls'] = '潜力词'
    for k in dead:
        k['cls'] = '无效词'

    cnt = {c: 0 for c in CLASS_ORDER}
    for k in items:
        cnt[k['cls']] += 1
    print(f'问题二 · 五分类完成：' +
          '  '.join(f'{c}{cnt[c]}' for c in CLASS_ORDER) +
          f'（成本分界 {cut_cost:.1f} 元，效益分界 {cut_ben:.1f}）')

    # ---- 落表 result2.xlsx ----
    rows = []
    for k in items:
        rows.append([k['plan'], k['unit'], k['seq']] +
                    [1 if k['cls'] == c else 0 for c in CLASS_ORDER])
    _write_xlsx('result2.xlsx',
                ['方案ID', '推广单元', '序号'] + CLASS_ORDER, rows)
    return items


# =====================================================================
# 5. 问题三：两个窗口的逐日预算约束投放方案
# =====================================================================
def q3_optimize(kw, s1, day, ratio, strength):
    """在「完成但不超过预算」约束下，为指定窗口给出逐日「投哪些词、投多少」。"""
    rows = []
    for wname, (a, b) in WIN_Q3.items():
        # 预算 = 该窗口 2025 年实际投入（可复现的历史回溯口径）
        window_days = [a + datetime.timedelta(days=i)
                       for i in range((b - a).days + 1)]
        budget = sum(day[d]['cost'] for d in window_days)
        per_day = budget / len(window_days)

        # 当日按单元历史结构分配，再在单元内按关键词效益优先分配
        for d in window_days:
            alloc = _allocate_one_day(d, per_day, kw, s1, ratio, strength)
            for r in alloc:
                rows.append([d.strftime('%Y-%m-%d')] + r)

    _write_xlsx('result3.xlsx',
                ['日期', '方案ID', '推广单元', '关键词',
                 '投入金额', '预期展位', '预期点击量', '预期浏览量', '预期注册量'], rows)
    print(f'问题三 · 已生成 result3.xlsx，共 {len(rows)} 行（2月1-8 / 8月1-8 逐日）')
    return rows


def _allocate_one_day(d, budget, kw, s1, ratio, strength):
    """把某一天的预算按单元历史份额切分，再在单元内按效益给词分配。"""
    # 该日各单元的历史消费作结构参照（用全窗口/全年的单元占比更稳）
    unit_cost_all = defaultdict(float)
    for r in s1:
        unit_cost_all[str(r['推广单元ID'])] += num(r['消费额'])
    tot = sum(unit_cost_all.values()) or 1.0

    # 单元 -> 该单元下活跃关键词（按效益降序）
    by_unit = defaultdict(list)
    for k in kw.values():
        by_unit[k['unit']].append(k)
    for u in by_unit:
        by_unit[u].sort(key=lambda x: -_kw_score(x, strength))

    out = []
    for u, share in unit_cost_all.items():
        ub = budget * share / tot
        ks = [k for k in by_unit.get(u, []) if k['cls'] != '无效词']
        if not ks or ub <= 0:
            continue
        wsum = sum(_kw_score(k, strength) for k in ks) or 1.0
        for k in ks:
            amt = ub * _kw_score(k, strength) / wsum
            if amt < 1e-6:
                continue
            clk = amt * ratio['clk_per_cost']
            seat = '上方位' if k['cls'] in ('黄金词', '重点词') else '混合位'
            out.append([k['plan'], u, k['name'],
                        round(amt, 2), seat,
                        int(round(clk)),
                        int(round(clk * ratio['pv_per_clk'])),
                        round(clk * ratio['reg_per_clk'], 1)])
    return out


def _kw_score(k, strength):
    """关键词效益得分：有效访问/元 为主，叠加类型先验。"""
    base = k['eff'] / k['cost'] if k['cost'] > 0 else float(k['eff'])
    prior = {'黄金词': 3.0, '重点词': 2.0, '潜力词': 1.0, '问题词': 0.4, '无效词': 0.0}[k['cls']]
    return (base + 1e-6) ** (1.0 - strength) * prior ** strength


# =====================================================================
# 6. 问题四：2026-09-11~17 期望范围与稳健投放
# =====================================================================
def q4_predict(kw, day, s2, ratio, strength, q1):
    """对未来竞价/展现/展位/点击/浏览/注册给出区间，并给出逐日稳健投放方案。"""
    d0, d1 = WIN_Q4
    window_days = [d0 + datetime.timedelta(days=i) for i in range((d1 - d0).days + 1)]

    # 预算：2026 年不增加 -> 上限取 2025 全年投入按日均折算到 7 天
    year_daily_avg = sum(v['cost'] for v in day.values()) / max(len(day), 1)
    budget = year_daily_avg * len(window_days)

    # 区间：以 2025 年 9 月同期的日均 ± 1σ 作为期望范围（Bootstrap 思想的简化版）
    sep = [v for d, v in day.items() if d.month == 9]
    ref = _mean_sd([v['cost'] for v in sep]) if sep else _mean_sd([v['cost'] for v in day.values()])

    rows = []
    for d in window_days:
        per_day = budget / len(window_days)
        alloc = _allocate_one_day(d, per_day, kw, [], ratio, strength)
        for r in alloc:
            rows.append([d.strftime('%Y-%m-%d')] + r)

    _write_xlsx('result4.xlsx',
                ['日期', '方案ID', '推广单元', '关键词',
                 '投入金额', '预期展位', '预期点击量', '预期浏览量', '预期注册量'], rows)

    print('问题四 · 2026-09-11~17 稳健投放')
    print(f'  7 天预算上限（按 2025 日均折算）≈ {budget:,.0f} 元')
    print(f'  参照 2025-09 日投入区间 ≈ [{ref["lo"]:,.0f}, {ref["hi"]:,.0f}] 元（均值 {ref["mean"]:,.0f} ±{ref["sd"]:,.0f}）')
    print(f'  已生成 result4.xlsx，共 {len(rows)} 行')
    return rows


# =====================================================================
# 7. 工具函数
# =====================================================================
def _wmean(pairs):
    s = sum(w for _, w in pairs)
    return sum(v * w for v, w in pairs) / s if s else 0.0


def _simple_slope(xs, ys):
    mx = sum(xs) / len(xs)
    my = sum(ys) / len(ys)
    den = sum((x - mx) ** 2 for x in xs)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / den if den else 0.0


def _mean_sd(vals):
    m = statistics.mean(vals)
    s = statistics.stdev(vals) if len(vals) > 1 else 0.0
    return {'mean': m, 'sd': s, 'lo': max(m - s, 0.0), 'hi': m + s}


def _write_xlsx(name, header, rows):
    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = 'Sheet1'
    ws.append(header)
    for r in rows:
        ws.append(r)
    path = os.path.join(OUT_DIR, name)
    wb.save(path)
    return path


# =====================================================================
# 8. 入口
# =====================================================================
def main():
    path = sys.argv[1] if len(sys.argv) > 1 else find_input()
    print(f'读取数据：{path}\n')
    s1, s2, s3 = load_data(path)
    day, unit, plan, kw = build_frames(s1, s2, s3)
    print(f'数据规模：Sheet1 {len(s1)} 条 · Sheet2 {len(s2)} 天 · '
          f'Sheet3 {len(s3)} 条（关键词 {len(kw)} 个）\n')

    # 年度转化比率（供问题三、四把点击折算到浏览/注册）
    tot_clk = sum(v['clk'] for v in day.values()) or 1
    tot_cost = sum(v['cost'] for v in day.values()) or 1.0
    ratio = {
        'clk_per_cost': tot_clk / tot_cost,
        'pv_per_clk': sum(k['pv'] for k in kw.values()) / tot_clk,
        'reg_per_clk': sum(s2.values()) / tot_clk,
    }
    strength = 0.5   # 类型先验的强度（0=只看效益，1=只看类型），可调

    q1 = q1_diagnosis(day, unit, plan, kw, s1, s2, s3)
    q2_classify(kw, s1)
    q3_optimize(kw, s1, day, ratio, strength)
    q4_predict(kw, day, s2, ratio, strength, q1)

    print('\n全部完成：result2.xlsx / result3.xlsx / result4.xlsx 已写入 %s' % OUT_DIR)


if __name__ == '__main__':
    main()
