### M-TIME-002 指数平滑与 ETS / 状态空间模型
- **task_types**：趋势、季节性、水平变化、短中期预测。
- **problem_signature**：近期观测比远期更重要；趋势/季节结构平滑演化。
- **core_idea**：递推更新水平、趋势和季节状态。
- **outputs**：状态分量、预测、区间、平滑参数。
- **assumptions**：状态随时间平滑变化；异常不会长期主导趋势。
- **validation**：滚动验证、不同季节周期、残差和区间覆盖。
- **selection_tags**：`time-series, smoothing, state-space, baseline`
- **fallbacks**：季节朴素、ARIMA、结构时间序列。
- **agent_actions**：与季节朴素和 ARIMA 比较 -> 检查状态解释 -> 评估短期/长期误差。
