### M-PRE-004 支持向量机 / 支持向量回归
- **task_types**：中小样本分类、非线性回归、高维特征。
- **problem_signature**：样本量中小、维数较高、希望最大间隔或核方法。
- **core_idea**：最大化分类间隔或在 ε 管道内控制回归误差。
- **assumptions**：特征尺度可比较；核函数能够表达相似性结构。
- **limitations**：大样本训练成本高；参数 C、γ、ε 敏感。
- **validation**：嵌套交叉验证、尺度敏感性、核和超参数敏感性。
- **selection_tags**：`kernel, small-sample, classification, regression`
- **fallbacks**：正则化线性模型、树模型。
- **agent_actions**：标准化 -> 选核 -> 嵌套调参 -> 评估 -> 检查支持向量和泛化。
