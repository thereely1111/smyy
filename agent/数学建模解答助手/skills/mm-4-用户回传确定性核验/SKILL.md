---
name: mm-4-用户回传确定性核验
description: 对用户回传清单与 S3 输出契约做离线确定性文件、版本、哈希、结构核验；不执行建模程序。
---

# S4 用户回传确定性核验

只接受本次 `case_id` 的 S3 `spec.json`、`expected_outputs.json`，以及用户上传 `return_manifest.json` 所在目录。先加载 `mm-0-路径与会话隔离`。禁止执行用户代码。经 `run.cmd verify_return.py --spec <绝对路径> --expected <绝对路径> --return-dir <绝对路径> --out artifacts/<case_id>/qa/return_check.json` 执行；输出 JSON `status=PASS` 且 `checks` 无 FAIL 时才允许登记 `user_executed_verified`，但它仅意味着文件与声明一致，不证明真实执行过程或数学结论。`NOT_TESTED` 的单位/范围不能被当成通过。

从 S3 起约定机器可核对的最小结构：`spec.json` 包含非空 `case_id`、`code_revision`、`source_code_sha256`、`input_data_sha256`；`expected_outputs.json` 包含同一 `case_id`、`code_revision` 和非空 `outputs` 数组，每项至少有 `path`（仅相对路径）、`question_id`、`kind` (`json|csv|png|jpeg`)，数值类必须声明 `fields`（字段名及 `unit`，若无量纲使用 `1`），可选 `min_rows`；`return_manifest.json` 含同版号与两份 hash、`exit_code: 0`、`run_timestamp`、`files`（每项 `path`、`sha256`、`question_id`、`kind`；数值类还须 `units`（字段名到单位的映射，逐项与 S3 契约一致）），并附 `run_log.txt`。少字段直接 FAIL，不猜测或补写用户文件。文件逐项登记为可信证据时还需要问题对应、CSV/JSON 字段与单位、非有限数和 SHA-256 均通过。输入文件和源码的原始 hash 应由 S3 另行保存并与实际文件对账；本脚本只验证回传声明是否与 S3 已冻结声明一致，不能自行证明 S3 声明真实。

相对路径不能有绝对路径、盘符、`.`、`..`、空段或符号链接；每个输入和文件最大 20MB，避免异常回传耗尽内存。输出是机器核验报告，不是论文 claims；结果接收智能体只从 PASS 的真实文件提取值，逐条记录 claim_id、文件哈希、单位和证据等级；脚本失败必须保持 WAIT_USER_RUN。
