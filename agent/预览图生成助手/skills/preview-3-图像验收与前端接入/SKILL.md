---
name: preview-3-图像验收与前端接入
description: 验收预览图的文件、尺寸、构图和缩小识别度，并输出 coverImage 接入契约。
---

# 图像验收与前端接入

## 静态验收

输出 `image_qa.json`，至少记录：

```json
{
  "status": "PASS|FAIL|NOT_TESTED",
  "file": "public/hero-assets/herb-drying-cover-v1.png",
  "mime": "image/png",
  "width": 1536,
  "height": 1024,
  "aspect_ratio": 1.5,
  "checks": {
    "file_exists": true,
    "readable": true,
    "landscape": true,
    "central_safe_zone": "PASS|FAIL|NOT_TESTED",
    "small_card_recognition": "PASS|FAIL|NOT_TESTED",
    "no_text_or_watermark": "PASS|FAIL|NOT_TESTED",
    "topic_matches_case": "PASS|FAIL|NOT_TESTED"
  },
  "human_review_required": true,
  "notes": []
}
```

## 验收规则

- PNG 可读，宽幅尺寸正常，推荐 1536x1024；
- 关键主体不贴边，适配组件 `object-fit: cover` 裁切；
- 缩小到约 300x110 仍能识别案例主题；
- 不出现文字、字母、数字、公式、logo、水印或错误伪标签；
- 视觉元素与案例主题和建模关系一致；
- 颜色和密度与已有案例封面协调；
- 仅静态检查通过不能替代用户在首页的人工预览。

## 前端接入

输出 `integration.json`：

```json
{
  "case_id": "herb-drying",
  "asset_file": "public/hero-assets/herb-drying-cover-v1.png",
  "cover_image_url": "/hero-assets/herb-drying-cover-v1.png",
  "data_file": "src/legacy/prototype/core.ts",
  "field": "coverImage",
  "snippet": "coverImage:'/hero-assets/herb-drying-cover-v1.png'",
  "browser_check": "http://localhost:5181/",
  "integration_status": "READY_FOR_REVIEW"
}
```

不要自动修改业务数据文件，除非父任务明确要求接入；默认只输出准确的 `snippet` 和对应案例 ID。若实际已修改，必须在交接中列明修改文件和验证结果。

## 失败处理

尺寸或文件不可读时返回 `FAILED`；主题不匹配、主体被裁切或含有禁用元素时返回 `PARTIAL`/`WAIT_USER`，建议重新生成并使用新 revision。不得覆盖旧图片或删除旧图片。
