# -*- coding: utf-8 -*-
"""
2026 美赛 D 题 · 成功的体育管理
球队业主的动态决策模型：战绩 + 财务 → 利润 / 估值 → 招募与运营策略

问题一：建立「战绩 + 财务 → 球队利润 / 估值」的现状模型与指标体系，辨识关键驱动
问题二：建立随战绩与财务滚动调整阵容招募与运营策略的动态决策模型（动态规划）
问题三：门票定价优化——票务收入最大 / 贡献利润最大 / 球迷效用均衡 三目标对比
问题四：场馆投资与长期价值——NPV、估值增量、敏感性分析与稳健性

运行环境：Python 3.10+ 与 numpy（无其它第三方依赖）
单位约定：金额 M$（百万美元）；票价 / 附加收入 / 边际成本 $ 每人；容量 / 需求 座每场
"""

import numpy as np

# ═══════════════════════════════════════════════════════════════
# 0. 球队与联赛基准设定
# ═══════════════════════════════════════════════════════════════
CAP    = 18000.0     # 主场容量（座）
GAMES  = 41.0        # 每赛季主场场次
P_CUR  = 75.0        # 现状平均票价（$）
ANC    = 22.0        # 每人餐饮 / 纪念品附加收入（$）
VC     = 15.0        # 每人边际服务成本（$）
LEAGUE = 130.0       # 联盟媒体分成与公共收入（M$ / 赛季）
FIXED  = 95.0        # 场馆运营、差旅等固定成本（M$ / 赛季）
PS_AVG = 140.0       # 联盟平均薪资总额（M$）
R_AVG  = 50.0        # 联盟平均阵容评级
K_TAL  = 18.0        # 薪资 → 阵容评级的对数弹性（递减回报）
S_WIN  = 8.0         # 战绩 logistic 斜率
W_LO, W_HI = 0.25, 0.72
M0, M1 = 15.0, 95.0  # 商品与本地收入 = M0 + M1·w（M$）
S0, S1 = 10.0, 85.0  # 赞助收入 = S0 + S1·w（M$）
D0, K_W, K_P = 12000.0, 9000.0, 70.0   # 每场需求（座）= D0 + K_W(w-0.5) - K_P(p-P_CUR)
VAL_MULT = 6.0       # 球队估值倍数：V = VAL_MULT × 年收入

PS_TIERS = np.array([110.0, 135.0, 160.0, 185.0, 210.0])   # 薪资档位（M$）
OPS      = np.array([0.0, 8.0, 20.0])                      # 运营投入档（M$）
OPS_MULT = np.array([1.00, 1.07, 1.15])                    # 对（商品+赞助）收入的放大


# ── 模型方程 ─────────────────────────────────────────────────
def roster_rating(ps):
    """薪资总额 PS → 阵容评级 R（对数递减回报）。"""
    return R_AVG + K_TAL * np.log(np.asarray(ps, dtype=float) / PS_AVG)


def win_rate(ps):
    """阵容评级 → 胜率 w（logistic 映射，截断到 [W_LO, W_HI]）。"""
    r = roster_rating(ps)
    w = 1.0 / (1.0 + np.exp(-(r - R_AVG) / S_WIN))
    return np.clip(w, W_LO, W_HI)


def demand(w, p, uplift=0.0):
    """每场需求（座）：随胜率线性上升、随票价线性下降，接上场馆体验上移量 uplift。"""
    return D0 + uplift + K_W * (w - 0.5) - K_P * (p - P_CUR)


def season(w, p, ps, oi, anc=ANC, cap=CAP, uplift=0.0, extra_cost=0.0):
    """单赛季完整损益：返回收入 / 成本 / 利润等一揽子指标。"""
    d    = demand(w, p, uplift)
    att  = min(d, cap)                      # 容量约束：超出容量的需求不予服务
    gate = GAMES * att * p                  # 门票收入（$）→ M$
    ancr = GAMES * att * anc                # 餐饮 / 纪念品附加收入
    mult = OPS_MULT[oi]
    mrch = mult * (M0 + M1 * w)             # 商品与本地收入
    spon = mult * (S0 + S1 * w)             # 赞助收入
    rev  = (gate + ancr) / 1e6 + mrch + spon + LEAGUE
    var  = GAMES * att * VC / 1e6           # 按到场上座数计的边际服务成本
    cost = ps + FIXED + var + OPS[oi] + extra_cost
    return dict(d=d, att=att, util=att / cap, gate=gate / 1e6, anc=ancr / 1e6,
                merch=mrch, spon=spon, league=LEAGUE, rev=rev, var=var,
                payroll=ps, fixed=FIXED, ops=OPS[oi] + extra_cost,
                cost=cost, profit=rev - cost, value=VAL_MULT * rev)


def lattice():
    """15 个（薪资档 × 运营档）组合的胜率与利润网格。"""
    tab = []
    for i, ps in enumerate(PS_TIERS):
        w = float(win_rate(ps))
        for j in range(len(OPS)):
            s = season(w, P_CUR, ps, j)
            tab.append((i, j, w, s))
    return tab


# ═══════════════════════════════════════════════════════════════
# 1. 问题一：现状模型与指标体系 · 关键驱动辨识
# ═══════════════════════════════════════════════════════════════
CUR_I, CUR_J = 2, 1                       # 现状：薪资 160 M$ / 运营投入 8 M$
W_CUR = float(win_rate(PS_TIERS[CUR_I]))
S_CUR = season(W_CUR, P_CUR, PS_TIERS[CUR_I], CUR_J)

print("=" * 74)
print("问题一  现状建模与指标体系（现状：薪资 160 M$ · 运营投入 8 M$ · 票价 $75）")
print("=" * 74)
print("  战绩链路：薪资 PS → 阵容评级 R → 胜率 w → 需求 / 收入 / 估值")
print("    R = 50 + 18·ln(PS/140)        w = 1/(1+exp(-(R-50)/8))  ∈ [0.25, 0.72]")
print("    V = 6.0 × 年收入（估值倍数）   利润 = 收入 − 成本")
print("  现状计算结果：")
print("    R = %.2f   胜率 w = %.4f  →  换算成 82 场赛季约 %.1f 胜"
      % (roster_rating(PS_TIERS[CUR_I]), W_CUR, 82 * W_CUR))
print("    每场需求 %.0f 座 / 容量 %.0f 座  →  上座率 %.1f%%"
      % (S_CUR["d"], CAP, S_CUR["util"] * 100))
print("    收入结构 (M$)：门票 %.2f | 附加 %.2f | 商品 %.2f | 赞助 %.2f | 联盟分成 %.2f"
      % (S_CUR["gate"], S_CUR["anc"], S_CUR["merch"], S_CUR["spon"], S_CUR["league"]))
print("    总收入 %.2f   总成本 %.2f（薪资 %.0f / 固定 %.0f / 边际 %.2f / 运营 %.0f）"
      % (S_CUR["rev"], S_CUR["cost"], S_CUR["payroll"], S_CUR["fixed"],
         S_CUR["var"], S_CUR["ops"]))
print("    利润 %.2f M$   利润率 %.1f%%   薪资收入比 %.1f%%   估值 %.1f M$"
      % (S_CUR["profit"], S_CUR["profit"] / S_CUR["rev"] * 100,
         S_CUR["payroll"] / S_CUR["rev"] * 100, S_CUR["value"]))

# —— 关键驱动辨识：各驱动做同等强度的扰动，看利润变化幅度 ——
print("\n  关键驱动辨识（对现状基准做 +10% 扰动，比较利润变化幅度）：")
drivers = [
    ("票价 p",      lambda: season(W_CUR, P_CUR * 1.10, PS_TIERS[CUR_I], CUR_J)["profit"]),
    ("薪资 PS",     lambda: season(float(win_rate(PS_TIERS[CUR_I] * 1.10)), P_CUR,
                                   PS_TIERS[CUR_I] * 1.10, CUR_J)["profit"]),
    ("附加收入 ANC", lambda: season(W_CUR, P_CUR, PS_TIERS[CUR_I], CUR_J, anc=ANC * 1.10)["profit"]),
    ("运营投入",     lambda: season(W_CUR, P_CUR, PS_TIERS[CUR_I], 2)["profit"]),
    ("战绩 w(+0.05)", lambda: season(W_CUR + 0.05, P_CUR, PS_TIERS[CUR_I], CUR_J)["profit"]),
]
base_p = S_CUR["profit"]
rank = []
for nm, fn in drivers:
    dp = fn() - base_p
    rank.append((nm, dp, fn()))
    print("    %-14s 利润 %.2f M$   变化 %+7.2f M$  （%+6.1f%%）"
          % (nm, fn(), dp, dp / base_p * 100))
rank.sort(key=lambda x: -abs(x[1]))
print("  敏感度排序（按利润绝对变化）：" + " > ".join(r[0] for r in rank))
print("  结论：战绩是最强驱动——胜率仅提高 0.05 就带来 +23.7% 的利润；薪资与票价分列二、三，")
print("        且方向相反（薪资是成本项、票价是收入项）；运营投入与附加收入属可调小项。")
print("        KPI 体系由此定为四类：竞技（胜率 / 胜场）、财务（利润率 / 薪资收入比）、")
print("        资产（估值 / 估值倍数）、球迷（上座率 / 消费者剩余）。")

# ═══════════════════════════════════════════════════════════════
# 2. 问题二：动态决策模型（5 年动态规划 + 三条对照策略）
# ═══════════════════════════════════════════════════════════════
GAMMA  = 1.0 / 1.08          # 年折现因子（r = 8%）
SW_UP, SW_DN = 18.0, 25.0    # 上调档位（签约）与下调档位（买断）的成本
T_YEAR = 5

tab = lattice()
PROF = np.zeros((5, 3)); WGRID = np.zeros((5, 3)); VAL = np.zeros((5, 3))
for i, j, w, s in tab:
    PROF[i, j] = s["profit"]; WGRID[i, j] = w; VAL[i, j] = s["value"]


def switch_cost(sp, s):
    """从档位 sp 换到 s 的换档成本（升档签约 / 降档买断 / 持平免费）。"""
    return SW_UP if s > sp else (SW_DN if s < sp else 0.0)


print("\n" + "=" * 74)
print("问题二  动态决策模型：薪资档 × 运营档 的 5 年最优路径（动态规划）")
print("=" * 74)
print("  状态 (i,j) = (薪资档 PS_TIERS[i], 运营档 OPS[j])，共 5×3 = 15 个状态")
print("  动作：每年薪资档与运营档各自最多移动一档（阵容重建需要时间）")
print("  递推式 F_t(sp) = max_s [ Π(s) − 换档(sp,s) + γ·F_{t+1}(s) ]，F_6(s) = V(s)")
print("  业主效用口径：5 年经营利润现值 + 末年球队估值现值（γ = 1/1.08）")
print("  薪资档位：", " / ".join("%.0f" % x for x in PS_TIERS), " M$")
print("  各档战绩：", " / ".join("%.3f" % float(win_rate(x)) for x in PS_TIERS))
print("  各档利润 (M$)，按运营档 0 / 8 / 20：")
for i in range(5):
    print("    PS=%3.0f  胜率 %.3f  →  %7.2f | %7.2f | %7.2f"
          % (PS_TIERS[i], WGRID[i, 0], PROF[i, 0], PROF[i, 1], PROF[i, 2]))

# —— 动态规划：后向递推（状态 15 个，动作限制为「档位最多移动一档」）——
states = [(i, j) for i in range(5) for j in range(3)]
ACTS = {sp: [s for s in states
             if abs(s[0] - sp[0]) <= 1 and abs(s[1] - sp[1]) <= 1] for sp in states}
F = {s: VAL[s] for s in states}            # F_6：末年估值（在 γ^5 处折现）
POLS = {}
for t in range(T_YEAR, 0, -1):
    Fn, Pn = {}, {}
    for sp in states:
        bestv, bests = -1e18, None
        for s in ACTS[sp]:
            v = PROF[s] - switch_cost(sp[0], s[0]) + GAMMA * F[s]
            if v > bestv:
                bestv, bests = v, s
        Fn[sp], Pn[sp] = bestv, bests
    F, POLS[t] = Fn, Pn

# 路径回溯：第 1 年从现状 (2,1) 出发，逐年按第 t 年策略前进
path = []
cur = (CUR_I, CUR_J)
for t in range(1, T_YEAR + 1):
    cur = POLS[t][cur]
    path.append(cur)
U_DP = F[(CUR_I, CUR_J)]

print("\n  动态规划最优路径（第 1 年由现状出发，逐年执行）：")
print("    年份  薪资(M$)  运营(M$)   胜率    利润(M$)   换档成本(M$)")
prev = (CUR_I, CUR_J)
prof_dp = []
for t, s in enumerate(path, start=1):
    sc = switch_cost(prev[0], s[0]); prev = s
    prof_dp.append(PROF[s]); print("    第%d年   %6.0f   %6.0f   %.3f   %8.2f   %8.1f"
          % (t, PS_TIERS[s[0]], OPS[s[1]], WGRID[s], PROF[s], sc))
print("    末年估值 V(第5年) = %.1f M$" % VAL[path[-1]])
print("  动态规划最优业主效用 U* = %.1f M$（经营利润现值 + 末年估值现值）" % U_DP)


def eval_strategy(name, states_path):
    """对给定年度状态序列评估：累计利润、平均胜率、末年估值、业主效用。"""
    prev = (CUR_I, CUR_J); pv = 0.0; tot = 0.0; avg_w = 0.0
    for t, s in enumerate(states_path, start=1):
        sc = switch_cost(prev[0], s[0]); prev = s
        pv += GAMMA ** (t - 1) * (PROF[s] - sc)
        tot += PROF[s]; avg_w += WGRID[s]
    last = states_path[-1]
    v_pv = GAMMA ** T_YEAR * VAL[last]
    return tot, avg_w / T_YEAR, VAL[last], pv + v_pv, states_path


# 三条对照策略（第 1 年为现状，第 2 年起执行）
sA = [(2, 1)] + [(4, 2)] * 4                       # A 争冠冲刺：直接堆到顶档 + 高运营
sB = [(2, 1)] + [(0, 0)] * 4                       # B 稳健经营：砍到最低档 + 零运营

# C 自适应反馈：以目标胜率 0.60 为标尺修正薪资档，按利润率修正运营档
sC = [(CUR_I, CUR_J)]
for t in range(2, T_YEAR + 1):
    ti, oj = sC[-1]
    w_last = WGRID[sC[-1]]
    err = w_last - 0.60
    ti = int(np.clip(ti + (1 if err < -0.03 else (-1 if err > 0.05 else 0)), 0, 4))
    marg = PROF[sC[-1]] / season(WGRID[sC[-1]], P_CUR, PS_TIERS[sC[-1][0]], sC[-1][1])["rev"]
    oj = int(np.clip(oj + (1 if marg > 0.13 else (-1 if marg < 0.08 else 0)), 0, 2))
    sC.append((ti, oj))

print("\n  三条对照策略 vs 动态规划最优（均为 5 年窗，含换档成本与末年估值）：")
print("    %-22s%12s%11s%14s%14s" % ("策略", "累计利润(M$)", "平均胜率", "末年估值(M$)", "业主效用(M$)"))
res_rows = []
for nm, sp in [("A 争冠冲刺（顶档+高运营）", sA),
               ("B 稳健经营（最低档+零运营）", sB),
               ("C 自适应反馈（目标胜率0.60）", sC),
               ("DP 最优路径", path)]:
    row = eval_strategy(nm, sp)
    res_rows.append((nm, row))
    print("    %-22s%12.2f%11.3f%14.1f%14.1f" % (nm, row[0], row[1], row[2], row[3]))
print("  结论：追求年度利润最大（策略 B）会牺牲估值；自适应反馈（策略 C）以战绩为标尺")
print("        动态修正投入，其业主效用 %.1f M$，接近动态规划最优 %.1f M$ 的 %.1f%%；"
      % (res_rows[2][1][3], U_DP, res_rows[2][1][3] / U_DP * 100))
print("        自适应规则可在信息不完整时逼近最优路径，是动态决策模型的落地形式。")

# ═══════════════════════════════════════════════════════════════
# 3. 问题三：门票定价优化（三目标对比）
# ═══════════════════════════════════════════════════════════════
W_P = W_CUR                                  # 定价分析沿用现状战绩
A1  = D0 + K_W * (W_P - 0.5) + K_P * P_CUR   # 需求截距：D(p) = A1 − K_P·p
MARGIN = ANC - VC                            # 每人边际贡献（票价之外）
P_CHOKE = A1 / K_P                           # 需求恰好为 0 的价格上界

P_REV  = A1 / (2 * K_P)                      # 目标①票务收入最大：max p·D(p)
P_PROF = (A1 - K_P * MARGIN) / (2 * K_P)     # 目标②贡献利润最大：max D(p)·(p+收益边际)
LAM    = 0.25                                # 目标③球迷效用权重
P_BAL  = (A1 * (1 - LAM) - K_P * MARGIN) / (2 * K_P - K_P * LAM)


def pricing(p):
    d = A1 - K_P * p
    att = min(d, CAP)
    ticket = GAMES * att * p / 1e6
    ancr = GAMES * att * ANC / 1e6
    contrib = (GAMES * att * (p + MARGIN)) / 1e6           # 门票 + 附加 − 边际成本
    cs = GAMES * 0.5 * (P_CHOKE - p) * att / 1e6           # 球迷效用（赛季消费者剩余）
    return dict(p=p, d=d, util=att / CAP, ticket=ticket, anc=ancr,
                contrib=contrib, cs=cs, elast=K_P * p / d if d > 0 else np.inf)


print("\n" + "=" * 74)
print("问题三  门票定价优化（现状战绩 w = %.4f，容量 %d 座，附加 %.0f $/人）" % (W_P, CAP, ANC))
print("=" * 74)
print("  需求曲线：D(p) = A1 − 70p，A1 = %.0f，需求为零的价格上界 $%.0f" % (A1, P_CHOKE))
print("  边际贡献口径：门票 + 附加 %.0f − 边际成本 %.0f = 每人 %.0f $（不含薪资与固定成本，属既定支出）"
      % (ANC, VC, MARGIN))
print("  %-26s%9s%9s%9s%11s%11s%11s%9s"
      % ("定价目标", "票价$", "需求座", "上座率", "票务(M$)", "贡献(M$)", "球迷效用(M$)", "弹性"))
plan = [("现状价（$75）", P_CUR), ("① 票务收入最大", P_REV),
        ("② 贡献利润最大", P_PROF), ("③ 均衡（利润+25%球迷效用）", P_BAL)]
prices = {}
for nm, p in plan:
    r = pricing(p); prices[nm] = r
    print("  %-26s%9.2f%9.0f%9.1f%%%11.2f%11.2f%11.2f%9.3f"
          % (nm, r["p"], r["d"], r["util"] * 100, r["ticket"], r["contrib"], r["cs"], r["elast"]))
BEST = prices["② 贡献利润最大"]
BAL  = prices["③ 均衡（利润+25%球迷效用）"]
SPREAD = abs(BAL["contrib"] - prices["① 票务收入最大"]["contrib"]) / BEST["contrib"] * 100
print("  贡献利润最高价 = $%.2f（%.2f M$）；均衡价 $%.2f 比其低 %.1f%%，但球迷效用 %.2f → %.2f M$（+%.1f%%），"
      % (BEST["p"], BEST["contrib"], BAL["p"], (BEST["contrib"] - BAL["contrib"]) / BEST["contrib"] * 100,
         BEST["cs"], BAL["cs"], (BAL["cs"] / BEST["cs"] - 1) * 100))
print("    上座率 %.1f%% → %.1f%%。" % (BEST["util"] * 100, BAL["util"] * 100))
print("  结论：在 $%.0f–$%.0f 这一整段宽区间内，贡献利润最大差异仅约 %.1f%%，定价对利润不敏感、"
      % (BAL["p"], prices["① 票务收入最大"]["p"], SPREAD))
print("        对上座率与球迷效用高度敏感；因此取区间偏低一侧的均衡价 $%.2f，用约 %.1f%% 的利润让步"
      % (BAL["p"], (BEST["contrib"] - BAL["contrib"]) / BEST["contrib"] * 100))
print("        换取球迷效用 +%.1f%%、上座率 +%.1f 个百分点的双赢结果。"
      % ((BAL["cs"] / BEST["cs"] - 1) * 100, (BAL["util"] - BEST["util"]) * 100))

# ═══════════════════════════════════════════════════════════════
# 4. 问题四：场馆投资与长期价值
# ═══════════════════════════════════════════════════════════════
CAPEX    = 420.0     # 新馆 / 大修总投资（M$）
TEAM_SH  = 0.40      # 球队自有出资占比（其余为公共部门分担）
UPLIFT   = 7500.0    # 新馆带来的每场需求上移（座）
ANC_NEW  = 30.0      # 新馆附加收入（$ / 人，含包厢与高端餐饮）
CAP_NEW  = 21500.0   # 新馆容量（座）
OPEX_NEW = 14.0      # 新增年运营成本（M$）
HORIZON  = 15        # 评估期（年）
R_DISC   = 0.07      # 折现率


def af(r, n):
    """年金现值系数。"""
    return (1.0 - (1.0 + r) ** -n) / r


I_TOP, J_TOP = 4, 2                       # 以争冠态（顶档 + 高运营）评估长期投资
W_TOP = float(WGRID[I_TOP, J_TOP])
BASE = season(W_TOP, P_BAL, PS_TIERS[I_TOP], J_TOP)
NEW  = season(W_TOP, P_BAL, PS_TIERS[I_TOP], J_TOP, anc=ANC_NEW, cap=CAP_NEW,
              uplift=UPLIFT, extra_cost=OPEX_NEW)

print("\n" + "=" * 74)
print("问题四  场馆投资与长期价值（争冠态 w = %.3f，定价用均衡价 $%.2f）" % (W_TOP, P_BAL))
print("=" * 74)
print("  投资方案：总投资 %.0f M$（球队出资 %.0f%% = %.0f M$，公共分担 %.0f%%），"
      % (CAPEX, TEAM_SH * 100, CAPEX * TEAM_SH, (1 - TEAM_SH) * 100))
print("            容量 %.0f → %.0f 座、每场需求上移 +%.0f 座、附加收入 %.0f → %.0f $/人、年运营 +%.0f M$"
      % (CAP, CAP_NEW, UPLIFT, ANC, ANC_NEW, OPEX_NEW))
print("  %-14s%12s%12s%12s" % ("指标", "现状场馆", "新馆", "增量"))
for nm, a, b in [("每场需求(座)", BASE["d"], NEW["d"]),
                 ("上座率", BASE["util"] * 100, NEW["util"] * 100),
                 ("总收入(M$)", BASE["rev"], NEW["rev"]),
                 ("运营利润(M$)", BASE["profit"], NEW["profit"]),
                 ("球队估值(M$)", BASE["value"], NEW["value"])]:
    print("  %-14s%12.2f%12.2f%12.2f" % (nm, a, b, b - a))
D_REV = NEW["rev"] - BASE["rev"]
D_PROF = NEW["profit"] - BASE["profit"]

npv_op  = D_PROF * af(R_DISC, HORIZON) - CAPEX * TEAM_SH
d_value = VAL_MULT * D_REV
payback = CAPEX * TEAM_SH / D_PROF
npv_all = npv_op + d_value
print("\n  增量年收入 %.2f M$、增量年利润 %.2f M$" % (D_REV, D_PROF))
print("  15 年增量现金流现值 %.2f M$（年金系数 %.3f）− 球队出资 %.0f M$ = 经营口径 NPV %+.2f M$"
      % (D_PROF * af(R_DISC, HORIZON), af(R_DISC, HORIZON), CAPEX * TEAM_SH, npv_op))
print("  静态回收期 %.1f 年；球队估值增量 ΔV = %.2f M$" % (payback, d_value))
print("  业主视角综合净收益（经营 NPV + 估值增量）= %+.2f M$   → 结论：基准情景下投资可行" % npv_all)

print("\n  敏感性分析（对增量年利润 D_PROF 与经营口径 NPV、估值增量 ΔV）：")
print("    %-30s%12s%13s%13s" % ("情景", "Δ利润(M$)", "NPV(M$)", "ΔV(M$)"))
cases = [
    ("悲观：需求上移 +4500 座", dict(uplift=4500.0, anc=26.0, capex=CAPEX, r=R_DISC)),
    ("基准：需求上移 +7500 座", dict(uplift=7500.0, anc=30.0, capex=CAPEX, r=R_DISC)),
    ("乐观：需求上移 +10500 座", dict(uplift=10500.0, anc=34.0, capex=CAPEX, r=R_DISC)),
    ("造价超支 +20%", dict(uplift=7500.0, anc=30.0, capex=CAPEX * 1.20, r=R_DISC)),
    ("折现率升至 9%", dict(uplift=7500.0, anc=30.0, capex=CAPEX, r=0.09)),
    ("折现率降至 5%", dict(uplift=7500.0, anc=30.0, capex=CAPEX, r=0.05)),
]
for nm, c in cases:
    s2 = season(W_TOP, P_BAL, PS_TIERS[I_TOP], J_TOP, anc=c["anc"], cap=CAP_NEW,
                uplift=c["uplift"], extra_cost=OPEX_NEW)
    dp = s2["profit"] - BASE["profit"]
    nv = dp * af(c["r"], HORIZON) - c["capex"] * TEAM_SH
    print("    %-30s%12.2f%13.2f%13.2f" % (nm, dp, nv, VAL_MULT * (s2["rev"] - BASE["rev"])))

# 保本门槛：需求上移多少座才抵得住新增运营与球队出资
lo, hi = 500.0, 20000.0
for _ in range(80):
    mid = (lo + hi) / 2
    s2 = season(W_TOP, P_BAL, PS_TIERS[I_TOP], J_TOP, anc=ANC_NEW, cap=CAP_NEW,
                uplift=mid, extra_cost=OPEX_NEW)
    if s2["profit"] - BASE["profit"] < CAPEX * TEAM_SH / af(R_DISC, HORIZON):
        lo = mid
    else:
        hi = mid
print("  稳健性：需求上移需 ≥ %.0f 座 / 场，才能覆盖新增运营成本与 %.0f M$ 球队出资（15 年、r=7%%）"
      % (hi, CAPEX * TEAM_SH))
print("          基准方案 +%.0f 座，安全边际 %.0f%%；悲观情景（+4500 座）经营口径 NPV 为负，"
      % (UPLIFT, (UPLIFT / hi - 1) * 100))
s_pess = season(W_TOP, P_BAL, PS_TIERS[I_TOP], J_TOP, anc=26.0, cap=CAP_NEW,
                uplift=4500.0, extra_cost=OPEX_NEW)
print("          但其估值增量 %.2f M$ 可抵消经营缺口 %.2f M$，综合净收益仍为 %+.2f M$。"
      % (VAL_MULT * (s_pess["rev"] - BASE["rev"]),
         -(s_pess["profit"] - BASE["profit"]) * af(R_DISC, HORIZON) + CAPEX * TEAM_SH,
         (s_pess["profit"] - BASE["profit"]) * af(R_DISC, HORIZON) - CAPEX * TEAM_SH
         + VAL_MULT * (s_pess["rev"] - BASE["rev"])))
print("          结论：场馆投资在基准与乐观情景下均可行，其价值主要来自估值增量而非年度现金流；")
print("                决策的关键变量是「需求上移能否兑现」，故建议分期建设、以首年上座率作为继续投入的触发条件。")
print("=" * 74)
