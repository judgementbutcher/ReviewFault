# 跨端数据契约 v2

schema v2 通过 `002_v0_2.sql` 从 v1 顺序升级，`001_initial.sql` 保持不变。升级保留
所有旧 `due_at`；有历史的项目标记 `needs_history_replay=1`，第一次 v2 作答时由仓储
回放 v1 `review_log`/`attempt` 后写回类型化状态。

## 调度与事件

- `schedule_state_v2`：队列需要的算法、版本、到期、复习次数与渐进迁移标记；
- `memory_schedule_state`：408 的状态、难度、稳定性和 lapse；
- `math_schedule_state`：数学熟练度与连续熟练次数；
- `review_event_v2`：公共的算法、反馈、时间、预设和到期变化；
- `memory_review_event_v2` / `math_review_event_v2`：类型专属证据；数学详情关联 `attempt`。

三张 v2 事件表和旧 `review_log` 都由触发器禁止更新、删除。旧字段继续只读保留供
审计与历史迁移，两端新作答只写 v2 事件。

## 设置、题库与删除

`learning_preferences` 保存可迁移的每日新 408 上限、学习时长、启用科目、队列类型、
记忆预设和数学强度。主题、提醒时间与通知权限属于设备设置，不进入数据库恢复覆盖范围。

题库普通查询必须带 `study_item.deleted_at IS NULL`，支持科目、类型、标签、状态、
`LIMIT/OFFSET`。删除只写墓碑并立即退出队列；恢复清除墓碑，保留原调度状态。媒体与
不可变历史不随软删除清理，v0.2 没有永久清除入口。
