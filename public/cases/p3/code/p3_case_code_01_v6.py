# -*- coding: utf-8 -*-
"""2026 国赛 C 题：微网与外部电网的电力调控策略
   问题一 —— 确定信息下的单日 MILP 最优购电计划
   数模演易 自动生成 · 可直接运行（需 pandas / pulp）
"""
import pandas as pd
import pulp

# ---------- 1. 储能与系统参数（附录1） ----------
ETA   = 0.9               # 充放电效率
S_INIT = 6000.0           # 0:00 储电量 (kWh)
S_MIN, S_MAX = 1200.0, 10800.0
P_MAX = 5000.0            # 最大充放功率 (kW)
DT    = 10 / 60           # 时段长度 (h)
C_MAX = P_MAX * DT        # 单时段最大充放电量 = 833.33 kWh

# ---------- 2. 读取附件1：典型日电价 / 负载 / 光伏预测 ----------
df = pd.read_excel("data/附件1.xlsx", sheet_name="预处理数据")
price = df["电价_元每kWh"].values
load  = df["小区负载电量"].values
pv    = df["光伏发电量"].values
T = len(df)                       # 144 个时段

# ---------- 3. 建立混合整数线性规划 ----------
prob = pulp.LpProblem("Microgrid_Day_Ahead", pulp.LpMinimize)
g = pulp.LpVariable.dicts("buy", range(T), lowBound=0)
c = pulp.LpVariable.dicts("chg", range(T), lowBound=0, upBound=C_MAX)
d = pulp.LpVariable.dicts("dis", range(T), lowBound=0, upBound=C_MAX)
s = pulp.LpVariable.dicts("soc", range(T + 1), lowBound=S_MIN, upBound=S_MAX)
u = pulp.LpVariable.dicts("flag", range(T), cat="Binary")   # 1=充电 0=放电

# 目标：全天购电费用最小
prob += pulp.lpSum(price[t] * g[t] for t in range(T))

for t in range(T):
    prob += g[t] + pv[t] + d[t] == load[t] + c[t]        # 功率平衡
    prob += c[t] <= C_MAX * u[t]                         # 充放互斥
    prob += d[t] <= C_MAX * (1 - u[t])
    prob += s[t + 1] == s[t] + c[t] - d[t] / ETA         # 储能递推

prob += s[0] == S_INIT
prob += s[T] == S_INIT          # 24:00 必须回到 0:00 的荷电状态

# ---------- 4. 求解 ----------
prob.solve(pulp.PULP_CBC_CMD(msg=0))

# ---------- 5. 结果汇总与导出 ----------
buy_kwh = sum(g[t].value() for t in range(T))
chg_kwh = sum(c[t].value() for t in range(T))
dis_kwh = sum(d[t].value() for t in range(T))
cost    = sum(price[t] * g[t].value() for t in range(T))

print("求解状态   :", pulp.LpStatus[prob.status])
print("全天购电量 : %.2f kWh" % buy_kwh)
print("全天购电费 : %.2f 元" % cost)
print("储能总充电 : %.2f kWh / 总放电 %.2f kWh" % (chg_kwh, dis_kwh))

out = pd.DataFrame({
    "时段": range(1, T + 1),
    "电价": price,
    "计划购电量": [g[t].value() for t in range(T)],
    "充电量": [c[t].value() for t in range(T)],
    "放电量": [d[t].value() for t in range(T)],
    "时段末储电量": [s[t].value() for t in range(T)],
})
out.to_excel("result1.xlsx", index=False)
print("已写出 result1.xlsx，共 %d 行" % len(out))
