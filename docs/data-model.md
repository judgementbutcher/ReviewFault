# 跨端数据契约 v4

schema v4 通过 `004_v0_4.sql` 从 v3 顺序升级，旧迁移保持不变。升级保留旧事件表并把
v1/v2/v3 历史投影为不可变 `review_action_v4`；`due_at`、难度和熟练度属于可重建调度投影，
不会作为同步事实覆盖其他设备。

## 调度与事件

- `schedule_state_v2`：队列需要的算法、版本、到期、复习次数与渐进迁移标记；
- `memory_schedule_state`：408 的状态、难度、稳定性和 lapse；
- `math_schedule_state`：数学熟练度与连续熟练次数；
- `review_event_v2`：公共的算法、反馈、时间、预设和到期变化；
- `memory_review_event_v2` / `math_review_event_v2`：类型专属证据；数学详情关联 `attempt`。

三张 v2 事件表和旧 `review_log` 都由触发器禁止更新、删除。旧字段继续只读保留供
审计与历史迁移。启用 v0.3 调度时，新作答写不可变的 `review_event_v3` 与类型明细；公共事件
记录参数校验和、时区偏移、耗时质量和 JSON 决策快照。`algorithm_parameter_registry` 固定算法名、
算法版本、参数版本、校验和及生效时间。切回 v0.2 参数时继续写 v2 事件，不改写任何历史。

v4 新作答同时写本地审计事件、`review_action_v4` 和同一设备计数器对应的 outbox 操作。
跨设备回放先按因果游标和设备内计数排序，再以时间/事实 ID 稳定打破并发平局；结果写回
`schedule_cache_v4`、类型化调度表和客户端当前读取的 `study_item` 投影。

## 同步与备份

- `local_device` 保存随机安装 UUID、账号/workspace 绑定和单调设备计数器；
- `sync_cursor`、`sync_revision`、`sync_outbox` 与 `sync_conflict` 保存本地同步状态；
- `relation_operation` 以 observed-remove 事实表达关系增删；
- pull 投影远端事实时抑制本地触发器产生的 outbox 回环；
- v4 备份排除设备身份、账号令牌、游标、outbox、冲突和本地笔迹草稿。

## 设置、题库与删除

`learning_preferences` 保存可迁移的每日新 408 上限、学习时长、启用科目、队列类型、
记忆预设和数学强度。主题、提醒时间与通知权限属于设备设置，不进入数据库恢复覆盖范围。

题库普通查询必须带 `study_item.deleted_at IS NULL`，支持科目、类型、标签、状态、
`LIMIT/OFFSET`。删除只写墓碑并立即退出队列；恢复清除墓碑，保留原调度状态。媒体与
不可变历史不随软删除清理，v0.2 没有永久清除入口。
