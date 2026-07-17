# 跨端数据契约（草案 v1）

以下字段是 SQLite 与导出格式共同的语义模型。数据库迁移落地时不得复用已发布字段表达不同含义。

## 核心实体

### `study_item`

所有可调度内容的公共部分：`id`、`kind`（`math_problem` / `memory_card`）、`subject`、`chapter_id`、`created_at`、`updated_at`、`suspended_at`、`scheduler_abi_version`，以及调度状态 `state`、`difficulty`、`stability_days`、`due_at`、`last_reviewed_at`、`repetitions`、`lapses`。

### `math_problem`

`study_item_id`、来源信息、题面 Markdown、解答 Markdown、错误步骤、关键提示、默认错因。题面与解答图片通过 `media_ref` 排序关联。一次重做的结果写入 `attempt`，不覆盖旧记录。

### `memory_card`

`study_item_id`、模板类型、题干、答案、提示数组、要点数组、遮挡定义和关联笔记 ID。模板专属字段在导出 JSON 中保持结构化，不能拼成一段不可逆文本。

### `review_log`

不可变事件：`id`、`study_item_id`、`reviewed_at`、`rating`、作答耗时、评分前后状态、当时的可提取率、设备 ID、算法 ABI 版本。`compensates_log_id` 为后续补偿撤销预留；首版不修改或删除已保存日志。

### `attempt`

数学重做详情：`id`、`math_problem_id`、开始/结束时间、结果、信心、错因、草稿媒体和复盘文字。它与调度评分关联，但两者不可互相替代。

### `media`

`id`、SHA-256、MIME、字节数、宽高/时长、创建时间、相对路径。业务实体仅保存引用，便于去重、迁移和缺失文件检查。

## 约束

- 所有时间点为有符号 64 位 UTC Unix 秒；持续时间明确带 `_seconds` 或 `_days` 后缀；
- 所有 ID 在客户端生成，禁止依赖自增 ID 做跨设备身份；
- 删除默认为墓碑，直到完成导出或同步清理；
- 调度状态和对应 `review_log` 必须原子提交；
- 导入前校验 schema 版本、哈希、引用完整性和数值范围。
