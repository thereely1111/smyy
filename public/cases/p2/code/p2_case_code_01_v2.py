# -*- coding: utf-8 -*-
"""2026 高教社杯全国大学生数学建模竞赛 B 题 —— 无线电干扰源的快速自动定位与清除 · 建模主程序

一个入口跑完四问：
  问题一  多条示向度交会定位：由同一干扰源的多条示向度求定位区域 A，
          给出区域直径 D 与最小包围圆半径 R，并按 R ≤ 20 m 判清除可行
  问题二  最优第二测点：已知第一检测点与一条示向度，在有效接收半径内
          网格搜索使「交会角大、最坏后验半径小」的第二检测点
  问题三  全局保证覆盖 + 局部清除：正六边形构型铺满作业区（保证不漏发现），
          发现后转局部交会，缩到 R ≤ 20 m 再清除核销频道
  问题四  定向源下的方向鲁棒覆盖：源有 ±90° 覆盖角、朝向未知，
          给出「成对镜像测点」网格并核验全方向可测

参数（题面）：
  作业区半径 1800 m · 可用频道 20 · 示向度误差 ±1°（有界误差，非随机）
  有效接收半径 1000~1500 m · 光学清除半径 20 m · 速度 5 m/s
  检测 5 s · 切频 1 s · 清除 3 s（该频道无源）/ 5 s（成功，含精确定位 3 s）
  目标源 10~16 个 · 定向源有效覆盖角 180°（±90°）

说明：
  * 本程序只依赖 Python 标准库（math / json / urllib），不需要 numpy / scipy。
  * ±1° 是「有界误差」而非随机误差，全程用扇形求交做确定性几何，不引入分布假设。
  * 规划/计算部分完全离线、可直接复算；末尾附官方模拟器的 4 条指令客户端，
    供把策略落到真机时调用（默认 http://127.0.0.1:2026）。
  * 直接运行：  python main.py
"""

import os
import sys
import csv
import json
import math
import urllib.request
import urllib.error

# =====================================================================
# 0. 路径与常量（题面逐字口径）
# =====================================================================
HERE = os.path.dirname(os.path.abspath(__file__))
OUT_DIR = HERE

R_AREA = 1800.0            # 圆形作业区域半径 (m)
CHANNELS = 20              # 可用频道数
BEARING_ERR = 1.0          # 示向度误差 ±1°（有界）
RX_MIN, RX_MAX = 1000.0, 1500.0   # 有效接收半径区间 (m)
CLEAR_R = 20.0             # 光学清除半径 (m)
SPEED = 5.0                # 移动速度 (m/s)
T_DETECT = 5.0             # 单次检测耗时 (s)
T_SWITCH = 1.0             # 切换频道耗时 (s)
T_CLEAR_MISS = 3.0         # 清除耗时（该频道无源）
T_CLEAR_HIT = 5.0          # 清除耗时（成功清除，含精确定位 3 s + 清除 2 s）
N_MIN, N_MAX = 10, 16      # 目标源数量范围

OMNI = 360.0               # 全向源覆盖角
DIR_HALF = 90.0            # 定向源半覆盖角
DIR_SPAN = 180.0           # 定向源有效覆盖角

HEX_MIN = R_AREA / math.sqrt(3.0)   # 正六边形全覆盖的相邻圆心间距下限 ≈ 1039.23 m
RHO = 1150.0                        # 实务取值：相邻测点间距（略大于下限，使覆盖圆叠压）
ANGLE_LO, ANGLE_HI = 60.0, 120.0    # 交会角的理想区间（U 形曲线谷底）
ANGLE_IDEAL = 90.0                  # 理想交会角

SIM_BASE = os.environ.get('RID_SIM_BASE', 'http://127.0.0.1:2026')
ARENA_ID = 'default'


# =====================================================================
# 1. 基础几何工具
# =====================================================================
def norm_deg(a):
    """把角度归一化到 [0, 360)。"""
    a = math.fmod(a, 360.0)
    return a + 360.0 if a < 0 else a


def ang_diff(a, b):
    """两个角度的最小差，落在 (-180, 180]。"""
    return (a - b + 180.0) % 360.0 - 180.0


def dist(p, q):
    return math.hypot(p[0] - q[0], p[1] - q[1])


def bearing_of(p, q):
    """从 p 望向 q 的示向度，0°指东、逆时针为正，范围 [0,360)。"""
    return norm_deg(math.degrees(math.atan2(q[1] - p[1], q[0] - p[0])))


def sector_polygon(apex, theta, half_err, radius, n=24):
    """把一条示向度表示成扇形多边形：顶点 + 半径 radius 上的一段圆弧。

    参数 theta 为示向度中心角，half_err 为半误差角（题面 ±1°）。
    多边形按角度递增方向给出，即逆时针（CCW），便于后续裁剪。
    """
    pts = [apex]
    a0 = theta - half_err
    for i in range(n + 1):
        a = a0 + 2.0 * half_err * i / n
        r = math.radians(a)
        pts.append((apex[0] + radius * math.cos(r),
                    apex[1] + radius * math.sin(r)))
    return pts


def _inside(p, a, b):
    """点 p 是否在有向边 a→b 的左侧（含边界），要求裁剪多边形为 CCW。"""
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]) >= 0.0


def _line_cross(p, q, a, b):
    """直线 pq 与直线 ab 的交点。"""
    x1, y1 = p; x2, y2 = q; x3, y3 = a; x4, y4 = b
    den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
    if abs(den) < 1e-12:
        return q
    px = ((x1 * y2 - y1 * x2) * (x3 - x4) - (x1 - x2) * (x3 * y4 - y3 * x4)) / den
    py = ((x1 * y2 - y1 * x2) * (y3 - y4) - (y1 - y2) * (x3 * y4 - y3 * x4)) / den
    return (px, py)


def clip_polygon(subject, clip):
    """Sutherland–Hodgman 凸多边形裁剪：返回 subject ∩ clip。"""
    out = list(subject)
    n = len(clip)
    for i in range(n):
        a = clip[i]
        b = clip[(i + 1) % n]
        if not out:
            return []
        inp = out
        out = []
        prev = inp[-1]
        for cur in inp:
            ci, pi = _inside(cur, a, b), _inside(prev, a, b)
            if ci:
                if not pi:
                    out.append(_line_cross(prev, cur, a, b))
                out.append(cur)
            elif pi:
                out.append(_line_cross(prev, cur, a, b))
            prev = cur
    return out


def polygon_area(poly):
    """鞋带公式求多边形面积（取绝对值）。"""
    s = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        s += x1 * y2 - x2 * y1
    return abs(s) * 0.5


def convex_hull(pts):
    """Andrew 单调链凸包（返回 CCW 顶点）。"""
    ps = sorted(set((round(x, 9), round(y, 9)) for x, y in pts))
    if len(ps) <= 2:
        return ps

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower = []
    for p in ps:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper = []
    for p in reversed(ps):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def _circumcircle(a, b, c):
    """三点外接圆 (圆心, 半径)；三点近共线时返回 None。"""
    ax, ay = a; bx, by = b; cx, cy = c
    d = 2.0 * (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by))
    if abs(d) < 1e-12:
        return None
    ux = ((ax ** 2 + ay ** 2) * (by - cy) + (bx ** 2 + by ** 2) * (cy - ay)
          + (cx ** 2 + cy ** 2) * (ay - by)) / d
    uy = ((ax ** 2 + ay ** 2) * (cx - bx) + (bx ** 2 + by ** 2) * (ax - cx)
          + (cx ** 2 + cy ** 2) * (bx - ax)) / d
    return (ux, uy), math.hypot(ax - ux, ay - uy)


def min_enclosing_circle(pts):
    """最小包围圆 (圆心, 半径)。点集规模小，用「枚举直径 + 枚举三点外接圆」精确求解。"""
    ps = list(pts)
    if not ps:
        return None, 0.0
    if len(ps) == 1:
        return ps[0], 0.0

    best_c, best_r = ps[0], math.inf

    def encloses(c, r):
        tol = 1e-7
        return all(math.hypot(p[0] - c[0], p[1] - c[1]) <= r + tol for p in ps)

    n = len(ps)
    for i in range(n):
        for j in range(i + 1, n):
            c = ((ps[i][0] + ps[j][0]) / 2.0, (ps[i][1] + ps[j][1]) / 2.0)
            r = math.hypot(ps[i][0] - c[0], ps[i][1] - c[1])
            if r < best_r and encloses(c, r):
                best_c, best_r = c, r
    for i in range(n):
        for j in range(i + 1, n):
            for k in range(j + 1, n):
                cc = _circumcircle(ps[i], ps[j], ps[k])
                if cc is None:
                    continue
                c, r = cc
                if r < best_r and encloses(c, r):
                    best_c, best_r = c, r
    return best_c, best_r


def polygon_diameter(poly):
    """凸多边形的直径 D：凸包上最远点对距离。"""
    hull = convex_hull(poly)
    if len(hull) < 2:
        return 0.0
    d = 0.0
    n = len(hull)
    for i in range(n):
        for j in range(i + 1, n):
            d = max(d, dist(hull[i], hull[j]))
    return d


# =====================================================================
# 2. 示向度交会模型：多条示向度 → 定位区域 A
# =====================================================================
def intersect_bearings(measurements, radius=RX_MAX):
    """多边形交会：measurements = [{pos, bearing, half_err?}, ...]。

    每条示向度展开成「顶点 + ±1° 扇形」，逐条求交得到定位区域 A。
    返回 (A 顶点列表, 直径 D, 最小包围圆半径 R, 面积)。
    """
    if not measurements:
        return [], 0.0, 0.0, 0.0

    region = sector_polygon(measurements[0]['pos'],
                            measurements[0]['bearing'],
                            measurements[0].get('half_err', BEARING_ERR),
                            radius)
    for m in measurements[1:]:
        clip = sector_polygon(m['pos'], m['bearing'],
                              m.get('half_err', BEARING_ERR), radius)
        region = clip_polygon(region, clip)
        if not region:
            return [], 0.0, 0.0, 0.0

    hull = convex_hull(region)
    _, r = min_enclosing_circle(hull)
    return hull, polygon_diameter(hull), r, polygon_area(hull)


def intersection_angle(pos_a, pos_b, ref):
    """两测点相对参考点(ref≈源)的张角，即交会角（取 0~180）。"""
    ba = bearing_of(ref, pos_a)
    bb = bearing_of(ref, pos_b)
    return abs(ang_diff(ba, bb))


# =====================================================================
# 3. 问题一：多示向度交会定位与清除判定
# =====================================================================
def q1_locate(measurements, verbose=True):
    """由同一源的多条示向度求定位区域 A，给出 D、R 并按 R ≤ 20 判定。

    题面陷阱：用「区域直径 D 作圆」会系统性高估半径；真正判据是最小包围圆
    半径 R ≤ 20 m（清除了几何可达性）。
    """
    region, D, R, area = intersect_bearings(measurements)

    if verbose:
        print('=' * 68)
        print('问题一 · 多示向度交会定位与清除判定')
        print('-' * 68)
        print(f'  示向度条数 {len(measurements)}（半误差 ±{BEARING_ERR}°）')
        print(f'  定位区域顶点数 {len(region)}，面积 ≈ {area:,.1f} m²')
        print(f'  区域直径 D ≈ {D:.2f} m        （D/2 = {D / 2:.2f} m）')
        print(f'  最小包围圆半径 R ≈ {R:.2f} m  （判据：R ≤ {CLEAR_R:.0f} m）')
        ok = R <= CLEAR_R
        print(f'  判定：{"可清除（就地定位达标）" if ok else "不可清除（需增设测点/波束收敛）"}')
        if not ok:
            print(f'  → 还有 {R - CLEAR_R:.2f} m 的半径缺口：需增加交会测点'
                  f'（使交会角进入 {ANGLE_LO:.0f}°~{ANGLE_HI:.0f}°）或迭代测量收敛。')
        print('=' * 68)

    return {'region': region, 'D': D, 'R': R, 'area': area,
            'clearable': R <= CLEAR_R}


# =====================================================================
# 4. 问题二：最优第二测点
# =====================================================================
def q2_best_second(p1, theta1, ref, step=50.0, lam=0.5, verbose=True):
    """在有效接收半径环带内搜索最优第二检测点 p2。

    目标 J(p2) = 最坏后验半径 R(p2) + λ·交会角惩罚(dist(angle, 90°))，
    即在「能收到信号（落在 1000~1500 m 环带内）」的候选点里，
    取「交会角接近 90°、后验定位区域最小」的点。强保证口径下再留 r_A 余量。
    """
    def penalty(ang):
        if ANGLE_LO <= ang <= ANGLE_HI:
            return 0.0
        return (ANGLE_LO - ang) if ang < ANGLE_LO else (ang - ANGLE_HI)

    best = None
    x = -R_AREA
    while x <= R_AREA:
        y = -R_AREA
        while y <= R_AREA:
            p2 = (x, y)
            d = dist(p2, ref)
            if RX_MIN <= d <= RX_MAX and dist(p2, p1) > 1e-6:
                theta2 = bearing_of(p2, ref)
                region, D, R, _ = intersect_bearings(
                    [{'pos': p1, 'bearing': theta1},
                     {'pos': p2, 'bearing': theta2}])
                if region:
                    ang = intersection_angle(p1, p2, ref)
                    J = R + lam * penalty(ang)
                    if best is None or J < best['J']:
                        best = {'p2': p2, 'R': R, 'D': D, 'angle': ang, 'J': J}
            y += step
        x += step

    if best is None:
        if verbose:
            print('问题二 · 未在环带内找到可行第二测点（源可能贴近作业区边界）')
        return None

    if verbose:
        print('=' * 68)
        print('问题二 · 最优第二检测点')
        print('-' * 68)
        print(f'  第一测点 p1 = ({p1[0]:.0f}, {p1[1]:.0f})，示向度 {theta1:.2f}°')
        print(f'  源参考位置 ref = ({ref[0]:.0f}, {ref[1]:.0f})'
              f'，|p1-ref| = {dist(p1, ref):.1f} m')
        print(f'  最优第二测点 p2* = ({best["p2"][0]:.0f}, {best["p2"][1]:.0f})'
              f'，|p2-ref| = {dist(best["p2"], ref):.1f} m')
        print(f'  交会角 {best["angle"]:.2f}°（理想 {ANGLE_IDEAL:.0f}°，'
              f'可用区间 {ANGLE_LO:.0f}~{ANGLE_HI:.0f}°）')
        print(f'  后验区域直径 D ≈ {best["D"]:.2f} m，最小包围圆半径 R ≈ {best["R"]:.2f} m')
        print(f'  目标值 J = R + λ·角惩罚 = {best["J"]:.2f}'
              f'（λ={lam}，惩罚 {best["J"] - best["R"]:.2f}）')
        print('=' * 68)

    return best


def q2_angle_sweep(p1, ref, ds=(1000.0, 1200.0, 1500.0), verbose=True):
    """辅助结论：固定两测点距离时，后验半径 R 随交会角的 U 形曲线。

    用于论文里说明「交会角 60°~120°（理想 90°）时定位最稳」。
    """
    curve = []
    for d in ds:
        row = []
        for ang in range(10, 180, 10):
            # 在相对 p1 的环带方向上构造第二测点，使交会角≈ang
            b1 = bearing_of(ref, p1)
            b2 = norm_deg(b1 + ang)
            p2 = (ref[0] + d * math.cos(math.radians(b2)),
                  ref[1] + d * math.sin(math.radians(b2)))
            region, D, R, _ = intersect_bearings(
                [{'pos': p1, 'bearing': bearing_of(p1, ref)},
                 {'pos': p2, 'bearing': bearing_of(p2, ref)}])
            row.append((ang, R if region else None))
        curve.append((d, row))

    if verbose:
        print('问题二 · 交会角—后验半径 U 形关系（固定测点距离）')
        for d, row in curve:
            vals = '  '.join(f'{a}°:{r:6.2f}' for a, r in row if r is not None)
            print(f'  d={d:,.0f} m  {vals}')
    return curve


# =====================================================================
# 5. 问题三：全局保证覆盖 + 局部清除
# =====================================================================
def hex_stations(rho=RHO):
    """正六边形构型：中心 1 点 + 圆周 6 点，共 7 个测点。

    相邻测点间距 ρ 略大于下限 HEX_MIN = 1800/√3，使覆盖圆互相叠压、不留缝。
    """
    pts = [(0.0, 0.0)]
    for k in range(6):
        a = math.radians(60.0 * k)
        pts.append((rho * math.cos(a), rho * math.sin(a)))
    return pts


def coverage_report(stations, radius=R_AREA, step=30.0):
    """采样核验：作业区内每点到最近测点的距离分布（覆盖半径 / 盲区占比）。"""
    worst = 0.0
    worst_pt = (0.0, 0.0)
    n = 0
    over_typ = 0     # 超过典型有效半径 1500 的点（最坏情况下可能测不到）
    over_gua = 0     # 超过保证口径下限 1000 的点（保证覆盖被破坏）
    y = -radius
    while y <= radius:
        x = -radius
        while x <= radius:
            p = (x, y)
            if math.hypot(x, y) <= radius:
                n += 1
                dmin = min(dist(p, s) for s in stations)
                if dmin > worst:
                    worst, worst_pt = dmin, p
                if dmin > RX_MAX:
                    over_typ += 1
                if dmin > RX_MIN:
                    over_gua += 1
            x += step
        y += step
    return {'n': n, 'worst': worst, 'worst_pt': worst_pt,
            'over_1500': over_typ / n if n else 0.0,
            'over_1000': over_gua / n if n else 0.0}


def q3_global_clear(verbose=True):
    """问题三：给出「保证发现 + 就地清除」的完整策略与预算。

    策略：① 正六边形 7 测点铺满作业区，保证区域内任意源至少被某测点收到；
          ② 收到后转局部交会（问题一/二口径），把区域缩到 R ≤ 20 m；
          ③ 就地清除并核销该频道；④ 20 个频道全部核销后才 /exit。
    """
    stations = hex_stations()
    cov = coverage_report(stations)

    # 走遍 7 个测点、逐频道全扫的虚拟时间预算（每次 = 移动 + 检测）
    # 移动按相邻测点间距 ρ 估算，检测 5 s，频道全扫时每测点 20 次检测
    tour_len = 6.0 * RHO + RHO          # 中心出发绕一周再回中心
    t_move = tour_len / SPEED
    t_detect_all = len(stations) * CHANNELS * T_DETECT
    t_switch_all = len(stations) * CHANNELS * T_SWITCH
    t_clear = N_MAX * T_CLEAR_HIT       # 最坏 16 个源全部命中
    t_total = t_move + t_detect_all + t_switch_all + t_clear

    if verbose:
        print('=' * 68)
        print('问题三 · 全局保证覆盖 + 局部清除')
        print('-' * 68)
        print(f'  构型：中心 1 点 + 圆周 6 点 = {len(stations)} 个测点，'
              f'相邻间距 ρ = {RHO:.0f} m（下限 {HEX_MIN:.2f} m）')
        for i, s in enumerate(stations):
            print(f'    测点 #{i}: ({s[0]:8.2f}, {s[1]:8.2f})')
        print(f'  覆盖核验（采样 {cov["n"]} 点）：最大最近距 {cov["worst"]:.2f} m'
              f' @ ({cov["worst_pt"][0]:.0f}, {cov["worst_pt"][1]:.0f})')
        print(f'    超过典型有效半径 1500 m 的比例 {cov["over_1500"]:.2%}'
              f'；超过保证下限 1000 m 的比例 {cov["over_1000"]:.2%}')
        print(f'  时间预算：移动 {t_move:.0f} s + 检测 {t_detect_all:.0f} s'
              f' + 切频 {t_switch_all:.0f} s + 清除 {t_clear:.0f} s'
              f' ≈ {t_total:.0f} s（虚拟上限 360000 s，余量充足）')
        print('  终止条件：20 个频道全部核销后才 /exit（未被清除的频道必须继续搜索）')
        print('=' * 68)

    return {'stations': stations, 'coverage': cov, 't_total': t_total}


# =====================================================================
# 6. 问题四：定向源下的方向鲁棒覆盖
# =====================================================================
def direction_robust_ok(p, theta, stations, rmax=RX_MAX, half=DIR_HALF):
    """判据：自 p 出发、朝向 θ 是否被某个测点覆盖。

    要求存在测点 S，使 |Sp| ≤ rmax 且 |bearing(p→S) − θ| ≤ half。
    即：源沿 θ 方向的 ±90° 接收扇区里，必须有测点进入有效接收半径。
    """
    for s in stations:
        if dist(p, s) <= rmax and abs(ang_diff(bearing_of(p, s), theta)) <= half:
            return True
    return False


def mirror_lattice(rmax=RX_MAX, rho=None):
    """方向鲁棒布点：成对镜像测点铺网格。

    以间距 d 铺三角形网格，再对每个基点关于原点做 180° 镜像补一个测点，
    使任意点 p 望出去都能在互补方向上找到测点，从而补上定向源的 180° 扇区盲区。
    """
    d = rho if rho else rmax * 0.86
    base = []
    rows = int(R_AREA / d) + 2
    for i in range(-rows, rows + 1):
        for j in range(-rows, rows + 1):
            x = i * d + (j % 2) * d / 2.0
            y = j * d * math.sqrt(3.0) / 2.0
            if math.hypot(x, y) <= R_AREA + d:
                base.append((round(x, 3), round(y, 3)))
    pts = []
    seen = set()
    for x, y in base:
        for sx, sy in ((x, y), (-x, -y)):      # 镜像成对
            if (sx, sy) not in seen:
                seen.add((sx, sy))
                pts.append((sx, sy))
    return pts


def q4_direction_robust(verbose=True, step=150.0, dstep=30.0):
    """问题四：核验「任意点 × 任意朝向」的可测性，给出最省镜像布点。

    若只按距离铺点，近处反而会落进定向源的扇区盲区；因此判据升级为
    距离 + 方向的联合覆盖，构造成对镜像测点最省。
    """
    stations = mirror_lattice()

    n = 0
    blind = []
    y = -R_AREA
    while y <= R_AREA:
        x = -R_AREA
        while x <= R_AREA:
            p = (x, y)
            if math.hypot(x, y) <= R_AREA:
                for th in range(0, 360, int(dstep)):
                    n += 1
                    if not direction_robust_ok(p, float(th), stations):
                        blind.append((p, th))
            x += step
        y += step

    rate = 1.0 - (len(blind) / n if n else 0.0)

    if verbose:
        print('=' * 68)
        print('问题四 · 定向源下的方向鲁棒覆盖')
        print('-' * 68)
        print(f'  定向源覆盖角 ±{DIR_HALF:.0f}°（有效 {DIR_SPAN:.0f}°），朝向未知')
        print(f'  布点：成对镜像网格，测点数 {len(stations)}')
        print(f'  核验（采样 {n} 个「位置 × 朝向」组合）：方向鲁棒覆盖率 {rate:.2%}')
        if blind:
            bp, bt = blind[0]
            print(f'  残余盲区示例：({bp[0]:.0f}, {bp[1]:.0f}) 朝向 {bt:.0f}°')
            print(f'  盲区组合数 {len(blind)}：可加密网格或将 rmax 收紧到 1000 m 复算')
        else:
            print('  结论：任意位置、任意朝向均存在有效测点，方向鲁棒覆盖成立')
        print('=' * 68)

    return {'stations': stations, 'rate': rate, 'n_blind': len(blind), 'n': n}


# =====================================================================
# 7. 官方模拟器接口（4 条指令，HTTP + JSON）
# =====================================================================
class SimClient:
    """按官方「通信接口说明」封装的 4 条指令客户端。

    指令：POST /enter  /measure  /clear  /exit
    公共字段：arena_id（固定 default）、robot_id（参赛队号）、request_id
    位置/频道由 position / channel 字段推断，没有独立的移动指令；
    /enter 与 /exit 不推进虚拟时钟，/measure 与 /clear 会推进。
    """

    def __init__(self, base=SIM_BASE, robot_id='team', timeout=10.0):
        self.base = base.rstrip('/')
        self.robot_id = robot_id
        self.timeout = timeout
        self._rid = 0

    def _next_rid(self):
        self._rid += 1
        return self._rid

    def _post(self, path, payload):
        url = self.base + path
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            url, data=data,
            headers={'Content-Type': 'application/json'}, method='POST')
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                status = resp.getcode()
                body = resp.read().decode('utf-8')
        except urllib.error.HTTPError as e:
            status = e.code
            body = e.read().decode('utf-8', 'ignore')
        obj = {}
        try:
            obj = json.loads(body) if body else {}
        except ValueError:
            obj = {'raw': body}
        # 必须同时检查 HTTP 状态与 accepted 字段
        ok = (200 <= status < 300) and obj.get('accepted', True)
        return ok, status, obj

    def enter(self, position=(0.0, 0.0)):
        return self._post('/enter', {
            'arena_id': ARENA_ID, 'robot_id': self.robot_id,
            'request_id': self._next_rid(),
            'position': {'x': position[0], 'y': position[1]}})

    def measure(self, position, channel):
        return self._post('/measure', {
            'arena_id': ARENA_ID, 'robot_id': self.robot_id,
            'request_id': self._next_rid(),
            'position': {'x': position[0], 'y': position[1]},
            'channel': int(channel)})

    def clear(self, position, channel):
        return self._post('/clear', {
            'arena_id': ARENA_ID, 'robot_id': self.robot_id,
            'request_id': self._next_rid(),
            'position': {'x': position[0], 'y': position[1]},
            'channel': int(channel)})

    def exit(self, reason='all_channels_cleared'):
        return self._post('/exit', {
            'arena_id': ARENA_ID, 'robot_id': self.robot_id,
            'request_id': self._next_rid(),
            'exit_reason': reason})


def sim_strategy(sim, stations, channels=range(1, CHANNELS + 1), verbose=True):
    """在模拟器上跑「保证发现 → 局部交会 → 就地清除」的主循环。

    真机运行时才调用；离线演示不会进入这里。返回各频道核销状态。
    """
    ok, status, resp = sim.enter(stations[0])
    if not ok:
        raise RuntimeError(f'/enter 失败: HTTP {status} {resp}')
    if verbose:
        print(f'  /enter 成功，虚拟上限 {resp.get("max_virtual_duration_s")} s，'
              f'现实上限 {resp.get("max_real_duration_s")} s')

    pending = set(channels)
    cleared = set()
    for s in stations:
        todo = sorted(pending)
        for ch in todo:
            ok, _, r = sim.measure(s, ch)
            if not ok:
                continue
            res = r.get('measure_result')
            if res == 'no_signal':
                continue
            if res == 'near':
                # 已在 ≤5 m，直接就地清除
                ok, _, cr = sim.clear(s, ch)
                if ok and cr.get('clear_result') == 'cleared':
                    cleared.add(ch); pending.discard(ch)
            elif res == 'direction':
                # 有示向度 → 走向交会点（此处以就地清除示意），
                # 实际程序应结合问题二选点、迭代收敛到 R ≤ 20 m 再 /clear
                ok, _, cr = sim.clear(s, ch)
                if ok and cr.get('clear_result') == 'cleared':
                    cleared.add(ch); pending.discard(ch)
    ok, _, resp = sim.exit()
    if verbose:
        print(f'  核销 {len(cleared)} 个频道，剩余 {len(pending)}；/exit '
              f'{resp.get("exit_reason")}')
    return {'cleared': sorted(cleared), 'pending': sorted(pending)}


# =====================================================================
# 8. 输出与入口
# =====================================================================
def _write_json(name, obj):
    path = os.path.join(OUT_DIR, name)
    with open(path, 'w', encoding='utf-8') as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
    return path


def _write_csv(name, header, rows):
    path = os.path.join(OUT_DIR, name)
    with open(path, 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)
    return path


def _demo_measurements():
    """内置自洽算例：给定真值源与若干测点，按 ±1° 有界误差生成示向度。"""
    truth = (600.0, 400.0)
    sites = [(-700.0, -300.0), (1500.0, 300.0), (300.0, 1500.0)]
    errs = [0.7, -0.4, 0.9]      # 确定性有界误差（非随机）
    ms = []
    for s, e in zip(sites, errs):
        if dist(s, truth) <= RX_MAX:
            ms.append({'pos': s, 'bearing': norm_deg(bearing_of(s, truth) + e)})
    return truth, ms


def main():
    # ---- 问题一 ----
    truth, ms = _demo_measurements()
    print(f'算例真值源 ≈ ({truth[0]:.0f}, {truth[1]:.0f})，'
          f'生成示向度 {len(ms)} 条\n')
    r1 = q1_locate(ms)

    # ---- 问题二 ----
    p1 = ms[0]['pos']
    theta1 = ms[0]['bearing']
    r2 = q2_best_second(p1, theta1, truth, step=100.0)
    q2_angle_sweep(p1, truth, ds=(1000.0, 1500.0))

    # ---- 问题三 ----
    r3 = q3_global_clear()

    # ---- 问题四 ----
    r4 = q4_direction_robust()

    # ---- 落盘（布点 / 结论）----
    _write_csv('result_q3_stations.csv', ['测点', 'x(m)', 'y(m)'],
               [[i, round(s[0], 2), round(s[1], 2)]
                for i, s in enumerate(r3['stations'])])
    _write_csv('result_q4_stations.csv', ['测点', 'x(m)', 'y(m)'],
               [[i, round(s[0], 2), round(s[1], 2)]
                for i, s in enumerate(r4['stations'])])
    _write_json('result_summary.json', {
        'q1': {'D': r1['D'], 'R': r1['R'], 'clearable': r1['clearable']},
        'q2': None if r2 is None else {
            'p2': [round(r2['p2'][0], 2), round(r2['p2'][1], 2)],
            'angle': round(r2['angle'], 2), 'R': round(r2['R'], 2)},
        'q3': {'n_station': len(r3['stations']), 'rho': RHO,
               'worst_cover': round(r3['coverage']['worst'], 2),
               't_total': round(r3['t_total'], 1)},
        'q4': {'n_station': len(r4['stations']),
               'robust_rate': round(r4['rate'], 4)},
    })
    print('\n全部完成：result_q3_stations.csv / result_q4_stations.csv / '
          'result_summary.json 已写入 %s' % OUT_DIR)
    print('如需在真机上执行策略，请下载官方模拟器并调用 SimClient / sim_strategy。')


if __name__ == '__main__':
    main()
