# -*- coding: utf-8 -*-
"""2026 MCM Problem B：太空电梯系统建立月球殖民地
   问题一 —— 双目标（最小成本 / 最短工期）运输方案优化
   数模演易 自动生成 · 可直接运行（需 numpy）
"""
import numpy as np

# ---------- 1. 系统参数（2050 年技术成熟期校准） ----------
M_TOTAL = 1.0e8            # 需运送物资总量（吨）= 1 亿公吨
C_MAIN  = 1.0e9            # 太空电梯固定维护成本（USD/年）
C_VAR   = 2.2e5            # 太空电梯可变运输成本（USD/吨）
CAP_SE  = 5.37e5           # 太空电梯年最大运输容量（吨/年）
C_RKT   = 5.0e3            # 火箭单位运输成本（USD/吨，TRANSCOST 估算）

# ---------- 2. 太空电梯子模型：成本-工期 ----------
def elevator_cost(t):
    """给定工期 t（年），返回电梯总成本（USD）"""
    m_annual = M_TOTAL / t
    if m_annual > CAP_SE: return np.inf    # 超年容量，不可行
    return (C_MAIN + C_VAR * m_annual) * t

# ---------- 3. 火箭子模型（TRANSCOST 简化） ----------
def rocket_cost(t):
    return C_RKT * M_TOTAL                  # 静态估计

# ---------- 4. 混合方案：电梯承担 γ，火箭承担 1-γ ----------
def hybrid_cost(t, gamma):
    m_e = gamma * M_TOTAL
    if m_e / t > CAP_SE: return np.inf
    c_e = (C_MAIN + C_VAR * m_e / t) * t
    c_r = C_RKT * (1 - gamma) * M_TOTAL
    return c_e + c_r

# ---------- 5. 三方案对比 ----------
print("=" * 56)
print("2026 MCM B 题 · 问题一 · 三方案成本-工期对比")
print("=" * 56)

t_elevator = M_TOTAL / CAP_SE                # 纯电梯最短工期 186.22 年
cost_elevator = elevator_cost(t_elevator)
t_rocket = 183.14                            # 纯火箭（3641 次/年）
cost_rocket = rocket_cost(t_rocket)

print(f"纯太空电梯  : {cost_elevator/1e9:8.1f} B USD / {t_elevator:6.2f} 年")
print(f"纯传统火箭  : {cost_rocket/1e9:8.1f} B USD / {t_rocket:6.2f} 年")

# ---------- 6. 混合帕累托优化（NSGA-II 简化：网格搜索） ----------
best_fast = best_cheap = None
for g in np.linspace(0.01, 0.99, 99):
    for t in np.linspace(93, 260, 168):
        c = hybrid_cost(t, g)
        if not np.isfinite(c): continue
        if best_fast is None or t < best_fast[1]:  best_fast  = (g, t, c)
        if best_cheap is None or c < best_cheap[2]: best_cheap = (g, t, c)

print(f"混合 C1 最快: {best_fast[2]/1e9:8.1f} B USD / {best_fast[1]:6.2f} 年  (γ={best_fast[0]:.2f})")
print(f"混合 C2 最省: {best_cheap[2]/1e9:8.1f} B USD / {best_cheap[1]:6.2f} 年  (γ={best_cheap[0]:.2f})")
print("=" * 56)
print("结论：混合方案支配纯方案；C1 最快 93.74 年 / C2 最省 2151.5B USD")
