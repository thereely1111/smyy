---
name: preview-1-案例视觉提炼
description: 从案例标题、题面摘要和建模关系提炼稳定、可生成、适合卡片封面的视觉 brief。
---

# 案例视觉提炼

## 目标

把数学建模案例的文字输入压缩成图像生成所需的视觉结构，避免只生成泛化的科技背景。

## 必填字段

输出 `visual_brief.json`，至少包含：

```json
{
  "case_id": "herb-drying",
  "title": "中药材热风烘干",
  "topic_summary": "热风作用下药材内部传热、传质和表面蒸发过程",
  "core_objects": ["药材切片", "干燥托盘", "热风"],
  "modeling_relations": ["热量传导", "水分扩散", "表面蒸发"],
  "visual_elements": ["水平热流线", "少量扩散等值线"],
  "composition": {
    "orientation": "landscape",
    "safe_zone": "center-horizontal",
    "edge_policy": "edges-background-only"
  },
  "style": "restrained academic editorial illustration",
  "palette": ["teal", "blue-green", "ink", "small amber accent"],
  "negative_constraints": ["text", "numbers", "logo", "watermark", "border", "people"],
  "confidence": "source-backed|title-only-draft"
}
```

## 提炼规则

1. 优先选择题目中实际出现的物体、系统和过程；
2. 可视化元素最多 2-3 个，每个都要能对应建模关系；
3. 不把公式、坐标轴、示例数字放进封面；
4. 核心对象应在中央横向安全区域，左右两侧留作背景和流线延展；
5. 只有标题没有摘要时，`confidence` 必须是 `title-only-draft`，并在交接中说明需人工确认；
6. 避免将行业 logo、学校徽标或品牌元素默认加入图像，除非用户明确授权并提供素材。

## 失败条件

核心对象完全不明、案例主题存在多个互相冲突解释，或用户要求生成正式数据图表时，停止并返回 `BLOCKED`，说明需要补充的输入。
