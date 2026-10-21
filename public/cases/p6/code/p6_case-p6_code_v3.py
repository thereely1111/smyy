# -*- coding: utf-8 -*-
"""
2026 美赛 A 题 · 智能手机电池耗电建模

问题一：建立锂电池 SOC 的连续时间模型，用放电日志辨识模型参数
问题二：预测四种典型使用场景下的剩余可用时间（续航）
问题三：给出三条可执行建议并量化续航增益
问题四：对模型系数做灵敏度分析，判定第一敏感参数

运行环境：Python 3.10 + numpy（无其它第三方依赖）

模型： dSOC/dt = -P_total / C * 100%
       P_total = phi(T) * (P_base + a_s*L + a_c*u + a_n*n)
       phi(T)  = 1 + 0.006|T-25| + 0.00035(T-25)^2
载荷： L 屏幕亮度(0~1)  u 处理器负载(0~1)  n 后台网络活跃度(0~1)  T 环境温度(°C)
"""

import numpy as np

# ────────────────────────────────────────────────────────────────
# 0. 试验数据说明
#    200 组放电试验，每组 50 个 SOC 采样点（共 10000 个观测）
#    额定能量 C = 4000 mAh x 3.85 V = 15.4 Wh
#    SOC 采样噪声 sigma = 0.021 SOC；时长记录抖动 5.8%
# ────────────────────────────────────────────────────────────────
N_EXP, N_PT = 200, 50
SOC_HI, SOC_LO = 0.96, 0.32
SIGMA_SOC, SIGMA_DUR = 0.021, 0.058
SIGMA_USE = 0.05                     # 同一场景重复使用的载荷波动（用于置信带）
C_WH = 15.4
P_TRUE = np.array([0.150, 1.350, 1.800, 1.100])   # P_base, a_s, a_c, a_n
rng = np.random.default_rng(1)


def phi(T):
    """温度修正因子：25°C 为基准；偏离越远，内阻与极化损耗越大，整机功耗越高。"""
    T = np.asarray(T, dtype=float)
    return 1.0 + 0.006 * np.abs(T - 25.0) + 0.00035 * (T - 25.0) ** 2


def power(L, u, n, T, p=P_TRUE):
    """整机平均功率 P_total（W）。"""
    return phi(T) * (p[0] + p[1] * L + p[2] * u + p[3] * n)


def endurance(L, u, n, T, p=P_TRUE):
    """从当前载荷放电至关机的剩余可用时间（h）。"""
    return C_WH / power(L, u, n, T, p)


# ────────────────────────────────────────────────────────────────
# 1. 生成试验数据（模拟手机电池统计口的放电日志）
# ────────────────────────────────────────────────────────────────
L = rng.uniform(0.10, 1.00, N_EXP)
u = rng.uniform(0.05, 1.00, N_EXP)
n = rng.uniform(0.00, 0.90, N_EXP)
T = rng.uniform(-10.0, 40.0, N_EXP)

dur = (SOC_HI - SOC_LO) * C_WH / power(L, u, n, T)          # 真实放电时长
dur_obs = dur * (1.0 + rng.normal(0.0, SIGMA_DUR, N_EXP))   # 记录时长（含抖动）
t_mat = np.array([np.linspace(0.0, d, N_PT) for d in dur_obs])
soc_true = SOC_HI - power(L, u, n, T)[:, None] / C_WH * t_mat
soc_obs = np.clip(soc_true + rng.normal(0.0, SIGMA_SOC, soc_true.shape), 0.0, 1.0)

# ────────────────────────────────────────────────────────────────
# 2. 问题一：参数辨识
#    SOC_HI - SOC_obs = phi(T)*(P_base + a_s*L + a_c*u + a_n*n) * t / C
#    是四个系数的线性方程，把 10000 个采样点池化后做最小二乘即可辨识
# ────────────────────────────────────────────────────────────────
ph = phi(T)
X = np.stack([
    ph[:, None] * t_mat / C_WH,
    (ph * L)[:, None] * t_mat / C_WH,
    (ph * u)[:, None] * t_mat / C_WH,
    (ph * n)[:, None] * t_mat / C_WH,
], axis=-1).reshape(-1, 4)                    # (试验 x 采样点) -> (N_EXP*N_PT) x 4
y = (SOC_HI - soc_obs).flatten()

coef, *_ = np.linalg.lstsq(X, y, rcond=None)
P_FIT = np.array([round(c, 2) for c in coef])  # 报告值（保留两位小数）
rX = X @ coef - y
sd = np.sqrt(np.diag(np.linalg.inv(X.T @ X)) * (rX @ rX / (len(y) - 4)))   # 系数标准差(W)

soc_pred = SOC_HI - power(L, u, n, T, P_FIT)[:, None] / C_WH * t_mat
res = soc_pred - soc_obs
rmse = float(np.sqrt(np.mean(res ** 2)))
maxdev = float(np.max(np.abs(res)))
r2 = float(1.0 - np.sum(res ** 2) / np.sum((soc_obs - soc_obs.mean()) ** 2))
dur_pred = (SOC_HI - SOC_LO) * C_WH / power(L, u, n, T, P_FIT)
mape = float(np.mean(np.abs(dur_pred - dur_obs) / dur_obs) * 100.0)

print("=" * 64)
print("问题一  参数辨识（池化最小二乘，%d 组试验 / %d 个采样点）" % (N_EXP, N_EXP * N_PT))
print("=" * 64)
for nm, v in zip(["P_base  静态底耗 (W)", "a_s     亮度系数 (W)", "a_c     负载系数 (W)",
                  "a_n     网络系数 (W)"], P_FIT):
    print("  %-22s = %.2f" % (nm, v))
print("  phi(T) = 1 + 0.006|T-25| + 0.00035(T-25)^2")
print("  拟合优度：R2 = %.3f    RMSE = %.3f SOC    MAPE = %.1f%%" % (r2, rmse, mape))
print("  最大瞬时偏差 = %.3f SOC" % maxdev)

# ────────────────────────────────────────────────────────────────
# 3. 问题二：四场景续航预测
#    置信带 = 参数不确定度（delta 法传播）与场景载荷波动(5%)的合成 95% 带
# ────────────────────────────────────────────────────────────────
SCEN = [
    ("A 轻度    待机 / 轻度刷屏",   0.20, 0.10, 0.05, 25.0),
    ("B 中度    常规使用",          0.55, 0.35, 0.40, 25.0),
    ("C 重度    游戏 + 高清视频",   0.90, 0.90, 0.80, 25.0),
    ("D 极端    高温户外导航",      0.70, 0.50, 0.60, 35.0),
]
print("\n" + "=" * 64)
print("问题二  四场景续航预测（电池能量 C = %.1f Wh）" % C_WH)
print("=" * 64)
print("%-24s%11s%10s%13s" % ("场景", "P_total(W)", "续航(h)", "95% 带(h)"))
scen = []
for nm, Ls, us, ns, Ts in SCEN:
    g = phi(Ts) * np.array([1.0, Ls, us, ns])
    P = float(g @ P_FIT)
    tt = C_WH / P
    s_param = C_WH / P ** 2 * float(np.sqrt(np.sum((g * sd) ** 2)))
    band = 1.96 * float(np.sqrt(s_param ** 2 + (SIGMA_USE * tt) ** 2))
    scen.append((nm, P, tt, band))
    print("%-24s%11.3f%10.2f%13s" % (nm, P, tt, "±%.2f" % band))

# ────────────────────────────────────────────────────────────────
# 4. 问题三：三条优化建议（基线取场景 B）
# ────────────────────────────────────────────────────────────────
base = scen[1][2]
ADV = [
    ("亮度阈值 0.55 -> 0.35",         0.35, 0.35, 0.40, 25.0, "强光下可视性下降", 1),
    ("后台网络 0.40 -> 0.22",         0.55, 0.35, 0.22, 25.0, "消息延迟约 15 min", 1),
    ("温控散热 35 -> 25°C（场景 D）",  0.70, 0.50, 0.60, 25.0, "需主动散热外壳", 3),
]
print("\n" + "=" * 64)
print("问题三  续航优化建议（基线 = 场景 B，%.2f h）" % base)
print("=" * 64)
gains = []
for nm, Ls, us, ns, Ts, cost, ri in ADV:
    tt = C_WH / float(power(Ls, us, ns, Ts, P_FIT))
    ref = scen[ri][2]
    gains.append(tt - ref)
    print("  %-28s 续航 %5.2f h   增益 %+5.1f%%   代价：%s" % (nm, tt, (tt / ref - 1) * 100, cost))
tt12 = C_WH / float(power(0.35, 0.35, 0.22, 25.0, P_FIT))
print("  %-28s 续航 %5.2f h   增益 %+5.1f%%" % ("联合（亮度 + 网络）", tt12, (tt12 / base - 1) * 100))
print("\n  逐条增益 +%.2f h、+%.2f h，合计 +%.2f h；联合实际 +%.2f h"
      % (gains[0], gains[1], gains[0] + gains[1], tt12 - base))
print("  原因：t = C/P 对功率是凸函数，同时压低多项载荷的收益大于逐条收益之和。")

# ────────────────────────────────────────────────────────────────
# 5. 问题四：灵敏度（±10% 单变量扰动，基线场景 B）
# ────────────────────────────────────────────────────────────────
print("\n" + "=" * 64)
print("问题四  灵敏度分析（各系数 ±10%，基线场景 B）")
print("=" * 64)
sens = []
for nm, idx in [("a_s", 1), ("a_c", 2), ("a_n", 3), ("P_base", 0)]:
    p2 = P_FIT.copy()
    p2[idx] *= 1.10
    tt = C_WH / float(power(0.55, 0.35, 0.40, 25.0, p2))
    sens.append((nm, tt / base - 1))
    print("  %-8s +10%%  ->  续航变化 %+.2f%%" % (nm, (tt / base - 1) * 100))
print("  敏感度排序：" + " > ".join(s[0] for s in sorted(sens, key=lambda x: x[1])))
print("  温度影响：|T-25| 每升高 5°C，phi 上升约 %.1f%%，续航下降约 %.1f%%"
      % ((phi(30.0) - 1) * 100, (1 - 1 / phi(30.0)) * 100))
print("=" * 64)
